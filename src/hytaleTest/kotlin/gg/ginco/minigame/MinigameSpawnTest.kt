package gg.ginco.minigame

import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import org.joml.Vector3d

/**
 * Integration tests for `/minigame region` spawn-point markers and the
 * start-of-game teleport in [gg.ginco.minigame.system.PhaseSystem].
 *
 * Positions are read from the authoritative `TransformComponent` on the world
 * thread (not `playerRef.transform`, which can lag the component).
 */
class MinigameSpawnTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String): Int {
        alice.runCommand("minigame create $gameType").join()
        return t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            MinigameTestHarness.instanceIdOf(store, MinigameTestHarness.controllerOfGameType(store, gameType)!!)
        }!!
    }

    private fun positionOf(t: HytaleTestHelper, player: SimulatedPlayer): Vector3d =
        t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            Vector3d(store.getComponent(MinigameTestHarness.refOf(player.ref()), TransformComponent.getComponentType())!!.position)
        }

    private fun spawnPointCount(t: HytaleTestHelper): Int =
        t.runOnWorldReturning {
            gg.ginco.minigame.util.Regions.spawnPointsOf(t.world).size
        }

    private fun near(actual: Vector3d, expected: Vector3d, tolerance: Double): Boolean {
        val dx = actual.x - expected.x; val dy = actual.y - expected.y; val dz = actual.z - expected.z
        return dx * dx + dy * dy + dz * dz <= tolerance * tolerance
    }

    /** `region spawn` creates a marker whose box is centred on the admin's position. */
    @HytaleTest(timeoutSeconds = 60)
    fun regionSpawnCreatesMarkerAtPlayerPosition(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.teleport(60.0, 64.0, 80.0).join()
        alice.runCommand("minigame region add spawn").join()

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            val spawns = gg.ginco.minigame.util.Regions.regionsOf(t.world, gg.ginco.minigame.util.Regions.SPAWN_TYPE)
            t.assertEquals(1, spawns.size, "exactly one marker should exist")
            val center = spawns.single().center()
            t.assertTrue(near(center, Vector3d(60.0, 64.0, 80.0), 0.5),
                "marker centre should be the admin position, got $center")
        }
        t.assertTrue(alice.sawMessage(" at (60.0, 64.0, 80.0)."),
            "create should confirm position, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    /** On Countdown->Playing every joined player appears at (one of) the instance's spawn points. */
    @HytaleTest(timeoutSeconds = 60)
    fun startTeleportsPlayersToSpawnPoints(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        createInstance(t, alice, "arena")

        // Two markers at distinct spots (marker order == creation order).
        alice.teleport(30.0, 64.0, 30.0).join()
        alice.runCommand("minigame region add spawn").join()
        alice.teleport(-30.0, 64.0, -30.0).join()
        alice.runCommand("minigame region add spawn").join()

        alice.runCommand("minigame join").join()
        bob.runCommand("minigame join").join()
        alice.runCommand("minigame start --seconds=1").join()

        val world = t.world
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                    "instance should reach Playing, got ${MinigameTestHarness.phaseOf(store, controller)}")
                val a = Vector3d(30.0, 64.0, 30.0)
                val b = Vector3d(-30.0, 64.0, -30.0)
                for (p in listOf(alice, bob)) {
                    val pos = store.getComponent(MinigameTestHarness.refOf(p.ref()), TransformComponent.getComponentType())!!.position
                    val atSpawn = near(Vector3d(pos), a, 4.0) || near(Vector3d(pos), b, 4.0)
                    t.assertTrue(atSpawn, "${p.name} should be at a spawn point on Playing, got $pos")
                }
            }
        }
        t.succeed()
    }

    /** Instances without markers do not move their players. */
    @HytaleTest(timeoutSeconds = 60)
    fun startWithoutSpawnPointsKeepsPlayersPut(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()
        val before = positionOf(t, alice)

        alice.runCommand("minigame start --seconds=1").join()

        val world = t.world
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                    "instance should reach Playing")
                val now = store.getComponent(MinigameTestHarness.refOf(alice.ref()), TransformComponent.getComponentType())!!.position
                t.assertTrue(near(Vector3d(now), before, 1.0),
                    "player must not move without spawn points, was $before now $now")
            }
        }
        t.succeed()
    }

    /** `region clear` removes every marker of the instance. */
    @HytaleTest(timeoutSeconds = 60)
    fun regionClearRemovesSpawnPoints(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.runCommand("minigame region add spawn").join()
        alice.teleport(10.0, 64.0, 10.0).join()
        alice.runCommand("minigame region add spawn").join()
        t.assertEquals(2, spawnPointCount(t), "two markers should exist before clear")

        alice.runCommand("minigame region clear").join()
        t.assertEquals(0, spawnPointCount(t), "clear should remove all markers")
        t.assertTrue(alice.sawMessage("Removed 2 marker(s)."),
            "clear should report the count, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    /** Regions are world content: they SURVIVE the end of an instance. */
    @HytaleTest(timeoutSeconds = 60)
    fun markersPersistAfterInstanceEnd(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.runCommand("minigame region add spawn").join()
        t.assertEquals(1, spawnPointCount(t), "marker should exist before stop")

        alice.runCommand("minigame stop").join()
        // NOTE: succeedWhen is asynchronous — post-state assertions belong
        // INSIDE the polled block.
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(t.world)
                t.assertTrue(MinigameTestHarness.controllers(store).isEmpty(), "instance should be gone")
                t.assertEquals(1, spawnPointCount(t),
                    "markers are world content — they must survive the instance")
            }
        }
        t.succeed()
    }
}
