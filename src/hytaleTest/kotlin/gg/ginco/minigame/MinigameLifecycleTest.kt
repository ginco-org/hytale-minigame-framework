package gg.ginco.minigame

import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.EndReason
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players

/**
 * Integration tests for the vertical slice, run inside a real Hytale server.
 *
 * Every test gets a fresh ephemeral void world; the framework slice (components,
 * [gg.ginco.minigame.system.PhaseSystem], the `/minigame` command tree) is
 * mounted once per server run by [MinigameTestHarness].
 *
 * ## KNOWN BLOCKER (framework bug, see handoff.md)
 *
 * `Phase` and `Role` are sealed interfaces; the server ECS validates a stored
 * component instance by exact class equality with the class passed to
 * `registerComponent` ("expected interface ...Phase but found class
 * ...Phase$Waiting"). No subtype instance can therefore ever be stored, so
 * `/minigame create` cannot materialise an instance. The tests that depend on
 * entity creation fail with that root cause until `Phase`/`Role` become
 * concrete classes (or are registered per concrete type).
 *
 * Commands are executed through the real `CommandManager.handleCommand` path via
 * `SimulatedPlayer.runCommand` (leading `/` optional). Assertions read the store
 * on the world thread — `Store` enforces thread identity — while the test body
 * runs off-thread (hytest contract). Command *text* responses are not captured:
 * they are written to the simulated player's fake network channel, which hytest
 * discards, so the guards below assert the observable store state instead.
 */
class MinigameLifecycleTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer {
        MinigameTestHarness.install(t.world)
        return Players.admin(t, name)
    }

    /** `/minigame create <gametype>` produces exactly one Waiting instance recording the gametype. */
    @HytaleTest(timeoutSeconds = 60)
    fun createPutsInstanceInWaiting(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame create capture-the-flag").join()

        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(t.world)
                val controllers = MinigameTestHarness.controllers(store)
                t.assertEquals(1, controllers.size, "exactly one instance should exist")
                t.assertEquals(
                    "capture-the-flag",
                    MinigameTestHarness.controllerOf(store, controllers[0])?.gameTypeId,
                    "instance should record the gametype argument"
                )
                t.assertEquals(Phase.Waiting, MinigameTestHarness.phaseOf(store, controllers[0]),
                    "fresh instance must be in Waiting")
            }
        }
    }

    /** `join` tags the player with InMinigame(controllerRef) + Role.Participant; `leave` strips both. */
    @HytaleTest(timeoutSeconds = 60)
    fun joinAndLeaveTagAndUntagPlayer(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame create lobby").join()
        alice.runCommand("minigame join").join()

        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(t.world)
                val ref = MinigameTestHarness.refOf(alice.ref())
                val inMinigame = MinigameTestHarness.inMinigameOf(store, ref)
                t.assertNotNull(inMinigame, "player should carry InMinigame after join")
                val controllerRef = inMinigame!!.controllerRef
                t.assertNotNull(controllerRef, "InMinigame should reference the joined instance")
                t.assertTrue(
                    MinigameTestHarness.controllers(store).contains(controllerRef),
                    "InMinigame.controllerRef should point at a live controller entity"
                )
                t.assertEquals(Role.Participant, MinigameTestHarness.roleOf(store, ref),
                    "player should be tagged Role.Participant")
            }
        }

        alice.runCommand("minigame leave").join()

        t.succeedWhen {
            t.runOnWorld {
                val ref = MinigameTestHarness.refOf(alice.ref())
                val store = MinigameTestHarness.storeOf(t.world)
                t.assertEquals(null, MinigameTestHarness.inMinigameOf(store, ref), "InMinigame should be gone after leave")
                t.assertEquals(null, MinigameTestHarness.roleOf(store, ref), "Role should be gone after leave")
            }
        }
    }

    /** join is rejected when no instance is joinable, and while already in one; state stays clean. */
    @HytaleTest(timeoutSeconds = 60)
    fun joinGuards(t: HytaleTestHelper) {
        val alice = player(t)

        // nothing exists yet
        alice.runCommand("minigame join").join()
        t.runOnWorld {
            val ref = MinigameTestHarness.refOf(alice.ref())
            val store = MinigameTestHarness.storeOf(t.world)
            t.assertEquals(null, MinigameTestHarness.inMinigameOf(store, ref),
                "join without any instance must not tag the player")
        }

        // already in one
        alice.runCommand("minigame create lobby").join()
        alice.runCommand("minigame join").join()
        t.runOnWorld {
            val ref = MinigameTestHarness.refOf(alice.ref())
            t.assertNotNull(MinigameTestHarness.inMinigameOf(MinigameTestHarness.storeOf(t.world), ref), "should be tagged after join")
        }
        alice.runCommand("minigame join").join()
        t.runOnWorld {
            val ref = MinigameTestHarness.refOf(alice.ref())
            val store = MinigameTestHarness.storeOf(t.world)
            val controllers = MinigameTestHarness.controllers(store)
            t.assertEquals(1, controllers.size, "second join must not create another instance")
            // the player still points at the same, single instance
            val target = MinigameTestHarness.inMinigameOf(store, ref)?.controllerRef
            t.assertEquals(controllers[0], target, "membership must still reference the original instance")
        }
        t.succeed()
    }

    /** `/minigame start [secs]`: Waiting -> Countdown immediately, -> Playing by PhaseSystem alone. */
    @HytaleTest(timeoutSeconds = 60)
    fun startDrivesWaitingToCountdownToPlaying(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame create race").join()
        alice.runCommand("minigame start 2").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val ref = MinigameTestHarness.controllers(store).single()
            val phase = MinigameTestHarness.phaseOf(store, ref)
            t.assertTrue(phase is Phase.Countdown,
                "right after `start` the instance must be Countdown, got $phase")
        }

        // PhaseSystem must reach Playing without any further command input; poll
        // via succeedWhen instead of sleeping (harness ticks at 20 TPS).
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val ref = MinigameTestHarness.controllers(store).single()
                val phase = MinigameTestHarness.phaseOf(store, ref)
                t.assertTrue(phase is Phase.Playing,
                    "instance should reach Playing, got $phase (tick=${t.tick()})")
            }
        }
    }

    /** `stop` drives Ending(FORCED) -> (PhaseSystem) -> instance removed + player tags stripped. */
    @HytaleTest(timeoutSeconds = 60)
    fun stopForcesEndingThenCleanupReleasesPlayers(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame create race").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame start 30").join()

        val world = t.world
        // Wait for Playing first so `stop` hits the Playing branch.
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val ref = MinigameTestHarness.controllers(store).singleOrNull()
                t.assertNotNull(ref, "instance should still exist before stop")
                t.assertTrue(MinigameTestHarness.phaseOf(store, ref!!) is Phase.Playing, "should be Playing")
            }
        }

        alice.runCommand("minigame stop").join()

        // stop => Ending(FORCED) ...
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val ref = MinigameTestHarness.controllers(store).singleOrNull()
            val phase = ref?.let { MinigameTestHarness.phaseOf(store, it) }
            t.assertTrue(
                phase is Phase.Ending && (phase as Phase.Ending).reason == EndReason.FORCED,
                "right after stop the instance must be Ending(FORCED), got $phase"
            )
        }
        // ... then PhaseSystem removes the instance and strips InMinigame/Role
        // from every participant (ENDING_DURATION_SECONDS = 3s of ticks).
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                t.assertTrue(MinigameTestHarness.controllers(store).isEmpty(), "instance should be removed after Ending")
                val ref = MinigameTestHarness.refOf(alice.ref())
                t.assertEquals(null, MinigameTestHarness.inMinigameOf(store, ref), "InMinigame must be stripped on cleanup")
                t.assertEquals(null, MinigameTestHarness.roleOf(store, ref), "Role must be stripped on cleanup")
            }
        }
    }

    /** `stop` with nothing running changes nothing. */
    @HytaleTest(timeoutSeconds = 60)
    fun stopWithoutInstancesIsANoop(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame stop").join()
        t.runOnWorld {
            t.assertTrue(MinigameTestHarness.controllers(MinigameTestHarness.storeOf(t.world)).isEmpty(),
                "no instance should exist")
        }
        t.succeed()
    }

    /** `/minigame status` runs for any sender and the store it reports matches reality (1 instance, 1 player). */
    @HytaleTest(timeoutSeconds = 60)
    fun statusRunsAndStoreItReportsMatches(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame create arena").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame status").join()

        // status has no store side effects; verify what it *counts* directly.
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controllers = MinigameTestHarness.controllers(store)
            t.assertEquals(1, controllers.size, "status should see exactly one instance")
        }
        t.succeed()
    }

    /** `leave` when not in a game changes nothing. */
    @HytaleTest(timeoutSeconds = 60)
    fun leaveWithoutInstanceIsRejected(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame leave").join()
        t.runOnWorld {
            val ref = MinigameTestHarness.refOf(alice.ref())
            val store = MinigameTestHarness.storeOf(t.world)
            t.assertEquals(null, MinigameTestHarness.inMinigameOf(store, ref), "no InMinigame should exist")
            t.assertEquals(null, MinigameTestHarness.roleOf(store, ref), "no Role should exist")
        }
        t.succeed()
    }

    /** join stays possible during Countdown (JoinCommand accepts Waiting/Countdown). */
    @HytaleTest(timeoutSeconds = 60)
    fun joinDuringCountdownIsAllowed(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        alice.runCommand("minigame create lobby").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame start 5").join()
        bob.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllers(store).single()
            val joined = listOf(alice, bob).count {
                MinigameTestHarness.inMinigameOf(store, MinigameTestHarness.refOf(it.ref())) != null
            }
            t.assertEquals(2, joined, "both players should be members during Countdown")
            t.assertTrue(MinigameTestHarness.phaseOf(store, controller) is Phase.Countdown,
                "should still be counting down")
        }
        t.succeed()
    }
}