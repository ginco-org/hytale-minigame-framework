package gg.ginco.minigame.system

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.RemoveReason
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.system.tick.TickingSystem
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.TeamRef
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import gg.ginco.minigame.util.Instances
import gg.ginco.minigame.util.PlayerSet
import gg.ginco.minigame.util.Regions
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
        val started = mutableListOf<Ref<EntityStore>>()
        // Stats flush candidates — only collected on a due tick, so the scan
        // below stays free of extra work the other 199 ticks out of 200.
        val statsHolders = mutableListOf<Ref<EntityStore>>()
        val flushDue = tickAndCheckFlushDue()

        val scanControllers = BiConsumer { chunk: ArchetypeChunk<EntityStore>, buffer: CommandBuffer<EntityStore> ->
            for (i in 0 until chunk.size()) {
                val ref = chunk.getReferenceTo(i)
                val phase = chunk.getComponent(i, Phase.componentType) ?: continue
                if (flushDue && chunk.getComponent(i, gg.ginco.minigame.component.InstanceStats.componentType) != null) {
                    statsHolders.add(ref)
                }

                when (phase.kind) {
                    Phase.Kind.COUNTDOWN -> {
                        val remaining = phase.seconds - dt
                        if (remaining <= 0f) {
                            buffer.replaceComponent(ref, Phase.componentType, Phase.playing())
                            PlayerSet(store, ref).sendMessage(Message.raw("The minigame has started!"))
                            started.add(ref)
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

        for (ref in started) {
            teleportPlayersToSpawnPoints(store, ref)
        }

        // Periodic stats flush while the game is LIVE — a crash mid-game then
        // costs the ticks since the last flush, not the whole game's counters.
        // Runs post-scan (outside the processing lock); each instance's write
        // is isolated so one bad ledger cannot kill the tick thread.
        if (flushDue) {
            for (ref in statsHolders) {
                try {
                    gg.ginco.minigame.stats.LifetimeStats.flush(store, ref)
                } catch (e: Exception) {
                    println("[MinigameFramework] periodic stats flush failed for $ref: $e")
                }
            }
        }

        for (ref in finished) {
            // A throwing cleanup must never kill the world's ticking thread:
            // isolate each instance's teardown and surface the failure.
            try {
            // Persist the finished game's counters into the lifetime ledger
            // before the controller (and its stats) is released.
            gg.ginco.minigame.stats.LifetimeStats.record(store, ref)
            val cleanupWorld = store.externalData.world
            gg.ginco.minigame.behavior.GameBehaviors.deactivateFor(cleanupWorld, ref)
            // An instance that runs in its own world is torn down whole: the
            // world, its controller, its children AND its players (who are sent
            // back to their lobby). The in-world path below would leave the
            // world running with nobody in it and players stranded inside.
            val world = store.externalData.world
            if (Instances.isInstanceWorld(world)) {
                Instances.endInstance(world, ref)
                continue
            }

            val players = PlayerSet(store, ref).all()
            for (player in players) {
                store.removeComponentIfExists(player, InMinigame.componentType)
                store.removeComponentIfExists(player, Role.componentType)
                store.removeComponentIfExists(player, TeamRef.componentType)
            }
            // Remove every non-player child entity linked to this instance
            // (teams, spawn-point markers, future marker types) — they must
            // not outlive it.
            val children = mutableListOf<Ref<EntityStore>>()
            val scanChildren = BiConsumer { chunk: ArchetypeChunk<EntityStore>, buffer: CommandBuffer<EntityStore> ->
                for (i in 0 until chunk.size()) {
                    val inMinigame = chunk.getComponent(i, InMinigame.componentType) ?: continue
                    if (inMinigame.controllerRef != ref) continue
                    if (chunk.getComponent(i, PlayerRef.getComponentType()) != null) continue
                    children.add(chunk.getReferenceTo(i))
                }
            }
            store.forEachChunk(Archetype.of(InMinigame.componentType), scanChildren)
            for (child in children) {
                store.removeEntity(child, RemoveReason.REMOVE)
            }
            store.removeEntity(ref, RemoveReason.REMOVE)
            } catch (e: Exception) {
                // A throwing cleanup must never kill the world's ticking
                // thread — that would strand every later world interaction
                // with "thread not accepting tasks" and cascade across tests.
                println("[MinigameFramework] instance cleanup failed for $ref: $e")
            }
        }
    }

    /**
     * Teleports the instance's players to its spawn-point markers when the
     * game starts: player i goes to target `i % n` (volumes first, then
     * points, each by id). With no markers players simply stay where they are.
     * Runs after the scan, on the world thread, so direct [TransformComponent]
     * mutation is legal.
     */
    private fun teleportPlayersToSpawnPoints(store: Store<EntityStore>, controllerRef: Ref<EntityStore>) {
        val world = store.externalData.world
        val spawns = Regions.spawnPointsOf(world)
        if (spawns.isEmpty()) return

        val players = PlayerSet(store, controllerRef).all()
        for ((index, player) in players.withIndex()) {
            val target = spawns[index % spawns.size]
            val transform = store.getComponent(player, TransformComponent.getComponentType()) ?: continue
            target.applyTo(transform)
        }
    }

    /** Advances this world's tick counter; true on flush-due ticks. */
    private fun tickAndCheckFlushDue(): Boolean {
        val n = TICK_COUNTER.get() + 1
        TICK_COUNTER.set(n)
        return n % FLUSH_INTERVAL_TICKS == 0
    }

    companion object {
        /**
         * Ticks between periodic stats flushes (200 = ~10s at 20 TPS): often
         * enough that a crash costs little, rarely enough that the atomic
         * temp+move write stays noise.
         */
        internal const val FLUSH_INTERVAL_TICKS = 200

        /**
         * One counter per WORLD THREAD: a single PhaseSystem instance is
         * ticked by every world, and each world's store may only be touched
         * from its own thread (same pattern as the builtin TriggerVolumes
         * system's thread-local scratch state).
         */
        private val TICK_COUNTER = ThreadLocal.withInitial { 0 }
    }
}
