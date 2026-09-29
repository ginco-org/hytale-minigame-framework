package gg.ginco.minigame.command

import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.util.PlayerSet
import gg.ginco.minigame.util.Stats

/**
 * `/minigame stats [--game=<id>]` — prints an instance's counters and their
 * per-player breakdowns. Read-only view over [Stats]; consumers define the
 * stat vocabulary themselves.
 */
class StatsCommand : AbstractWorldCommand("stats", "minigame.commands.stats.desc") {

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

        val counters = Stats.countersOf(store, target.ref)
        context.sendMessage(
            if (counters.isEmpty()) Message.raw("Instance #${target.id} has no stats yet.")
            else Message.raw(
                counters.entries.joinToString(
                    prefix = "Instance #${target.id} stats: ",
                    transform = { "${it.key}=${it.value}" }
                )
            )
        )

        // Per-player breakdowns for stats where at least one player scored.
        for (stat in counters.keys) {
            val breakdown = Stats.breakdown(store, target.ref, stat)
            if (breakdown.isEmpty()) continue
            val names = breakdown.entries.joinToString { entry ->
                val member = PlayerSet.findByUuid(store, entry.key)
                val name = member?.let {
                    store.getComponent(it, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType())
                        ?.username
                } ?: entry.key.toString()
                "$name=${entry.value}"
            }
            context.sendMessage(Message.raw("$stat: $names"))
        }
    }
}
