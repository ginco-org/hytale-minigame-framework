package gg.ginco.minigame.hytaletest

import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.protocol.packets.player.ClientReady
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler
import com.hypixel.hytale.server.core.permissions.PermissionsModule
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.InstanceId
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.command.MinigameCommand

/**
 * Test-side helpers for the minigame framework suite.
 *
 * Unlike earlier revisions this harness does **not** mount the framework:
 * since hytest ships the plugin under test's manifest on the merged test jar
 * (with hytest attached as a sub-plugin), the server loads the real
 * [gg.ginco.minigame.Plugin] — components, [PhaseSystem] and the
 * `/minigame` command tree are registered by the plugin's own
 * `setup()`/`start()` at server boot. Nothing about the framework is stubbed
 * or re-registered here.
 *
 * What remains is test plumbing: granting simulated players the admin
 * permission group the subcommands gate on, and store accessors for
 * assertions. Store scans require the world thread — call them from inside
 * `t.runOnWorld { }` (the test body runs off-thread per the hytest contract).
 */
object MinigameTestHarness {

    // ---- permissions -------------------------------------------------------

    /** Adds the player to `hytale:Admin` (the group the admin subcommands gate on). */
    fun grantAdmin(player: PlayerRef) {
        adminProvider()?.addUserToGroup(player.uuid, HytalePermissionsProvider.GROUP_ADMIN)
    }

    fun hasAdmin(player: PlayerRef): Boolean =
        PermissionsModule.get().hasPermission(player.uuid, "ginco:minigame.create", true)

    private fun adminProvider(): HytalePermissionsProvider? =
        PermissionsModule.get().firstPermissionProvider as? HytalePermissionsProvider

    // ---- store access ------------------------------------------------------

    fun storeOf(world: World): Store<EntityStore> = world.entityStore.store

    fun phaseOf(store: Store<EntityStore>, ref: Ref<EntityStore>): Phase? =
        store.getComponent(ref, Phase.componentType)

    fun controllerOf(store: Store<EntityStore>, ref: Ref<EntityStore>): ControllerComponent? =
        store.getComponent(ref, ControllerComponent.componentType)

    fun inMinigameOf(store: Store<EntityStore>, ref: Ref<EntityStore>): InMinigame? =
        store.getComponent(ref, InMinigame.componentType)

    fun roleOf(store: Store<EntityStore>, ref: Ref<EntityStore>): Role? =
        store.getComponent(ref, Role.componentType)

    fun instanceIdOf(store: Store<EntityStore>, ref: Ref<EntityStore>): Int? =
        store.getComponent(ref, InstanceId.componentType)?.value

    /** Every controller entity in [store]. */
    fun controllers(store: Store<EntityStore>): List<Ref<EntityStore>> {
        val out = mutableListOf<Ref<EntityStore>>()
        val consumer =
            java.util.function.BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
                for (i in 0 until chunk.size()) {
                    if (chunk.getComponent(i, ControllerComponent.componentType) != null) {
                        out.add(chunk.getReferenceTo(i))
                    }
                }
            }
        store.forEachChunk(MinigameCommand.CONTROLLERS, consumer)
        return out
    }

    /** The controller entity created with [gameTypeId], or null. */
    fun controllerOfGameType(store: Store<EntityStore>, gameTypeId: String): Ref<EntityStore>? =
        controllers(store).firstOrNull { controllerOf(store, it)?.gameTypeId == gameTypeId }

    /** Entity ref for a simulated player (fails the test if the player is not in a world). */
    fun refOf(player: PlayerRef): Ref<EntityStore> = player.reference ?: error("player is not in a world")

    // ---- instance worlds ---------------------------------------------------

    /**
     * Publishes an instance asset that tests can spawn from, by registering a
     * temporary writable asset pack.
     *
     * `/instance edit new` cannot be used for this: the server's base asset pack
     * is the read-only `Assets.zip`, so the command is a silent no-op (it sends
     * no message, creates nothing and reports no error). `InstancesPlugin`
     * resolves instance assets by walking the registered packs' roots live, so a
     * pack registered at runtime is picked up immediately.
     *
     * Returns the created asset directory; call [removeAssetPack] when done.
     */
    fun createInstanceAsset(name: String): InstanceAsset {
        val root = java.nio.file.Files.createTempDirectory("minigame-test-assets")
        val assetDir = root.resolve("Server").resolve("Instances").resolve(name)
        java.nio.file.Files.createDirectories(assetDir)
        // A default WorldConfig is enough: spawnInstance fills in the uuid,
        // display name, instance config and return point itself. The world needs
        // no chunk storage of its own — the copy carries the asset's resources
        // folder across, and an empty one is a valid template.
        // Deliberately canSaveChunks=true (the default): unlike the per-test
        // lobby worlds (TestWorldFactory — empty storage providers, saves are
        // no-ops), instance worlds keep real storage, and their shutdown saves
        // chunk-STORE RESOURCES (instance config, framework regions) through
        // the saving pipeline. With canSaveChunks=false that pipeline is
        // disabled and Store.shutdown0's saveAllResources0().join() never
        // completes — the world thread stalls >30s and starves the player
        // return (CI run 37456263862: two instance tests failed on 2-core
        // runners). The removal-time chunk save stays as teardown cost here.
        //
        // Two knobs copied from hytest 0.1.1's TestWorldFactory, for the same
        // reason: these instance worlds are void test artifacts whose queued
        // loads are pure teardown tax. The default world-map provider runs
        // ChunkTracker scans at world init and on every join (every test join,
        // spawn or return queues loads); DisabledWorldMapProvider removes them
        // at the source — no simulated player renders a map. And the default
        // pregenerate region (±512 blocks = 81 queued chunk loads per world)
        // only matters for the "Waiting for loading chunks..." drain world
        // removal does against a ~5s budget; WorldPregenerateSystem skips
        // itself when the region is null. Neither knob affects anything a suite
        // test asserts (membership, phases, teams, regions), and keepLoadedRegion
        // is deliberately left at the default — an empty one breaks player
        // placement (bisected in hytest).
        val config = com.hypixel.hytale.server.core.universe.world.WorldConfig().apply {
            worldMapProvider = com.hypixel.hytale.server.core.universe.world.worldmap.provider.DisabledWorldMapProvider()
            getChunkConfig().setPregenerateRegion(null)
        }
        com.hypixel.hytale.server.core.universe.world.WorldConfig
            .save(assetDir.resolve("instance.bson"), config).join()
        val packId = "gg.ginco:MinigameTestAssets-" + name
        com.hypixel.hytale.server.core.asset.AssetModule.get().registerPack(
            packId, root, com.hypixel.hytale.common.plugin.PluginManifest(),
            com.hypixel.hytale.assetstore.AssetPack.PackSource.RUNTIME)
        return InstanceAsset(name, packId, root)
    }

    /** Unregisters the pack [createInstanceAsset] installed and deletes its files. */
    fun removeAssetPack(asset: InstanceAsset) {
        com.hypixel.hytale.server.core.asset.AssetModule.get().unregisterPack(asset.packId)
        asset.root.toFile().deleteRecursively()
    }

    data class InstanceAsset(val name: String, val packId: String, val root: java.nio.file.Path)

    /** Writes a definition for a game type that plays in [assetName]. */
    fun saveDefinitionWithAsset(gameTypeId: String, assetName: String, teamNames: List<String> = emptyList()) {
        gg.ginco.minigame.definition.DefinitionStore.save(
            gg.ginco.minigame.definition.MinigameDefinition(
                gameTypeId = gameTypeId,
                instanceAsset = assetName,
                teams = teamNames.map {
                    gg.ginco.minigame.definition.TeamDefinition(name = it)
                },
            )
        )
    }

    /** The instance world whose InstanceWorldConfig names [assetName], or null. */
    fun instanceWorldOf(assetName: String): World? =
        gg.ginco.minigame.util.Instances.instanceWorlds()
            .firstOrNull { gg.ginco.minigame.util.Instances.assetNameOf(it) == assetName }

    /**
     * Waits (bounded) until the instance world spawned from [assetName] is no
     * longer loaded — i.e. the server finished removing it.
     *
     * Tests end their instances in `finally`, but the builtin reaps the world
     * asynchronously and the server's removal pipeline then saves its chunks.
     * Serializing on the reap keeps a next test from starting while the last
     * one's world is still going down — overlapping used to starve its
     * instance work (phase transfers timed out mid-assert; CI run 37458914071).
     * Since hytest 0.1.1 (disabled map provider + no pregenerate for the
     * instance template — see [createInstanceAsset]) the queued-chunk-load
     * drain is empty, so the wait is normally sub-second and never its best-
     * effort budget; keep the budget as the 2-core slow-runner backstop.
     *
     * Returns false when the world was still loaded at the deadline: the
     * caller decides whether that matters (test teardown treats it as best
     * effort — every test scopes unique asset names, so a lingering world
     * cannot collide with the next test's).
     */
    fun awaitInstanceWorldGone(assetName: String, timeoutMillis: Long = 60_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (instanceWorldOf(assetName) == null) return true
            Thread.sleep(250)
        }
        return instanceWorldOf(assetName) == null
    }

    /** Asserts a player's entity sits in [world]'s store (throws when it does not). */
    fun assertInWorld(player: PlayerRef, world: World) {
        val ref = player.reference ?: error("player ${player.username} is not in a world")
        check(ref.store === world.entityStore.store) {
            "player ${player.username} is not in world '${world.name}'"
        }
    }

    /**
     * Keeps [player] answering the client-ready handshake for the rest of [t].
     *
     * A world switch is impossible for a simulated player without this. hytest
     * kicks `PacketHandler.clientReadyForChunksFuture` directly at login, which
     * completes it but leaves the field non-null; the server's own
     * `ClientReady` packet handler is what nulls it again, and only then can
     * `World.spawnPlayer` install a fresh future. So the *second* world a player
     * enters throws `IllegalStateException("Tried to hook client ready but
     * something is already waiting for it!")` inside `World.addPlayer`, aborting
     * the join — which is exactly what a teleport into an instance world is.
     *
     * Sending the packet a real client sends fixes it from the outside, with no
     * change to the server or to hytest.
     */
    fun keepClientReady(t: HytaleTestHelper, player: SimulatedPlayer) {
        val task = com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR
            .scheduleAtFixedRate({
                val handler = player.ref().packetHandler as? GamePacketHandler ?: return@scheduleAtFixedRate
                // readyForChunks clears the pending future (and the field the next
                // join needs free); readyForGameplay ends the ready-timeout wait.
                handler.handle(ClientReady(true, false))
                handler.handle(ClientReady(true, true))
            }, 0L, 50L, java.util.concurrent.TimeUnit.MILLISECONDS)
        t.doneFuture().whenComplete { _, _ -> task.cancel(false) }
    }
}