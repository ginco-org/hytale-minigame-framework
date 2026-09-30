package gg.ginco.minigame.command

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
import gg.ginco.minigame.util.Regions
import org.joml.Vector3d

/**
 * `/minigame region` — in-world region marker management.
 *
 * Markers are DATA with an open type id: `/minigame region add <type>` records
 * where something is; the framework reports boundary crossings as events
 * (`PlayerEnterRegionEvent` / `PlayerExitRegionEvent` carrying the type) and
 * leaves every decision about what that means to consuming plugins.
 *
 * The one reserved type is `spawn`: those markers are the framework's teleport
 * targets when an instance starts. Everything else belongs to plugins.
 *
 * Regions are WORLD content: they live in the instance world's chunk store
 * (saved and loaded with the world), are shared by every instance running in
 * that world, and are removed only via `region clear` — not when an instance
 * ends. Region definitions are NOT part of minigame definitions; the world is
 * their durable home.
 */
class RegionCommand : AbstractCommandCollection("region", "minigame.commands.region.desc") {
    init {
        addSubCommand(RegionAddCommand())
        addSubCommand(RegionListCommand())
        addSubCommand(RegionClearCommand())
    }

    companion object {
        /** Half-extent of the box stored around the chosen position. */
        const val MARKER_HALF_EXTENT = 2.0

        /**
         * The live instance an untargeted marker command applies to: the
         * lowest-id live instance, or the one named by `--game=<id>`. Sends
         * the rejection message itself when no target exists.
         *
         * Marker commands need A live instance only to resolve the world and
         * for user-facing messages; markers themselves are world content.
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

/** Creates a marker of the command's kind at the sender's position: `/minigame region add <type> [--game=<id>]`. */
class RegionAddCommand : AbstractPlayerCommand("add", "minigame.commands.region.add.desc") {

    private val typeArg = withRequiredArg("type", "minigame.commands.region.add.type.desc", ArgTypes.STRING)
    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val type = typeArg.get(context)
        val target = RegionCommand.resolveTarget(context, store, gameArg) ?: return

        // Authoritative position: the sender's TransformComponent in this
        // store. (playerRef.transform lags component teleports.)
        val position = Vector3d(store.getComponent(ref, TransformComponent.getComponentType())!!.position)
        val extent = RegionCommand.MARKER_HALF_EXTENT
        val id = Regions.addMarker(
            world, type,
            position.x - extent, position.y - extent, position.z - extent,
            position.x + extent, position.y + extent, position.z + extent,
        )

        val count = Regions.markersOf(world, type).size
        context.sendMessage(
            Message.raw("Created $type marker '$id' (#$count of type) at ${RegionCommand.format(position)}.")
        )
    }
}

/** Lists an instance's region markers: `/minigame region list [--game=<id>]`. */
class RegionListCommand : AbstractWorldCommand("list", "minigame.commands.region.list.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val target = RegionCommand.resolveTarget(context, store, gameArg) ?: return

        val markers = Regions.markersOf(world)
        context.sendMessage(
            if (markers.isEmpty()) Message.raw("No region markers in this world.")
            else Message.raw(
                markers.joinToString(
                    prefix = "Region markers: ",
                    transform = { "${it.type} at ${RegionCommand.format(it.center())}" }
                )
            )
        )
    }
}

/** Removes region markers: `/minigame region clear [--type=<type>]`. */
class RegionClearCommand : AbstractWorldCommand("clear", "minigame.commands.region.clear.desc") {

    private val typeArg = withOptionalArg("type", "minigame.commands.region.clear.type.desc", ArgTypes.STRING)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val typeName = if (context.provided(typeArg)) context.get(typeArg) else null

        val removed = Regions.removeMarkers(world, typeName)
        val scope = if (typeName != null) " $typeName" else ""
        context.sendMessage(Message.raw("Removed $removed$scope marker(s)."))
    }
}
