package gg.ginco.minigame.util

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.InstanceStats
import java.util.UUID

/**
 * Counter-based stats for one minigame instance — the query/mutation surface
 * consumers build scoreboards and win conditions on.
 *
 * Stat ids are arbitrary, consumer-chosen strings; the framework fixes no
 * vocabulary. Counters live on the controller entity
 * ([InstanceStats]) and are released with the instance — like preserved
 * membership, per-player counters are keyed by UUID so they survive
 * disconnects. Nothing persists to disk yet.
 *
 * All functions must run on the world thread; they mutate components
 * directly (never call them inside a forEachChunk consumer — scan, then
 * mutate).
 */
object Stats {

    /** Adds [by] to the instance-wide counter [stat] (creates it at 0 first). */
    fun add(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, stat: String, by: Int = 1) {
        val stats = statsOf(store, controllerRef)
        stats.counters[stat] = (stats.counters[stat] ?: 0) + by
    }

    /** Adds [by] to [playerRef]'s personal counter [stat] (creates it at 0 first). */
    fun addForPlayer(
        store: Store<EntityStore>,
        controllerRef: Ref<EntityStore>,
        playerRef: PlayerRef,
        stat: String,
        by: Int = 1,
    ) {
        val stats = statsOf(store, controllerRef)
        val playerCounters = stats.perPlayer.getOrPut(playerRef.uuid) { mutableMapOf() }
        playerCounters[stat] = (playerCounters[stat] ?: 0) + by
    }

    /** Instance-wide value of [stat] (0 when never touched). */
    fun total(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, stat: String): Int =
        statsOf(store, controllerRef).counters[stat] ?: 0

    /** [playerRef]'s personal value of [stat] (0 when never touched). */
    fun playerTotal(
        store: Store<EntityStore>,
        controllerRef: Ref<EntityStore>,
        playerRef: PlayerRef,
        stat: String,
    ): Int = statsOf(store, controllerRef).perPlayer[playerRef.uuid]?.get(stat) ?: 0

    /** Snapshot of all instance-wide counters, sorted by stat id. */
    fun countersOf(store: Store<EntityStore>, controllerRef: Ref<EntityStore>): Map<String, Int> {
        val sorted = java.util.TreeMap(statsOf(store, controllerRef).counters)
        val out = LinkedHashMap<String, Int>()
        for ((k, v) in sorted) out[k] = v
        return out
    }

    /** Snapshot of one player's counters, sorted by stat id. */
    fun playerCountersOf(
        store: Store<EntityStore>,
        controllerRef: Ref<EntityStore>,
        playerRef: PlayerRef,
    ): Map<String, Int> {
        val sorted = java.util.TreeMap(statsOf(store, controllerRef).perPlayer[playerRef.uuid] ?: emptyMap())
        val out = LinkedHashMap<String, Int>()
        for ((k, v) in sorted) out[k] = v
        return out
    }

    /**
     * Per-player breakdown of one [stat]: player uuid → value (entries with
     * zero/absent values are omitted). Keys cover disconnected players too —
     * per-player counters are uuid-keyed by design.
     */
    fun breakdown(
        store: Store<EntityStore>,
        controllerRef: Ref<EntityStore>,
        stat: String,
    ): Map<UUID, Int> {
        val out = LinkedHashMap<UUID, Int>()
        for ((uuid, counters) in statsOf(store, controllerRef).perPlayer) {
            counters[stat]?.let { if (it != 0) out[uuid] = it }
        }
        return out
    }

    private fun statsOf(store: Store<EntityStore>, controllerRef: Ref<EntityStore>): InstanceStats =
        store.getComponent(controllerRef, InstanceStats.componentType)
            ?: InstanceStats().also { store.addComponent(controllerRef, InstanceStats.componentType, it) }
}
