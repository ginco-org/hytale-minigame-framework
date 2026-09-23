package gg.ginco.minigame.command

import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.RemoveReason
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
import gg.ginco.minigame.util.RegionKind
import gg.ginco.minigame.util.Regions
import org.joml.Vector3d
import java.util.function.BiConsumer

/**
 * `/minigame region` — in-game region and marker management.
 *
 * Markers are DATA: `<kind>` markers record where something is; the framework
 * reports boundary crossings as events
 * (`PlayerEnterRegionEvent` / `PlayerExitRegionEvent`) and leaves every
 * decision about what that means to consuming plugins. `spawn` markers are
 * the one exception wired into the framework itself: they are the teleport
 * targets used when an instance starts.
 *
 * All marker entities carry `Bounds` + `InMinigame` -> their instance (and
 * are removed with it) plus their kind component.
 */
class RegionCommand : AbstractCommandCollection("region", "minigame.commands.region.desc") {
    init {
        addSubCommand(RegionSpawnCommand())
        addSubCommand(RegionGoalCommand())
        addSubCommand(RegionDeathCommand())
        addSubCommand(RegionSafeCommand())
        addSubCommand(RegionCaptureCommand())
        addSubCommand(RegionListCommand())
        addSubCommand(RegionClearCommand())
    }

    companion object {
        /** Half-extent of the box stored around the chosen position. */
        const val MARKER_HALF_EXTENT = 2.0

        /**
         * Creates a marker entity of [kind] centred on [position], linked to
         * [instanceRef]. Returns the marker ref, or null when the store
         * rejected the entity.
         */
        fun createMarker(
            store: Store<EntityStore>,
            instanceRef: Ref<EntityStore>,
            kind: RegionKind,
            position: Vector3d,
        ): Ref<EntityStore>? {
            val extent = MARKER_HALF_EXTENT
            val markerType = kind.componentType()
            val archetype = Archetype.of(Bounds.componentType, InMinigame.componentType, markerType)
            @Suppress("UNCHECKED_CAST")
            val components = arrayOfNulls<Component<EntityStore>>(archetype.length())
            components[Bounds.componentType.index] = Bounds(
                Vector3d(position.x - extent, position.y - extent, position.z - extent),
                Vector3d(position.x + extent, position.y + extent, position.z + extent),
            )
            components[InMinigame.componentType.index] = InMinigame(instanceRef)
            components[markerType.index] = newMarkerComponent(kind)
            val holder = store.registry.newHolder(archetype, components as Array<Component<EntityStore>>)
            return store.addEntity(holder, AddReason.SPAWN)
        }

        private fun newMarkerComponent(kind: RegionKind): Component<EntityStore> = when (kind) {
            RegionKind.SPAWN -> SpawnPoint()
            RegionKind.GOAL -> gg.ginco.minigame.component.Goal()
            RegionKind.DEATH -> gg.ginco.minigame.component.DeathZone()
            RegionKind.SAFE -> gg.ginco.minigame.component.SafeZone()
            RegionKind.CAPTURE -> gg.ginco.minigame.component.CaptureZone()
        }

        /**
         * The live instance an untargeted marker command applies to: the
         * lowest-id live instance, or the one named by `--game=<id>`. Sends
         * the rejection message itself when no target exists.
         */
        fun resolveTarget(context: CommandContext, store: Store<EntityStore>, gameArg: com.hypixel.hytale.server.core.command.system.arguments.system.Argument<*, Int>): MinigameCommand.InstanceSnapshot? {
            val targetId = if (context.provided(gameArg)) context.get(gameArg) else null
            val target = MinigameCommand.snapshot(store)
                .filter { it.isLive && (targetId == null || it.id == targetId) }
                .firstOrNull()
            if (target == null) {
                context.sendMessage(
                    if (targetId != null) Message.raw("Instance #$targetId does not exist or is not running.")
                    else Message.raw("No running instances. Create one with /minigame create first.")
                )
            }
            return target
        }

        fun format(position: Vector3d): String = "(%.1f, %.1f, %.1f)".format(position.x, position.y, position.z)
    }
}

/**
 * Creates a marker of the command's kind at the sender's position:
 * `/minigame region <kind> [--game=<id>]`.
 */
abstract class RegionMarkerCreateCommand(name: String, description: String) :
    AbstractPlayerCommand(name, description) {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    protected abstract fun kind(): RegionKind

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val kind = kind()
        val target = RegionCommand.resolveTarget(context, store, gameArg) ?: return

        // Authoritative position: the sender's TransformComponent in this
        // store. (playerRef.transform lags component teleports.)
        val position = Vector3d(store.getComponent(ref, TransformComponent.getComponentType())!!.position)
        val markerRef = RegionCommand.createMarker(store, target.ref, kind, position)
        if (markerRef == null) {
            context.sendMessage(Message.raw("Failed to create ${kind.kindName} marker."))
            return
        }

        val count = Regions.markersOf(store, target.ref, kind).size
        context.sendMessage(
            Message.raw("Created ${kind.kindName} marker #$count for instance #${target.id} at ${RegionCommand.format(position)}.")
        )
    }
}

class RegionSpawnCommand : RegionMarkerCreateCommand("spawn", "minigame.commands.region.spawn.desc") {
    override fun kind() = RegionKind.SPAWN
}

class RegionGoalCommand : RegionMarkerCreateCommand("goal", "minigame.commands.region.goal.desc") {
    override fun kind() = RegionKind.GOAL
}

class RegionDeathCommand : RegionMarkerCreateCommand("death", "minigame.commands.region.death.desc") {
    override fun kind() = RegionKind.DEATH
}

class RegionSafeCommand : RegionMarkerCreateCommand("safe", "minigame.commands.region.safe.desc") {
    override fun kind() = RegionKind.SAFE
}

class RegionCaptureCommand : RegionMarkerCreateCommand("capture", "minigame.commands.region.capture.desc") {
    override fun kind() = RegionKind.CAPTURE
}

/** Lists an instance's markers grouped by kind: `/minigame region list [--game=<id>]`. */
class RegionListCommand : AbstractWorldCommand("list", "minigame.commands.region.list.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val target = RegionCommand.resolveTarget(context, store, gameArg) ?: return

        val markers = Regions.markersOf(store, target.ref)
        context.sendMessage(
            if (markers.isEmpty()) Message.raw("Instance #${target.id} has no markers.")
            else Message.raw(
                markers.joinToString(
                    prefix = "Instance #${target.id} markers: ",
                    transform = { "${it.kind.kindName} at ${RegionCommand.format(it.bounds.center())}" }
                )
            )
        )
    }
}

/** Removes markers of an instance: `/minigame region clear [--game=<id>] [--type=<kind>]`. */
class RegionClearCommand : AbstractWorldCommand("clear", "minigame.commands.region.clear.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)
    private val typeArg = withOptionalArg("type", "minigame.commands.region.clear.type.desc", ArgTypes.STRING)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val targetId = if (context.provided(gameArg)) context.get(gameArg) else null
        val typeName = if (context.provided(typeArg)) context.get(typeArg) else null

        val kind = typeName?.let { parsed ->
            RegionKind.fromName(parsed).also {
                if (it == null) context.sendMessage(
                    Message.raw("Unknown marker type '$parsed'. Types: ${RegionKind.entries.joinToString { k -> k.kindName }}.")
                )
            }
        }
        if (typeName != null && kind == null) return

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
        val markers = Regions.markersOf(store, target.ref).filter { kind == null || it.kind == kind }
        for (marker in markers) {
            store.removeEntity(marker.ref, RemoveReason.REMOVE)
        }
        val scope = if (kind != null) " ${kind.kindName}" else ""
        context.sendMessage(Message.raw("Removed ${markers.size}$scope marker(s) from instance #${target.id}."))
    }
}
