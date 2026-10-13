package gg.ginco.minigame

import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent
import gg.ginco.minigame.asset.MinigameAssets
import gg.ginco.minigame.command.MinigameCommand
import gg.ginco.minigame.definition.DefinitionStore
import gg.ginco.minigame.system.InstanceEntrySystem
import gg.ginco.minigame.system.PhaseSystem
import gg.ginco.minigame.system.RegionSystem
import gg.ginco.minigame.util.Membership
import java.util.logging.Level

class Plugin(init: JavaPluginInit) : JavaPlugin(init) {

    override fun setup() {
        Components.register(entityStoreRegistry)
        entityStoreRegistry.registerSystem(PhaseSystem())
        // Regions are the builtin TriggerVolumes module now; this only wires
        // our typed events to its crossing events (a bus subscription, not a system).
        RegionSystem.register()
        // Runs in every world; only acts inside instance worlds, where it
        // finishes a /minigame join whose transfer crossed a world boundary.
        entityStoreRegistry.registerSystem(InstanceEntrySystem())
        // Registered in setup() like the server's own lifecycle listeners —
        // start()-time registration does not feed the bus's hasListener()
        // gate, and the disconnect event is silently skipped when no listener
        // is visible at dispatch time.
        eventRegistry.register(PlayerDisconnectEvent::class.java) { e ->
            Membership.handleDisconnect(e.playerRef)
        }
        // Minigame/MinigameMap asset types (pack-relative Server/Minigames +
        // Server/MinigameMaps): each minigame plugin's own bundled pack feeds
        // them via AssetPackRegisterEvent after that plugin's setup0().
        MinigameAssets.registerStores(this)
        logger.at(Level.INFO).log("MinigameFramework components and systems registered")
    }

    override fun start() {
        commandRegistry.registerCommand(MinigameCommand())
        val loaded = DefinitionStore.init(dataDirectory)
        gg.ginco.minigame.stats.LifetimeStats.init(dataDirectory)
        gg.ginco.minigame.behavior.GameBehaviors.init(eventRegistry)
        // definitions() compiles the asset stores; assets are live by now —
        // pack registration of the carrying jar(s) ran before start0().
        val assetBacked = DefinitionStore.all().size
        logger.at(Level.INFO).log(
            "MinigameFramework started — $loaded data-dir + ${assetBacked - loaded} asset-derived minigame definition(s) loaded")
    }

    override fun shutdown() {
        logger.at(Level.INFO).log("MinigameFramework shut down")
    }
}
