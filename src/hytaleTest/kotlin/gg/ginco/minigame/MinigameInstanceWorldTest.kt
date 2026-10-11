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
 *
 * ## Why the specs under test are batched into one lifecycle walkthrough
 *
 * Each instance world pays create + join + settle + stop + teardown (plus the
 * builtin's asynchronous removal), measured as ~30–60s per test on 2-core CI
 * runners — the whole earlier suite spent 5 worlds on behaviors that are
 * checkpoints of ONE lifecycle (issue #1's timing analysis). Tests here
 * therefore walk Waiting → membership → Playing → stop/return inside a single
 * shared world instead of spawning one world per facet. The tests stay
 * revertible one-per-facet (`@HytaleTest` worlds are fresh per test) — that is
 * the trade-off of batching, and this comment is where the decision lives.
 */
class MinigameInstanceWorldTest {

    private var asset: MinigameTestHarness.InstanceAsset? = null
    private var gameTypeId: String? = null
    private var world: World? = null
    private var next = 0

    /** The admin who created the current test's instance; stays in the lobby (see [setupInstance]). */
    private var creator: SimulatedPlayer? = null

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

        // The admin runs create FROM THE LOBBY and stays there — the instance
        // world is a separate place, which is exactly what the tests assert. The
        // handle is kept for commands the lobby is the right caller for (stop).
        val alice = player(t)
        creator = alice
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
        creator = null
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
     * ONE instance world walks the whole lifecycle, asserting every facet in
     * sequence: create spawns the world (its controller, teams and Waiting
     * phase) and leaves the lobby empty; join teleports the player in and
     * applies the full membership on arrival; the world's own `PhaseSystem`
     * drives Countdown into Playing; `stop` sends the joined player back to
     * the lobby — membership stripped — and the builtin's `RemovalSystem` reaps
     * the world.
     *
     * `stop` is run by the creating admin from the LOBBY, naming the asset:
     * that exercises the long path — ENDING phase, later reaped by
     * [gg.ginco.minigame.system.PhaseSystem] cleanup via the same
     * [Instances.endInstance] API a direct call would use — rather than a
     * caller inside the instance world, for whom `stop` is the short
     * direct-teardown path.
     *
     * Each step waits with a short explicit budget (the steps themselves settle
     * in ~0.1–5s; 30s is a slow-runner cap, not a delay), and phase/membership
     * reads batch everything they need into a single
     * [Instances.runOnWorld] hop per poll instead of two.
     */
    @HytaleTest(timeoutSeconds = 180)
    fun instanceWorldLifecycleRunsInItsOwnWorld(t: HytaleTestHelper) {
        try {
            val lobby = t.world
            val world = setupInstance(t, "walk", listOf("red", "blue"))
            val gameType = gameTypeId!!

            // --- create: the world is the instance ---------------------------
            t.assertTrue(world.name.startsWith("instance-"),
                "instance world should carry the builtin's name prefix, got '${world.name}'")
            t.assertTrue(world.name.contains(assetName),
                "instance world name should name its asset, got '${world.name}'")
            t.assertTrue(Instances.isInstanceWorld(world), "spawned world should be an instance world")

            // The lobby must NOT hold the controller: that is the point of
            // running the instance in its own world.
            t.runOnWorld {
                val lobbyControllers = MinigameTestHarness.controllers(MinigameTestHarness.storeOf(lobby))
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

            // --- join: one hop in, membership applied on arrival --------------
            val bob = player(t, "Bob")
            bob.runCommand("minigame join --asset=$assetName --team=red").join()

            // The hop is applied by the builtin's teleport tick, so wait for the
            // arrival rather than sleeping.
            t.assertEventually(30) {
                val ref = bob.ref().reference
                t.assertNotNull(ref, "player should still be in a world mid-teleport")
                t.assertEquals(world.entityStore.store, ref!!.store,
                    "player should have landed in the instance world '${world.name}'")
            }

            // Refs cannot cross stores: the membership is read — and the
            // controller identity compared — inside the instance world, in ONE
            // hop per poll. Arrival and the membership write are separate ticks
            // (InstanceEntrySystem applies InMinigame/Role/TeamRef on its tick
            // after the entity lands, as three sequential component writes), so
            // poll for the COMPLETE membership instead of snapshotting the
            // moment arrival is detected — a read between the writes sees a
            // half-applied state.
            var membership: MembershipSnapshot? = null
            t.assertEventually(30) {
                membership = onInstanceWorld(world) {
                    val store = world.entityStore.store
                    val ref = bob.ref().reference!!
                    MembershipSnapshot(
                        MinigameTestHarness.inMinigameOf(store, ref)?.controllerRef,
                        MinigameTestHarness.roleOf(store, ref),
                        store.getComponent(ref, gg.ginco.minigame.component.TeamRef.componentType)?.teamRef
                            ?.let { store.getComponent(it, gg.ginco.minigame.component.TeamData.componentType)?.name },
                        Instances.findController(world, gameType),
                    )
                }
                t.assertNotNull(membership.linkedController,
                    "the joined player should be linked into the instance on arrival")
                t.assertEquals(membership.worldController, membership.linkedController,
                    "the membership should link to the instance world's own controller")
                t.assertEquals(Role.participant(), membership.role,
                    "player should be a participant in the instance world")
                t.assertEquals("red", membership.teamName,
                    "the roster holds uuids, so the player should be linked to their team on arrival")
            }

            // --- start: the world's own PhaseSystem advances the phase --------
            // Start is run FROM THE LOBBY (by the creator). A caller inside the
            // instance world would deadlock: StartCommand's asset path hops to
            // each matching instance world's thread via Instances.runOnWorld,
            // and the world's single-thread executor cannot run that hop while
            // it is running the command itself — it self-enqueues and blocks
            // (measured locally: the walk body then never completed and, since
            // hytest's body executor is single-threaded, every later test's
            // body queued behind the blocked first body and timed out in
            // cascade).
            creator!!.runCommand("minigame start --asset=$assetName --seconds=1").join()

            t.assertEventually(30) {
                val phase = onInstanceWorld(world) {
                    val store = world.entityStore.store
                    MinigameTestHarness.phaseOf(store, Instances.findController(world, gameType)!!)
                }
                t.assertEquals(Phase.Kind.PLAYING, phase?.kind,
                    "the instance world's own PhaseSystem should advance the phase, got $phase")
            }

            // --- stop: players go home, membership does not follow, world reaps
            val worldName = world.name
            creator!!.runCommand("minigame stop --asset=$assetName").join()
            // Already being dismantled — stop cleanup from re-ending it. The
            // reap wait below names the world, not this stale reference.
            this.world = null

            t.assertEventually(30) {
                t.assertEquals(lobby.entityStore.store, bob.ref().reference?.store,
                    "the joined player should be back in their lobby world after the instance ends")
            }
            t.runOnWorld {
                val ref = bob.ref().reference
                t.assertTrue(ref != null && MinigameTestHarness.inMinigameOf(MinigameTestHarness.storeOf(lobby), ref) == null,
                    "membership must not follow the player back into the lobby")
            }
            awaitWorldGone(t, worldName)
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

            // A create every time SPAWNS: there is no create-or-join — whoever
            // runs it, wherever, a second create for the same game type opens a
            // second copy of the asset's world. The spawn doubles as the counter
            // test to an implementation caching one world per asset name.
            val secondAdmin = player(t, "Bob")
            secondAdmin.runCommand("minigame create ${gameTypeId!!}").join()

            var names: List<String> = emptyList()
            t.assertEventually(60) {
                names = Instances.instanceWorlds()
                    .filter { Instances.assetNameOf(it) == assetName }.map { it.name }
                t.assertEquals(2, names.size, "a second create must spawn its own world, got $names")
            }
            t.assertTrue(names.contains(first.name) && names.any { it != first.name },
                "both worlds must be live and distinct, got $names")

            // Release BOTH spawned instances through the public stop path before
            // the generic cleanup runs: an asset-named stop ends every instance
            // of this test's asset, and each world is empty, so the builtin
            // reaps both immediately. Release-together matters here — stopping
            // only the first (cleanup's direct endInstance) would leave the
            // second world loaded and spin the cleanup wait until its
            // best-effort budget (that was the whole 60s of the old
            // eachCreate!). The generic cleanup is told the world is already
            // gone by nulling it, so it removes only definition + assets.
            creator!!.runCommand("minigame stop --asset=$assetName").join()
            this.world = null
            t.assertEventually(30) {
                t.assertEquals(null, MinigameTestHarness.instanceWorldOf(assetName),
                    "no instance world of '$assetName' should remain after stopping both instances")
            }
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
     * Snapshot of a player's membership, safe to carry off the world thread.
     *
     * Declared INSIDE the test class on purpose: the fast-suite gate excludes
     * the class file from the merged Hytest.jar (and every other class file it
     * names), and a top-level private data class would compile to its own
     * `MembershipSnapshot.class` that a pattern on the test class does not
     * cover.
     */
    private data class MembershipSnapshot(
        /** The controller the player's InMinigame link names, or null. */
        val linkedController: Ref<EntityStore>?,
        val role: Role?,
        val teamName: String?,
        /** The controller the polled world holds for this game type, or null. */
        val worldController: Ref<EntityStore>?,
    )
}
