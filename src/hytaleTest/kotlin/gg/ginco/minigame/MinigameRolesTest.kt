package gg.ginco.minigame

import com.hypixel.hytale.protocol.GameMode
import com.hypixel.hytale.server.core.entity.entities.Player
import com.hypixel.hytale.server.core.entity.entities.player.HiddenPlayersManager
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Roles as RolesUtil
import gg.ginco.minigame.util.Roles

/**
 * Mechanical role helpers ([Roles.spectate]/[Roles.unspectate]/
 * [Roles.disqualify]): the framework applies the mechanics; consumers decide
 * when. Spectator = Creative + flight + hidden from instance members (0.6.8
 * has no spectator game mode).
 */
class MinigameRolesTest {

    /** spectate: hidden from members, Creative, flying — and reversible. */
    @HytaleTest(timeoutSeconds = 60)
    fun spectatorEnableAndDisable(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()
        bob.runCommand("minigame join").join()

        val aliceRef = MinigameTestHarness.refOf(alice.ref())
        val bobRef = MinigameTestHarness.refOf(bob.ref())

        t.runOnWorld { Roles.spectate(store(t), aliceRef, alice.ref()) }

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val alicePlayer = store.getComponent(aliceRef, Player.getComponentType())!!
            t.assertEquals(GameMode.Creative, alicePlayer.gameMode, "spectator should be Creative")
            t.assertTrue(alicePlayer.isFlying(aliceRef, store), "spectator should fly")
            t.assertTrue(alicePlayer.isNoClip, "spectator should move through the arena (no-clip)")

            val bobsView = bob.ref().hiddenPlayersManager
            t.assertTrue(bobsView.isPlayerHidden(alice.uuid), "bob should not see the spectator")
        }

        t.runOnWorld { Roles.unspectate(store(t), aliceRef, alice.ref()) }

        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val alicePlayer = store.getComponent(aliceRef, Player.getComponentType())!!
            t.assertEquals(GameMode.Adventure, alicePlayer.gameMode, "unspectate restores Adventure")
            t.assertFalse(alicePlayer.isFlying(aliceRef, store), "flight should be off")
            t.assertFalse(alicePlayer.isNoClip, "collision should be restored")
            val bobsView = bob.ref().hiddenPlayersManager
            t.assertFalse(bobsView.isPlayerHidden(alice.uuid), "bob should see Alice again")
        }
        t.succeed()
    }

    /**
     * A participant who joins AFTER someone started spectating must not see
     * the spectator either — visibility is caught up at every join path.
     */
    @HytaleTest(timeoutSeconds = 60)
    fun lateJoinerDoesNotSeeExistingSpectator(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val aliceRef = MinigameTestHarness.refOf(alice.ref())
            // The consumer's composition: spectator role + the mechanics.
            store.replaceComponent(aliceRef, Role.componentType, Role.spectator())
            Roles.spectate(store, aliceRef, alice.ref())
        }

        // Bob joins AFTER Alice became a spectator.
        bob.runCommand("minigame join").join()

        t.runOnWorld {
            val bobsView = bob.ref().hiddenPlayersManager
            t.assertTrue(bobsView.isPlayerHidden(alice.uuid),
                "a member joining later must not see the existing spectator")
        }

        // And unspectate still restores everything for everyone.
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            Roles.unspectate(store, MinigameTestHarness.refOf(alice.ref()), alice.ref())
        }
        t.runOnWorld {
            val bobsView = bob.ref().hiddenPlayersManager
            t.assertFalse(bobsView.isPlayerHidden(alice.uuid), "unspectate restores visibility for late joiners too")
        }
        t.succeed()
    }

    /** Disqualification swaps the role and records the reason; instance members are untouched. */
    @HytaleTest(timeoutSeconds = 60)
    fun disqualifySetsRoleWithReason(t: HytaleTestHelper) {
        val alice = player(t)
        val bob = player(t, "Bob")
        createInstance(t, alice, "arena")
        alice.runCommand("minigame join").join()
        bob.runCommand("minigame join").join()

        val world = t.world
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val bobRef = MinigameTestHarness.refOf(bob.ref())
            Roles.disqualify(store, bobRef, "fell out of the world")
            val role = store.getComponent(bobRef, Role.componentType)
            t.assertEquals(Role.Kind.DISQUALIFIED, role?.kind, "bob should be disqualified")
            t.assertEquals("fell out of the world", role?.reason, "the reason should be recorded")

            val aliceRole = store.getComponent(MinigameTestHarness.refOf(alice.ref()), Role.componentType)
            t.assertEquals(Role.Kind.PARTICIPANT, aliceRole?.kind, "other players are untouched")
        }

        // Role mechanics are data only: no game mode/flight side effects.
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(world)
            val bobPlayer = store.getComponent(MinigameTestHarness.refOf(bob.ref()), Player.getComponentType())!!
            t.assertEquals(GameMode.Adventure, bobPlayer.gameMode,
                "disqualify must not change mechanics — that is the consumer's call")
        }
        t.succeed()
    }

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer = Players.admin(t, name)

    private fun createInstance(t: HytaleTestHelper, alice: SimulatedPlayer, gameType: String) {
        alice.runCommand("minigame create $gameType").join()
    }

    private fun store(t: HytaleTestHelper) = MinigameTestHarness.storeOf(t.world)
}
