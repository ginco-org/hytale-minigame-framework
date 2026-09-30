package gg.ginco.minigame

import com.hypixel.hytale.protocol.GameMode
import com.hypixel.hytale.server.core.entity.entities.Player
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.event.PlayerEnterRegionEvent
import gg.ginco.minigame.event.PlayerExitRegionEvent
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Roles
import org.joml.Vector3d

/**
 * Region detection tests: the framework's job is to detect crossings and
 * fire [gg.ginco.minigame.event.PlayerEnterRegionEvent] /
 * [gg.ginco.minigame.event.PlayerExitRegionEvent] — nothing else. These
 * tests double as the reference for consuming plugins: the win/kill
 * behaviour here lives entirely in TEST-side listeners (hytest's
 * `onEvent`/`captureEvents`), exactly where a real plugin would put it.
 *
 * Regions are world content: each test's world starts empty, markers are
 * placed with `/minigame region add <type>`, and events carry the marker's
 * id + open type id.
 */
class MinigameRegionEventsTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String): Int {
        alice.runCommand("minigame create $gameType").join()
        return t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            MinigameTestHarness.instanceIdOf(store, MinigameTestHarness.controllerOfGameType(store, gameType)!!)
        }!!
    }

    /** Entering then leaving a goal region fires exactly one enter and one exit, with full payload. */
    @HytaleTest(timeoutSeconds = 60)
    fun enterAndExitEventsCarryPayload(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.teleport(40.0, 64.0, 40.0).join()

        // Capture BEFORE placing the region: the crossing fires on the first
        // region tick after placement (she is standing inside it).
        val enters = t.captureEvents(PlayerEnterRegionEvent::class.java)
        val exits = t.captureEvents(PlayerExitRegionEvent::class.java)

        alice.runCommand("minigame region add goal").join()

        val world = t.world
        val goalId = t.runOnWorldReturning {
            gg.ginco.minigame.util.Regions.markersOf(world, "goal").single().id
        }

        alice.runCommand("minigame join").join()
        // She is standing inside the goal from placement: the first region
        // tick after the join fires enter #1.
        t.assertEventually {
            val pos = t.runOnWorldReturning {
                val st = MinigameTestHarness.storeOf(world)
                Vector3d(st.getComponent(MinigameTestHarness.refOf(alice.ref()),
                    TransformComponent.getComponentType())!!.position)
            }
            val zones = t.runOnWorldReturning {
                gg.ginco.minigame.util.Regions.markersOf(world).map { "${it.id}/${it.type}:${it.contains(pos)}" }
            }
            t.assertEquals(1, enters.size,
                "enter should fire on join (pos=$pos zones=$zones captured=${enters.map { it.type }})")
        }

        alice.runCommand("minigame start --seconds=1").join()
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                    "should be Playing")
            }
        }
        t.waitForTicks(10) // stay inside across the phase flip: no re-fire
        t.assertEquals(1, enters.size, "staying inside must not re-fire enter")

        alice.teleport(-40.0, 64.0, -40.0).join() // out again
        t.assertEventually { t.assertEquals(1, exits.size, "exit event should fire") }

        alice.teleport(40.0, 64.0, 40.0).join() // back in
        t.assertEventually { t.assertEquals(2, enters.size, "re-enter should fire") }

        // Payload assertions (world thread for store-backed parts).
        t.runOnWorld {
            val enter = enters.first()
            t.assertEquals("Alice", enter.player.username, "enter should carry the player")
            t.assertEquals(goalId, enter.regionId, "enter should carry the region id")
            t.assertEquals("goal", enter.type, "enter should carry the type id")
            t.assertEquals(2, enters.size, "exactly one re-enter after one exit")
            val exit = exits.single()
            t.assertEquals("goal", exit.type, "exit should carry the type id")
            t.assertEquals(goalId, exit.regionId, "exit should carry the region id")
        }
        t.succeed()
    }

    /** Region events are instance-phase agnostic — consumers gate on phase themselves. */
    @HytaleTest(timeoutSeconds = 60)
    fun eventsFireInAnyPhase(t: HytaleTestHelper) {
        val alice = player(t)
        val enters = t.captureEvents(PlayerEnterRegionEvent::class.java)

        createInstance(t, alice, "arena")
        alice.teleport(15.0, 64.0, 15.0).join()
        alice.runCommand("minigame region add hazard").join()
        alice.runCommand("minigame join").join()

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            t.assertEquals(Phase.Kind.WAITING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                "still Waiting")
        }

        alice.teleport(15.0, 64.0, 15.0).join() // into the hazard, pre-game
        t.assertEventually {
            t.assertTrue(enters.isNotEmpty(), "enter should fire even while Waiting")
            t.assertEquals("hazard", enters.single().type, "type should be hazard")
        }
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            t.assertEquals(Phase.Kind.WAITING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                "the framework must not act on the crossing itself — still Waiting")
        }
        t.succeed()
    }

    /** Reference consumer: "death zones eliminate" is PLUGIN logic — a listener swaps the role. */
    @HytaleTest(timeoutSeconds = 60)
    fun consumerImplementsEliminationOnDeathZone(t: HytaleTestHelper) {
        val alice = player(t)
        val world = t.world

        t.onEvent(PlayerEnterRegionEvent::class.java) { e ->
            if (e.type != "death") return@onEvent
            // World thread (system tick) — direct store mutation is legal here.
            val store = world.entityStore.store
            if (store.getComponent(e.playerEntity, Role.componentType) != null) {
                store.replaceComponent(e.playerEntity, Role.componentType,
                    Role.disqualified("died in a death zone"))
            }
        }

        createInstance(t, alice, "arena")
        alice.teleport(-60.0, 64.0, -60.0).join()
        alice.runCommand("minigame region add death").join()
        alice.teleport(60.0, 64.0, 60.0).join() // stand OUTSIDE the zone pre-game
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame start --seconds=1").join()
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                    "should be Playing")
            }
        }

        alice.teleport(-60.0, 64.0, -60.0).join() // into the death zone
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val role = store.getComponent(MinigameTestHarness.refOf(alice.ref()), Role.componentType)
                t.assertEquals(Role.disqualified("died in a death zone"), role,
                    "the consumer listener should have disqualified Alice")
            }
        }
        t.succeed()
    }

    /** Reference consumer: "reaching the goal wins" is PLUGIN logic via the public phase API. */
    @HytaleTest(timeoutSeconds = 60)
    fun consumerImplementsWinOnGoal(t: HytaleTestHelper) {
        val alice = player(t)
        val world = t.world

        t.onEvent(PlayerEnterRegionEvent::class.java) { e ->
            if (e.type != "goal") return@onEvent
            val store = world.entityStore.store
            val controllerRef = store.getComponent(e.playerEntity, InMinigame.componentType)
                ?.controllerRef ?: return@onEvent
            val phase = store.getComponent(controllerRef, Phase.componentType) ?: return@onEvent
            // Play-only gating is the consumer's job (events fire in all phases).
            if (phase.kind != Phase.Kind.PLAYING) return@onEvent
            store.replaceComponent(controllerRef, Phase.componentType,
                Phase.ending(gg.ginco.minigame.component.EndReason.WIN_CONDITION))
        }

        createInstance(t, alice, "arena")
        alice.runCommand("minigame region add goal").join() // added at world spawn
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame start --seconds=1").join()
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                    "should be Playing")
            }
        }

        alice.teleport(70.0, 64.0, 20.0).join() // cross INTO the goal while Playing
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                val phase = MinigameTestHarness.phaseOf(store, controller)
                t.assertTrue(
                    phase == null || (phase.kind == Phase.Kind.ENDING &&
                        phase.reason == gg.ginco.minigame.component.EndReason.WIN_CONDITION),
                    "instance should end via the consumer's WIN_CONDITION, got $phase"
                )
            }
        }
        t.succeed()
    }

    /** Custom type ids work end to end: creation, events, list, typed clear. */
    @HytaleTest(timeoutSeconds = 60)
    fun customTypeIdsWorkEndToEnd(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.teleport(5.0, 64.0, 5.0).join()
        alice.runCommand("minigame region add laser-base").join()
        // Capture BEFORE joining: she is standing inside the marker, so the
        // enter fires on the first region tick after the join.
        val enters = t.captureEvents(PlayerEnterRegionEvent::class.java)
        alice.runCommand("minigame join").join()
        alice.drainReceivedMessages()

        alice.teleport(5.0, 64.0, 5.0).join() // into the custom marker
        t.assertEventually {
            t.assertTrue(enters.isNotEmpty(), "enter should fire for a custom type id")
            t.assertEquals("laser-base", enters.single().type, "the event should carry the plugin's own type id")
        }

        alice.runCommand("minigame region list").join()
        t.assertTrue(alice.sawMessage("laser-base at (5.0, 64.0, 5.0)"),
            "list should show the custom marker, got: ${alice.receivedMessages()}")
        alice.runCommand("minigame region clear --type=laser-base").join()
        t.assertTrue(alice.sawMessage("Removed 1 laser-base marker(s)."),
            "typed clear should work for custom ids, got: ${alice.receivedMessages()}")
        t.succeed()
    }
}
