package gg.ginco.minigame.behavior

import gg.ginco.minigame.util.PlayerSet
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.util.Phases
import gg.ginco.minigame.util.Stats

/**
 * A reusable behavior package that a minigame (or any plugin) can activate
 * for an instance: trigger-driven logic that runs for the lifetime of the
 * activation and tears down cleanly.
 *
 * ## The model
 *
 * - Behaviors are registered by **id** in [GameBehaviors] (open vocabulary,
 *   consumer-owned — like region types and stat ids).
 * - A game **activates** behaviors (via its definition's `behaviors` list);
 *   the framework calls [onActivate] once with a [MinigameContext] bound to
 *   that instance.
 * - The context exposes everything a behavior needs: the game world and
 *   store, the controller, membership queries, region queries, stats, the
 *   event bus, and scheduling. Listeners registered through the context are
 *   unregistered automatically on deactivation.
 * - [onDeactivate] runs when the instance ends. **Strict deactivation**:
 *   drop your state — there is no pause/resume.
 *
 * The framework provides the trigger plumbing; the BEHAVIOR is entirely the
 * package's — an invincibility behavior cancels damage, a no-block-breaking
 * behavior cancels block breaks, a score system listens for region events
 * and updates stats.
 */
abstract class GameBehavior(val id: String) {

    /** Human-readable name for listings (defaults to the id). */
    open val displayName: String get() = id

    /** Called once when the behavior is activated for an instance. */
    open fun onActivate(ctx: MinigameContext) {}

    /** Called once when the behavior is deactivated (instance end or disable). */
    open fun onDeactivate(ctx: MinigameContext) {}

    /** Convenience: the members currently in the instance. World thread. */
    protected fun MinigameContext.members(): PlayerSet = PlayerSet(store, controllerRef)

    override fun toString(): String = "GameBehavior($id)"
}
