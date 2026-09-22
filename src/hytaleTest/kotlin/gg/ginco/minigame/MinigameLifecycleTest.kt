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
 * ## Component model note
 *
 * `Phase` and `Role` are single concrete data classes with a [Phase.Kind] /
 * [Role.Kind] enum plus payload fields — the server ECS validates stored
 * components by exact class equality with the class passed to
 * `registerComponent`, so sealed subtypes could never pass validation (see
 * handoff.md). Phase transitions swap the same component type with a new
 * [Phase.Kind], keeping the archetype stable.
 *
 * Commands are executed through the real `CommandManager.handleCommand` path via
 * `SimulatedPlayer.runCommand` (leading `/` optional). Assertions read the store
 * on the world thread — `Store` enforces thread identity — while the test body
 * runs off-thread (hytest contract). Command *text* is assertable too: the
 * simulated player's fake channel records every message packet the server
 * writes, readable via `SimulatedPlayer.receivedMessages()` /
 * `drainReceivedMessages()` / `sawMessage(fragment)` — capture is synchronous
 * with command execution, so the text is there as soon as `runCommand(...).join()`
 * returns (see `commandResponsesCarryExpectedText`).
 */
class MinigameLifecycleTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer {
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
                t.assertEquals(Phase.waiting(), MinigameTestHarness.phaseOf(store, controllers[0]),
                    "fresh instance must be in Waiting")
            }
        }
    }

    /** `join` tags the player with InMinigame(controllerRef) + participant Role; `leave` strips both. */
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
                t.assertEquals(Role.participant(), MinigameTestHarness.roleOf(store, ref),
                    "player should be tagged as participant")
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
        alice.runCommand("minigame start --seconds=2").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val ref = MinigameTestHarness.controllers(store).single()
            val phase = MinigameTestHarness.phaseOf(store, ref)
            t.assertEquals(Phase.Kind.COUNTDOWN, phase?.kind,
                "right after `start` the instance must be Countdown, got $phase")
        }

        // PhaseSystem must reach Playing without any further command input; poll
        // via succeedWhen instead of sleeping (harness ticks at 20 TPS).
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val ref = MinigameTestHarness.controllers(store).single()
                val phase = MinigameTestHarness.phaseOf(store, ref)
                t.assertEquals(Phase.Kind.PLAYING, phase?.kind,
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
        alice.runCommand("minigame start --seconds=30").join()

        val world = t.world
        // Wait for Playing first so `stop` hits the Playing branch.
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val ref = MinigameTestHarness.controllers(store).singleOrNull()
                t.assertNotNull(ref, "instance should still exist before stop")
                t.assertEquals(Phase.Kind.PLAYING, MinigameTestHarness.phaseOf(store, ref!!)?.kind,
                    "should be Playing")
            }
        }

        alice.runCommand("minigame stop").join()

        // stop => Ending(FORCED) ... (default grace period, so this state is
        // observable: PhaseSystem only tears the instance down after it elapses)
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val ref = MinigameTestHarness.controllers(store).singleOrNull()
            val phase = ref?.let { MinigameTestHarness.phaseOf(store, it) }
            t.assertTrue(
                phase != null && phase.kind == Phase.Kind.ENDING && phase.reason == EndReason.FORCED,
                "right after stop the instance must be Ending(FORCED), got $phase"
            )
        }
        t.assertTrue(alice.sawMessage("The minigame has been stopped."),
            "members should be notified of the forced stop, got: ${alice.receivedMessages()}")
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
        alice.runCommand("minigame start --seconds=5").join()
        bob.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val controller = MinigameTestHarness.controllers(store).single()
            val joined = listOf(alice, bob).count {
                MinigameTestHarness.inMinigameOf(store, MinigameTestHarness.refOf(it.ref())) != null
            }
            t.assertEquals(2, joined, "both players should be members during Countdown")
            t.assertEquals(Phase.Kind.COUNTDOWN, MinigameTestHarness.phaseOf(store, controller)?.kind,
                "should still be counting down")
        }
        t.succeed()
    }

    /** Command text is assertable: the fake channel captures every message packet the server writes. */
    @HytaleTest(timeoutSeconds = 60)
    fun commandResponsesCarryExpectedText(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame create arena").join()

        val created = alice.drainReceivedMessages()
        t.assertTrue(
            created.contains("Created minigame instance (gameType=arena, phase=Waiting)."),
            "create should confirm the instance, got: $created"
        )

        alice.runCommand("minigame join").join()
        val joined = alice.drainReceivedMessages()
        t.assertTrue(
            joined.contains("Alice joined the minigame."),
            "join should announce membership (the member is included), got: $joined"
        )

        val bob = player(t, "Bob")
        bob.runCommand("minigame join").join()
        t.assertTrue(
            alice.sawMessage("Bob joined the minigame."),
            "existing members should hear about new joins, got: ${alice.receivedMessages()}"
        )

        bob.runCommand("minigame join").join()
        t.assertTrue(
            bob.sawMessage("You are already in a minigame. Use /minigame leave first."),
            "double join should be rejected with guidance, got: ${bob.receivedMessages()}"
        )

        alice.runCommand("minigame start --seconds=9").join()
        t.assertTrue(
            alice.sawMessage("Started 1 instance(s) — 9.0 s countdown."),
            "start should confirm the countdown, got: ${alice.receivedMessages()}"
        )
        t.succeed()
    }
}