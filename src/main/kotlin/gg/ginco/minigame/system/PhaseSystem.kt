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
import gg.ginco.minigame.component.TeamRef
import gg.ginco.minigame.util.PlayerSet
import gg.ginco.minigame.util.Teams
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
 * Transitions are phase-component swaps: same component type ([Phase] is one
 * concrete class; only its [Phase.Kind] changes), so the archetype is stable.
 *
 * ## Writes must go through the CommandBuffer
 *
 * Inside the `forEachChunk` consumer the store's processing lock is held, so
 * `store.replaceComponent` etc. throw "Store is currently processing". The
 * ECS hands systems a [CommandBuffer] for exactly this: buffer the write, and
 * it is applied when the scan returns (buffer `consume()` at end of tick/scan).
 * The cleanup pass after the scan runs outside the lock, so plain `Store`
 * writes are legal there.
 */
class PhaseSystem : TickingSystem<EntityStore>() {

    override fun tick(dt: Float, systemIndex: Int, store: Store<EntityStore>) {
        val finished = mutableListOf<Ref<EntityStore>>()

        val scanControllers = BiConsumer { chunk: ArchetypeChunk<EntityStore>, buffer: CommandBuffer<EntityStore> ->
            for (i in 0 until chunk.size()) {
                val ref = chunk.getReferenceTo(i)
                val phase = chunk.getComponent(i, Phase.componentType) ?: continue

                when (phase.kind) {
                    Phase.Kind.COUNTDOWN -> {
                        val remaining = phase.seconds - dt
                        if (remaining <= 0f) {
                            buffer.replaceComponent(ref, Phase.componentType, Phase.playing())
                            PlayerSet(store, ref).sendMessage(Message.raw("The minigame has started!"))
                        } else {
                            buffer.replaceComponent(ref, Phase.componentType, Phase.countdown(remaining))
                        }
                    }

                    Phase.Kind.PLAYING ->
                        buffer.replaceComponent(ref, Phase.componentType, Phase.playing(phase.seconds + dt))

                    Phase.Kind.ENDING -> {
                        val remaining = phase.seconds - dt
                        if (remaining <= 0f) {
                            finished.add(ref)
                        } else {
                            buffer.replaceComponent(ref, Phase.componentType, Phase.ending(phase.reason, remaining))
                        }
                    }

                    Phase.Kind.WAITING, Phase.Kind.CLEANUP -> {}
                }
            }
        }

        store.forEachChunk(Archetype.of(ControllerComponent.componentType, Phase.componentType), scanControllers)

        for (ref in finished) {
            val players = PlayerSet(store, ref).all()
            for (player in players) {
                store.removeComponentIfExists(player, InMinigame.componentType)
                store.removeComponentIfExists(player, Role.componentType)
                store.removeComponentIfExists(player, TeamRef.componentType)
            }
            // Team entities carry InMinigame -> this controller; they must not
            // outlive the instance.
            for (team in Teams.of(store, ref)) {
                store.removeEntity(team.ref, RemoveReason.REMOVE)
            }
            store.removeEntity(ref, RemoveReason.REMOVE)
        }
    }
}
