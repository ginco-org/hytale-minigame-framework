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
 * The [PhaseSystem][gg.ginco.minigame.system.PhaseSystem] dispatches its own
 * transitions through [broadcast] post-scan (Countdown → Playing) and its
 * out-of-scan writes go through [set] (auto-start).
 */
object Phases {

    /** Replaces the phase of [ref] and dispatches [PhaseChangedEvent]. */
    fun set(store: Store<EntityStore>, ref: Ref<EntityStore>, phase: Phase) {
        val old = store.getComponent(ref, Phase.componentType)?.kind
        store.replaceComponent(ref, Phase.componentType, phase)
        broadcast(ref, old ?: phase.kind, phase.kind)
    }

    /**
     * Dispatches [PhaseChangedEvent] for a transition whose component write
     * already happened through another channel (e.g. a CommandBuffer scan —
     * see [gg.ginco.minigame.system.PhaseSystem]). Always call post-scan:
     * listeners run synchronously on the calling thread and must be allowed
     * to write to the store.
     */
    fun broadcast(ref: Ref<EntityStore>, old: Phase.Kind, new: Phase.Kind) {
        if (old == new) return
        HytaleServer.get().eventBus
            .dispatchFor(PhaseChangedEvent::class.java)
            .dispatch(PhaseChangedEvent(ref, old, new))
    }
}
