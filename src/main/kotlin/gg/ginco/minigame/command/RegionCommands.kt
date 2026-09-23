package gg.ginco.minigame.command

import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Bounds
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.SpawnPoint
import gg.ginco.minigame.util.SpawnPoints
import org.joml.Vector3d

/**
 * `/minigame region` — in-game region and marker management.
 *
 * v1 covers spawn-point markers only: an admin stands where players should
 * appear and runs `/minigame region spawn`. The marker stores a small
 * [Bounds] box around that position; when an instance transitions Countdown
 * -> Playing, [gg.ginco.minigame.system.PhaseSystem] teleports its players
 * to the markers (round-robin, creation order). Instances without markers
 * don't move their players.
 *
 * Markers belong to one instance (via `InMinigame` -> controller) and are
 * removed with it.
 */
class RegionCommand : AbstractCommandCollection("region", "minigame.commands.region.desc") {
    init {
        addSubCommand(RegionSpawnCommand())
        addSubCommand(RegionListCommand())
        addSubCommand(RegionClearCommand())
    }

    companion object {
        /** Created via newHolder with exactly these components; see CreateCommand for slot rules. */
        val SPAWN_ARCHETYPE: Archetype<EntityStore> by lazy {
            Archetype.of(SpawnPoint.componentType, Bounds.componentType, InMinigame.componentType)
        }

        /** Half-extent of the box stored around the chosen position. */
        const val MARKER_HALF_EXTENT = 2.0
    }
}

/** Creates a spawn-point marker at the sender's position: `/minigame region spawn [--game=<id>]`. */
class RegionSpawnCommand : AbstractPlayerCommand("spawn", "minigame.commands.region.spawn.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
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

        // Authoritative position: the sender's TransformComponent in this
        // store. (playerRef.transform lags component teleports — markers were
        // created at the join point instead of the teleported spot.)
        val position = Vector3d(store.getComponent(ref, TransformComponent.getComponentType())!!.position)
        val extent = RegionCommand.MARKER_HALF_EXTENT
        val archetype = RegionCommand.SPAWN_ARCHETYPE
        @Suppress("UNCHECKED_CAST")
        val components = arrayOfNulls<Component<EntityStore>>(archetype.length())
        components[SpawnPoint.componentType.index] = SpawnPoint()
        components[Bounds.componentType.index] = Bounds(
            Vector3d(position.x - extent, position.y - extent, position.z - extent),
            Vector3d(position.x + extent, position.y + extent, position.z + extent),
        )
        components[InMinigame.componentType.index] = InMinigame(target.ref)
        val holder = store.registry.newHolder(archetype, components as Array<Component<EntityStore>>)
        val markerRef = store.addEntity(holder, AddReason.SPAWN)
        if (markerRef == null) {
            context.sendMessage(Message.raw("Failed to create spawn point."))
            return
        }

        val index = SpawnPoints.of(store, target.ref).size
        context.sendMessage(
            Message.raw(
                "Spawn point #%d for instance #%d at (%.1f, %.1f, %.1f)."
                    .format(index, target.id, position.x, position.y, position.z)
            )
        )
    }
}

/** Lists an instance's spawn-point markers: `/minigame region list [--game=<id>]`. */
class RegionListCommand : AbstractWorldCommand("list", "minigame.commands.region.list.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val targetId = if (context.provided(gameArg)) context.get(gameArg) else null
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

        val spawns = SpawnPoints.of(store, target.ref)
        context.sendMessage(
            if (spawns.isEmpty()) Message.raw("Instance #${target.id} has no spawn points.")
            else Message.raw(
                spawns.joinToString(
                    prefix = "Instance #${target.id} spawn points: ",
                    transform = {
                        val c = it.bounds.center()
                        "(%.1f, %.1f, %.1f)".format(c.x, c.y, c.z)
                    }
                )
            )
        )
    }
}

/** Removes all spawn-point markers of an instance: `/minigame region clear [--game=<id>]`. */
class RegionClearCommand : AbstractWorldCommand("clear", "minigame.commands.region.clear.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val targetId = if (context.provided(gameArg)) context.get(gameArg) else null
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

        // Scan first, mutate after (store writes are illegal inside the scan).
        val spawns = SpawnPoints.of(store, target.ref)
        for (spawn in spawns) {
            store.removeEntity(spawn.ref, com.hypixel.hytale.component.RemoveReason.REMOVE)
        }
        context.sendMessage(Message.raw("Removed ${spawns.size} spawn point(s) from instance #${target.id}."))
    }
}
