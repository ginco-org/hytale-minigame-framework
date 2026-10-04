package gg.ginco.minigame.util

import com.hypixel.hytale.builtin.instances.InstancesPlugin
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig
import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.RemoveReason
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.math.vector.Rotation3f
import com.hypixel.hytale.math.vector.Transform
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.command.MinigameCommand
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.InstanceId
import gg.ginco.minigame.component.PendingInstanceEntry
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.component.TeamRef
import org.joml.Vector3d
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Bridge between the framework's instances and the server's builtin
 * `Hytale:Instances` module (the `Hytale:Instances` builtin plugin).
 *
 * ## Semantics (verified on 0.6.8, not inferred)
 *
 * - **Spawn is COPY, not reference.** `InstancesPlugin.spawnInstance` walks the
 *   instance asset folder (`<packRoot>/Server/Instances/<name>`), copies every
 *   file into a fresh runtime world folder — `instance.bson` and `resources/`
 *   included — and loads it as a new `World`
 *   (`InstancesPlugin.copyAndLoadInstance`). Region (trigger-volume) markers
 *   placed in the template world are therefore cloned into each spawn, while one
 *   placed in the lobby is not.
 * - **The world key is per-spawn.** Without an authored `InstanceKey` (the
 *   default) the key is `instance-<name>-<random uuid>`, so every call yields a
 *   *distinct* world. `spawnInstance` de-duplicates only for assets that pin a
 *   fixed `InstanceKey`; two `/minigame create`s of the same game type must
 *   therefore expect two worlds.
 * - **A world switch REBUILDS the entity in the target store, keeping its
 *   components.** `PlayerRef.removeFromStore()` removes the entity with
 *   `RemoveReason.UNLOAD` and hands the resulting `Holder` to the target world,
 *   which re-adds it (`World.addPlayer`) — components, framework ones included,
 *   survive the hop; only refs and store handles do not. Measured: `InMinigame`
 *   + `Role` applied before the jump were readable in the instance world's
 *   store afterwards.
 * - **Cross-store refs are fatal to read.** `store.getComponent(foreignRef, …)`
 *   throws `IllegalArgumentException("Incorrect store for entity reference")`,
 *   and every store read asserts the caller is on that store's thread. Nothing
 *   here ever hands a ref to a store it does not belong to, or reads a store
 *   from a foreign thread.
 *
 * ## Rough edges in the builtin (documented, not worked around)
 *
 * - `InstancesPlugin.onPlayerAddToWorld` calls
 *   `playerComponent.startClientReadyTimeout()` *before* `World.spawnPlayer`
 *   installs the client-ready future (`World.java` :1243 vs the
 *   `setClientReadyForChunksFuture` at :1246). A real client never notices, but
 *   the call throws on a simulated player and aborts the rest of the join
 *   future.
 * - `InstancesPlugin` only ever *sets* the client-ready future; it never
 *   completes it on arrival. A headless client must answer `ClientReady` itself
 *   before the join finishes.
 * - `AssetModule.getBaseAssetPack()` is the read-only `Assets.zip`
 *   (`PackSource.CLI`, `isImmutable=true`), so `/instance edit new` is a silent
 *   no-op on a stock test server: it sends no message, creates no asset, and
 *   reports no error.
 */
object Instances {
    private const val SPAWN_TIMEOUT_SECONDS = 60L

    // ---- world classification -----------------------------------------------

    /** True when [world] is a spawned instance world rather than a normal world. */
    fun isInstanceWorld(world: World): Boolean =
        InstanceWorldConfig.get(world.worldConfig) != null

    /** Every loaded instance world, ordered by name (deterministic for assertions). */
    fun instanceWorlds(): List<World> =
        Universe.get().worlds.values.filter { isInstanceWorld(it) }.sortedBy { it.name }

    /** The instance asset name [world] was spawned from, or null when not an instance world. */
    fun assetNameOf(world: World): String? =
        InstanceWorldConfig.get(world.worldConfig)?.instanceName

    /**
     * True when [controllerRef] belongs to a different store than [store] — i.e.
     * the instance runs in its own world. Reading `Ref.store` is safe from any
     * thread; only *component access* is thread- and store-bound.
     */
    fun isCrossWorld(store: Store<EntityStore>, controllerRef: Ref<EntityStore>): Boolean =
        controllerRef.store !== store

    /**
     * The cross-world controller an entity is a member of, or null when there is
     * none or it lives in the entity's own store.
     *
     * Deriving "cross-world" from the [InMinigame] link instead of a separate
     * marker means there is exactly one place that can go stale: the link the
     * membership itself uses.
     */
    fun crossWorldControllerOf(store: Store<EntityStore>, ref: Ref<EntityStore>): Ref<EntityStore>? =
        store.getComponent(ref, InMinigame.componentType)?.controllerRef?.takeIf { isCrossWorld(store, it) }

    // ---- spawning ------------------------------------------------------------

    /**
     * Spawns the instance world for [assetName] and runs [body] on it with a
     * controller for [gameTypeId] created **in that world's own store**.
     *
     * [returnPoint] is the creating admin's position+rotation, mirroring
     * `/instance spawn`: the builtin stores it as the instance's
     * `WorldReturnPoint`, which is where players are put when they leave.
     *
     * Returns the new world, or null when the asset could not be spawned
     * (unknown asset, copy failure). [body] runs on the target world's thread —
     * the ECS asserts it, and the create/team-spawn pass does plain store
     * writes.
     */
    fun createInInstance(
        sourceWorld: World,
        assetName: String,
        returnPoint: Transform,
        gameTypeId: String,
        body: (store: Store<EntityStore>, controllerRef: Ref<EntityStore>, instanceWorld: World, id: Int) -> Unit,
    ): World? {
        val instanceWorld = try {
            InstancesPlugin.get().spawnInstance(assetName, sourceWorld, returnPoint)
                .orTimeout(SPAWN_TIMEOUT_SECONDS, TimeUnit.SECONDS).join()
        } catch (e: Exception) {
            System.err.println("[MinigameFramework] failed to spawn instance asset '$assetName': $e")
            return null
        }

        return try {
            runOnWorld(instanceWorld) {
                val id = InstanceId.allocate()
                val ref = createController(instanceWorld.entityStore.store, gameTypeId, id)
                    ?: return@runOnWorld null
                body(instanceWorld.entityStore.store, ref, instanceWorld, id.value)
                instanceWorld
            }
        } catch (e: Exception) {
            System.err.println("[MinigameFramework] failed to populate instance world '${instanceWorld.name}': $e")
            null
        }
    }

    /**
     * Creates the controller entity inside [store] — the instance world's own
     * store. Holder arrays are slot-indexed by `ComponentType.getIndex()`, not
     * by construction order (see `CreateCommand`).
     */
    private fun createController(
        store: Store<EntityStore>,
        gameTypeId: String,
        id: InstanceId,
    ): Ref<EntityStore>? {
        val archetype = MinigameCommand.CONTROLLERS
        @Suppress("UNCHECKED_CAST")
        val components = arrayOfNulls<Component<EntityStore>>(archetype.length())
        components[ControllerComponent.componentType.index] = ControllerComponent(gameTypeId = gameTypeId)
        components[Phase.componentType.index] = Phase.waiting()
        components[InstanceId.componentType.index] = id
        val holder = store.registry.newHolder(archetype, components as Array<Component<EntityStore>>)
        return store.addEntity(holder, AddReason.SPAWN)
    }

    // ---- lookup (must run on [world]'s thread) --------------------------------

    /**
     * The framework controller living in [world]'s store, or null. Each instance
     * world is a private copy, so at most one exists there; [gameTypeId] only
     * disambiguates a world reused across game types.
     */
    fun findController(world: World, gameTypeId: String? = null): Ref<EntityStore>? {
        val store = world.entityStore.store
        var found: Ref<EntityStore>? = null
        val consumer = java.util.function.BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val component = chunk.getComponent(i, ControllerComponent.componentType) ?: continue
                if (gameTypeId == null || component.gameTypeId == gameTypeId) found = chunk.getReferenceTo(i)
            }
        }
        store.forEachChunk(Archetype.of(ControllerComponent.componentType), consumer)
        return found
    }

    // ---- moving players ------------------------------------------------------

    /**
     * Sends [playerRef]'s entity into [targetWorld], overriding the instance
     * return point with the player's current position+rotation so they come back
     * to where they stood (the world's own `WorldReturnPoint` is the spawn-time
     * admin position, wrong for everyone else).
     *
     * **Must run on the player's current world thread.** The transfer lands on a
     * later tick, so the player is still in their old world when this returns;
     * arrival is handled by `InstanceEntrySystem` inside the instance world.
     */
    fun teleportPlayerIn(playerRef: PlayerRef, targetWorld: World) {
        val entity = playerRef.reference ?: return
        val store = entity.store
        val here = store.getComponent(entity, TransformComponent.getComponentType())
        val overrideReturn = here?.let {
            Transform(Vector3d(it.position), Rotation3f(it.rotation.yaw(), 0f, 0f))
        }
        InstancesPlugin.teleportPlayerToInstance(entity, store, targetWorld, overrideReturn)
    }

    /**
     * Returns [playerRef] to the world/point recorded when they were teleported
     * into their instance.
     *
     * **Must run on the player's current (instance) world thread.**
     */
    fun teleportPlayerOut(playerRef: PlayerRef) {
        val entity = playerRef.reference ?: return
        InstancesPlugin.exitInstance(entity, entity.store)
    }

    // ---- membership at the instance boundary ---------------------------------

    /**
     * Applies the membership a player needs to be *in* [controllerRef]: a link to
     * the controller, their role, and — because rosters hold uuids and survive
     * everything else — a `TeamRef` for whichever team lists them.
     *
     * **Must run on [store]'s thread**; [playerEntity] and [controllerRef] must
     * both belong to [store].
     */
    fun applyMembership(
        store: Store<EntityStore>,
        playerEntity: Ref<EntityStore>,
        controllerRef: Ref<EntityStore>,
        role: Role,
    ) {
        store.addComponent(playerEntity, InMinigame.componentType, InMinigame(controllerRef))
        store.addComponent(playerEntity, Role.componentType, role)

        val uuid = store.getComponent(playerEntity, PlayerRef.getComponentType())?.uuid ?: return
        for (team in Teams.of(store, controllerRef)) {
            val roster = store.getComponent(team.ref, TeamMembers.componentType) ?: continue
            if (uuid in roster.members) {
                store.addComponent(playerEntity, TeamRef.componentType, TeamRef(team.ref))
                break
            }
        }
    }

    /**
     * Adds [uuid] to a team's roster inside its instance world, creating
     * [teamName] if the instance has no such team yet.
     *
     * Team membership has to be recorded on the *instance* world's thread and in
     * its store — the roster and the team entities live there. Must run on that
     * world's thread.
     */
    fun addTeamMember(instanceWorld: World, controllerRef: Ref<EntityStore>, teamName: String, uuid: java.util.UUID) {
        val store = instanceWorld.entityStore.store
        if (!controllerRef.isValid()) return
        // findByName yields a snapshot; createTeam yields the ref — unify on the ref.
        val teamRef = Teams.findByName(store, controllerRef, teamName)?.ref
            ?: Teams.createTeam(store, controllerRef, TeamData(name = teamName))
            ?: return
        val roster = store.getComponent(teamRef, TeamMembers.componentType)?.members ?: return
        if (uuid !in roster) roster.add(uuid)
    }

    /**
     * Tags [playerEntity] as *arriving* into [worldName], returning the role to
     * apply once they are there. Used by `JoinCommand`, which cannot apply
     * membership itself: the entity is still in the lobby store at that point.
     */
    fun markPendingEntry(store: Store<EntityStore>, playerEntity: Ref<EntityStore>, worldName: String, role: Role) {
        store.addComponent(playerEntity, PendingInstanceEntry.componentType, PendingInstanceEntry(worldName, role.kind.name, role.reason))
    }

    /** Strips every membership component from [playerEntity] (does not touch rosters). */
    fun stripMembership(store: Store<EntityStore>, playerEntity: Ref<EntityStore>) {
        store.removeComponentIfExists(playerEntity, InMinigame.componentType)
        store.removeComponentIfExists(playerEntity, Role.componentType)
        store.removeComponentIfExists(playerEntity, TeamRef.componentType)
    }

    // ---- teardown ------------------------------------------------------------

    /**
     * Ends an instance that lives in its own world: releases and sends every
     * player home, removes the controller and its non-player children, then flags
     * the world for removal.
     *
     * **Must run on the instance world's thread** ([runOnWorld]) — the controller
     * and its players live in that store.
     *
     * Removal is deliberately asynchronous: `safeRemoveInstance` only rewrites the
     * world's removal conditions to remove-when-empty and marks the instance as
     * having had a player; the builtin's `RemovalSystem` reaps it on a chunk-store
     * tick (measured: gone within the next second).
     */
    fun endInstance(instanceWorld: World, controllerRef: Ref<EntityStore>) {
        val store = instanceWorld.entityStore.store

        // Every player in the world is exited, not just those the InMinigame link
        // already names. An instance world belongs to exactly one instance, and
        // the link is applied a tick AFTER arrival — so a player who is in the
        // store but not yet linked would otherwise be missed, leaving the world
        // non-empty and therefore never reaped.
        val players = mutableListOf<Ref<EntityStore>>()
        val scanPlayers = java.util.function.BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                if (chunk.getComponent(i, PlayerRef.getComponentType()) != null) players.add(chunk.getReferenceTo(i))
            }
        }
        store.forEachChunk(Archetype.of(PlayerRef.getComponentType()), scanPlayers)

        // Players first: exitInstance reads the return point off the player's own
        // entity, and membership must be stripped while the refs are still valid.
        for (player in players) {
            stripMembership(store, player)
            InstancesPlugin.exitInstance(player, store)
        }

        val children = mutableListOf<Ref<EntityStore>>()
        val scanChildren = java.util.function.BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val inMinigame = chunk.getComponent(i, InMinigame.componentType) ?: continue
                if (inMinigame.controllerRef != controllerRef) continue
                if (chunk.getComponent(i, PlayerRef.getComponentType()) != null) continue
                children.add(chunk.getReferenceTo(i))
            }
        }
        store.forEachChunk(Archetype.of(InMinigame.componentType), scanChildren)
        for (child in children) store.removeEntity(child, RemoveReason.REMOVE)
        store.removeEntity(controllerRef, RemoveReason.REMOVE)

        InstancesPlugin.safeRemoveInstance(instanceWorld)
    }

    // ---- threading -----------------------------------------------------------

    /** Runs [body] on [world]'s thread and returns its result, unwrapping exceptions. */
    fun <T> runOnWorld(world: World, body: () -> T): T =
        try {
            CompletableFuture.supplyAsync(body, world).join()
        } catch (e: java.util.concurrent.CompletionException) {
            throw (e.cause ?: e)
        }

    /** Runs [body] now when already on [world]'s thread, else hops to it (fire and forget). */
    fun onWorldThread(world: World, body: () -> Unit) {
        if (world.isInThread()) body() else world.execute(body)
    }
}
