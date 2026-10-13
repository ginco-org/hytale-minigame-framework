package gg.ginco.minigame.util

import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.component.TeamMembers
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

    /** Archetype of a team entity: exactly these three components. */
    val TEAM_ARCHETYPE: Archetype<EntityStore> by lazy {
        Archetype.of(TeamData.componentType, TeamMembers.componentType, InMinigame.componentType)
    }

    /**
     * Creates a team entity linked to [controllerRef] carrying [data].
     * Returns the team ref, or null when the store rejected the entity.
     * (Holder arrays are slot-indexed by ComponentType — see CreateCommand.)
     */
    fun createTeam(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, data: TeamData): Ref<EntityStore>? {
        val archetype = TEAM_ARCHETYPE
        @Suppress("UNCHECKED_CAST")
        val components = arrayOfNulls<Component<EntityStore>>(archetype.length())
        components[TeamData.componentType.index] = data
        components[TeamMembers.componentType.index] = TeamMembers()
        components[InMinigame.componentType.index] = InMinigame(controllerRef)
        val holder = store.registry.newHolder(archetype, components as Array<Component<EntityStore>>)
        return store.addEntity(holder, AddReason.SPAWN)
    }


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

    /**
     * The join-default team: the **lightest** rostered team of [controllerRef]
     * that still has room (`maxPlayers <= 0` means uncapped). Ties keep
     * creation order — definition order — so sequential joins alternate
     * deterministically and both sides fill up evenly. Null when the instance
     * has no teams (players then join unteamed) or every team is full; a team
     * without a roster component is skipped, never partially joined.
     */
    fun smallest(store: Store<EntityStore>, controllerRef: Ref<EntityStore>): TeamSnapshot? {
        var best: TeamSnapshot? = null
        var bestSize = Int.MAX_VALUE
        for (team in of(store, controllerRef)) {
            val roster = store.getComponent(team.ref, TeamMembers.componentType)?.members ?: continue
            val max = team.data.maxPlayers
            if (max > 0 && roster.size >= max) continue // full
            if (roster.size < bestSize) {
                best = team
                bestSize = roster.size
            }
        }
        return best
    }

    data class TeamSnapshot(val ref: Ref<EntityStore>, val data: TeamData)
}
