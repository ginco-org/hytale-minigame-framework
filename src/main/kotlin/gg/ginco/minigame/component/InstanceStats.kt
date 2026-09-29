package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import java.util.UUID

/**
 * Runtime stats of one minigame instance: instance-wide counters plus
 * per-player counters keyed by UUID (so they survive disconnects — same
 * rule as preserved membership).
 *
 * Stat ids are arbitrary, consumer-chosen strings ("kills", "goals:red",
 * "laps") — the framework fixes no vocabulary and attaches no meaning.
 * The component lives on the controller entity and is released with the
 * instance; nothing here persists to disk yet.
 */
class InstanceStats(
    /** Instance-wide counters: stat id → value. */
    val counters: MutableMap<String, Int> = mutableMapOf(),
    /** Per-player counters: player uuid → (stat id → value). */
    val perPlayer: MutableMap<UUID, MutableMap<String, Int>> = mutableMapOf(),
) : Component<EntityStore> {

    override fun clone(): InstanceStats = InstanceStats(
        counters.toMutableMap(),
        perPlayer.mapValues { (_, v) -> v.toMutableMap() }.toMutableMap(),
    )

    companion object {
        lateinit var componentType: ComponentType<EntityStore, InstanceStats>
    }
}
