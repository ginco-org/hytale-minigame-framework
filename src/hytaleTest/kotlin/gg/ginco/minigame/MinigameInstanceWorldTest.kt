package gg.ginco.minigame

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.definition.DefinitionStore
import gg.ginco.minigame.definition.MinigameDefinition
import gg.ginco.minigame.definition.TeamDefinition
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.InstanceRegistry
import gg.ginco.minigame.util.Instances
import gg.ginco.minigame.util.Teams

/**
 * Instance worlds: a minigame instance that runs in its own temporary world,
 * backed by the server's builtin `Hytale:Instances` module.
 *
 * Each test authors its own instance asset — a runtime-registered writable asset
 * pack, because `/instance edit new` cannot do it on a test server (the base
 * pack is the read-only `Assets.zip`, which makes the command a silent no-op) —
 * and drives the normal `/minigame` flow over it.
 *
 * The semantics pinned down here, all measured on 0.6.8:
 * - `spawnInstance` **copies** the asset folder into a fresh world; the name is
 *   `instance-<asset>-<uuid>`, so every create is a *different* world.
 * - A player switching worlds has their entity rebuilt in the target store with
 *   its components intact — refs and store handles do not survive the hop,
 *   components do.
 *
 * Every read of an instance world goes through [Instances.runOnWorld]: a store
 * asserts its own thread, and the test body runs on the harness's world.
 */
class MinigameInstanceWorldTest {

    private var asset: MinigameTestHarness.InstanceAsset? = null
    private var gameTypeId: String? = null
    private var world: World? = null
    private var next = 0

    /**
     * Each test gets a UNIQUE asset name, instance id and asset pack.
     *
     * The instance worlds themselves are shared server state and are reaped
     * asynchronously at remove-when-empty speed; a later test reusing an earlier
     * one's asset name would find (and could join) its predecessor's world.
     */
    private fun scope(t: HytaleTestHelper, tag: String): String {
        val unique = "$tag-${next++}"
        asset = MinigameTestHarness.createInstanceAsset(unique)
        gameTypeId = "inst-$unique"
        return unique
    }

    @Suppress("UNUSED_PARAMETER")
    private fun setupInstance(t: HytaleTestHelper, tag: String, teams: List<String> = emptyList()): World {
        val unique = scope(t, tag)
        DefinitionStore.save(
            MinigameDefinition(gameTypeId = gameTypeId!!, instanceAsset = unique,
                teams = teams.map { TeamDefinition(name = it) }))

        // The admin teleports in and STAYS: this is the world every instance
        // assertion below is made against.
        val alice = player(t)
        alice.runCommand("minigame create ${gameTypeId!!}").join()
        val created = awaitInstanceWorld(t, unique)
        world = created
        return created
    }

    /**
     * Ends the instance (so its world does not linger into the next test), drops
     * the definition and unregisters the asset pack.
     */
    private fun cleanup() {
        world?.let { w ->
            runCatching { Instances.runOnWorld(w) { Instances.findController(w)?.let { Instances.endInstance(w, it) } } }
            // Serialize on the reap: on CI the removal pipeline's chunk save
            // occupies a world thread ~30s, and overlapping the next test
            // starves its instance work (see awaitInstanceWorldGone). Best
            // effort — a lingering world cannot collide with the next test's
            // uniquely-named asset.
            runCatching { MinigameTestHarness.awaitInstanceWorldGone(assetName) }
                .onFailure { System.err.println("[hytest] instance world '$assetName' still loaded after cleanup wait") }
        }
        world = null
        gameTypeId?.let { id -> runCatching { DefinitionStore.remove(id) } }
        gameTypeId = null
        asset?.let { a -> runCatching { MinigameTestHarness.removeAssetPack(a) } }
        asset = null
        InstanceRegistry.clear()
    }

    private fun player(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer {
        val p = Players.admin(t, name)
        // A simulated player can only cross a world boundary once the
        // client-ready handshake is answered — see keepClientReady.
        MinigameTestHarness.keepClientReady(t, p)
        return p
    }

    private fun <T> onInstanceWorld(world: World, body: () -> T): T = Instances.runOnWorld(world, body)

    /** The unique asset name the current test is scoped to. */
    private val assetName: String get() = requireNotNull(asset).name

    /** Waits until the asset's instance world is loaded, then returns it. */
    private fun awaitInstanceWorld(t: HytaleTestHelper, assetName: String): World {
        var found: World? = null
        t.assertEventually(60) {
            found = MinigameTestHarness.instanceWorldOf(assetName)
            t.assertNotNull(found, "instance world for '$assetName' should be loaded")
        }
        return found!!
    }

    private fun awaitWorldGone(t: HytaleTestHelper, worldName: String) {
        t.assertEventually(60) {
            t.assertEquals(null, Universe.get().getWorld(worldName),
                "instance world '$worldName' should have been removed")
        }
    }

    /**
     * `create` for a definition that names an instance asset spawns that asset's
     * world and builds the controller — and its teams — inside it, not in the
     * world the admin ran the command from.
     */
    @HytaleTest(timeoutSeconds = 120)
    fun createSpawnsInstanceWorldWithControllerAndTeams(t: HytaleTestHelper) {
        try {
            val world = setupInstance(t, "arena", listOf("red", "blue"))
            val gameType = gameTypeId!!

            t.assertTrue(world.name.startsWith("instance-"),
                "instance world should carry the builtin's name prefix, got '${world.name}'")
            t.assertTrue(world.name.contains(assetName),
                "instance world name should name its asset, got '${world.name}'")
            t.assertTrue(Instances.isInstanceWorld(world), "spawned world should be an instance world")

            // The lobby must NOT hold the controller: that is the point of
            // running the instance in its own world.
            t.runOnWorld {
                val lobbyControllers = MinigameTestHarness.controllers(MinigameTestHarness.storeOf(t.world))
                t.assertTrue(lobbyControllers.isEmpty(),
                    "the lobby must not hold the instance's controller, found $lobbyControllers")
            }

            onInstanceWorld(world) {
                val store = world.entityStore.store
                val controller = Instances.findController(world, gameType)
                t.assertNotNull(controller, "controller should exist in the instance world")
                // assertion above already proved non-null; local val for the compiler
                val ref = controller ?: error("no controller")
                t.assertEquals(gameType, MinigameTestHarness.controllerOf(store, ref)?.gameTypeId,
                    "controller should carry the game type")
                t.assertEquals(Phase.waiting(), MinigameTestHarness.phaseOf(store, ref),
                    "instance should start in Waiting")
                t.assertEquals(listOf("red", "blue"), Teams.of(store, ref).map { it.data.name },
                    "the definition's teams should be spawned in the instance world")
            }
            t.succeed()
        } finally {
            cleanup()
        }
    }

    /** Two creates of one game type yield two distinct worlds (the world key is per spawn). */
    @HytaleTest(timeoutSeconds = 120)
    fun eachCreateSpawnsItsOwnWorld(t: HytaleTestHelper) {
        try {
            val first = setupInstance(t, "duel")

            val alice = player(t, "Bob")
            alice.runCommand("minigame create ${gameTypeId!!}").join()

            var names: List<String> = emptyList()
            t.assertEventually(60) {
                names = Instances.instanceWorlds()
                    .filter { Instances.assetNameOf(it) == assetName }.map { it.name }
                t.assertEquals(2, names.size, "a second create must spawn its own world, got $names")
            }
            t.assertTrue(names.contains(first.name) && names.any { it != first.name },
                "both worlds must be live and distinct, got $names")
            t.succeed()
        } finally {
            cleanup()
        }
    }

    /** A definition naming an unknown instance asset is rejected without side effects. */
    @HytaleTest(timeoutSeconds = 120)
    fun unknownInstanceAssetIsRejected(t: HytaleTestHelper) {
        val gameType = "inst-ghost-${java.util.UUID.randomUUID().toString().substring(0, 8)}"
        try {
            DefinitionStore.save(MinigameDefinition(gameTypeId = gameType, instanceAsset = "no-such-asset-anywhere"))
            val alice = player(t)

            alice.runCommand("minigame create $gameType").join()

            t.assertTrue(alice.sawMessage("does not exist"),
                "create should report the missing asset, got: ${alice.receivedMessages()}")
            // Nothing OUR definition named may exist; other tests' instance worlds
            // are still winding down (removal is asynchronous), so assert on the
            // asset name this test owns rather than on a global count.
            t.assertEquals(null, MinigameTestHarness.instanceWorldOf("no-such-asset-anywhere"),
                "no world may exist for the missing asset")
            t.runOnWorld {
                t.assertTrue(MinigameTestHarness.controllers(MinigameTestHarness.storeOf(t.world)).isEmpty(),
                    "no controller should have been created")
            }
            t.succeed()
        } finally {
            runCatching { DefinitionStore.remove(gameType) }
            InstanceRegistry.clear()
        }
    }

    /**
     * Join teleports the player into the instance world and the membership is
     * applied *there* — the join is finished on arrival, inside that world's
     * store, because the controller is not readable anywhere else.
     */
    @HytaleTest(timeoutSeconds = 180)
    fun joinTeleportsPlayerAndAppliesMembership(t: HytaleTestHelper) {
        try {
            val world = setupInstance(t, "ctf", listOf("red"))

            val alice = player(t, "Bob")
            alice.runCommand("minigame join --asset=$assetName --team=red").join()

            // The hop is applied by the builtin's teleport tick, so wait for the
            // arrival rather than sleeping.
            t.assertEventually(60) {
                val ref = alice.ref().reference
                t.assertNotNull(ref, "player should still be in a world mid-teleport")
                t.assertEquals(world.entityStore.store, ref!!.store,
                    "player should have landed in the instance world '${world.name}'")
            }

            // Refs cannot cross stores: everything is read, and the controller
            // identity compared, inside the instance world.
            val membership = onInstanceWorld(world) {
                val store = world.entityStore.store
                val ref = alice.ref().reference!!
                val inMinigame = MinigameTestHarness.inMinigameOf(store, ref)
                val teamRef = store.getComponent(ref, gg.ginco.minigame.component.TeamRef.componentType)?.teamRef
                MembershipSnapshot(
                    inMinigame?.controllerRef,
                    MinigameTestHarness.roleOf(store, ref),
                    teamRef?.let { store.getComponent(it, gg.ginco.minigame.component.TeamData.componentType)?.name },
                )
            }
            val controller = onInstanceWorld(world) { Instances.findController(world, gameTypeId!!) }

            onInstanceWorld(world) {
                t.assertNotNull(controller, "the instance world should hold a controller")
                t.assertEquals(controller, membership.controllerRef,
                    "the membership should link to the instance world's own controller")
            }
            t.assertEquals(Role.participant(), membership.role,
                "player should be a participant in the instance world")
            t.assertEquals("red", membership.teamName,
                "the roster holds uuids, so the player should be linked to their team on arrival")
            t.succeed()
        } finally {
            cleanup()
        }
    }

    /**
     * Phase transitions run inside the instance world: its own `PhaseSystem`
     * ticks its own controller from countdown into playing.
     */
    @HytaleTest(timeoutSeconds = 180)
    fun phaseTransitionsRunInTheInstanceWorld(t: HytaleTestHelper) {
        try {
            val world = setupInstance(t, "race")

            val alice = player(t, "Bob")
            alice.runCommand("minigame start --asset=$assetName --seconds=1").join()

            t.assertEventually(60) {
                val controller = onInstanceWorld(world) { Instances.findController(world, gameTypeId!!) }
                t.assertNotNull(controller, "controller should still exist")
                val phase = onInstanceWorld(world) {
                    MinigameTestHarness.phaseOf(world.entityStore.store, controller!!)
                }
                t.assertEquals(Phase.Kind.PLAYING, phase?.kind,
                    "the instance world's own PhaseSystem should advance the phase, got $phase")
            }
            t.succeed()
        } finally {
            cleanup()
        }
    }

    /** Stop tears the instance world down; the builtin's RemovalSystem reaps it. */
    @HytaleTest(timeoutSeconds = 180)
    fun stopRemovesTheInstanceWorld(t: HytaleTestHelper) {
        try {
            val world = setupInstance(t, "quick")

            val alice = player(t, "Bob")
            alice.runCommand("minigame stop --asset=$assetName").join()

            t.assertEventually(60) {
                t.assertEquals(null, MinigameTestHarness.instanceWorldOf(assetName),
                    "no instance world for '$assetName' should remain")
            }
            // Already gone — stop cleanup from re-ending it.
            this.world = null
            t.succeed()
        } finally {
            cleanup()
        }
    }

    /**
     * Ending an instance returns a joined player to the lobby they came from and
     * takes their membership with them.
     */
    @HytaleTest(timeoutSeconds = 180)
    fun endInstanceReturnsJoinedPlayerToTheLobby(t: HytaleTestHelper) {
        try {
            val world = setupInstance(t, "return")
            val lobby = t.world

            val alice = player(t, "Bob")
            alice.runCommand("minigame join --asset=$assetName").join()
            // Arrival and the membership write are separate ticks: wait for the
            // link too, so "a joined player" means fully joined.
            t.assertEventually(60) {
                t.assertEquals(world.entityStore.store, alice.ref().reference?.store,
                    "player should be in the instance world before it ends")
                val linked = onInstanceWorld(world) {
                    MinigameTestHarness.inMinigameOf(world.entityStore.store, alice.ref().reference!!)
                }
                t.assertNotNull(linked, "player should be linked into the instance before it ends")
            }
            val controller = onInstanceWorld(world) { Instances.findController(world, gameTypeId!!)!! }
            onInstanceWorld(world) { Instances.endInstance(world, controller) }

            t.assertEventually(60) {
                t.assertEquals(lobby.entityStore.store, alice.ref().reference?.store,
                    "player should be back in their lobby world after the instance ends")
            }
            awaitWorldGone(t, world.name)
            this.world = null
            t.succeed()
        } finally {
            cleanup()
        }
    }
}

/** Snapshot of a player's membership, safe to carry off the world thread. */
private data class MembershipSnapshot(
    val controllerRef: Ref<EntityStore>?,
    val role: Role?,
    val teamName: String?,
)
