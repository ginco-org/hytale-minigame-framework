package gg.ginco.minigame

import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import java.util.logging.Level

class Plugin(init: JavaPluginInit) : JavaPlugin(init) {

    override fun setup() {
        logger.at(Level.INFO).log("MinigameFramework initialized")
    }

    override fun start() {
        logger.at(Level.INFO).log("MinigameFramework started")
    }

    override fun shutdown() {
        logger.at(Level.INFO).log("MinigameFramework shut down")
    }
}
