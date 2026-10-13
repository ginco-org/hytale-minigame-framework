package gg.ginco.minigame.command

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.util.Parties
import gg.ginco.minigame.util.PlayerSet
import java.util.function.BiConsumer
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider

/**
 * `/minigame party` — party lifecycle.
 *
 * A party is a uuid-keyed, disconnect-surviving group whose leader can drive
 * a single `/minigame join` for everyone (same instance, same team — see
 * [JoinCommand]). Subcommands are self-scoped player actions and therefore
 * ungated, exactly like `join`/`leave`/`team join`; the admin-gated commands
 * are the ones that create or mutate instances.
 *
 * Invites resolve the target by name IN THE INVITER'S WORLD — parties are
 * formed where the players stand.
 */
class PartyCommand : AbstractCommandCollection("party", "minigame.commands.party.desc") {
    init {
        // Every party subcommand is player-facing.
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER)
        addSubCommand(PartyCreateCommand())
        addSubCommand(PartyInviteCommand())
        addSubCommand(PartyAcceptCommand())
        addSubCommand(PartyLeaveCommand())
        addSubCommand(PartyListCommand())
    }
}

/** `/minigame party create` — the sender founds and leads a new party. */
class PartyCreateCommand : AbstractPlayerCommand("create", "minigame.commands.party.create.desc") {
    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        Parties.create(playerRef) ?: run {
            context.sendMessage(Message.raw("You are already in a party. Use /minigame party leave first."))
            return
        }
        context.sendMessage(Message.raw("Party created. Invite players with /minigame party invite <name>."))
    }
}

/** `/minigame party invite <name>` — leader-only; the target must stand in the inviter's world. */
class PartyInviteCommand : AbstractPlayerCommand("invite", "minigame.commands.party.invite.desc") {

    private val nameArg = withRequiredArg("name", "minigame.commands.party.invite.name.desc", ArgTypes.STRING)

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val party = Parties.partyOf(playerRef.uuid)
        if (party == null) {
            context.sendMessage(Message.raw("You are not in a party. Create one with /minigame party create."))
            return
        }
        if (!party.isLeader(playerRef.uuid)) {
            context.sendMessage(Message.raw("Only the party leader can invite."))
            return
        }

        val name = nameArg.get(context)
        val target = PlayerSet.findByUsername(store, name)
        if (target == null) {
            context.sendMessage(Message.raw("No player named '$name' in this world."))
            return
        }
        if (Parties.partyOf(target.second.uuid) != null) {
            context.sendMessage(Message.raw("$name is already in a party."))
            return
        }

        Parties.invite(playerRef.uuid, target.second)
        target.second.sendMessage(
            Message.raw("${playerRef.username} invited you to their party. Join with /minigame party accept."))
        context.sendMessage(Message.raw("Invited $name."))
    }
}

/** `/minigame party accept` — joins the party that invited the sender. */
class PartyAcceptCommand : AbstractPlayerCommand("accept", "minigame.commands.party.accept.desc") {
    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        if (Parties.invitedTo(playerRef.uuid) == null) {
            context.sendMessage(Message.raw("You have no pending party invite."))
            return
        }
        val party = Parties.accept(playerRef) ?: run {
            context.sendMessage(Message.raw("That party no longer exists."))
            return
        }
        for (member in party.memberUuids) {
            val memberPlayer = PlayerSet.findByUuid(store, member)?.let {
                store.getComponent(it, PlayerRef.getComponentType())
            } ?: continue
            memberPlayer.sendMessage(
                if (member == playerRef.uuid) Message.raw("You joined ${party.leaderName}'s party.")
                else Message.raw("${playerRef.username} joined the party."))
        }
    }
}

/** `/minigame party leave` — a member leaves; the LEADER leaving disbands the party. */
class PartyLeaveCommand : AbstractPlayerCommand("leave", "minigame.commands.party.leave.desc") {
    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val outcome = Parties.leave(playerRef.uuid) ?: run {
            context.sendMessage(Message.raw("You are not in a party."))
            return
        }
        context.sendMessage(
            if (outcome.disbanded) Message.raw("You left the party; it was disbanded.")
            else Message.raw("You left the party."))
        val leaver = playerRef.username
        for (member in outcome.otherMembers) {
            val memberPlayer = PlayerSet.findByUuid(store, member)?.let {
                store.getComponent(it, PlayerRef.getComponentType())
            } ?: continue
            memberPlayer.sendMessage(
                if (outcome.disbanded) Message.raw("$leaver left the party; it was disbanded.")
                else Message.raw("$leaver left the party."))
        }
    }
}

/** `/minigame party list` — the sender's own party: leader, members, pending invites. */
class PartyListCommand : AbstractPlayerCommand("list", "minigame.commands.party.list.desc") {
    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val party = Parties.partyOf(playerRef.uuid)
        if (party == null) {
            context.sendMessage(Message.raw("You are not in a party. Create one with /minigame party create."))
            return
        }
        val members = party.memberUuids.joinToString(", ") { party.names[it] ?: it.toString() }
        val invited = party.invitedUuids.joinToString(", ") { party.names[it] ?: it.toString() }
        context.sendMessage(
            Message.raw("Party led by ${party.leaderName}: $members" +
                (if (invited.isEmpty()) "" else " (invited: $invited)")))
    }
}
