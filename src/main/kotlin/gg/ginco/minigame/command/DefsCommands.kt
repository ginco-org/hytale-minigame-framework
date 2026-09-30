package gg.ginco.minigame.command

import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.definition.DefinitionStore

/**
 * `/minigame defs` — durable definition management (the editor save path).
 *
 * - `list` shows every loaded definition
 * - `reload` re-reads all definition files from the data directory
 * - `save [--game=<id>]` captures a live instance's current teams and
 *   markers into its definition — configure an instance in-game, save it,
 *   and every future `/minigame create <gametype>` reproduces the setup.
 */
class DefsCommand : AbstractCommandCollection("defs", "minigame.commands.defs.desc") {
    init {
        addSubCommand(DefsListCommand())
        addSubCommand(DefsReloadCommand())
        addSubCommand(DefsSaveCommand())
    }
}

class DefsListCommand : AbstractWorldCommand("list", "minigame.commands.defs.list.desc") {

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val defs = DefinitionStore.all()
        context.sendMessage(
            if (defs.isEmpty()) Message.raw("No minigame definitions loaded. Save one with /minigame defs save.")
            else Message.raw(
                defs.joinToString(prefix = "${defs.size} definition(s): ") {
                    "${it.gameTypeId} (${it.teams.size} team(s))"
                }
            )
        )
    }
}

class DefsReloadCommand : AbstractWorldCommand("reload", "minigame.commands.defs.reload.desc") {

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val count = DefinitionStore.loadAll()
        context.sendMessage(Message.raw("Reloaded $count minigame definition(s)."))
    }
}

class DefsSaveCommand : AbstractWorldCommand("save", "minigame.commands.defs.save.desc") {

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

        val gameTypeId = store.getComponent(target.ref, gg.ginco.minigame.component.ControllerComponent.componentType)?.gameTypeId
        if (gameTypeId.isNullOrBlank()) {
            context.sendMessage(Message.raw("Instance #${target.id} has no game type to save under."))
            return
        }

        val definition = DefinitionStore.saveFromInstance(store, target.ref, gameTypeId)
        context.sendMessage(
            Message.raw(
                "Saved definition '${definition.gameTypeId}' " +
                    "(${definition.teams.size} team(s))."
            )
        )
    }
}
