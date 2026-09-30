package gg.ginco.minigame.system

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.system.tick.TickingSystem
import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.RegionPresence
import gg.ginco.minigame.event.PlayerEnterRegionEvent
import gg.ginco.minigame.event.PlayerExitRegionEvent
import gg.ginco.minigame.util.Regions
import org.joml.Vector3d
import java.util.function.BiConsumer

/**
 * Detects region boundary crossings and reports them as events — nothing
 * more. This system deliberately attaches NO behaviour to crossings: whether
 * entering a death zone eliminates a player or entering a goal wins the game
 * is minigame logic, written by consuming plugins as listeners for
 * [PlayerEnterRegionEvent] / [PlayerExitRegionEvent]. Events fire in every
 * instance phase; consumers gate on the instance's `Phase` themselves when
 * their rule is play-only.
 *
 * Per tick: snapshot the players once (position + current [RegionPresence]),
 * diff each snapshot against the region markers of the player's WORLD, then
 * fire the events and update the presence component. All writes happen after
 * the scan (the store's processing lock forbids writes inside consumers).
 *
 * Regions are world content (chunk-store resource) — the same arena markers
 * are visible to every minigame instance running in that world, which is
 * correct because an instance *is* a world. `spawn`-type regions do not emit
 * events: they are teleport targets, not gameplay regions.
 *
 * Listeners run synchronously on the world thread during this tick: mutating
 * store/entity state is allowed, blocking is not.
 */
class RegionSystem : TickingSystem<EntityStore>() {

    private class PlayerState(
        val entity: com.hypixel.hytale.component.Ref<EntityStore>,
        val player: PlayerRef,
        val position: Vector3d,
        val inside: List<String>,
    )

    override fun tick(dt: Float, systemIndex: Int, store: Store<EntityStore>) {
        val players = mutableListOf<PlayerState>()
        val scan = BiConsumer { chunk: com.hypixel.hytale.component.ArchetypeChunk<EntityStore>,
                                 buffer: com.hypixel.hytale.component.CommandBuffer<EntityStore> ->
            for (i in 0 until chunk.size()) {
                val player = chunk.getComponent(i, PlayerRef.getComponentType()) ?: continue
                val position = chunk.getComponent(i, TransformComponent.getComponentType())?.position ?: continue
                players.add(
                    PlayerState(
                        chunk.getReferenceTo(i),
                        player,
                        Vector3d(position),
                        chunk.getComponent(i, RegionPresence.componentType)?.inside?.toList() ?: emptyList(),
                    )
                )
            }
        }
        store.forEachChunk(Archetype.of(PlayerRef.getComponentType()), scan)

        val world = store.externalData.world
        val zones = Regions.markersOf(world).filter { it.type != Regions.SPAWN_TYPE }

        for (player in players) {
            val current = zones.filter { it.contains(player.position) }

            val entered = current.filter { it.id !in player.inside }
            val exited = player.inside.filter { id -> current.none { it.id == id } }
            if (entered.isNotEmpty() || exited.isNotEmpty() || System.getProperty("hytest.regionTraceAll") == "true" ||
                (player.inside.isNotEmpty() || current.isNotEmpty())) {
                println("[RegionTrace] player=${player.player.username} inside=${player.inside} " +
                    "current=${current.map { it.id }} entered=${entered.map { it.id }} exited=${exited}")
            }

            for (marker in entered) {
                dispatch(PlayerEnterRegionEvent(player.player, player.entity, marker.id, marker.type))
            }
            for (markerId in exited) {
                // Exit events fire while the marker still exists; markers
                // removed while occupied silently drop out of the presence.
                val type = Regions.typeOf(world, markerId) ?: continue
                dispatch(PlayerExitRegionEvent(player.player, player.entity, markerId, type))
            }

            if (entered.isNotEmpty() || exited.isNotEmpty()) {
                val presence = store.getComponent(player.entity, RegionPresence.componentType)
                val newInside = current.map { it.id }.toMutableList()
                if (presence != null) {
                    presence.inside.clear()
                    presence.inside.addAll(newInside)
                } else {
                    store.addComponent(player.entity, RegionPresence.componentType, RegionPresence(newInside))
                }
            }
        }
    }

    private fun dispatch(event: Any) {
        when (event) {
            is PlayerEnterRegionEvent -> {
                println("[RegionTrace] dispatch ENTER ${event.regionId} for ${event.player.username}")
                HytaleServer.get().eventBus.dispatchFor(PlayerEnterRegionEvent::class.java).dispatch(event)
            }
            is PlayerExitRegionEvent -> {
                println("[RegionTrace] dispatch EXIT ${event.regionId} for ${event.player.username}")
                HytaleServer.get().eventBus.dispatchFor(PlayerExitRegionEvent::class.java).dispatch(event)
            }
        }
    }
}
