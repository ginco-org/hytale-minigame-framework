package gg.ginco.minigame

import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.component.TeamRef
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Teams

/**
 * Integration tests for the `/minigame team` tree, run inside a real server
 * with the real plugin (see [MinigameLifecycleTest] for the general model).
 *
 * Team model under test: a team is an entity with `TeamData` + `TeamMembers` +
 * `InMinigame` (linking it to its instance); players carry `TeamRef` -> team
 * and are listed in the team's roster. Names are unique per instance, not
 * globally. Instance cleanup must release both sides.
 */
class MinigameTeamTest {

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    /** Creates an instance as [alice] and returns its per-boot instance id. */
    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String): Int {
        alice.runCommand("minigame create $gameType").join()
        return t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            MinigameTestHarness.instanceIdOf(store, MinigameTestHarness.controllerOfGameType(store, gameType)!!)
        }!!
    }

    /** @HytaleTest helper: returns the roster size of the team named [name] in [instanceId]. */
    private fun rosterSize(t: HytaleTestHelper, instanceId: Int, name: String): Int =
        t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllers(store).firstOrNull {
                MinigameTestHarness.instanceIdOf(store, it) == instanceId
            } ?: return@runOnWorldReturning -1
            val team = Teams.findByName(store, controller, name) ?: return@runOnWorldReturning -1
            store.getComponent(team.ref, TeamMembers.componentType)?.members?.size ?: 0
        }

    /** `team create` produces a team entity wired to its instance, with an empty roster at score 0. */
    @HytaleTest(timeoutSeconds = 60)
    fun createTeamProducesTeamEntity(t: HytaleTestHelper) {
        val alice = player(t)
        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val controller = MinigameTestHarness.controllers(store).single()
            val teams = Teams.of(store, controller)
            t.assertEquals(1, teams.size, "exactly one team should exist")
            val team = teams.single()
            t.assertEquals("red", team.data.name, "team should carry the requested name")
            t.assertEquals(0, team.data.score, "new teams start at score 0")
            t.assertEquals(0, store.getComponent(team.ref, TeamMembers.componentType)?.members?.size,
                "new teams start with an empty roster")
            val controllerOfTeam = store.getComponent(team.ref, gg.ginco.minigame.component.InMinigame.componentType)?.controllerRef
            t.assertEquals(controller, controllerOfTeam, "team must be linked to its instance")
        }
        t.assertTrue(alice.sawMessage("Created team 'red' for instance #$instanceId."),
            "create should confirm team and instance, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    /** Team names are unique per instance, but two instances may each have 'red'. */
    @HytaleTest(timeoutSeconds = 60)
    fun teamNamesAreUniquePerInstanceOnly(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "one")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame team create red").join()
        t.assertTrue(alice.sawMessage("already has a team named 'red'"),
            "duplicate name must be rejected, got: ${alice.receivedMessages()}")

        createInstance(t, alice, "two")
        alice.runCommand("minigame team create red --game=${highestInstanceId(t)}").join()
        t.assertTrue(alice.sawMessage("Created team 'red' for instance #"),
            "the same name must be allowed on another instance, got: ${alice.receivedMessages()}")

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val redTeams = MinigameTestHarness.controllers(store).flatMap { Teams.of(store, it) }
            t.assertEquals(2, redTeams.size, "each instance should have its own 'red' team")
        }
        t.succeed()
    }

    /** `team join` sets TeamRef, updates the roster, and announces to the team. */
    @HytaleTest(timeoutSeconds = 60)
    fun teamJoinTagsPlayerAndUpdatesRoster(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame team join red").join()

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val ref = MinigameTestHarness.refOf(alice.ref())
            val teamRef = store.getComponent(ref, TeamRef.componentType)?.teamRef
            t.assertNotNull(teamRef, "team join should set TeamRef on the player")
            t.assertEquals("red", teamRef?.let { store.getComponent(it, TeamData.componentType)?.name },
                "TeamRef should point at the 'red' team")
            val roster = teamRef?.let { store.getComponent(it, TeamMembers.componentType)?.members }
            t.assertEquals(1, roster?.size, "roster should list exactly the joiner")
            t.assertEquals(ref, roster?.single(), "roster should reference the joiner's entity")
        }
        t.assertTrue(alice.sawMessage("Alice joined team 'red'."),
            "the joiner should hear the team announcement, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    /** Guards: not in a minigame, unknown team, and double join are all rejected cleanly. */
    @HytaleTest(timeoutSeconds = 60)
    fun teamJoinGuards(t: HytaleTestHelper) {
        val alice = player(t)
        alice.runCommand("minigame team join red").join()
        t.assertTrue(alice.sawMessage("You are not in a minigame."),
            "team join without membership must be rejected, got: ${alice.receivedMessages()}")

        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()
        alice.drainReceivedMessages()
        alice.runCommand("minigame team join red").join()
        t.assertTrue(alice.sawMessage("No team named 'red' in your minigame."),
            "unknown team must be rejected, got: ${alice.receivedMessages()}")

        alice.runCommand("minigame team create blue").join()
        alice.runCommand("minigame team join blue").join()
        t.assertEquals(1, rosterSize(t, instanceId, "blue"), "first join should land")
        alice.runCommand("minigame team join red").join()
        t.assertTrue(alice.sawMessage("You are already on team 'blue'."),
            "double join must be rejected, got: ${alice.receivedMessages()}")
        t.assertEquals(1, rosterSize(t, instanceId, "blue"), "rejected join must not change the roster")
        t.succeed()
    }

    /** `team leave` clears TeamRef and the roster entry; rejoining works afterwards. */
    @HytaleTest(timeoutSeconds = 60)
    fun teamLeaveRemovesMembership(t: HytaleTestHelper) {
        val alice = player(t)
        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame team join red").join()

        alice.runCommand("minigame team leave").join()
        t.assertEquals(0, rosterSize(t, instanceId, "red"), "roster should drop the leaver")
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val ref = MinigameTestHarness.refOf(alice.ref())
            t.assertEquals(null, store.getComponent(ref, TeamRef.componentType), "TeamRef must be gone")
        }
        t.assertTrue(alice.sawMessage("Alice left team 'red'."),
            "leave should announce to the leaver, got: ${alice.receivedMessages()}")

        alice.runCommand("minigame team join red").join()
        t.assertEquals(1, rosterSize(t, instanceId, "red"), "rejoining after leaving should work")
        t.succeed()
    }

    /** Teams are namespaced per instance: joining 'red' lands in YOUR instance's team. */
    @HytaleTest(timeoutSeconds = 60)
    fun teamJoinIsScopedToOwnInstance(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        val oneId = createInstance(t, alice, "one")
        val twoId = createInstance(t, bob, "two")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame join").join()
        bob.runCommand("minigame join --game=$twoId").join()
        bob.runCommand("minigame team create red").join() // bob is admin too; team on HIS instance
        bob.runCommand("minigame team join red").join()

        t.assertEquals(1, rosterSize(t, twoId, "red"), "bob should be in instance #two's red team")
        t.assertEquals(0, rosterSize(t, oneId, "red"), "instance #one's red team must stay empty")
        t.assertTrue(bob.sawMessage("Bob joined team 'red'."),
            "joiner should hear the announcement, got: ${bob.receivedMessages()}")
        t.succeed()
    }

    /** `team list` reports each team with score and member count. */
    @HytaleTest(timeoutSeconds = 60)
    fun teamListReportsTeamsAndCounts(t: HytaleTestHelper) {
        val alice = player(t)
        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame team create blue").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame team join red").join()
        alice.drainReceivedMessages()

        alice.runCommand("minigame team list").join()
        t.assertTrue(alice.sawMessage("Instance #$instanceId teams: 'red' (0 pts, 1 player(s))"),
            "list should show red with its member, got: ${alice.receivedMessages()}")
        t.assertTrue(alice.sawMessage("'blue' (0 pts, 0 player(s))"),
            "list should show blue as empty, got: ${alice.receivedMessages()}")
        t.succeed()
    }

    /** Leaving the minigame also leaves the team (roster must not keep the player). */
    @HytaleTest(timeoutSeconds = 60)
    fun leavingMinigameAlsoLeavesTeam(t: HytaleTestHelper) {
        val alice = player(t)
        val instanceId = createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame team join red").join()

        alice.runCommand("minigame leave").join()
        t.assertEquals(0, rosterSize(t, instanceId, "red"), "roster must drop players who left the instance")
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val ref = MinigameTestHarness.refOf(alice.ref())
            t.assertEquals(null, store.getComponent(ref, TeamRef.componentType), "TeamRef must be gone")
            t.assertEquals(null, store.getComponent(ref, gg.ginco.minigame.component.InMinigame.componentType),
                "InMinigame must be gone")
        }
        t.succeed()
    }

    /** Instance cleanup removes the instance's teams entirely. */
    @HytaleTest(timeoutSeconds = 60)
    fun instanceCleanupReleasesTeams(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame join").join()
        alice.runCommand("minigame team join red").join()
        alice.runCommand("minigame stop").join()

        t.succeedWhen {
            t.runOnWorld {
                val store = MinigameTestHarness.storeOf(t.world)
                t.assertTrue(MinigameTestHarness.controllers(store).isEmpty(), "instance should be gone")
                t.assertEquals(0, teamEntityCount(t), "team entities should be removed with their instance")
                val ref = MinigameTestHarness.refOf(alice.ref())
                t.assertEquals(null, store.getComponent(ref, TeamRef.componentType),
                    "player TeamRef must be stripped on cleanup")
            }
        }
        t.succeed()
    }

    /** Teams are not players: PlayerSet counts stay player-only once teams carry InMinigame. */
    @HytaleTest(timeoutSeconds = 60)
    fun teamsAreNotCountedAsPlayers(t: HytaleTestHelper) {
        val alice = player(t)
        createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()
        alice.drainReceivedMessages()

        alice.runCommand("minigame team create red").join()
        alice.runCommand("minigame status").join()
        t.assertTrue(alice.sawMessage("players=1"),
            "status must count only players, got: ${alice.receivedMessages()}")

        val teamCount = teamEntityCount(t)
        t.assertEquals(1, teamCount, "the team entity should exist in the store")
        t.succeed()
    }

    private fun teamEntityCount(t: HytaleTestHelper): Int =
        t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            var n = 0
            store.forEachChunk(
                com.hypixel.hytale.component.Archetype.of(TeamData.componentType),
                java.util.function.BiConsumer<com.hypixel.hytale.component.ArchetypeChunk<EntityStore>,
                    com.hypixel.hytale.component.CommandBuffer<EntityStore>> { chunk, _ -> n += chunk.size() }
            )
            n
        }

    private fun highestInstanceId(t: HytaleTestHelper): Int =
        t.runOnWorldReturning {
            val store = MinigameTestHarness.storeOf(t.world)
            MinigameTestHarness.controllers(store).maxOf { MinigameTestHarness.instanceIdOf(store, it) ?: 0 }
        }
}
