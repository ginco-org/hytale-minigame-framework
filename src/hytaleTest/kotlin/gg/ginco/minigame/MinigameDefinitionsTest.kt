package gg.ginco.minigame

import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Regions
import gg.ginco.minigame.util.Teams
import org.joml.Vector3d

/**
 * Assets-as-config v1: definitions are JSON in the plugin data directory;
 * `/minigame defs save` captures a live instance (the in-game editor write
 * path), `reload` re-reads disk (the boot path is the same code), and
 * `/minigame create <gametype>` spawns teams and markers from the
 * definition. Storage backend is swappable — tests only use the commands.
 */
class MinigameDefinitionsTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    /** The full editor loop: build an instance in-game, save it, create from the definition. */
    @HytaleTest(timeoutSeconds = 60)
    fun saveThenCreateSpawnsTeamsAndMarkers(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame create def-roundtrip").join()
        alice.teleport(40.0, 64.0, 40.0).join()
        alice.runCommand("minigame region add spawn").join()
        alice.teleport(-40.0, 64.0, -40.0).join()
        alice.runCommand("minigame region add goal").join()
        alice.runCommand("minigame team create red").join()
        alice.drainReceivedMessages()

        alice.runCommand("minigame defs save").join()
        t.assertTrue(alice.sawMessage("Saved definition 'def-roundtrip' (1 team(s), 2 marker(s))."),
            "save should capture teams and markers, got: ${alice.receivedMessages()}")

        // A fresh instance of the same game type spawns FROM the definition.
        // (No drain here — the create confirmation is asserted at the end.)
        alice.runCommand("minigame create def-roundtrip").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val clones = MinigameTestHarness.controllers(store)
                .filter { MinigameTestHarness.controllerOf(store, it)?.gameTypeId == "def-roundtrip" }
            t.assertEquals(2, clones.size, "both arena instances should exist")

            val clone = clones.firstOrNull {
                Regions.markersOf(store, it).isNotEmpty()
            } ?: return@runOnWorld
            val markers = Regions.markersOf(store, clone)
            t.assertEquals(2, markers.size, "definition markers should be spawned")
            t.assertEquals(
                setOf("spawn", "goal"), markers.map { it.type }.toSet(),
                "marker types should come from the definition")
            val goal = markers.first { it.type == "goal" }
            t.assertTrue(goal.contains(Vector3d(-40.0, 64.0, -40.0)),
                "the goal marker should sit at its defined bounds")

            val teams = Teams.of(store, clone)
            t.assertEquals(1, teams.size, "definition teams should be spawned")
            t.assertEquals("red", teams.single().data.name, "team name should come from the definition")
            val roster = store.getComponent(teams.single().ref, gg.ginco.minigame.component.TeamMembers.componentType)?.members?.size
            t.assertEquals(0, roster, "definition teams start with empty rosters")
        }
        t.assertTrue(alice.sawMessage("Created instance #"),
            "create should confirm, got: ${alice.receivedMessages()}")
        t.assertTrue(alice.sawMessage("from definition 'def-roundtrip' (1 team(s), 2 marker(s))."),
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
        t.assertTrue(alice.sawMessage("def-reload (1 team(s), 0 marker(s))"),
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
            t.assertEquals(0, Regions.markersOf(store, controller).size, "no markers should exist")
        }
        t.assertTrue(alice.sawMessage("(gameType=plain-nodef, phase=Waiting)."),
            "plain create should use the plain message, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String) {
        alice.runCommand("minigame create $gameType").join()
    }
}
