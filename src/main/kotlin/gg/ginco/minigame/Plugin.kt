package gg.ginco.minigame

import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import gg.ginco.minigame.command.MinigameCommand
import gg.ginco.minigame.system.PhaseSystem
import gg.ginco.minigame.system.RegionSystem
import java.util.logging.Level

class Plugin(init: JavaPluginInit) : JavaPlugin(init) {

    override fun setup() {
        Components.register(entityStoreRegistry)
        entityStoreRegistry.registerSystem(PhaseSystem())
        entityStoreRegistry.registerSystem(RegionSystem())
        logger.at(Level.INFO).log("MinigameFramework components and systems registered")
    }

    override fun start() {
        commandRegistry.registerCommand(MinigameCommand())
        logger.at(Level.INFO).log("MinigameFramework started")
    }

    override fun shutdown() {
        logger.at(Level.INFO).log("MinigameFramework shut down")
    }
}
