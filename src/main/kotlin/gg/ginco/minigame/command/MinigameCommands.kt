package gg.ginco.minigame.command

import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext
import com.hypixel.hytale.server.core.command.system.arguments.system.Argument
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.EndReason
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.InstanceId
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.RegionPresence
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.component.TeamRef
import gg.ginco.minigame.util.Membership
import gg.ginco.minigame.util.PlayerSet
import gg.ginco.minigame.util.Teams
import java.util.function.BiConsumer

/**
 * `/minigame` — instance management.
 *
 * Every instance carries a numeric [InstanceId] (`#1`, `#2`, ...) so players
 * can target a specific one once several exist. Subcommands without
 * `--game=<id>` act on every eligible instance (`start`, `stop`) or fall back
 * to the lowest-id eligible one (`join`).
 *
 * Typical flow:
 * ```
 * /minigame create mygame   (admin)  -> instance #1 in Waiting
 * /minigame join            (player) -> join the lowest-id joinable instance
 * /minigame join --game=2   (player) -> join instance #2 specifically
 * /minigame start --seconds=<secs>   (admin) -> all Waiting -> Countdown -> (PhaseSystem) -> Playing
 * /minigame start --game=1 --seconds=7 (admin) -> only instance #1
 * /minigame stop [--game=<id>]       (admin) -> Ending(FORCED) -> (PhaseSystem) cleanup
 * /minigame status          (anyone) -> list instances
 * /minigame leave           (player)
 * ```
 *
 * Optional args are NAMED in hytale's command system (`--seconds=7`): a bare
 * positional "7" fails to parse and the command never runs (the future
 * completes normally — a silent no-op).
 *
 * All commands run on the world thread via their base classes, so direct store
 * mutation is safe. Description keys are not backed by translation assets yet;
 * they display as-is until plugin assets are set up.
 */
class MinigameCommand : AbstractCommandCollection("minigame", "minigame.commands.parent.desc") {
    init {
        addSubCommand(CreateCommand())
        addSubCommand(StartCommand())
        addSubCommand(StopCommand())
        addSubCommand(StatusCommand())
        addSubCommand(JoinCommand())
        addSubCommand(LeaveCommand())
        addSubCommand(TeamCommand())
        addSubCommand(RegionCommand())
    }

        /** Immutable snapshot of one instance's state (safe to keep after a scan). */
        data class InstanceSnapshot(
            val ref: Ref<EntityStore>,
            val id: Int,
            val phase: Phase,
        ) {
            val isJoinable: Boolean get() = phase.kind == Phase.Kind.WAITING || phase.kind == Phase.Kind.COUNTDOWN
            val isLive: Boolean
                get() = phase.kind == Phase.Kind.WAITING || phase.kind == Phase.Kind.COUNTDOWN ||
                    phase.kind == Phase.Kind.PLAYING
        }

    companion object {
        /**
         * Lazily built: [Archetype.of] captures the ComponentType objects at
         * construction time, so it must not run before [gg.ginco.minigame.Components.register]
         * has assigned the companion component types — an eagerly initialised
         * archetype would snapshot nulls and every store scan would silently
         * match nothing (validation then rejects any holder built from it).
         *
         * Every component a controller ever carries is part of the archetype:
         * instances are created through `newHolder(archetype, ...)`, and the
         * ECS matches archetypes by exact component set — a controller with
         * [InstanceId] would be invisible to a [ControllerComponent]+[Phase]
         * archetype.
         */
        val CONTROLLERS: Archetype<EntityStore> by lazy {
            Archetype.of(ControllerComponent.componentType, Phase.componentType, InstanceId.componentType)
        }

        /** Shared scanner shape; avoids Kotlin overload ambiguity between the BiConsumer/BiPredicate forEachChunk variants. */
        fun controllerScanner(body: (ArchetypeChunk<EntityStore>, Int) -> Unit):
                BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> =
            BiConsumer { chunk, _ -> for (i in 0 until chunk.size()) body(chunk, i) }

        /**
         * Every instance, ordered by id. Scan only — the store must not be
         * written while its processing lock is held; mutate after the scan.
         */
        fun snapshot(store: Store<EntityStore>): List<InstanceSnapshot> {
            val out = mutableListOf<InstanceSnapshot>()
            store.forEachChunk(CONTROLLERS, controllerScanner { chunk, i ->
                val id = chunk.getComponent(i, InstanceId.componentType) ?: return@controllerScanner
                val phase = chunk.getComponent(i, Phase.componentType) ?: return@controllerScanner
                out.add(InstanceSnapshot(chunk.getReferenceTo(i), id.value, phase))
            })
            return out.sortedBy { it.id }
        }

        /** The instance players mean when no `--game` id is given: the lowest-id joinable one, if any. */
        fun defaultJoinable(store: Store<EntityStore>): InstanceSnapshot? =
            snapshot(store).firstOrNull { it.isJoinable }

        /** Reads the shared `--game=<id>` optional arg; null when the caller did not restrict the target. */
        fun targetInstance(context: CommandContext, gameArg: Argument<*, Int>): Int? =
            if (context.provided(gameArg)) context.get(gameArg) else null
    }
}

/** Creates a new minigame instance entity in the waiting phase, with the next instance id. */
class CreateCommand : AbstractWorldCommand("create", "minigame.commands.create.desc") {

    private val gameTypeArg = withRequiredArg("gametype", "minigame.commands.create.gametype.desc", ArgTypes.STRING)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val gameTypeId = gameTypeArg.get(context)

        // Build the components FIRST, placed at their ComponentType's slot.
        //
        // Archetype slots are indexed by ComponentType.getIndex() (registry-
        // wide), not by construction order — and Holder/validateComponents
        // compares each array slot against the archetype's slot at the same
        // position. So the component array must be sized to the archetype's
        // slot array with each component stored at its type's index; a plain
        // positional array (e.g. [Controller, Phase]) fails validation with
        // "Invalid component at index 0 expected null but found ...".
        val id = InstanceId.allocate()
        val archetype = MinigameCommand.CONTROLLERS
        @Suppress("UNCHECKED_CAST")
        val components = arrayOfNulls<Component<EntityStore>>(archetype.length())
        components[ControllerComponent.componentType.index] = ControllerComponent(gameTypeId = gameTypeId)
        components[Phase.componentType.index] = Phase.waiting()
        components[InstanceId.componentType.index] = id
        val holder = store.registry.newHolder(archetype, components as Array<Component<EntityStore>>)
        val ref = store.addEntity(holder, AddReason.SPAWN)
        if (ref == null) {
            context.sendMessage(Message.raw("Failed to create minigame instance."))
            return
        }

        context.sendMessage(Message.raw("Created instance #${id.value} (gameType=$gameTypeId, phase=Waiting)."))
    }
}

/** Moves eligible instances into the countdown phase; [gg.ginco.minigame.system.PhaseSystem] takes over from there. */
class StartCommand : AbstractWorldCommand("start", "minigame.commands.start.desc") {

    private val secondsArg = withDefaultArg(
        "seconds", "minigame.commands.start.seconds.desc",
        ArgTypes.INTEGER, DEFAULT_COUNTDOWN_SECONDS, "minigame.commands.start.seconds.default"
    )
    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val countdownSeconds = secondsArg.get(context).toFloat()
        val targetId = MinigameCommand.targetInstance(context, gameArg)

        // Scan first, mutate after: store writes are illegal while forEachChunk
        // holds the processing lock ("Store is currently processing"), and any
        // throw here is swallowed by the async-command wrapper (logged only).
        val targets = MinigameCommand.snapshot(store).filter {
            it.phase.kind == Phase.Kind.WAITING && (targetId == null || it.id == targetId)
        }
        for (instance in targets) {
            store.replaceComponent(instance.ref, Phase.componentType, Phase.countdown(countdownSeconds))
        }

        val scope = if (targetId != null) "instance #$targetId" else "all waiting instances"
        context.sendMessage(
            when {
                targets.isNotEmpty() -> Message.raw("Started ${targets.size} instance(s) ($scope) — $countdownSeconds s countdown.")
                targetId != null -> Message.raw("Instance #$targetId does not exist or is not waiting.")
                else -> Message.raw("No instances in Waiting phase. Use /minigame create first.")
            }
        )
    }

    private companion object {
        const val DEFAULT_COUNTDOWN_SECONDS = 5
    }
}

/** Forces live instances into the ending phase with [gg.ginco.minigame.component.EndReason.FORCED]. */
class StopCommand : AbstractWorldCommand("stop", "minigame.commands.stop.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val targetId = MinigameCommand.targetInstance(context, gameArg)

        // Scan first, mutate after (see StartCommand — store writes are illegal
        // inside the forEachChunk processing window).
        val targets = MinigameCommand.snapshot(store).filter { it.isLive && (targetId == null || it.id == targetId) }
        for (instance in targets) {
            store.replaceComponent(instance.ref, Phase.componentType, Phase.ending(EndReason.FORCED))
            PlayerSet(store, instance.ref).sendMessage(Message.raw("The minigame has been stopped."))
        }

        val scope = if (targetId != null) " (instance #$targetId)" else ""
        context.sendMessage(
            when {
                targets.isNotEmpty() -> Message.raw("Stopped ${targets.size} instance(s)$scope.")
                targetId != null -> Message.raw("Instance #$targetId does not exist or is not running.")
                else -> Message.raw("No running instances.")
            }
        )
    }
}

/** Lists all instances with their id, phase and participant count. */
class StatusCommand : AbstractWorldCommand("status", "minigame.commands.status.desc") {

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val lines = MinigameCommand.snapshot(store).map { instance ->
            val players = PlayerSet(store, instance.ref).count()
            "#${instance.id} gameType=${gameTypeOf(store, instance.ref)} phase=${describe(instance.phase)} players=$players"
        }

        context.sendMessage(
            if (lines.isEmpty()) Message.raw("No minigame instances. Create one with /minigame create <gametype>.")
            else Message.raw(lines.joinToString("\n"))
        )
    }

    private fun describe(phase: Phase): String = when (phase.kind) {
        Phase.Kind.COUNTDOWN -> "Countdown(${phase.seconds.toInt()}s)"
        Phase.Kind.PLAYING -> "Playing(${phase.seconds.toInt()}s)"
        Phase.Kind.ENDING -> "Ending(${phase.reason}, ${phase.seconds.toInt()}s)"
        else -> phase.kind.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun gameTypeOf(store: Store<EntityStore>, ref: Ref<EntityStore>): String =
        store.getComponent(ref, ControllerComponent.componentType)?.gameTypeId ?: "?"
}

/** Joins the sender to a joinable instance: `--game=<id>` targets one, otherwise the lowest-id joinable. */
class JoinCommand : AbstractPlayerCommand("join", "minigame.commands.join.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        if (store.getComponent(ref, InMinigame.componentType) != null) {
            context.sendMessage(Message.raw("You are already in a minigame. Use /minigame leave first."))
            return
        }

        val targetId = MinigameCommand.targetInstance(context, gameArg)
        val target = if (targetId != null) {
            MinigameCommand.snapshot(store).firstOrNull { it.id == targetId && it.isJoinable }
        } else {
            MinigameCommand.defaultJoinable(store)
        }

        if (target == null) {
            val message = if (targetId != null) {
                "Instance #$targetId does not exist or is not joinable right now (Waiting or Countdown)."
            } else {
                "No joinable instance right now (Waiting or Countdown)."
            }
            context.sendMessage(Message.raw(message))
            return
        }

        store.addComponent(ref, InMinigame.componentType, InMinigame(target.ref))
        store.addComponent(ref, Role.componentType, Role.participant())

        PlayerSet(store, target.ref).sendMessage(Message.raw("${playerRef.username} joined the minigame."))
    }
}

/** Removes the sender from whatever instance they are in. */
class LeaveCommand : AbstractPlayerCommand("leave", "minigame.commands.leave.desc") {

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val controllerRef = Membership.release(store, ref)
        if (controllerRef == null) {
            context.sendMessage(Message.raw("You are not in a minigame."))
            return
        }

        if (controllerRef.isValid()) {
            PlayerSet(store, controllerRef).sendMessage(Message.raw("${playerRef.username} left the minigame."))
        }
    }
}

/**
 * `/minigame team` — team management within one minigame instance.
 *
 * A team is an entity with [TeamData] + [TeamMembers] + `InMinigame` (linking
 * it to its instance). Players carry `TeamRef` -> team; membership is two-way
 * (the team's [TeamMembers] lists the player too), so game systems can walk
 * either direction and cleanup can drop everything when the instance ends.
 *
 * Team names are unique per instance (not globally): two instances may each
 * have a team named `red`.
 */
class TeamCommand : AbstractCommandCollection("team", "minigame.commands.team.desc") {
    init {
        addSubCommand(TeamCreateCommand())
        addSubCommand(TeamJoinCommand())
        addSubCommand(TeamLeaveCommand())
        addSubCommand(TeamListCommand())
    }
}

/** Creates a team for an instance: `/minigame team create <name> [--game=<id>]`. */
class TeamCreateCommand : AbstractWorldCommand("create", "minigame.commands.team.create.desc") {

    private val nameArg = withRequiredArg("name", "minigame.commands.team.create.name.desc", ArgTypes.STRING)
    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val name = nameArg.get(context)
        val targetId = MinigameCommand.targetInstance(context, gameArg)

        val target = MinigameCommand.snapshot(store)
            .filter { it.isLive && (targetId == null || it.id == targetId) }
            .firstOrNull()
        if (target == null) {
            context.sendMessage(
                if (targetId != null) Message.raw("Instance #$targetId does not exist or is not running.")
                else Message.raw("No running instances. Create one with /minigame create first.")
            )
            return
        }
        if (Teams.findByName(store, target.ref, name) != null) {
            context.sendMessage(Message.raw("Instance #${target.id} already has a team named '$name'."))
            return
        }

        val archetype = TEAM_ARCHETYPE
        @Suppress("UNCHECKED_CAST")
        val components = arrayOfNulls<Component<EntityStore>>(archetype.length())
        components[TeamData.componentType.index] = TeamData(name = name)
        components[TeamMembers.componentType.index] = TeamMembers()
        components[InMinigame.componentType.index] = InMinigame(target.ref)
        val holder = store.registry.newHolder(archetype, components as Array<Component<EntityStore>>)
        val ref = store.addEntity(holder, AddReason.SPAWN)
        if (ref == null) {
            context.sendMessage(Message.raw("Failed to create team."))
            return
        }

        context.sendMessage(Message.raw("Created team '$name' for instance #${target.id}."))
    }

    private companion object {
        /** Created via newHolder with exactly these components; see CreateCommand for slot rules. */
        val TEAM_ARCHETYPE: Archetype<EntityStore> by lazy {
            Archetype.of(TeamData.componentType, TeamMembers.componentType, InMinigame.componentType)
        }
    }
}

/** Joins the sender to a team of their own instance: `/minigame team join <name>`. */
class TeamJoinCommand : AbstractPlayerCommand("join", "minigame.commands.team.join.desc") {

    private val nameArg = withRequiredArg("name", "minigame.commands.team.join.name.desc", ArgTypes.STRING)

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val controllerRef = store.getComponent(ref, InMinigame.componentType)?.controllerRef
        if (controllerRef == null) {
            context.sendMessage(Message.raw("You are not in a minigame."))
            return
        }

        val existingTeam = store.getComponent(ref, TeamRef.componentType)?.teamRef
        if (existingTeam != null && existingTeam.isValid()) {
            val name = store.getComponent(existingTeam, TeamData.componentType)?.name ?: "?"
            context.sendMessage(Message.raw("You are already on team '$name'. Use /minigame team leave first."))
            return
        }

        val name = nameArg.get(context)
        val team = Teams.findByName(store, controllerRef, name)
        if (team == null) {
            context.sendMessage(Message.raw("No team named '$name' in your minigame."))
            return
        }
        val maxPlayers = team.data.maxPlayers
        val roster = store.getComponent(team.ref, TeamMembers.componentType)?.members ?: mutableListOf()
        if (maxPlayers in 1..roster.size) {
            context.sendMessage(Message.raw("Team '$name' is full ($maxPlayers player(s))."))
            return
        }

        store.addComponent(ref, TeamRef.componentType, TeamRef(team.ref))
        roster.add(ref)

        // Announce to the team including the joiner (already in the roster).
        for (member in roster) {
            val memberPlayer = store.getComponent(member, PlayerRef.getComponentType()) ?: continue
            memberPlayer.sendMessage(Message.raw("${playerRef.username} joined team '$name'."))
        }
    }
}

/** Removes the sender from their team: `/minigame team leave`. */
class TeamLeaveCommand : AbstractPlayerCommand("leave", "minigame.commands.team.leave.desc") {

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val teamRef = store.getComponent(ref, TeamRef.componentType)?.teamRef
        if (teamRef == null) {
            context.sendMessage(Message.raw("You are not on a team."))
            return
        }

        store.removeComponentIfExists(ref, TeamRef.componentType)

        if (teamRef.isValid()) {
            val data = store.getComponent(teamRef, TeamData.componentType)
            val name = data?.name ?: "?"
            val members = store.getComponent(teamRef, TeamMembers.componentType)?.members
            members?.remove(ref)

            // Confirm to the leaver even when they were the last member;
            // announce to whoever remains.
            playerRef.sendMessage(Message.raw("You left team '$name'."))
            for (member in members ?: emptyList()) {
                val memberPlayer = store.getComponent(member, PlayerRef.getComponentType()) ?: continue
                memberPlayer.sendMessage(Message.raw("${playerRef.username} left team '$name'."))
            }
        }
    }
}

/** Lists an instance's teams: `/minigame team list [--game=<id>]`. */
class TeamListCommand : AbstractWorldCommand("list", "minigame.commands.team.list.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val targetId = MinigameCommand.targetInstance(context, gameArg)
        val target = MinigameCommand.snapshot(store)
            .filter { it.isLive && (targetId == null || it.id == targetId) }
            .firstOrNull()
        if (target == null) {
            context.sendMessage(
                if (targetId != null) Message.raw("Instance #$targetId does not exist or is not running.")
                else Message.raw("No running instances.")
            )
            return
        }

        val teams = Teams.of(store, target.ref)
        context.sendMessage(
            if (teams.isEmpty()) Message.raw("Instance #${target.id} has no teams yet.")
            else Message.raw(
                teams.joinToString(
                    prefix = "Instance #${target.id} teams: ",
                    transform = { "'${it.data.name}' (${it.data.score} pts, ${memberCount(store, it.ref)} player(s))" }
                )
            )
        )
    }

    private fun memberCount(store: Store<EntityStore>, teamRef: Ref<EntityStore>): Int =
        store.getComponent(teamRef, TeamMembers.componentType)?.members?.size ?: 0
}
