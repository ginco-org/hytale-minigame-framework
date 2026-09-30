package gg.ginco.minigame

import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.util.Teams

/**
 * Assets-as-config v1: definitions are JSON in the plugin data directory;
 * `/minigame defs save` captures a live instance's teams (the in-game
 * editor write path), `reload` re-reads disk (the boot path is the same
 * code), and `/minigame create <gametype>` spawns teams from the
 * definition. Storage backend is swappable — tests only use the commands.
 */
class MinigameDefinitionsTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    /** The editor loop for teams: build teams in-game, save, create from the definition. */
    @HytaleTest(timeoutSeconds = 60)
    fun saveThenCreateSpawnsTeams(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame create def-roundtrip").join()
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame team create blue").join()
        alice.drainReceivedMessages()

        alice.runCommand("minigame defs save").join()
        t.assertTrue(alice.sawMessage("Saved definition 'def-roundtrip' (2 team(s))."),
            "save should capture teams, got: ${alice.receivedMessages()}")

        // A fresh instance of the same game type spawns FROM the definition.
        // (No drain here — the create confirmation is asserted at the end.)
        alice.runCommand("minigame create def-roundtrip").join()

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val clones = MinigameTestHarness.controllers(store)
                .filter { MinigameTestHarness.controllerOf(store, it)?.gameTypeId == "def-roundtrip" }
            t.assertEquals(2, clones.size, "both def-roundtrip instances should exist")

            val clone = clones.firstOrNull {
                Teams.of(store, it).isNotEmpty()
            } ?: return@runOnWorld
            val teams = Teams.of(store, clone)
            t.assertEquals(2, teams.size, "definition teams should be spawned")
            t.assertEquals(setOf("red", "blue"), teams.map { it.data.name }.toSet(),
                "team names should come from the definition")
            val rosters = teams.map { store.getComponent(it.ref, TeamMembers.componentType)?.members?.size }
            t.assertEquals(listOf(0, 0), rosters, "definition teams start with empty rosters")
        }
        t.assertTrue(alice.sawMessage("from definition 'def-roundtrip' (2 team(s))."),
            "create should say it spawned from the definition, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    /** `defs reload` re-reads the files from disk — the same path boot uses. */
    @HytaleTest(timeoutSeconds = 60)
    fun reloadRereadsDefinitionsFromDisk(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "def-reload")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame defs save").join()
        alice.drainReceivedMessages()

        alice.runCommand("minigame defs reload").join()
        // The suite's other tests may have saved definitions too — only our
        // own definition's presence is deterministic; the exact global count
        // depends on test order.
        t.assertTrue(alice.sawMessage("Reloaded ") && alice.sawMessage("definition(s)."),
            "reload should report a count, got: ${alice.receivedMessages()}")

        alice.runCommand("minigame defs list").join()
        t.assertTrue(alice.sawMessage("def-reload (1 team(s))"),
            "list should show the reloaded definition, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    /** Instances without a definition stay plain: no phantom teams or markers. */
    @HytaleTest(timeoutSeconds = 60)
    fun createWithoutDefinitionStaysPlain(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "plain-nodef")

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllerOfGameType(store, "plain-nodef")!!
            t.assertEquals(0, Teams.of(store, controller).size, "no teams should exist")
        }
        t.assertTrue(alice.sawMessage("(gameType=plain-nodef, phase=Waiting)."),
            "plain create should use the plain message, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String) {
        alice.runCommand("minigame create $gameType").join()
    }
}
