package gg.ginco.minigame.command

import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.definition.DefinitionStore
import gg.ginco.minigame.util.Instances

/**
 * `/minigame list` — what can be played, and what is running right now:
 *
 * - bare: every minigame a player can queue or create — [DefinitionStore.all],
 *   data-dir definitions first, shipped asset-backed games after (an editor
 *   save shadows its asset, mirroring [DefinitionStore.get]).
 * - `list instances`: every live instance in EVERY world. `/minigame status`
 *   is deliberately scoped to the current world plus the sender's own queue;
 *   this is the whole-board view. Each instance world is read on its own
 *   thread; the caller's world is read directly — a hop-and-wait to the world
 *   whose thread is running this command would deadlock it (the reason
 *   `start --asset` must be run from the lobby, see its class comment).
 *
 * `instances` is a usage variant (one required positional word) rather than a
 * subcommand collection: bare `/minigame list` must execute, and a collection
 * can only print usage.
 */
class ListCommand : AbstractWorldCommand("list", "minigame.commands.list.desc") {

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER)
        addUsageVariant(InstancesVariant())
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val defs = DefinitionStore.all()
        context.sendMessage(
            if (defs.isEmpty()) Message.raw("No minigames available.")
            else Message.raw(
                defs.joinToString(prefix = "${defs.size} minigame(s): ") { def ->
                    buildString {
                        append(def.gameTypeId)
                        def.displayName.takeUnless { it.isEmpty() || it == def.gameTypeId }
                            ?.let { append(" — ").append(it) }
                        if (def.teams.isNotEmpty()) {
                            append(" (teams: ").append(def.teams.joinToString("/") { it.name }).append(")")
                        }
                        append(if (def.instanceAsset.isEmpty()) " (plays in this world)" else " (instance: ${def.instanceAsset})")
                    }
                }
            )
        )
    }

    /** The `list instances` usage variant: same command, one required positional word. */
    class InstancesVariant : AbstractWorldCommand("minigame.commands.list.instances.desc") {

        private val sectionArg = withRequiredArg("section", "minigame.commands.list.section.desc", ArgTypes.STRING)

        override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
            val section = sectionArg.get(context).lowercase()
            if (section != "instances") {
                context.sendMessage(Message.raw("Unknown list section '$section' — try 'instances'."))
                return
            }

            // The caller's world first — its store is legal on this thread —
            // then every instance world on its own.
            val lines = mutableListOf<String>()
            MinigameCommand.snapshot(store).mapTo(lines) {
                MinigameCommand.instanceLine(store, it, " — world '${world.name}'")
            }
            for (instanceWorld in Instances.instanceWorlds()) {
                if (instanceWorld === world) continue
                lines += Instances.runOnWorld(instanceWorld) {
                    val worldStore = instanceWorld.entityStore.store
                    MinigameCommand.snapshot(worldStore).map {
                        MinigameCommand.instanceLine(worldStore, it, " — world '${instanceWorld.name}'")
                    }
                }
            }

            context.sendMessage(
                if (lines.isEmpty()) Message.raw("No running instances. Queue one with /minigame queue <minigame>.")
                else Message.raw(lines.joinToString("\n"))
            )
        }
    }
}
