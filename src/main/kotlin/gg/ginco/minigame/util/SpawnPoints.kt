package gg.ginco.minigame.util

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Bounds
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.SpawnPoint
import java.util.function.BiConsumer

/**
 * Lookup helpers for the spawn-point marker entities of one minigame instance.
 *
 * A spawn point is an entity with [SpawnPoint] + [Bounds] + `InMinigame`
 * (linking it to its instance). `Bounds` is a small box around the position
 * the admin stood on when creating it; [Bounds.center] is the teleport target.
 */
object SpawnPoints {

    data class SpawnSnapshot(val ref: Ref<EntityStore>, val bounds: Bounds)

    /** Every spawn point of [controllerRef]'s instance, in creation order. */
    fun of(store: Store<EntityStore>, controllerRef: Ref<EntityStore>): List<SpawnSnapshot> {
        val out = mutableListOf<SpawnSnapshot>()
        val consumer = BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val spawn = chunk.getComponent(i, SpawnPoint.componentType) ?: continue
                val inMinigame = chunk.getComponent(i, InMinigame.componentType) ?: continue
                if (inMinigame.controllerRef == controllerRef) {
                    val bounds = chunk.getComponent(i, Bounds.componentType)
                    if (bounds != null) out.add(SpawnSnapshot(chunk.getReferenceTo(i), bounds))
                }
            }
        }
        store.forEachChunk(Archetype.of(SpawnPoint.componentType), consumer)
        return out
    }
}
