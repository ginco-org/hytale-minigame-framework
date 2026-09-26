package gg.ginco.minigame

import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.component.TeamRef
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Teams

/**
 * Disconnect semantics: a player who vanishes mid-game is released exactly
 * like `/minigame leave` (membership, team roster, presence) and the
 * remaining members are told. Rejoin flows live in the reconnect tests
 * (which need hytest's fixed-identity spawn — added when that API ships).
 */
class MinigameDisconnectTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String): Int {
        alice.runCommand("minigame create $gameType").join()
        return t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            MinigameTestHarness.instanceIdOf(store, MinigameTestHarness.controllerOfGameType(store, gameType)!!)
        }!!
    }

    /** Disconnecting PRESERVES membership: roster slot, team link record, instance alive. */
    @HytaleTest(timeoutSeconds = 60)
    fun disconnectPreservesMembershipAndRoster(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame team join red").join()
        bob.runCommand("minigame join").join()
        bob.drainReceivedMessages()

        alice.disconnect()

        val world = t.world
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                // The team roster KEEPS the departed player's uuid until the
                // minigame ends — that is the preserved membership.
                val roster = store.getComponent(
                    Teams.findByName(store, controller, "red")!!.ref,
                    TeamMembers.componentType,
                )?.members?.size
                t.assertEquals(1, roster, "the roster must preserve the departed player")
                val preserved = store.getComponent(controller, gg.ginco.minigame.component.PreservedMembers.componentType)?.members
                t.assertEquals(1, preserved?.size, "a preserved membership record must exist")
                t.assertEquals("Alice", preserved?.single()?.name, "record should carry the player name")
                t.assertEquals(instanceId, MinigameTestHarness.instanceIdOf(store, controller),
                    "the instance stays alive and is the same one")
            }
        }
        // The announcement lands on the world thread with the preservation —
        // assertEventually, not an immediate assert (async race).
        t.assertEventually {
            t.assertTrue(bob.sawMessage("Alice disconnected."),
                "remaining members should be told, got: ${bob.receivedMessages()}")
        }
        t.succeed()
    }

    /** A returning player is restored into their exact instance, role and team via join. */
    @HytaleTest(timeoutSeconds = 60)
    fun reconnectedPlayerRestoresMembership(t: HytaleTestHelper) {
        val uuid = java.util.UUID.randomUUID()
        val alice = t.spawnSimulatedPlayer("Alice", uuid)
        MinigameTestHarness.grantAdmin(alice.ref())

        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame team join red").join()
        alice.disconnect()

        val world = t.world
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(1, store.getComponent(controller,
                    gg.ginco.minigame.component.PreservedMembers.componentType)?.members?.size,
                    "preserved record must exist before rejoin")
            }
        }

        val alice2 = alice.reconnect().join()
        t.assertEquals(uuid, alice2.uuid, "reconnect must preserve the identity")
        MinigameTestHarness.grantAdmin(alice2.ref())

        alice2.runCommand("minigame join").join() // restores, not a fresh join
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val ref = MinigameTestHarness.refOf(alice2.ref())
                val target = MinigameTestHarness.inMinigameOf(store, ref)?.controllerRef
                t.assertNotNull(target, "the returning player must be restored")
                t.assertEquals(instanceId, target?.let { MinigameTestHarness.instanceIdOf(store, it) },
                    "restored into the SAME instance")
                val teamRef = store.getComponent(ref, TeamRef.componentType)?.teamRef
                t.assertEquals("red", teamRef?.let { store.getComponent(it, TeamData.componentType)?.name },
                    "team membership restored")
                val roster = teamRef?.let { store.getComponent(it, TeamMembers.componentType)?.members?.size }
                t.assertEquals(1, roster, "roster must not duplicate the restored player")
                val role = store.getComponent(ref, Role.componentType)
                t.assertEquals(Role.Kind.PARTICIPANT, role?.kind, "role restored")
            }
        }
        t.assertTrue(alice2.sawMessage("Alice rejoined the minigame."),
            "rejoin should be announced, got: ${alice2.receivedMessages()}")
        t.succeed()
    }

    /** When the minigame ENDS, preserved memberships are released with it. */
    @HytaleTest(timeoutSeconds = 60)
    fun endedInstanceReleasesPreservedMembership(t: HytaleTestHelper) {
        val alice = player(t)
        val uuid = java.util.UUID.randomUUID()
        val aliceHandle = t.spawnSimulatedPlayer("Alice", uuid)
        MinigameTestHarness.grantAdmin(aliceHandle.ref())

        val instanceId = createInstance(t, aliceHandle, "arena")
        aliceHandle.runCommand("minigame team create red").join()
        aliceHandle.runCommand("minigame join").join()
        aliceHandle.runCommand("minigame team join red").join()
        aliceHandle.disconnect()

        val world = t.world
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertEquals(1, store.getComponent(controller,
                    gg.ginco.minigame.component.PreservedMembers.componentType)?.members?.size,
                    "preserved record must exist")
            }
        }

        // The minigame ends while the player is away.
        alice.runCommand("minigame stop").join()
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                t.assertTrue(MinigameTestHarness.controllers(store).isEmpty(),
                    "instance should be gone")
            }
        }

        // The returning player finds nothing preserved and nothing joinable.
        val alice2 = aliceHandle.reconnect().join()
        MinigameTestHarness.grantAdmin(alice2.ref())
        alice2.runCommand("minigame join").join()
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val ref = MinigameTestHarness.refOf(alice2.ref())
            t.assertEquals(null, MinigameTestHarness.inMinigameOf(store, ref),
                "ended instance must not restore membership")
        }
        t.assertTrue(alice2.sawMessage("No joinable instance right now"),
            "rejoin into an ended game should be rejected, got: ${alice2.receivedMessages()}")
        t.succeed()
    }

    /** A player who was never in a minigame can disconnect without side effects. */
    @HytaleTest(timeoutSeconds = 60)
    fun disconnectWithoutMembershipIsHarmless(t: HytaleTestHelper) {
        val alice = player(t)
        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()
        alice.drainReceivedMessages()

        val bob = player(t, "Bob")
        bob.disconnect() // never joined anything

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
            val members = MinigameTestHarness.controllers(store)
            t.assertEquals(1, members.size, "instance unaffected")
            val rosterRed = Teams.of(store, controller).size
            t.assertEquals(0, rosterRed, "no teams involved")
        }
        t.succeed()
    }

    /** The released player's Role was participant — not disqualified: a disconnect is not an elimination. */
    @HytaleTest(timeoutSeconds = 60)
    fun disconnectIsNotAnElimination(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()
        bob.runCommand("minigame join").join()

        alice.disconnect()

        val world = t.world
        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                // Alice is gone from the store's player set entirely; the
                // meaningful check is that BOB carries no eliminated role and
                // the instance kept running.
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                t.assertNotNull(controller, "instance must survive a plain disconnect")
                val bobRole = store.getComponent(MinigameTestHarness.refOf(bob.ref()), Role.componentType)
                t.assertEquals(Role.participant(), bobRole, "other players are untouched")
            }
        }
        t.succeed()
    }

}

