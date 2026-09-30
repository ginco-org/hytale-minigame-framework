package gg.ginco.minigame.util

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.event.PhaseChangedEvent

/**
 * THE phase mutation API: every phase write outside the PhaseSystem's own
 * tick goes through [set], which dispatches [PhaseChangedEvent] on the
 * server's event bus (old kind → new kind). Consumers — including game
 * behaviors — listen for these to drive play-only logic.
 *
 * The PhaseSystem itself writes via its CommandBuffer inside the scan and
 * dispatches its own transitions (Countdown → Playing) post-scan.
 */
object Phases {

    /** Replaces the phase of [ref] and dispatches [PhaseChangedEvent]. */
    fun set(store: Store<EntityStore>, ref: Ref<EntityStore>, phase: Phase) {
        val old = store.getComponent(ref, Phase.componentType)?.kind
        store.replaceComponent(ref, Phase.componentType, phase)
        if (old != phase.kind) {
            HytaleServer.get().eventBus
                .dispatchFor(PhaseChangedEvent::class.java)
                .dispatch(PhaseChangedEvent(ref, old ?: phase.kind, phase.kind))
        }
    }
}
