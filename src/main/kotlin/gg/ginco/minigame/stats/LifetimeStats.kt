package gg.ginco.minigame.stats

import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.jellyparty.codec.annotations.SerializableObject
import gg.ginco.jellyparty.codec.annotations.SerialWithCodec
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InstanceStats
import gg.ginco.minigame.util.Stats
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Lifetime accumulation of [InstanceStats] across instances, keyed by game
 * type, persisted as JSON under `<dataDir>/stats/<gameTypeId>.json`.
 *
 * Recorded when an instance's game ends (PhaseSystem cleanup, every stop /
 * win / timeout path): the instance's counters and per-player counters merge
 * ADDITIVELY into the lifetime file — a restart-safe, per-game-type ledger.
 * Stat ids remain consumer vocabulary.
 *
 * All methods are safe to call from the world thread; writes are atomic
 * (temp file + move) and the file is tiny. Concurrent flushes for the same
 * game type from parallel instance worlds serialize on the store object.
 */
object LifetimeStats {

    private lateinit var dir: Path

    /** Points the store at [dataDir]/stats. Called from plugin start. */
    fun init(dataDir: Path) {
        dir = dataDir.resolve("stats")
        Files.createDirectories(dir)
    }

    private fun fileFor(gameTypeId: String): Path = dir.resolve("$gameTypeId.json")

    /**
     * Merges the live [InstanceStats] of [controllerRef] into the lifetime
     * ledger for its game type and persists the file.
     */
    fun record(store: Store<EntityStore>, controllerRef: Ref<EntityStore>) {
        val gameTypeId = store.getComponent(controllerRef, ControllerComponent.componentType)?.gameTypeId
            ?: return
        val stats = store.getComponent(controllerRef, InstanceStats.componentType) ?: return
        synchronized(fileFor(gameTypeId).toAbsolutePath()) {
            val ledger = load(gameTypeId)
            for ((stat, value) in stats.counters) {
                ledger.counters[stat] = (ledger.counters[stat] ?: 0) + value
            }
            for ((uuid, counters) in stats.perPlayer) {
                val entry = ledger.players.firstOrNull { it.uuid == uuid.toString() }
                    ?: LifetimePlayerEntry(uuid.toString()).also { ledger.players.add(it) }
                for ((stat, value) in counters) {
                    entry.counters[stat] = (entry.counters[stat] ?: 0) + value
                }
            }
            write(gameTypeId, ledger)
        }
    }

    /** Loads the lifetime ledger for [gameTypeId] (empty when no file exists). */
    fun load(gameTypeId: String): LifetimeStatsFile {
        val file = fileFor(gameTypeId)
        if (!Files.isRegularFile(file)) return LifetimeStatsFile(gameTypeId = gameTypeId)
        return try {
            val decoded = LifetimeStatsFileCodec.decodeJson(
                com.hypixel.hytale.codec.util.RawJsonReader(Files.readString(file).toCharArray()),
                com.hypixel.hytale.codec.ExtraInfo(),
            ) ?: LifetimeStatsFile(gameTypeId = gameTypeId)
            decoded.copy(gameTypeId = gameTypeId)
        } catch (e: Exception) {
            System.err.println("[MinigameFramework] failed to load lifetime stats ${file.fileName}: ${e.message}")
            LifetimeStatsFile(gameTypeId = gameTypeId)
        }
    }

    private fun write(gameTypeId: String, ledger: LifetimeStatsFile) {
        val bson = LifetimeStatsFileCodec.encode(ledger, com.hypixel.hytale.codec.ExtraInfo())
        val settings = org.bson.json.JsonWriterSettings.builder()
            .outputMode(org.bson.json.JsonMode.RELAXED)
            .indent(true)
            .build()
        val json = bson.asDocument().toJson(settings)
        val file = fileFor(gameTypeId)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.createDirectories(file.parent)
        Files.writeString(tmp, json)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
