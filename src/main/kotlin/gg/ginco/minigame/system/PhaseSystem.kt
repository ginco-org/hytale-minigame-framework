package gg.ginco.minigame.system

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.RemoveReason
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.system.tick.TickingSystem
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.util.PlayerSet
import java.util.function.BiConsumer

/**
 * Advances every minigame instance through its lifecycle each world tick:
 *
 * ```
 * Waiting -> Countdown(remaining--) -> Playing(elapsed++) -> Ending(remaining--) -> removed
 * ```
 *
 * When an instance is removed, its players are released (InMinigame + Role stripped)
 * so nobody is stuck referencing a dead controller.
 *
 * Transitions are phase-component swaps: same component type, so the archetype is
 * stable and in-place replacement during chunk iteration is safe.
 */
class PhaseSystem : TickingSystem<EntityStore>() {

    override fun tick(dt: Float, systemIndex: Int, store: Store<EntityStore>) {
        val finished = mutableListOf<Ref<EntityStore>>()

        val scanControllers = BiConsumer { chunk: ArchetypeChunk<EntityStore>, _: CommandBuffer<EntityStore> ->
            for (i in 0 until chunk.size()) {
                val ref = chunk.getReferenceTo(i)
                val phase = chunk.getComponent(i, Phase.componentType) ?: continue

                when (phase) {
                    is Phase.Countdown -> {
                        val remaining = phase.remainingSeconds - dt
                        if (remaining <= 0f) {
                            store.replaceComponent(ref, Phase.componentType, Phase.Playing())
                            PlayerSet(store, ref).sendMessage(Message.raw("The minigame has started!"))
                        } else {
                            store.replaceComponent(ref, Phase.componentType, Phase.Countdown(remaining))
                        }
                    }

                    is Phase.Playing ->
                        store.replaceComponent(ref, Phase.componentType, Phase.Playing(phase.elapsedSeconds + dt))

                    is Phase.Ending -> {
                        val remaining = phase.remainingSeconds - dt
                        if (remaining <= 0f) {
                            finished.add(ref)
                        } else {
                            store.replaceComponent(ref, Phase.componentType, Phase.Ending(phase.reason, remaining))
                        }
                    }

                    Phase.Waiting, Phase.Cleanup -> {}
                }
            }
        }

        store.forEachChunk(Archetype.of(ControllerComponent.componentType, Phase.componentType), scanControllers)

        for (ref in finished) {
            val players = PlayerSet(store, ref).all()
            for (player in players) {
                store.removeComponentIfExists(player, InMinigame.componentType)
                store.removeComponentIfExists(player, Role.componentType)
            }
            store.removeEntity(ref, RemoveReason.REMOVE)
        }
    }
}
