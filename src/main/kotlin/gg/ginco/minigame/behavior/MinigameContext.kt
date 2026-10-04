package gg.ginco.minigame.behavior

import com.hypixel.hytale.event.EventRegistry
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.util.PlayerSet
import gg.ginco.minigame.util.Regions
import gg.ginco.minigame.util.Stats
import org.joml.Vector3d
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The facade a [GameBehavior] works through for one minigame instance: the
 * instance world and store, the controller, and every subsystem the
 * framework exposes — membership, regions, stats, the event bus, scheduling.
 *
 * ## Threading
 *
 * Handler-style calls ([onEvent], [runLater]) may run on other threads; the
 * world-mutating helpers ([runOnWorld], stats/region queries) must run on
 * the world thread. The context's [runOnWorld] is the safe bridge.
 *
 * ## Lifecycle
 *
 * Event listeners registered via [onEvent] are unregistered automatically
 * when the behavior deactivates (instance end or explicit disable) — no
 * manual cleanup. World-content state (regions) intentionally survives.
 */
class MinigameContext internal constructor(
    val world: World,
    val store: Store<EntityStore>,
    val controllerRef: Ref<EntityStore>,
    val gameTypeId: String,
    private val eventRegistry: EventRegistry,
) {
    private val unregistrars = mutableListOf<() -> Unit>()
    private var disposed = false

    // ---- members ------------------------------------------------------------

    /** The players currently in the instance (world-thread queries). */
    fun players(): PlayerSet = PlayerSet(store, controllerRef)

    /** The player entity of [uuid] in this world, or null when absent. */
    fun playerEntity(uuid: UUID): Ref<EntityStore>? = PlayerSet.findByUuid(store, uuid)

    /** The membership link of [playerEntity], or null when not a member. */
    fun membershipOf(playerEntity: Ref<EntityStore>): InMinigame? =
        store.getComponent(playerEntity, InMinigame.componentType)

    // ---- events ---------------------------------------------------------

    /**
     * Registers [handler] as a listener for [eventClass] (server events,
     * framework events, or events dispatched by other plugins). The
     * registration is unregistered automatically on deactivation.
     */
    fun <E : Any> onEvent(eventClass: Class<E>, handler: (E) -> Unit) {
        check(!disposed) { "context already deactivated" }
        val registration = eventRegistry.register(eventClass) { event -> handler(event) }!!
        println("[BehaviorDiag] ctx: listener registered for ${eventClass.simpleName}")
        unregistrars.add { runCatching { registration.unregister() } }
    }

    /** [onEvent] for Kotlin callers using a reified type. */
    inline fun <reified E : Any> onEvent(noinline handler: (E) -> Unit) = onEvent(E::class.java, handler)

    // ---- scheduling -------------------------------------------------------

    /** Runs [body] on the instance world's thread. */
    fun runOnWorld(body: () -> Unit) {
        world.execute(body)
    }

    /** Runs [body] on the world thread after [ticks] (~50ms each). */
    fun runLater(ticks: Long, body: () -> Unit) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule({
            runOnWorld(body)
        }, ticks * 50L, TimeUnit.MILLISECONDS)
    }

    // ---- regions ----------------------------------------------------------

    /** Region markers of [type] (all types when null), world content. */
    fun regions(type: String? = null): List<Regions.RegionInfo> = Regions.regionsOf(world, type)

    /** The first marker of [type] containing [position], or null. */
    fun regionAt(type: String, position: Vector3d): Regions.RegionInfo? =
        Regions.regionAt(world, type, position)

    // ---- stats -------------------------------------------------------------

    /** Adds [by] to the instance-wide counter [stat]. */
    fun addStat(stat: String, by: Int = 1) = Stats.add(store, controllerRef, stat, by)

    /** Adds [by] to [playerRef]'s personal counter [stat]. */
    fun addPlayerStat(playerRef: PlayerRef, stat: String, by: Int = 1) =
        Stats.addForPlayer(store, controllerRef, playerRef, stat, by)

    /** The instance-wide value of [stat] (0 when never touched). */
    fun statTotal(stat: String): Int = Stats.total(store, controllerRef, stat)

    /** [playerRef]'s personal value of [stat] (0 when never touched). */
    fun playerStatTotal(playerRef: PlayerRef, stat: String): Int =
        Stats.playerTotal(store, controllerRef, playerRef, stat)

    // ---- internals ---------------------------------------------------------

    /** Unhooks event listeners; called by the host after [GameBehavior.onDeactivate]. */
    internal fun disposeEventListeners() {
        disposed = true
        for (unregistrar in unregistrars) {
            runCatching { unregistrar() }
        }
        unregistrars.clear()
    }
}
