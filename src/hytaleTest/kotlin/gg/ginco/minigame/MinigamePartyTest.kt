package gg.ginco.minigame

import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.definition.DefinitionStore
import gg.ginco.minigame.definition.MinigameDefinition
import gg.ginco.minigame.definition.TeamDefinition
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Parties
import gg.ginco.minigame.util.Teams

/**
 * Parties: groups that join together and land on the same team. The
 * framework owns the plumbing (uuid-keyed state, leader-driven join with
 * atomic rejection); game meaning stays consumer-side.
 *
 * Every test uses own game type ids — definitions persist across tests —
 * and clears [Parties] in a finally so no party leaks into the next test.
 */
class MinigamePartyTest {

    private fun player(t: HytaleTestHelper, name: String): SimulatedPlayer = Players.admin(t, name)

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String) {
        alice.runCommand("minigame create $gameType").join()
    }

    /** The party lifecycle commands: create, invite, accept, list, leave, disband. */
    @HytaleTest(timeoutSeconds = 60)
    fun partyLifecycleCommands(t: HytaleTestHelper) {
        try {
            val alice = player(t, "Alice")
            val bob = player(t, "Bob")

            alice.runCommand("minigame party create").join()
            t.assertTrue(alice.sawMessage("Party created."),
                "create should confirm, got: ${alice.receivedMessages()}")

            alice.runCommand("minigame party create").join()
            t.assertTrue(alice.sawMessage("You are already in a party."),
                "double create must be rejected, got: ${alice.receivedMessages()}")

            alice.runCommand("minigame party invite Bob").join()
            t.assertTrue(alice.sawMessage("Invited Bob."),
                "the inviter should be told, got: ${alice.receivedMessages()}")
            t.assertTrue(bob.sawMessage("Alice invited you to their party."),
                "the invitee should be told how to accept, got: ${bob.receivedMessages()}")

            bob.runCommand("minigame party accept").join()
            t.assertTrue(bob.sawMessage("You joined Alice's party."),
                "accept should confirm to the joiner, got: ${bob.receivedMessages()}")
            t.assertTrue(alice.sawMessage("Bob joined the party."),
                "the leader should hear about the accept, got: ${alice.receivedMessages()}")

            bob.runCommand("minigame party list").join()
            t.assertTrue(bob.sawMessage("Party led by Alice: Alice, Bob"),
                "list should show leader and members, got: ${bob.receivedMessages()}")

            bob.runCommand("minigame party leave").join()
            t.assertTrue(bob.sawMessage("You left the party."),
                "leave should confirm, got: ${bob.receivedMessages()}")
            t.assertTrue(alice.sawMessage("Bob left the party."),
                "the leader should hear about the leave, got: ${alice.receivedMessages()}")
            bob.runCommand("minigame party list").join()
            t.assertTrue(bob.sawMessage("You are not in a party."),
                "the leaver is out, got: ${bob.receivedMessages()}")

            // The leader leaving disbands: re-invite Bob first, then disband.
            alice.runCommand("minigame party invite Bob").join()
            bob.runCommand("minigame party accept").join()
            alice.drainReceivedMessages(); bob.drainReceivedMessages()
            alice.runCommand("minigame party leave").join()
            t.assertTrue(alice.sawMessage("You left the party; it was disbanded."),
                "leader leave should say disbanded, got: ${alice.receivedMessages()}")
            t.assertTrue(bob.sawMessage("Alice left the party; it was disbanded."),
                "members should hear about the disband, got: ${bob.receivedMessages()}")
            alice.runCommand("minigame party list").join()
            t.assertTrue(alice.sawMessage("You are not in a party."),
                "a disbanded party is gone for everyone, got: ${alice.receivedMessages()}")
            t.succeed()
        } finally {
            Parties.clear()
        }
    }

    /** A leader-driven join puts the whole party into one instance AND one team. */
    @HytaleTest(timeoutSeconds = 60)
    fun partyJoinsTogetherOntoOneTeam(t: HytaleTestHelper) {
        try {
            DefinitionStore.save(MinigameDefinition(
                gameTypeId = "party-join",
                teams = listOf(TeamDefinition(name = "red"), TeamDefinition(name = "blue")),
            ))
            val alice = player(t, "Alice")
            val bob = player(t, "Bob")
            createInstance(t, alice, "party-join")

            alice.runCommand("minigame party create").join()
            alice.runCommand("minigame party invite Bob").join()
            bob.runCommand("minigame party accept").join()
            alice.drainReceivedMessages(); bob.drainReceivedMessages()

            alice.runCommand("minigame join").join()
            t.assertTrue(alice.sawMessage("Alice's party (2 players) joined the minigame."),
                "the join should announce the party as a group, got: ${alice.receivedMessages()}")

            val world = t.world
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                val controller = MinigameTestHarness.controllerOfGameType(store, "party-join")!!
                for (p in listOf(alice, bob)) {
                    val ref = MinigameTestHarness.refOf(p.ref())
                    t.assertEquals(controller,
                        MinigameTestHarness.inMinigameOf(store, ref)?.controllerRef,
                        "${p.name} should be in the party's instance")
                    val teamRef = store.getComponent(ref, gg.ginco.minigame.component.TeamRef.componentType)?.teamRef
                    val teamName = teamRef?.let { store.getComponent(it, TeamData.componentType)?.name }
                    t.assertEquals("red", teamName, "${p.name} should be on the SAME team")
                }
                val roster = store.getComponent(
                    Teams.findByName(store, controller, "red")!!.ref, TeamMembers.componentType)?.members
                t.assertEquals(setOf(alice.uuid, bob.uuid), roster?.toSet(),
                    "the team roster should hold the whole party (uuid-keyed)")
            }
            t.succeed()
        } finally {
            runCatching { DefinitionStore.remove("party-join") }
            Parties.clear()
        }
    }

    /**
     * A full team rejects the WHOLE party — no partial join. Both definition
     * teams cap at 1, so a party of 2 fits nowhere: nobody joins.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun fullTeamRejectsWholeParty(t: HytaleTestHelper) {
        try {
            DefinitionStore.save(MinigameDefinition(
                gameTypeId = "party-full",
                teams = listOf(
                    TeamDefinition(name = "red", maxPlayers = 1),
                    TeamDefinition(name = "blue", maxPlayers = 1),
                ),
            ))
            val alice = player(t, "Alice")
            val bob = player(t, "Bob")
            createInstance(t, alice, "party-full")

            alice.runCommand("minigame party create").join()
            alice.runCommand("minigame party invite Bob").join()
            bob.runCommand("minigame party accept").join()
            alice.drainReceivedMessages()

            val world = t.world
            val instanceId = t.runOnWorldReturning {
                val store = MinigameTestHarness.storeOf(world)
                MinigameTestHarness.instanceIdOf(store, MinigameTestHarness.controllerOfGameType(store, "party-full")!!)
            }
            alice.runCommand("minigame join").join()
            t.assertTrue(
                alice.sawMessage("No team in instance #$instanceId has room for a party of 2 — nobody joined."),
                "the rejection should name the instance and party size, got: ${alice.receivedMessages()}")

            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                for (p in listOf(alice, bob)) {
                    t.assertEquals(null, MinigameTestHarness.inMinigameOf(store, MinigameTestHarness.refOf(p.ref())),
                        "${p.name} must not have joined — rejection is atomic")
                }
                val controller = MinigameTestHarness.controllerOfGameType(store, "party-full")!!
                for (team in Teams.of(store, controller)) {
                    val roster = store.getComponent(team.ref, TeamMembers.componentType)?.members
                    t.assertEquals(0, roster?.size, "team '${team.data.name}' must stay empty")
                }
            }
            t.succeed()
        } finally {
            runCatching { DefinitionStore.remove("party-full") }
            Parties.clear()
        }
    }

    /** Only the leader drives a party join; a member's join is refused outright. */
    @HytaleTest(timeoutSeconds = 60)
    fun memberCannotDrivePartyJoin(t: HytaleTestHelper) {
        try {
            val alice = player(t, "Alice")
            val bob = player(t, "Bob")
            createInstance(t, alice, "party-solo")

            alice.runCommand("minigame party create").join()
            alice.runCommand("minigame party invite Bob").join()
            bob.runCommand("minigame party accept").join()
            bob.drainReceivedMessages()

            bob.runCommand("minigame join").join()
            t.assertTrue(bob.sawMessage("Only the party leader can start a party join."),
                "a member's join should be refused, got: ${bob.receivedMessages()}")
            val world = t.world
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(world)
                t.assertEquals(null, MinigameTestHarness.inMinigameOf(store, MinigameTestHarness.refOf(bob.ref())),
                    "the refused join must not tag the member")
            }
            t.succeed()
        } finally {
            Parties.clear()
        }
    }

    /** Disconnect preserves the party slot; the returning player is still a member. */
    @HytaleTest(timeoutSeconds = 60)
    fun partySlotSurvivesDisconnect(t: HytaleTestHelper) {
        try {
            val uuid = java.util.UUID.randomUUID()
            val alice = player(t, "Alice")
            val bob = t.spawnSimulatedPlayer("Bob", uuid)
            MinigameTestHarness.grantAdmin(bob.ref())

            alice.runCommand("minigame party create").join()
            alice.runCommand("minigame party invite Bob").join()
            bob.runCommand("minigame party accept").join()
            alice.drainReceivedMessages()

            bob.disconnect()
            // The roster keeps the uuid — same preservation rule as team
            // rosters and preserved membership.
            t.assertTrue(Parties.partyOf(uuid) != null, "the party slot must survive the disconnect")
            alice.runCommand("minigame party list").join()
            t.assertTrue(alice.sawMessage("Party led by Alice: Alice, Bob"),
                "list should still name the disconnected member, got: ${alice.receivedMessages()}")

            val bob2 = bob.reconnect().join()
            bob2.runCommand("minigame party list").join()
            t.assertTrue(bob2.sawMessage("Party led by Alice: Alice, Bob"),
                "the returning player is still a member, got: ${bob2.receivedMessages()}")
            t.succeed()
        } finally {
            Parties.clear()
        }
    }
}
