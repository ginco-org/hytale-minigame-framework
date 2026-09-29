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
}
