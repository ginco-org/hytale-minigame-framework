package gg.ginco.minigame

import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.InstanceStats
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Stats

/**
 * Stats pillar v1: generic counter buckets on the instance (instance-wide +
 * per-player, uuid-keyed). Stat ids are consumer vocabulary; counters are
 * released with the instance.
 */
class MinigameStatsTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String): Int {
        alice.runCommand("minigame create $gameType").join()
        return t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            MinigameTestHarness.instanceIdOf(store, MinigameTestHarness.controllerOfGameType(store, gameType)!!)
        }!!
    }

    private fun controllerOf(t: HytaleTestHelper, gameType: String) = t.runOnWorldReturning {
        val store = MinigameTestHarness.storeOf(t.world)
        MinigameTestHarness.controllerOfGameType(store, gameType)!!
    }

    /** Adds accumulate; instance-wide and per-player counters are independent. */
    @HytaleTest(timeoutSeconds = 60)
    fun addAccumulatesInstanceAndPlayerCounters(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()
        bob.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            Stats.add(store, controller, "kills", 2)
            Stats.addForPlayer(store, controller, alice.ref(), "kills", 1)
        }

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            t.assertEquals(2, Stats.total(store, controller, "kills"), "instance-wide counter")
            t.assertEquals(1, Stats.playerTotal(store, controller, alice.ref(), "kills"),
                "alice's personal counter")
            t.assertEquals(0, Stats.playerTotal(store, controller, bob.ref(), "kills"),
                "bob's counter should default to 0")
        }
        t.succeed()
    }

    /** Counters are per instance — another instance starts at zero. */
    @HytaleTest(timeoutSeconds = 60)
    fun statsArePerInstance(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "one")
        createInstance(t, alice, "two")
        alice.runCommand("minigame join").join() // joins the lowest-id instance ("one")

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val one = MinigameTestHarness.controllerOfGameType(store, "one")!!
            val two = MinigameTestHarness.controllerOfGameType(store, "two")!!
            Stats.add(store, one, "goals", 3)
            t.assertEquals(3, Stats.total(store, one, "goals"), "instance one counter")
            t.assertEquals(0, Stats.total(store, two, "goals"),
                "the other instance must not see one's counters")
        }
        t.succeed()
    }

    /** Per-player counters are uuid-keyed: they survive disconnect + rejoin. */
    @HytaleTest(timeoutSeconds = 60)
    fun playerCountersSurviveDisconnectAndRejoin(t: HytaleTestHelper) {
        val uuid = java.util.UUID.randomUUID()
        val alice = t.spawnSimulatedPlayer("Alice", uuid)
        MinigameTestHarness.grantAdmin(alice.ref())
        createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            Stats.addForPlayer(store, controller, alice.ref(), "laps", 4)
        }

        alice.disconnect()
        val alice2 = alice.reconnect().join()
        alice2.runCommand("minigame join").join() // restores membership

        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(4, Stats.playerTotal(store, controller, alice2.ref(), "laps"),
                    "uuid-keyed counters survive the disconnect/rejoin round trip")
            }
        }
        t.succeed()
    }

    /** `/minigame stats` prints totals and per-player breakdowns by name. */
    @HytaleTest(timeoutSeconds = 60)
    fun statsCommandPrintsCounters(t: HytaleTestHelper) {
        val alice = player(t)
        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame team join red").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            Stats.add(store, controller, "goals", 2)
            Stats.addForPlayer(store, controller, alice.ref(), "goals", 1)
        }
        alice.drainReceivedMessages()

        alice.runCommand("minigame stats").join()
        t.assertTrue(alice.sawMessage("Instance #$instanceId stats: goals=2"),
            "totals should be printed, got: ${alice.receivedMessages()}")
        t.assertTrue(alice.sawMessage("goals: Alice=1"),
            "per-player breakdown should use member names, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    /** Stats are released with the instance. */
    @HytaleTest(timeoutSeconds = 60)
    fun statsDieWithInstance(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            Stats.add(store, controller, "kills", 5)
        }

        alice.runCommand("minigame stop").join()
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                t.assertTrue(MinigameTestHarness.controllers(store).isEmpty(), "instance should be gone")
            }
        }
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            var leftover = 0
            store.forEachChunk(
                com.hypixel.hytale.component.Archetype.of(InstanceStats.componentType),
                java.util.function.BiConsumer { chunk: com.hypixel.hytale.component.ArchetypeChunk<EntityStore>, _ ->
                    leftover += chunk.size()
                }
            )
            t.assertEquals(0, leftover, "stats components must be released with their instance")
        }
        t.succeed()
    }

    /**
     * Lifetime ledger: recorded at cleanup, merged across instances.
     *
     * Own game type id + a `before` baseline: discovery order across classes
     * follows the test jar's entry order, which is not stable across
     * rebuilds, and the ledger file accumulates whatever wrote it earlier —
     * an absolute assert here flips green/red on execution order alone.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun lifetimeStatsRecordAndMerge(t: HytaleTestHelper) {
        val alice = player(t)
        val gameType = "lifetime-record"
        val before = gg.ginco.minigame.stats.LifetimeStats.load(gameType).counters["goals"] ?: 0
        createInstance(t, alice, gameType)
        alice.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, gameType)!!
            Stats.add(store, controller, "goals", 2)
            Stats.addForPlayer(store, controller, alice.ref(), "goals", 1)
        }

        alice.runCommand("minigame stop").join()
        // ENDING grace (3s) runs before cleanup records the ledger.
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                t.assertTrue(MinigameTestHarness.controllers(store).isEmpty(), "instance should be gone")
            }
        }

        val ledger = gg.ginco.minigame.stats.LifetimeStats.load(gameType)
        t.assertEquals(before + 2, ledger.counters["goals"], "lifetime instance counter should accumulate")
        val playerEntry = ledger.players.singleOrNull { it.uuid == alice.uuid.toString() }
        t.assertNotNull(playerEntry, "player entry should exist")
        t.assertEquals(1, playerEntry?.counters?.get("goals"), "player lifetime counter should accumulate")
        t.succeed()
    }

    /** A second instance of the same game type MERGES into the ledger (adds, not replaces). */
    @HytaleTest(timeoutSeconds = 60)
    fun lifetimeStatsMergeAcrossInstances(t: HytaleTestHelper) {
        val alice = player(t)
        val gameType = "lifetime-merge"
        // `before`, not an absolute, and an own game type id: the ledger
        // accumulates whatever ran earlier, and test-class order follows the
        // rebuilt jar's entry order — not stable across runs.
        val before = gg.ginco.minigame.stats.LifetimeStats.load(gameType).counters["goals"] ?: 0

        createInstance(t, alice, gameType)
        alice.runCommand("minigame join").join()
        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, gameType)!!
            Stats.add(store, controller, "goals", 3)
        }
        alice.runCommand("minigame stop").join()
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                t.assertTrue(MinigameTestHarness.controllers(store).isEmpty(), "instance should be gone")
            }
        }

        t.assertEquals(before + 3, gg.ginco.minigame.stats.LifetimeStats.load(gameType).counters["goals"],
            "the second instance's goals must ADD to the ledger")
        t.succeed()
    }

    /**
     * Periodic flush: the ledger is written WHILE the instance is live — a
     * crash mid-game costs the ticks since the last flush, not the game.
     * PhaseSystem flushes every FLUSH_INTERVAL_TICKS (~10s).
     */
    @HytaleTest(timeoutSeconds = 120)
    fun periodicFlushWritesWhileInstanceIsLive(t: HytaleTestHelper) {
        val alice = player(t)
        val gameType = "lifetime-flush"
        val before = gg.ginco.minigame.stats.LifetimeStats.load(gameType).counters["goals"] ?: 0
        createInstance(t, alice, gameType)
        alice.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, gameType)!!
            Stats.add(store, controller, "goals", 2)
        }

        // Still playing — no stop, no cleanup — and the ledger already holds
        // the counters. 6x the flush interval covers scheduler jitter.
        t.assertEventually(60) {
            t.runOnWorld {
                t.assertTrue(MinigameTestHarness.controllers(MinigameTestHarness.storeOf(world)).isNotEmpty(),
                    "the instance must still be live — this is a mid-game flush")
            }
            t.assertEquals(before + 2, gg.ginco.minigame.stats.LifetimeStats.load(gameType).counters["goals"],
                "the periodic flush should write the live counters to the ledger")
        }

        alice.runCommand("minigame stop").join()
        t.assertEventually {
            t.runOnWorld {
                t.assertTrue(MinigameTestHarness.controllers(MinigameTestHarness.storeOf(world)).isEmpty(),
                    "instance should be gone")
            }
        }
        t.succeed()
    }

    /**
     * No double-count across flush and final merge: counters added before a
     * flush and counters added after it must appear exactly once in total.
     */
    @HytaleTest(timeoutSeconds = 120)
    fun finalMergeDoesNotDoubleCountAfterFlushes(t: HytaleTestHelper) {
        val alice = player(t)
        val gameType = "lifetime-final"
        val before = gg.ginco.minigame.stats.LifetimeStats.load(gameType).counters["goals"] ?: 0
        createInstance(t, alice, gameType)
        alice.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, gameType)!!
            Stats.add(store, controller, "goals", 2)
        }
        // Wait for one periodic flush to fire with the first 2 on board.
        t.assertEventually(60) {
            t.assertEquals(before + 2, gg.ginco.minigame.stats.LifetimeStats.load(gameType).counters["goals"],
                "the pre-stop counters should be flushed while live")
        }
        // Then more play AFTER the flush.
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, gameType)!!
            Stats.add(store, controller, "goals", 3)
        }

        alice.runCommand("minigame stop").join()
        t.assertEventually {
            t.runOnWorld {
                t.assertTrue(MinigameTestHarness.controllers(MinigameTestHarness.storeOf(world)).isEmpty(),
                    "instance should be gone")
            }
        }

        // 2 (flushed) + 3 (final delta) — a final merge that re-recorded the
        // flushed 2 would read before + 7.
        t.assertEquals(before + 5, gg.ginco.minigame.stats.LifetimeStats.load(gameType).counters["goals"],
            "flushed counters must not be counted again by the final merge")
        t.succeed()
    }
}