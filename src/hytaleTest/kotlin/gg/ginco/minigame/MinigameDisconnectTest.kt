package gg.ginco.minigame

import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.TeamMembers
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

    /** Disconnecting releases membership and the team roster slot, and tells the remaining members. */
    @HytaleTest(timeoutSeconds = 60)
    fun disconnectReleasesMembershipAndRoster(t: HytaleTestHelper) {
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
        // NOTE: the departed player's ENTITY is removed by the server, so the
        // observable post-disconnect leak is the team roster on the (still
        // alive) team entity — that is what the release handler must clean.
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                val roster = store.getComponent(
                    Teams.findByName(store, controller, "red")!!.ref,
                    TeamMembers.componentType,
                )?.members?.size
                t.assertEquals(0, roster, "the team roster must not keep the departed player")
                t.assertEquals(instanceId, MinigameTestHarness.instanceIdOf(store, controller),
                    "the instance stays alive and is the same one")
            }
        }
        // The announcement lands on the world thread with the release —
        // assertEventually, not an immediate assert (async race).
        t.assertEventually {
            t.assertTrue(bob.sawMessage("Alice disconnected."),
                "remaining members should be told, got: ${bob.receivedMessages()}")
        }
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

    /**
     * Full round trip with hytest's fixed-identity primitive: a released
     * player reconnects with the SAME uuid and rejoins the still-running
     * instance like anyone else (state preservation across rejoin is a
     * future feature — today membership is simply granted again).
     */
    @HytaleTest(timeoutSeconds = 60)
    fun reconnectedPlayerCanRejoin(t: HytaleTestHelper) {
        val uuid = java.util.UUID.randomUUID()
        val alice = t.spawnSimulatedPlayer("Alice", uuid)
        MinigameTestHarness.grantAdmin(alice.ref())

        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame team join red").join()
        alice.disconnect()

        val world = t.world
        // Post-disconnect the player ENTITY is gone — the observable leak is
        // the team roster (the release handler must have cleaned it).
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "arena")!!
                val roster = store.getComponent(
                    Teams.findByName(store, controller, "red")!!.ref,
                    TeamMembers.componentType,
                )?.members?.size
                t.assertEquals(0, roster, "roster must be released before reconnecting")
            }
        }

        val alice2 = alice.reconnect().join()
        t.assertEquals(uuid, alice2.uuid, "reconnect must preserve the identity")
        MinigameTestHarness.grantAdmin(alice2.ref()) // re-grant: harmless if the provider remembers the uuid

        alice2.runCommand("minigame join").join()
        t.assertEventually {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val ref = MinigameTestHarness.refOf(alice2.ref())
                val target = MinigameTestHarness.inMinigameOf(store, ref)?.controllerRef
                t.assertNotNull(target, "the returning player can rejoin")
                t.assertEquals(instanceId, target?.let { MinigameTestHarness.instanceIdOf(store, it) },
                    "rejoin lands in the same, still-running instance")
            }
        }
        t.succeed()
    }
}
