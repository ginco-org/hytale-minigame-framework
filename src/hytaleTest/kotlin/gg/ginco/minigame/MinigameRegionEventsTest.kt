package gg.ginco.minigame

import com.hypixel.hytale.event.EventRegistration
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.hytest.runtime.HytestPlugin
import gg.ginco.hytest.runtime.api.AfterEach
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.event.PlayerEnterRegionEvent
import gg.ginco.minigame.event.PlayerExitRegionEvent
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Region detection tests: the framework's job is to detect crossings and fire
 * [PlayerEnterRegionEvent] / [PlayerExitRegionEvent] — nothing else. These
 * tests double as the reference for consuming plugins: the win/kill behaviour
 * here lives entirely in TEST-side listeners, exactly where a real plugin
 * would put it.
 */
class MinigameRegionEventsTest {

    private val registrations = CopyOnWriteArrayList<EventRegistration<*, *>>()

    @AfterEach
    fun unregisterListeners() {
        for (reg in registrations) runCatching { reg.unregister() }
        registrations.clear()
    }

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String): Int {
        alice.runCommand("minigame create $gameType").join()
        return t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            MinigameTestHarness.instanceIdOf(store, MinigameTestHarness.controllerOfGameType(store, gameType)!!)
        }!!
    }

    private fun trackEnter(t: HytaleTestHelper, into: CopyOnWriteArrayList<PlayerEnterRegionEvent>) {
        registrations.add(HytestPlugin.EVENTS!!.register(PlayerEnterRegionEvent::class.java) { into.add(it) })
    }

    private fun trackExit(t: HytaleTestHelper, into: CopyOnWriteArrayList<PlayerExitRegionEvent>) {
        registrations.add(HytestPlugin.EVENTS!!.register(PlayerExitRegionEvent::class.java) { into.add(it) })
    }

    /** Entering then leaving a goal marker fires exactly one enter and one exit, with full payload. */
    @HytaleTest(timeoutSeconds = 60)
    fun enterAndExitEventsCarryPayload(t: HytaleTestHelper) {
        val alice = player(t)
        val enters = CopyOnWriteArrayList<PlayerEnterRegionEvent>()
        val exits = CopyOnWriteArrayList<PlayerExitRegionEvent>()
        trackEnter(t, enters)
        trackExit(t, exits)

        val instanceId = createInstance(t, alice, "arena")
        alice.teleport(40.0, 64.0, 40.0).join()
        alice.runCommand("minigame region add goal").join()

        val world = t.world
        val ids = t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            val marker = gg.ginco.minigame.util.Regions.markersOf(store, controller, "goal").single().ref
            Triple(instanceId, marker, MinigameTestHarness.refOf(alice.ref()))
        }

        alice.runCommand("minigame join").join()
        alice.runCommand("minigame start --seconds=1").join()
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, controller)?.kind, "should be Playing")
            }
        }

        alice.teleport(40.0, 64.0, 40.0).join() // into the goal
        t.succeedWhen {
            t.assertTrue(enters.isNotEmpty(), "enter event should fire")
        }
        t.waitForTicks(10) // stay inside for half a second: edge detection, not a stream
        t.assertEquals(1, enters.size, "staying inside must not re-fire enter")

        alice.teleport(-40.0, 64.0, -40.0).join() // out again
        t.succeedWhen {
            t.assertTrue(exits.isNotEmpty(), "exit event should fire")
        }

        // Payload assertions (world thread for store-backed parts).
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val enter = enters.single()
            t.assertEquals("Alice", enter.player.username, "enter should carry the player")
            t.assertEquals(ids.third, enter.playerEntity, "enter should carry the player entity ref")
            t.assertEquals(ids.second, enter.marker, "enter should carry the marker ref")
            t.assertEquals("goal", enter.type, "enter should carry the marker type")
            t.assertEquals(ids.first, MinigameTestHarness.instanceIdOf(store, enter.instance), "enter should carry the instance")
            val exit = exits.single()
            t.assertEquals("goal", exit.type, "exit should carry the marker type")
            t.assertEquals(ids.second, exit.marker, "exit should carry the marker ref")
        }
        t.succeed()
    }

    /** Region events are instance-phase agnostic — consumers gate on phase themselves. */
    @HytaleTest(timeoutSeconds = 60)
    fun eventsFireInAnyPhase(t: HytaleTestHelper) {
        val alice = player(t)
        val enters = CopyOnWriteArrayList<PlayerEnterRegionEvent>()
        trackEnter(t, enters)

        createInstance(t, alice, "arena")
        alice.teleport(15.0, 64.0, 15.0).join()
        alice.runCommand("minigame region add death").join()
        alice.runCommand("minigame join").join()

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            t.assertEquals(Phase.Kind.WAITING, MinigameTestHarness.phaseOf(store, controller)?.kind, "still Waiting")
        }

        alice.teleport(15.0, 64.0, 15.0).join() // into the death zone, pre-game
        t.succeedWhen {
            t.assertTrue(enters.isNotEmpty(), "enter should fire even while Waiting")
        }
        t.assertEquals("death", enters.single().type, "type should be death")
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            t.assertEquals(Phase.Kind.WAITING, MinigameTestHarness.phaseOf(store, controller)?.kind,
                "the framework must not act on the crossing itself — still Waiting")
        }
        t.succeed()
    }

    /**
     * Reference consumer: "death zones eliminate" is PLUGIN logic — a listener
     * swaps the player's Role; the framework only reported the crossing.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun consumerImplementsEliminationOnDeathZone(t: HytaleTestHelper) {
        val alice = player(t)
        val world = t.world

        registrations.add(HytestPlugin.EVENTS!!.register(PlayerEnterRegionEvent::class.java) { e ->
            if (e.type != "death") return@register
            // World thread (system tick) — direct store mutation is legal here.
            val store = world.entityStore.store
            if (store.getComponent(e.playerEntity, Role.componentType) != null) {
                store.replaceComponent(e.playerEntity, Role.componentType, Role.disqualified("died in a death zone"))
            }
        })

        createInstance(t, alice, "arena")
        alice.teleport(-60.0, 64.0, -60.0).join()
        alice.runCommand("minigame region add death").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame start --seconds=1").join()
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, controller)?.kind, "should be Playing")
            }
        }

        alice.teleport(-60.0, 64.0, -60.0).join() // into the death zone
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val role = store.getComponent(MinigameTestHarness.refOf(alice.ref()), Role.componentType)
                t.assertEquals(Role.disqualified("died in a death zone"), role,
                    "the consumer listener should have disqualified Alice")
            }
        }
        t.succeed()
    }

    /**
     * Reference consumer: "reaching the goal wins" is PLUGIN logic — the
     * listener ends the instance with WIN_CONDITION via the public phase API.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun consumerImplementsWinOnGoal(t: HytaleTestHelper) {
        val alice = player(t)
        val world = t.world

        registrations.add(HytestPlugin.EVENTS!!.register(PlayerEnterRegionEvent::class.java) { e ->
            if (e.type != "goal") return@register
            val store = world.entityStore.store
            val phase = store.getComponent(e.instance, Phase.componentType) ?: return@register
            // Play-only gating is the consumer's job (events fire in all phases).
            if (phase.kind != Phase.Kind.PLAYING) return@register
            store.replaceComponent(e.instance, Phase.componentType, Phase.ending(gg.ginco.minigame.component.EndReason.WIN_CONDITION))
        })

        createInstance(t, alice, "arena")
        alice.teleport(70.0, 64.0, 20.0).join()
        alice.runCommand("minigame region add goal").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame start --seconds=1").join()

        alice.teleport(70.0, 64.0, 20.0).join() // into the goal -> consumer ends the game
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                val phase = MinigameTestHarness.phaseOf(store, controller)
                t.assertTrue(
                    phase == null || (phase.kind == Phase.Kind.ENDING && phase.reason == gg.ginco.minigame.component.EndReason.WIN_CONDITION),
                    "instance should end via the consumer's WIN_CONDITION, got $phase"
                )
            }
        }
        t.succeed()
    }

    /** Type ids are open: a plugin can invent its own ("laser-base") and listen for exactly that. */
    @HytaleTest(timeoutSeconds = 60)
    fun customTypeIdsWorkEndToEnd(t: HytaleTestHelper) {
        val alice = player(t)
        val enters = CopyOnWriteArrayList<PlayerEnterRegionEvent>()
        trackEnter(t, enters)

        createInstance(t, alice, "arena")
        alice.teleport(5.0, 64.0, 5.0).join()
        alice.runCommand("minigame region add laser-base").join()
        alice.runCommand("minigame join").join()

        alice.teleport(5.0, 64.0, 5.0).join() // into the custom marker
        // succeedWhen polls asynchronously — assert the payload INSIDE it.
        t.succeedWhen {
            t.assertTrue(enters.isNotEmpty(), "enter should fire for a custom type id")
            t.assertEquals("laser-base", enters.single().type, "the event should carry the plugin's own type id")
        }

        // Untyped list/clear treat custom ids like any other.
        alice.drainReceivedMessages()
        alice.runCommand("minigame region list").join()
        t.assertTrue(alice.sawMessage("laser-base at (5.0, 64.0, 5.0)"),
            "list should show the custom marker, got: ${alice.receivedMessages()}")
        alice.runCommand("minigame region clear --type=laser-base").join()
        t.assertTrue(alice.sawMessage("Removed 1 laser-base marker(s)"),
            "typed clear should work for custom ids, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    /** `region list` groups markers by kind; `clear --type=<kind>` removes just that kind. */
    @HytaleTest(timeoutSeconds = 60)
    fun regionListAndTypedClear(t: HytaleTestHelper) {
        val alice = player(t)
        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame region add spawn").join()
        alice.teleport(30.0, 64.0, 30.0).join()
        alice.runCommand("minigame region add goal").join()
        alice.teleport(-30.0, 64.0, -30.0).join()
        alice.runCommand("minigame region add death").join()
        alice.drainReceivedMessages()

        alice.runCommand("minigame region list").join()
        t.assertTrue(alice.sawMessage("spawn at"), "list should show the spawn marker, got: ${alice.receivedMessages()}")
        t.assertTrue(alice.sawMessage("goal at (30.0, 64.0, 30.0)"), "list should show the goal marker, got: ${alice.receivedMessages()}")
        t.assertTrue(alice.sawMessage("death at (-30.0, 64.0, -30.0)"), "list should show the death marker, got: ${alice.receivedMessages()}")

        alice.runCommand("minigame region clear --type=goal").join()
        t.assertTrue(alice.sawMessage("Removed 1 goal marker(s) from instance #$instanceId."),
            "typed clear should report scope, got: ${alice.receivedMessages()}")

        alice.runCommand("minigame region clear").join()
        t.assertTrue(alice.sawMessage("Removed 2 marker(s) from instance #$instanceId."),
            "untyped clear should remove the rest, got: ${alice.receivedMessages()}")

        alice.runCommand("minigame region list").join()
        t.assertTrue(alice.sawMessage("has no markers"), "list should be empty after clear, got: ${alice.receivedMessages()}")
        t.succeed()
    }
}
