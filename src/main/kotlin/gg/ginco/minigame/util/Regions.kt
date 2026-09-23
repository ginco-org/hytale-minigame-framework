package gg.ginco.minigame.util

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Bounds
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.RegionMarker
import org.joml.Vector3d
import java.util.function.BiConsumer

/** Immutable snapshot of one region marker. */
data class MarkerSnapshot(
    val ref: Ref<EntityStore>,
    val type: String,
    val bounds: Bounds,
) {
    fun contains(position: Vector3d): Boolean = bounds.contains(position)
    fun center(): Vector3d = bounds.center()
}

/**
 * Lookup helpers for the region markers of one minigame instance — the
 * query surface consuming plugins build their game rules on.
 *
 * A region marker is an entity with `Bounds` + `InMinigame` -> its instance
 * + a [RegionMarker] carrying its **type id**. Type ids are arbitrary,
 * plugin-chosen strings ("goal", "capture-point-a", "lasertag-base", ...);
 * the framework fixes no vocabulary. Crossings are reported as events
 * ([gg.ginco.minigame.event.PlayerEnterRegionEvent] carries the type);
 * meaning is attached by consumers.
 *
 * One reserved id: [SPAWN_TYPE] markers are the teleport targets used when
 * an instance starts.
 */
object Regions {

    /** Reserved marker type the framework itself consumes (start-of-game teleports). */
    const val SPAWN_TYPE = "spawn"

    /** Every marker of [controllerRef]'s instance (optionally filtered by [type]), in creation order. */
    fun markersOf(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, type: String? = null): List<MarkerSnapshot> {
        val out = mutableListOf<MarkerSnapshot>()
        val consumer = BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val bounds = chunk.getComponent(i, Bounds.componentType) ?: continue
                val marker = chunk.getComponent(i, RegionMarker.componentType) ?: continue
                val inMinigame = chunk.getComponent(i, InMinigame.componentType) ?: continue
                if (inMinigame.controllerRef != controllerRef) continue
                if (type != null && marker.type != type) continue
                out.add(MarkerSnapshot(chunk.getReferenceTo(i), marker.type, bounds))
            }
        }
        store.forEachChunk(Archetype.of(Bounds.componentType), consumer)
        return out
    }

    /** The first marker of [type] containing [position], or null. */
    fun markerAt(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, type: String, position: Vector3d): MarkerSnapshot? =
        markersOf(store, controllerRef, type).firstOrNull { it.contains(position) }

    /** The type id of the marker entity [marker], or null when it is not a marker. */
    fun typeOf(store: Store<EntityStore>, marker: Ref<EntityStore>): String? =
        store.getComponent(marker, RegionMarker.componentType)?.type
}
