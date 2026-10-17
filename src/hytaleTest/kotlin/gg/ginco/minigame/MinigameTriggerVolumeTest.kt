package gg.ginco.minigame

import com.hypixel.hytale.builtin.points.PointsPlugin
import com.hypixel.hytale.builtin.points.manager.PointEntry
import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent
import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Regions
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The region layer, after the refactor onto the server's builtin modules:
 * Direct `Regions.addRegion` authoring now creates a **TriggerVolume**, spawn markers can be
 * **TriggerVolumes** or **Points**, and boundary crossings come from the
 * builtin's `TriggerVolumeEvent` (adapted back to the framework's typed events
 * by [gg.ginco.minigame.system.RegionSystem]).
 *
 * These tests pin the new backend and the guarantees that did NOT change:
 * regions are world content that survives instance end, custom type ids work,
 * and both spawn backends feed the start-of-game teleport. The typed
 * crossing-event semantics stay covered by `MinigameRegionEventsTest`.
 */
class MinigameTriggerVolumeTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String): Int {
        alice.runCommand("minigame create $gameType").join()
        return t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            MinigameTestHarness.instanceIdOf(store, MinigameTestHarness.controllerOfGameType(store, gameType)!!)
        }!!
    }

    /**
     * `Regions.addRegion(world, type, …)` stores a builtin TriggerVolume keyed
     * `<type>` (valueless tag — the map tools set keys without values) in that
     * world's manager — the region is the builtin manager's content now, not
     * the framework's own chunk-store resource.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun regionAddCreatesTaggedTriggerVolume(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.teleport(20.0, 64.0, 20.0).join()
        MinigameTestHarness.addRegionMarker(t, t.world, alice, "capture")

        val world = t.world
        t.runOnWorld {
            val manager = world.entityStore.store
                .getResource(TriggerVolumesPlugin.get().managerResourceType)
            t.assertNotNull(manager, "world should hold the builtin TriggerVolumeManager")
            val volume = manager!!.getVolumes().single()
            t.assertTrue(volume.rawTags.containsKey("capture"),
                "the region's type id should be authored as a bare tag key, got ${volume.rawTags}")
        }
        t.succeed()
    }

    /**
     * Consumers may listen to the builtin `TriggerVolumeEvent` directly — it
     * carries the raw crossing (world name, volume id, entity) on the world
     * thread, with no effects attached to the volume. This is the alternative
     * surface the adapter sits on top of; documenting it here keeps the
     * "either event is usable" contract honest.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun rawBuiltinEventFiresForFrameworkRegion(t: HytaleTestHelper) {
        val alice = player(t)
        val world = t.world
        val raw = CopyOnWriteArrayList<TriggerVolumeEvent>()
        val registration = HytaleServer.get().eventBus
            .registerGlobal(TriggerVolumeEvent::class.java) { e -> raw.add(e) }
        t.doneFuture().whenComplete { _, _ -> runCatching { registration?.unregister() } }

        createInstance(t, alice, "arena")
        alice.teleport(90.0, 64.0, 90.0).join()
        MinigameTestHarness.addRegionMarker(t, t.world, alice, "beacon")

        t.assertEventually(30) {
            t.assertTrue(raw.any { it.volumeId.isNotEmpty() },
                "a raw TriggerVolumeEvent should fire for the tagged region (no effects attached)")
        }
        t.runOnWorld {
            // The event names the world the same way the builtin resolves it.
            t.assertEquals(world.name.lowercase(), raw.first().worldName,
                "the event should carry the builtin's lowercase world name")
        }
        t.succeed()
    }

    /**
     * Spawn markers can be **Points** (id/name/position/rotation/tags). A
     * Point keyed `spawn` (valueless tag) is a start-of-game teleport target
     * and, as the richer backend, carries a facing the framework applies.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun pointSpawnMarkerTeleportsWithRotation(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        val world = t.world

        // Register a spawn point at a distinct spot with a 90° yaw.
        t.runOnWorld {
            val manager = world.entityStore.store
                .getResource(PointsPlugin.get().managerResourceType)!!
            val id = manager.generateUniquePointId()
            val point = PointEntry(id, world.name.lowercase(),
                Vector3d(-20.0, 70.0, -20.0), Vector3f(0f, 90f, 0f), "spawn", true)
            point.setTags(mapOf(Regions.SPAWN_TYPE to ""))
            manager.register(id, point)
        }
        t.assertEquals(1, t.runOnWorldReturning { Regions.spawnPointsOf(world).size },
            "the point should be a spawn target")

        alice.runCommand("minigame join").join()
        alice.runCommand("minigame start --seconds=1").join()

        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                    "instance should reach Playing")
                val transform = store.getComponent(MinigameTestHarness.refOf(alice.ref()),
                    TransformComponent.getComponentType())!!
                val pos = transform.position
                t.assertTrue(
                    pos.distanceSquared(Vector3d(-20.0, 70.0, -20.0)) < 1.0,
                    "the player should be teleported to the point spawn marker, got $pos")
                // 90° yaw round-trips through PointEntry (degrees) into the
                // transform (radians) — the point backend carries a facing.
                t.assertTrue(Math.abs(transform.rotation.yaw() - Math.PI.toFloat() / 2) < 0.01f,
                    "the point's yaw should be applied, got ${transform.rotation.yaw()}")
            }
        }
        t.succeed()
    }

    /**
     * A TriggerVolume spawn region and a Point spawn target coexist: both feed
     * the start teleport, volumes first. Players are distributed across the
     * combined target list.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun volumeAndPointSpawnTargetsCoexist(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        createInstance(t, alice, "arena")
        val world = t.world

        alice.teleport(30.0, 64.0, 30.0).join()
        MinigameTestHarness.addRegionMarker(t, t.world, alice, "spawn")

        t.runOnWorld {
            val manager = world.entityStore.store
                .getResource(PointsPlugin.get().managerResourceType)!!
            val id = manager.generateUniquePointId()
            val point = PointEntry(id, world.name.lowercase(),
                Vector3d(-30.0, 64.0, -30.0), Vector3f(), "spawn", true)
            point.setTags(mapOf(Regions.SPAWN_TYPE to ""))
            manager.register(id, point)
        }
        t.assertEquals(2, t.runOnWorldReturning { Regions.spawnPointsOf(world).size },
            "both the volume and the point should be spawn targets")

        alice.runCommand("minigame join").join()
        bob.runCommand("minigame join").join()
        alice.runCommand("minigame start --seconds=1").join()

        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                    "instance should reach Playing")
                val a = Vector3d(30.0, 64.0, 30.0)
                val b = Vector3d(-30.0, 64.0, -30.0)
                for (p in listOf(alice, bob)) {
                    val pos = store.getComponent(MinigameTestHarness.refOf(p.ref()),
                        TransformComponent.getComponentType())!!.position
                    val atTarget = Vector3d(pos).distanceSquared(a) < 16.0 ||
                        Vector3d(pos).distanceSquared(b) < 16.0
                    t.assertTrue(atTarget, "${p.name} should be at a spawn target, got $pos")
                }
            }
        }
        t.succeed()
    }

    /**
     * Regions are world content: a non-spawn region created the builtin way
     * stays in the world's TriggerVolumeManager after the instance that placed
     * it ends (the framework clears nothing on teardown).
     */
    @HytaleTest(timeoutSeconds = 60)
    fun regionsSurviveInstanceEnd(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        MinigameTestHarness.addRegionMarker(t, t.world, alice, "checkpoint")
        val world = t.world
        t.assertEquals(1, t.runOnWorldReturning { Regions.regionsOf(world, "checkpoint").size },
            "region should exist before stop")

        alice.runCommand("minigame stop").join()
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                t.assertTrue(MinigameTestHarness.controllers(store).isEmpty(), "instance should be gone")
                t.assertEquals(1, Regions.regionsOf(world, "checkpoint").size,
                    "a region is world content — it must survive the instance")
                t.assertEquals(1, world.entityStore.store
                    .getResource(TriggerVolumesPlugin.get().managerResourceType)!!
                    .getVolumes().size,
                    "the builtin manager should still hold the region's volume")
            }
        }
        t.succeed()
    }

    /**
     * A tagged volume removed through the builtin manager directly (the
     * programmatic path a headless consumer would use, since the in-game point
     * tool needs client packets) stops being a framework region — the facade
     * reads the manager live, with no framework-side cache to invalidate.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun programmaticRemovalReflectsImmediately(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.teleport(45.0, 64.0, 45.0).join()
        MinigameTestHarness.addRegionMarker(t, t.world, alice, "door")
        val world = t.world

        t.runOnWorld {
            val manager = world.entityStore.store
                .getResource(TriggerVolumesPlugin.get().managerResourceType)!!
            val volume = manager.getVolumes().single()
            manager.unregister(volume.id)
        }
        t.runOnWorld {
            t.assertTrue(Regions.regionsOf(world, "door").isEmpty(),
                "removing the volume in the builtin manager should drop the region")
        }
        t.succeed()
    }
}
