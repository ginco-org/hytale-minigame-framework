package gg.ginco.minigame

import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent
import gg.ginco.minigame.command.MinigameCommand
import gg.ginco.minigame.definition.DefinitionStore
import gg.ginco.minigame.system.PhaseSystem
import gg.ginco.minigame.system.RegionSystem
import gg.ginco.minigame.util.Membership
import java.util.logging.Level

class Plugin(init: JavaPluginInit) : JavaPlugin(init) {

    override fun setup() {
        Components.register(entityStoreRegistry)
        entityStoreRegistry.registerSystem(PhaseSystem())
        entityStoreRegistry.registerSystem(RegionSystem())
        // Registered in setup() like the server's own lifecycle listeners —
        // start()-time registration does not feed the bus's hasListener()
        // gate, and the disconnect event is silently skipped when no listener
        // is visible at dispatch time.
        eventRegistry.register(PlayerDisconnectEvent::class.java) { e ->
            Membership.handleDisconnect(e.playerRef)
        }
        logger.at(Level.INFO).log("MinigameFramework components and systems registered")
    }

    override fun start() {
        commandRegistry.registerCommand(MinigameCommand())
        val loaded = DefinitionStore.init(dataDirectory)
        logger.at(Level.INFO).log("MinigameFramework started — $loaded minigame definition(s) loaded")
    }

    override fun shutdown() {
        logger.at(Level.INFO).log("MinigameFramework shut down")
    }
}
