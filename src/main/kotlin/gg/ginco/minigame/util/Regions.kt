package gg.ginco.minigame.util

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Bounds
import gg.ginco.minigame.component.CaptureZone
import gg.ginco.minigame.component.DeathZone
import gg.ginco.minigame.component.Goal
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.SafeZone
import gg.ginco.minigame.component.SpawnPoint
import org.joml.Vector3d
import java.util.function.BiConsumer

/**
 * The marker vocabulary of the framework, plus lookup helpers for consumers.
 *
 * A region marker is an entity with `Bounds` + `InMinigame` -> its instance,
 * plus one kind component ([SpawnPoint], [Goal], [DeathZone], [SafeZone],
 * [CaptureZone]). The kinds are DATA, not behaviour: the framework reports
 * boundary crossings as events
 * ([gg.ginco.minigame.event.PlayerEnterRegionEvent] carries [kind]); what a
 * death zone or a goal *means* is implemented by the consuming plugin.
 *
 * Component types are resolved lazily — they exist only after
 * `Components.register` ran during plugin setup.
 */
enum class RegionKind(val kindName: String, private val type: () -> ComponentType<EntityStore, out Component<EntityStore>>) {
    SPAWN("spawn", { SpawnPoint.componentType }),
    GOAL("goal", { Goal.componentType }),
    DEATH("death", { DeathZone.componentType }),
    SAFE("safe", { SafeZone.componentType }),
    CAPTURE("capture", { CaptureZone.componentType }),
    ;

    @Suppress("UNCHECKED_CAST")
    fun componentType(): ComponentType<EntityStore, Component<EntityStore>> =
        type() as ComponentType<EntityStore, Component<EntityStore>>

    companion object {
        fun fromName(name: String): RegionKind? = entries.firstOrNull { it.kindName == name }

        /** The kind whose marker component is present on [marker], or null. */
        fun ofComponent(store: Store<EntityStore>, marker: Ref<EntityStore>): RegionKind? =
            entries.firstOrNull { store.getComponent(marker, it.componentType()) != null }
    }
}

/** Immutable snapshot of one region marker. */
data class MarkerSnapshot(
    val ref: Ref<EntityStore>,
    val kind: RegionKind,
    val bounds: Bounds,
) {
    fun contains(position: Vector3d): Boolean = bounds.contains(position)
    fun center(): Vector3d = bounds.center()
}

/**
 * Lookup helpers for the region markers of one minigame instance — the
 * query surface consuming plugins build their game rules on.
 */
object Regions {

    /** Every marker of [controllerRef]'s instance, any kind, in creation order. */
    fun markersOf(store: Store<EntityStore>, controllerRef: Ref<EntityStore>): List<MarkerSnapshot> {
        val out = mutableListOf<MarkerSnapshot>()
        val consumer = BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val bounds = chunk.getComponent(i, Bounds.componentType) ?: continue
                val inMinigame = chunk.getComponent(i, InMinigame.componentType) ?: continue
                if (inMinigame.controllerRef != controllerRef) continue
                val kind = RegionKind.entries.firstOrNull { chunk.getComponent(i, it.componentType()) != null }
                if (kind != null) out.add(MarkerSnapshot(chunk.getReferenceTo(i), kind, bounds))
            }
        }
        store.forEachChunk(Archetype.of(Bounds.componentType), consumer)
        return out
    }

    /** Markers of one kind within [controllerRef]'s instance, in creation order. */
    fun markersOf(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, kind: RegionKind): List<MarkerSnapshot> =
        markersOf(store, controllerRef).filter { it.kind == kind }

    /** The first marker of [kind] containing [position], or null. */
    fun markerAt(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, kind: RegionKind, position: Vector3d): MarkerSnapshot? =
        markersOf(store, controllerRef, kind).firstOrNull { it.contains(position) }
}
