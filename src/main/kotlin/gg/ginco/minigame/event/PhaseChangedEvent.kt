package gg.ginco.minigame.event

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Phase

/**
 * Fired when an instance's phase changes
 * (Waiting → Countdown → Playing → Ending → Cleanup).
 *
 * Fired on the world thread of the instance's world. Consumers use this for
 * play-only gating (e.g. region-crossing effects that count only while
 * Playing) and for game start/end triggers.
 */
class PhaseChangedEvent(
    val instance: Ref<EntityStore>,
    val old: Phase.Kind,
    val new: Phase.Kind,
) : com.hypixel.hytale.event.IEvent<Void> {

    override fun toString(): String =
        "PhaseChangedEvent(instance=$instance, old=$old, new=$new)"
}
