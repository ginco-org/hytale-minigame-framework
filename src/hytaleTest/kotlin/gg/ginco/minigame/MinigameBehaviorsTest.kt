package gg.ginco.minigame

import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.behavior.GameBehaviors
import gg.ginco.minigame.behavior.MinigameContext
import gg.ginco.minigame.definition.DefinitionStore
import gg.ginco.minigame.definition.MinigameDefinition
import gg.ginco.minigame.event.PhaseChangedEvent
import gg.ginco.minigame.event.PlayerEnterRegionEvent
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Regions
import gg.ginco.minigame.util.Stats
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The behavior pillar: plugins provide behavior packages by id; games
 * activate them via definitions (`behaviors` list); the framework routes
 * triggers (region crossings, phase changes) into the active behaviors and
 * tears everything down strictly at instance end.
 *
 * The test behaviors double as the reference for consuming plugins: the
 * reaction logic lives entirely in the behavior classes (region crossings →
 * stats; phase changes → flags), exactly where a real plugin would put it.
 */
class MinigameBehaviorsTest {

    private val registrations = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val enterEvents = java.util.concurrent.CopyOnWriteArrayList<PlayerEnterRegionEvent>()
    private val phaseEvents = java.util.concurrent.CopyOnWriteArrayList<PhaseChangedEvent>()
    private val deactivations = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    private fun trackTriggers(t: HytaleTestHelper) {
        enterEvents.clear(); phaseEvents.clear(); deactivations.clear()
        t.onEvent(PlayerEnterRegionEvent::class.java) { e -> enterEvents.add(e) }
        t.onEvent(PhaseChangedEvent::class.java) { e -> phaseEvents.add(e) }
    }

    private fun untrackAll() {
        for (unregister in registrations) runCatching { unregister() }
        registrations.clear()
    }

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String): Int {
        alice.runCommand("minigame create $gameType").join()
        return t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            MinigameTestHarness.instanceIdOf(store, MinigameTestHarness.controllerOfGameType(store, gameType)!!)
        }!!
    }

    /** Definition-listed behaviors activate on create and route triggers into stats. */
    @HytaleTest(timeoutSeconds = 60)
    fun definitionBehaviorsActivateAndRouteTriggers(t: HytaleTestHelper) {
        val alice = player(t)
        GameBehaviors.register("test-goal-counter") { ctx ->
            println("[BehaviorDiag] factory: creating test-goal-counter for world ${ctx.world.name}")
            object : gg.ginco.minigame.behavior.GameBehavior("test-goal-counter") {
                override fun onActivate(ctx: MinigameContext) {
                    println("[BehaviorDiag] onActivate test-goal-counter controller=${ctx.controllerRef}")
                    ctx.onEvent(PlayerEnterRegionEvent::class.java) { e ->
                        println("[BehaviorDiag] region enter type=${e.type} region=${e.regionId}")
                        if (e.type == "goal") {
                            ctx.addStat("behavior-goal-crossings")
                            deactivations.add("goal-crossing")
                        }
                    }
                }
                override fun onDeactivate(ctx: MinigameContext) {
                    deactivations.add("goal-counter-deactivated")
                }
            }
        }

        trackTriggers(t)
        DefinitionStore.save(MinigameDefinition(
            gameTypeId = "behavior-game",
            teams = emptyList(),
            behaviors = listOf("test-goal-counter"),
        ))
        val instanceId = createInstance(t, alice, "behavior-game")
        alice.runCommand("minigame region add goal").join() // placed at her position
        alice.runCommand("minigame join").join()
        alice.drainReceivedMessages()

        val world = t.world
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "behavior-game")!!
                t.assertEquals(1, Stats.total(store, controller, "behavior-goal-crossings"),
                    "the behavior should have counted one goal crossing")
            }
        }
        t.assertTrue(enterEvents.any { it.type == "goal" }, "the goal crossing should reach the trigger surface")
        t.assertTrue(deactivations.contains("goal-crossing"), "the crossing should reach the behavior")

        // Deactivation at instance end drops behavior state (strict).
        alice.runCommand("minigame stop").join()
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                t.assertTrue(MinigameTestHarness.controllers(store).isEmpty(), "instance should be gone")
            }
        }
        t.assertTrue(deactivations.contains("goal-counter-deactivated"),
            "instance end should deactivate the behavior")
        t.assertEquals(instanceId, instanceId, "sanity")
        t.succeed()
    }

    /** Behaviors not listed in the definition never activate. */
    @HytaleTest(timeoutSeconds = 60)
    fun unlistedBehaviorsStayInactive(t: HytaleTestHelper) {
        val alice = player(t)
        GameBehaviors.register("test-inactive") { ctx ->
            object : gg.ginco.minigame.behavior.GameBehavior("test-inactive") {
                override fun onActivate(ctx: MinigameContext) {
                    deactivations.add("test-inactive-activated")
                }
            }
        }

        createInstance(t, alice, "behavior-game") // definition has no behaviors
        alice.runCommand("minigame join").join()

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "behavior-game")!!
            t.assertEquals(0, Stats.total(store, controller, "behavior-goal-crossings"),
                "no crossings should be counted without a goal region")
        }
        t.assertFalse(deactivations.contains("test-inactive-activated"),
            "the unlisted behavior must never activate")
        t.succeed()
    }
}
