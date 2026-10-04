package gg.ginco.minigame.system

import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.universe.Universe
import gg.ginco.minigame.event.PlayerEnterRegionEvent
import gg.ginco.minigame.event.PlayerExitRegionEvent
import gg.ginco.minigame.util.Regions

/**
 * Bridges the server's builtin **TriggerVolumes** crossings to the framework's
 * typed region events.
 *
 * ## Why an adapter, and not just the builtin event
 *
 * The builtin already does everything the old hand-rolled `RegionSystem` did
 * (per-tick spatial crossing detection with a snapshot, ENTER/EXIT/TICK,
 * per-entity tracking) and reports it as
 * [com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent]. That
 * event, however, is **keyed by world name** and carries only the volume id,
 * entity ref/uuid and the volume's raw tag indexes — a consumer must itself
 * resolve the world, look the volume up in that world's manager to recover the
 * region type, and filter for the framework's tag. It also fires for every
 * volume in the world, tagged by the framework or not.
 *
 * This adapter absorbs that resolution once, centrally, and re-dispatches the
 * framework's own [PlayerEnterRegionEvent] / [PlayerExitRegionEvent] (which
 * carry the region id + open type id, and fire only for framework regions, in
 * every phase). Consumers — behaviors, tests — keep the typed API. A consumer
 * that wants the builtin's raw surface can still listen to `TriggerVolumeEvent`
 * directly; nothing here hides it.
 *
 * ## Threading
 *
 * `TriggerVolumeEvent` is dispatched from the builtin's ticking system, so
 * listeners here run **synchronously on the world thread** during that world's
 * tick (verified: `thread=WorldThread - <world>`). Mutating store/entity state
 * is therefore allowed; blocking is not. The re-dispatched framework events run
 * on the same thread.
 */
object RegionSystem {

    private var registered = false

    /**
     * Subscribes to the builtin event bus. Called from plugin `setup()` like
     * the framework's other lifecycle listeners — start()-time registration
     * does not feed the bus's `hasListener()` gate.
     */
    fun register() {
        if (registered) return
        HytaleServer.get().eventBus.registerGlobal(
            com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent::class.java
        ) { event -> onCrossing(event) }
        registered = true
    }

    private fun onCrossing(
        event: com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent,
    ) {
        val kind = event.triggerEventType
        // Only ENTER/EXIT crossings are reported; TICK is the builtin's own
        // per-tick pulse and is not a region transition.
        val isEnter = kind == com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType.ENTER
        val isExit = kind == com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType.EXIT
        if (!isEnter && !isExit) return

        val world = Universe.get().getWorld(event.worldName) ?: return
        val volume = Regions.managerOf(world)?.getVolume(event.volumeId) ?: return
        val type = Regions.typeOf(volume) ?: return // not a framework region

        val store = world.entityStore.store
        // `spawn` regions are teleport targets, not gameplay regions: they do
        // not emit crossing events (matching the pre-refactor behaviour).
        if (type == Regions.SPAWN_TYPE) return

        val player = Regions.playerEntityOf(store, event.entityRef) ?: return

        val bus = HytaleServer.get().eventBus
        if (isEnter) {
            bus.dispatchFor(PlayerEnterRegionEvent::class.java)
                .dispatch(PlayerEnterRegionEvent(player, event.entityRef, volume.id, type))
        } else {
            bus.dispatchFor(PlayerExitRegionEvent::class.java)
                .dispatch(PlayerExitRegionEvent(player, event.entityRef, volume.id, type))
        }
    }
}
