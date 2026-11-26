package gg.ginco.minigame.system

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.system.tick.TickingSystem
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.PreStartFreeze
import gg.ginco.minigame.component.Role
import java.util.function.BiConsumer

/**
 * Keeps participants frozen for the whole pre-start wait.
 *
 * Queued players are only moved into the map when the countdown starts; direct
 * joins land in the map during WAITING. Both happen before the game is live, and
 * without this system they can walk around the arena (and reach objectives)
 * during the countdown. The system reconciles every tick instead of reacting to
 * events, so it also covers players who were already in the world when the
 * countdown began and players arriving mid-countdown.
 *
 * Only [Role.Kind.PARTICIPANT]s freeze — spectators keep flying around — and
 * only freezes this system applied are undone ([PreStartFreeze]).
 */
class PlayerFreezeSystem : TickingSystem<EntityStore>() {

    override fun tick(dt: Float, systemIndex: Int, store: Store<EntityStore>) {
        val preStart = mutableSetOf<Ref<EntityStore>>()
        val scanControllers = BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val phase = chunk.getComponent(i, Phase.componentType) ?: continue
                if (phase.kind == Phase.Kind.WAITING || phase.kind == Phase.Kind.COUNTDOWN) {
                    preStart.add(chunk.getReferenceTo(i))
                }
            }
        }
        store.forEachChunk(Archetype.of(ControllerComponent.componentType, Phase.componentType), scanControllers)

        val freeze = mutableListOf<Ref<EntityStore>>()
        val thaw = mutableListOf<Ref<EntityStore>>()
        val scanPlayers = BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val ref = chunk.getReferenceTo(i)
                val inMinigame = chunk.getComponent(i, InMinigame.componentType)
                val role = chunk.getComponent(i, Role.componentType)
                val marked = chunk.getComponent(i, PreStartFreeze.componentType) != null
                val shouldFreeze = role?.kind == Role.Kind.PARTICIPANT &&
                    inMinigame?.controllerRef?.let { it in preStart } == true
                if (shouldFreeze && !marked) freeze.add(ref)
                else if (!shouldFreeze && marked) thaw.add(ref)
            }
        }
        store.forEachChunk(Archetype.of(PlayerRef.getComponentType()), scanPlayers)

        for (ref in freeze) PreStartFreeze.apply(store, ref)
        for (ref in thaw) PreStartFreeze.clear(store, ref)
    }
}
