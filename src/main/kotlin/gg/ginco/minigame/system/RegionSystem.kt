package gg.ginco.minigame.system

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.system.tick.TickingSystem
import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.RegionPresence
import gg.ginco.minigame.event.PlayerEnterRegionEvent
import gg.ginco.minigame.event.PlayerExitRegionEvent
import gg.ginco.minigame.util.MarkerSnapshot
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
 * diff each snapshot against the zone markers of its instance, then — after
 * the scan, since the store must not be written while its processing lock is
 * held — fire the events and update the presence component.
 *
 * `spawn`-type markers do not emit events: they are teleport targets, not
 * gameplay regions.
 *
 * Listeners run synchronously on the world thread during this tick: mutating
 * store/entity state is allowed, blocking is not.
 */
class RegionSystem : TickingSystem<EntityStore>() {

    private class PlayerState(
        val entity: Ref<EntityStore>,
        val player: PlayerRef,
        val controller: Ref<EntityStore>,
        val position: Vector3d,
        val inside: List<Ref<EntityStore>>,
    )

    override fun tick(dt: Float, systemIndex: Int, store: Store<EntityStore>) {
        val players = mutableListOf<PlayerState>()
        val scan = BiConsumer { chunk: ArchetypeChunk<EntityStore>, buffer: CommandBuffer<EntityStore> ->
            for (i in 0 until chunk.size()) {
                val player = chunk.getComponent(i, PlayerRef.getComponentType()) ?: continue // players only
                val inMinigame = chunk.getComponent(i, InMinigame.componentType) ?: continue
                val controller = inMinigame.controllerRef ?: continue
                val position = chunk.getComponent(i, TransformComponent.getComponentType())?.position ?: continue
                players.add(
                    PlayerState(
                        chunk.getReferenceTo(i),
                        player,
                        controller,
                        Vector3d(position),
                        chunk.getComponent(i, RegionPresence.componentType)?.inside?.toList() ?: emptyList(),
                    )
                )
            }
        }
        store.forEachChunk(Archetype.of(InMinigame.componentType), scan)

        val zonesByController = HashMap<Ref<EntityStore>, List<MarkerSnapshot>>()

        for (player in players) {
            val zones = zonesByController.getOrPut(player.controller) {
                Regions.markersOf(store, player.controller).filter { it.type != Regions.SPAWN_TYPE }
            }
            val current = zones.filter { it.contains(player.position) }

            val entered = current.filter { it.ref !in player.inside }
            val exited = player.inside.filter { inside -> current.none { it.ref == inside } }

            for (marker in entered) {
                dispatch(PlayerEnterRegionEvent(player.player, player.entity, player.controller, marker.ref, marker.type))
            }
            for (markerRef in exited) {
                // Exits fire while the marker still exists; markers removed
                // while occupied silently drop out of the tracked presence.
                val type = Regions.typeOf(store, markerRef) ?: continue
                dispatch(PlayerExitRegionEvent(player.player, player.entity, player.controller, markerRef, type))
            }

            if (entered.isNotEmpty() || exited.isNotEmpty()) {
                val newInside = current.map { it.ref }.toMutableList()
                if (store.getComponent(player.entity, RegionPresence.componentType) != null) {
                    store.replaceComponent(player.entity, RegionPresence.componentType, RegionPresence(newInside))
                } else {
                    store.addComponent(player.entity, RegionPresence.componentType, RegionPresence(newInside))
                }
            }
        }
    }

    private fun dispatch(event: Any) {
        when (event) {
            is PlayerEnterRegionEvent ->
                HytaleServer.get().eventBus.dispatchFor(PlayerEnterRegionEvent::class.java).dispatch(event)
            is PlayerExitRegionEvent ->
                HytaleServer.get().eventBus.dispatchFor(PlayerExitRegionEvent::class.java).dispatch(event)
        }
    }
}
