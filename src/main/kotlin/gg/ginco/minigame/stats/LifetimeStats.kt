package gg.ginco.minigame.stats

import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InstanceId
import gg.ginco.minigame.component.InstanceStats
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifetime accumulation of [InstanceStats] across instances, keyed by game
 * type, persisted as JSON under `<dataDir>/stats/<gameTypeId>.json`.
 *
 * Two write paths, one invariant — **never record the same counter twice**:
 *
 * - **Periodic flush** while the instance is live (driven by PhaseSystem
 *   every [flush] call it schedules): merges the DELTA since the previous
 *   flush into the ledger, so a server crash mid-game loses at most the
 *   ticks since the last flush instead of the whole game.
 * - **Final record** at instance end (PhaseSystem cleanup, every stop / win
 *   / timeout path): merges the final remaining delta and forgets the
 *   instance's flush state.
 *
 * Both paths merge ADDITIVELY into the lifetime file — a restart-safe,
 * per-game-type ledger. Stat ids remain consumer vocabulary.
 *
 * All methods are safe to call from the world thread; writes are atomic
 * (temp file + move) and the file is tiny. Concurrent flushes for the same
 * game type from parallel instance worlds serialize on the store object.
 */
object LifetimeStats {

    private lateinit var dir: Path

    /**
     * What this instance's counters were at the last flush, per instance id
     * (per-boot unique, so ids never alias within a boot). Snapshots are the
     * double-count guard: every write merges only the delta against them.
     * Entries for instances that vanish without cleanup (a world torn down
     * around them) live until process end — bounded by the boot's instance
     * count, each a handful of small maps.
     */
    private data class Snapshot(val counters: Map<String, Int>, val perPlayer: Map<UUID, Map<String, Int>>)

    private val flushed = ConcurrentHashMap<Int, Snapshot>()

    /** Points the store at [dataDir]/stats. Called from plugin start. */
    fun init(dataDir: Path) {
        dir = dataDir.resolve("stats")
        Files.createDirectories(dir)
    }

    private fun fileFor(gameTypeId: String): Path = dir.resolve("$gameTypeId.json")

    /**
     * Flushes the DELTA of [controllerRef]'s counters since the last flush
     * into the lifetime ledger (a no-op when nothing changed). Returns true
     * when the file was written.
     *
     * Runs on the caller's (world) thread: the read/merge is in-memory, and
     * the write is a small atomic temp+move — matching the sync-write pattern
     * of [gg.ginco.minigame.definition.DefinitionStore.save]. An async write
     * would race the test/game reading the ledger right after.
     */
    fun flush(store: Store<EntityStore>, controllerRef: Ref<EntityStore>): Boolean =
        flushOnce(store, controllerRef)

    /**
     * Records the finished instance's remaining counters into the lifetime
     * ledger (final delta since the last flush) and forgets its flush state.
     */
    fun record(store: Store<EntityStore>, controllerRef: Ref<EntityStore>) {
        flushOnce(store, controllerRef)
        store.getComponent(controllerRef, InstanceId.componentType)?.let { flushed.remove(it.value) }
    }

    private fun flushOnce(store: Store<EntityStore>, controllerRef: Ref<EntityStore>): Boolean {
        val gameTypeId = store.getComponent(controllerRef, ControllerComponent.componentType)?.gameTypeId
            ?: return false
        val stats = store.getComponent(controllerRef, InstanceStats.componentType) ?: return false
        val instanceId = store.getComponent(controllerRef, InstanceId.componentType)?.value ?: return false

        val last = flushed[instanceId] ?: Snapshot(emptyMap(), emptyMap())

        // Deltas against the snapshot — never the raw counters, or a flush
        // after a flush would double-count everything before it.
        val deltaCounters = HashMap<String, Int>()
        for ((stat, value) in stats.counters) {
            val delta = value - (last.counters[stat] ?: 0)
            if (delta != 0) deltaCounters[stat] = delta
        }
        val deltaPlayers = HashMap<UUID, MutableMap<String, Int>>()
        for ((uuid, counters) in stats.perPlayer) {
            for ((stat, value) in counters) {
                val delta = value - (last.perPlayer[uuid]?.get(stat) ?: 0)
                if (delta != 0) deltaPlayers.getOrPut(uuid) { mutableMapOf() }[stat] = delta
            }
        }
        if (deltaCounters.isEmpty() && deltaPlayers.isEmpty()) return false

        // Build the merged ledger in FRESH local structures — never mutate the
        // decoded file state (decoded maps may be immutable, and any throw on
        // the world thread kills the tick thread).
        val ledger = load(gameTypeId)
        val countersOut = LinkedHashMap<String, Int>()
        for ((stat, value) in ledger.counters) countersOut[stat] = value
        for ((stat, delta) in deltaCounters) countersOut[stat] = (countersOut[stat] ?: 0) + delta
        val playersOut = LinkedHashMap<String, MutableMap<String, Int>>()
        for ((uuid, counters) in ledger.players.associate { it.uuid to it.counters }) {
            playersOut[uuid] = LinkedHashMap(counters)
        }
        for ((uuid, counters) in deltaPlayers) {
            val target = playersOut.getOrPut(uuid.toString()) { mutableMapOf() }
            for ((stat, delta) in counters) target[stat] = (target[stat] ?: 0) + delta
        }

        synchronized(fileFor(gameTypeId).toAbsolutePath()) {
            write(
                gameTypeId,
                LifetimeStatsFile(
                    gameTypeId = gameTypeId,
                    counters = countersOut,
                    players = playersOut.map { (uuid, counters) ->
                        LifetimePlayerEntry(uuid, counters)
                    }.toMutableList(),
                ),
            )
        }

        flushed[instanceId] = Snapshot(
            stats.counters.toMap(),
            stats.perPlayer.mapValues { (_, v) -> v.toMap() },
        )
        return true
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
            // Normalize to mutable: the codec's decode path may hand back
            // unmodifiable maps, and callers merge additively in place.
            decoded.copy(
                counters = decoded.counters.toMutableMap(),
                players = decoded.players
                    .map { it.copy(counters = it.counters.toMutableMap()) }
                    .toMutableList(),
            )
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
