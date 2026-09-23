package gg.ginco.minigame.util

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.TeamData
import java.util.function.BiConsumer

/**
 * Lookup helpers for the team entities of one minigame instance.
 *
 * A team is an entity carrying [TeamData] + `TeamMembers` + `InMinigame`
 * (the latter links it to its instance, exactly like on players — teams
 * "are in" the instance they belong to). Player membership is two-way:
 * the player carries `TeamRef` -> team entity, the team lists the player
 * in `TeamMembers`.
 */
object Teams {

    /** Snapshot of (ref, data) for every team of [controllerRef], ordered by creation. */
    fun of(store: Store<EntityStore>, controllerRef: Ref<EntityStore>): List<TeamSnapshot> {
        val out = mutableListOf<TeamSnapshot>()
        val consumer = BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val data = chunk.getComponent(i, TeamData.componentType) ?: continue
                val inMinigame = chunk.getComponent(i, InMinigame.componentType) ?: continue
                if (inMinigame.controllerRef == controllerRef) {
                    out.add(TeamSnapshot(chunk.getReferenceTo(i), data))
                }
            }
        }
        store.forEachChunk(Archetype.of(TeamData.componentType), consumer)
        return out
    }

    /** The team named [name] within [controllerRef]'s instance, or null. */
    fun findByName(
        store: Store<EntityStore>,
        controllerRef: Ref<EntityStore>,
        name: String
    ): TeamSnapshot? = of(store, controllerRef).firstOrNull { it.data.name == name }

    data class TeamSnapshot(val ref: Ref<EntityStore>, val data: TeamData)
}
