package gg.ginco.minigame.behavior

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.event.EventRegistry
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * Registry of [GameBehavior]s: the open, consumer-owned vocabulary of
 * behavior packages plugins can provide and games can activate.
 *
 * Consumer plugins register in their own setup:
 *
 * ```kotlin
 * GameBehaviors.register("capture-behavior") { ctx -> CaptureBehavior(ctx) }
 * ```
 *
 * Games then activate by id (definition `behaviors` list). Registration is
 * global (one framework classloader per server); ids must be unique — a
 * duplicate registration replaces the previous factory.
 */
object GameBehaviors {

    fun interface Factory {
        fun create(ctx: MinigameContext): GameBehavior
    }

    private val factories = LinkedHashMap<String, Factory>()
    private val hosts = mutableListOf<BehaviorHost>()

    /** Wires the registry to the framework plugin's event registry (context events). */
    internal fun init(eventRegistry: EventRegistry) {
        eventRegistryForContexts = eventRegistry
    }

    internal var eventRegistryForContexts: EventRegistry? = null
        private set

    /** Registers a behavior factory under [id]. Overwrites an existing id. */
    fun register(id: String, factory: Factory) {
        factories[id] = factory
    }

    fun ids(): List<String> = factories.keys.toList()

    /** All live behavior hosts (one per activated instance). */
    fun hosts(): List<BehaviorHost> = hosts.toList()

    internal fun create(id: String, ctx: MinigameContext): GameBehavior? {
        val factory = factories[id] ?: return null
        return factory.create(ctx)
    }

    internal fun track(host: BehaviorHost) {
        hosts.add(host)
    }

    internal fun untrack(host: BehaviorHost) {
        hosts.remove(host)
    }

    /**
     * Activates [behaviorIds] for an instance: builds the host (context bound
     * to [world]/[store]/[controllerRef]), instantiates each behavior and
     * runs [BehaviorHost.activateAll]. Returns the host.
     */
    fun activate(
        world: World,
        store: Store<EntityStore>,
        controllerRef: Ref<EntityStore>,
        gameTypeId: String,
        behaviorIds: List<String>,
    ): BehaviorHost {
        val host = BehaviorHost(world, store, controllerRef, gameTypeId, behaviorIds,
            eventRegistryForContexts ?: error("GameBehaviors not initialized"))
        track(host)
        host.activateAll()
        return host
    }

    /** Deactivates the host bound to [controllerRef] in [world], if any. */
    fun deactivateFor(world: World, controllerRef: Ref<EntityStore>) {
        val host = hosts.firstOrNull { it.world == world && it.controllerRef == controllerRef } ?: return
        host.deactivateAll()
        untrack(host)
    }
}
