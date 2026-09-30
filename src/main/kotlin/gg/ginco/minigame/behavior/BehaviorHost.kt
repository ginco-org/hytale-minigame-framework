package gg.ginco.minigame.behavior

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.event.EventRegistry
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Phase

/**
 * Activates and tracks [GameBehavior]s for one minigame instance.
 *
 * Built when an instance spawns with behaviors in its definition; the host
 * owns the behavior instances and their context, and is released when the
 * instance ends (deactivation unhooks every event listener and drops
 * behavior state — strict deactivation).
 */
class BehaviorHost internal constructor(
    val world: World,
    val store: Store<EntityStore>,
    val controllerRef: Ref<EntityStore>,
    val gameTypeId: String,
    behaviorIds: List<String>,
    eventRegistry: EventRegistry,
) {
    private val context = MinigameContext(world, store, controllerRef, gameTypeId, eventRegistry)
    private val active = mutableListOf<GameBehavior>()
    private var deactivated = false

    val behaviorIds: List<String> get() = active.map { it.id }

    init {
        for (id in behaviorIds) {
            val behavior = GameBehaviors.create(id, context)
            if (behavior != null) {
                active.add(behavior)
                println("[BehaviorDiag] host: created behavior $id")
            } else {
                println("[BehaviorDiag] host: unknown behavior '$id' — skipping (known: ${GameBehaviors.ids()})")
            }
        }
    }

    /** Activates every behavior this host was built with (idempotent). */
    fun activateAll() {
        for (behavior in active) {
            println("[BehaviorDiag] host: activating ${behavior.id}")
            behavior.onActivate(context)
        }
    }

    /** Deactivates every behavior (idempotent). */
    fun deactivateAll() {
        if (deactivated) return
        deactivated = true
        for (behavior in active.asReversed()) {
            runCatching { behavior.onDeactivate(context) }
                .onFailure { e -> println("[MinigameFramework] behavior ${behavior.id} deactivation failed: $e") }
        }
        context.disposeEventListeners()
        active.clear()
    }
}
