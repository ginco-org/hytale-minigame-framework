package gg.ginco.minigame.command

import com.hypixel.hytale.builtin.instances.InstancesPlugin
import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.math.vector.Rotation3f
import com.hypixel.hytale.math.vector.Transform
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext
import com.hypixel.hytale.server.core.command.system.arguments.system.Argument
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.EndReason
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.InstanceId
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.component.TeamRef
import gg.ginco.minigame.definition.DefinitionStore
import gg.ginco.minigame.definition.ResolvedMinigame
import gg.ginco.minigame.command.DefsCommand
import gg.ginco.minigame.command.StatsCommand
import gg.ginco.minigame.util.InstanceRegistry
import gg.ginco.minigame.util.Instances
import gg.ginco.minigame.util.Membership
import gg.ginco.minigame.util.Parties
import gg.ginco.minigame.util.PlayerSet
import gg.ginco.minigame.util.Teams
import org.joml.Vector3d
import java.util.function.BiConsumer

/**
 * `/minigame` — instance management.
 *
 * Every instance carries a numeric [InstanceId] (`#1`, `#2`, ...) so players
 * can target a specific one once several exist. Subcommands without
 * `--game=<id>` act on every eligible instance (`start`, `stop`) or fall back
 * to the lowest-id eligible one (`join`).
 *
 * Typical flow:
 * ```
 * /minigame queue <minigame> [map] (player) -> the queue: joins an open
 *      instance for that minigame (the first queuer spawns it — map per its
 *      map argument or random); balanced teams; auto-starts a countdown at
 *      the min players floor; queueing elsewhere switches queues
 * /minigame create mygame [map]  (admin)  -> the raw/testing surface:
 *      instance #1 in Waiting; [map] pins one MinigameMap asset's instance,
 *      omitting it picks a random playable map of the minigame (data-dir
 *      definitions keep their own instanceAsset behavior)
 * /minigame dequeue         (player) -> leave the queue (pre-start only;
 *      live exits go through /minigame leave)
 * /minigame join            (player) -> join the lowest-id joinable instance
 * /minigame join --game=2   (player) -> join instance #2 specifically
 * /minigame start --seconds=<secs>   (admin) -> all Waiting -> Countdown -> (PhaseSystem) -> Playing
 * /minigame start --game=1 --seconds=7 (admin) -> only instance #1
 * /minigame stop [--minigame=<g>]    (admin) -> end every queue world of one minigame
 * /minigame stop [--game=<id>]       (admin) -> Ending(FORCED) -> (PhaseSystem) cleanup
 * /minigame status          (anyone) -> list instances
 * /minigame leave           (player)
 * ```
 *
 * Optional args are NAMED in hytale's command system (`--seconds=7`): a bare
 * positional "7" fails to parse and the command never runs (the future
 * completes normally — a silent no-op).
 *
 * All commands run on the world thread via their base classes, so direct store
 * mutation is safe. Description keys are not backed by translation assets yet;
 * they display as-is until plugin assets are set up.
 */
class MinigameCommand : AbstractCommandCollection("minigame", "minigame.commands.parent.desc") {
    init {
        // The player-facing gate for this tree: the server's DEFAULT group
        // (hytale:Adventurer) is what regular players land in. Subcommands
        // without their own groups inherit one level — collections below set
        // it again for their children; admin-only leaves override explicitly.
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER)
        addSubCommand(QueueCommand())
        addSubCommand(DequeueCommand())
        addSubCommand(CreateCommand())
        addSubCommand(StartCommand())
        addSubCommand(StopCommand())
        addSubCommand(StatusCommand())
        addSubCommand(JoinCommand())
        addSubCommand(LeaveCommand())
        addSubCommand(TeamCommand())
        addSubCommand(PartyCommand())
        addSubCommand(DefsCommand())
        addSubCommand(StatsCommand())
    }

        /** Immutable snapshot of one instance's state (safe to keep after a scan). */
        data class InstanceSnapshot(
            val ref: Ref<EntityStore>,
            val id: Int,
            val phase: Phase,
        ) {
            val isJoinable: Boolean get() = phase.kind == Phase.Kind.WAITING || phase.kind == Phase.Kind.COUNTDOWN
            val isLive: Boolean
                get() = phase.kind == Phase.Kind.WAITING || phase.kind == Phase.Kind.COUNTDOWN ||
                    phase.kind == Phase.Kind.PLAYING
        }

    companion object {
        /**
         * The standard countdown when a minigame starts without an explicit
         * `--seconds`: admin starts and the auto-start floor both use it.
         */
        const val DEFAULT_COUNTDOWN_SECONDS = 5
        /**
         * Lazily built: [Archetype.of] captures the ComponentType objects at
         * construction time, so it must not run before [gg.ginco.minigame.Components.register]
         * has assigned the companion component types — an eagerly initialised
         * archetype would snapshot nulls and every store scan would silently
         * match nothing (validation then rejects any holder built from it).
         *
         * Every component a controller ever carries is part of the archetype:
         * instances are created through `newHolder(archetype, ...)`, and the
         * ECS matches archetypes by exact component set — a controller with
         * [InstanceId] would be invisible to a [ControllerComponent]+[Phase]
         * archetype.
         */
        val CONTROLLERS: Archetype<EntityStore> by lazy {
            Archetype.of(ControllerComponent.componentType, Phase.componentType, InstanceId.componentType)
        }

        /** Shared scanner shape; avoids Kotlin overload ambiguity between the BiConsumer/BiPredicate forEachChunk variants. */
        fun controllerScanner(body: (ArchetypeChunk<EntityStore>, Int) -> Unit):
                BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> =
            BiConsumer { chunk, _ -> for (i in 0 until chunk.size()) body(chunk, i) }

        /**
         * Every instance, ordered by id. Scan only — the store must not be
         * written while its processing lock is held; mutate after the scan.
         */
        fun snapshot(store: Store<EntityStore>): List<InstanceSnapshot> {
            val out = mutableListOf<InstanceSnapshot>()
            store.forEachChunk(CONTROLLERS, controllerScanner { chunk, i ->
                val id = chunk.getComponent(i, InstanceId.componentType) ?: return@controllerScanner
                val phase = chunk.getComponent(i, Phase.componentType) ?: return@controllerScanner
                out.add(InstanceSnapshot(chunk.getReferenceTo(i), id.value, phase))
            })
            return out.sortedBy { it.id }
        }

        /** The instance players mean when no `--game` id is given: the lowest-id joinable one, if any. */
        fun defaultJoinable(store: Store<EntityStore>): InstanceSnapshot? =
            snapshot(store).firstOrNull { it.isJoinable }

        /** Reads the shared `--game=<id>` optional arg; null when the caller did not restrict the target. */
        fun targetInstance(context: CommandContext, gameArg: Argument<*, Int>): Int? =
            if (context.provided(gameArg)) context.get(gameArg) else null
    }
}

/** Creates a new minigame instance entity in the waiting phase, with the next instance id. */
class CreateCommand : AbstractWorldCommand("create", "minigame.commands.create.desc") {

    private val gameTypeArg = withRequiredArg("gametype", "minigame.commands.create.gametype.desc", ArgTypes.STRING)
    private val mapArg = withOptionalArg("map", "minigame.commands.create.map.desc", ArgTypes.STRING)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val gameTypeId = gameTypeArg.get(context)
        val mapId = if (context.provided(mapArg)) context.get(mapArg) else null

        when (val resolved = DefinitionStore.resolve(gameTypeId, mapId)) {
            is ResolvedMinigame.Failure -> context.sendMessage(Message.raw(resolved.message))
            is ResolvedMinigame.Ready -> create(context, world, store, resolved)
        }
    }

    private fun create(
        context: CommandContext,
        world: World,
        store: Store<EntityStore>,
        resolved: ResolvedMinigame.Ready,
    ) {
        // An instance-asset game plays in its own temporary world; the
        // controller and teams are created over there instead. Everything that
        // touches the instance world runs there (its store is a different
        // thread), so this is the one place a create does not run on the
        // sender's world thread.
        if (resolved.instanceAsset.isNotEmpty()) {
            createInInstanceWorld(context, world, store, resolved)
            return
        }

        // Build the components FIRST, placed at their ComponentType's slot.
        //
        // Archetype slots are indexed by ComponentType.getIndex() (registry-
        // wide), not by construction order — and Holder/validateComponents
        // compares each array slot against the archetype's slot at the same
        // position. So the component array must be sized to the archetype's
        // slot array with each component stored at its type's index; a plain
        // positional array (e.g. [Controller, Phase]) fails validation with
        // "Invalid component at index 0 expected null but found ...".
        val id = InstanceId.allocate()
        val archetype = MinigameCommand.CONTROLLERS
        @Suppress("UNCHECKED_CAST")
        val components = arrayOfNulls<Component<EntityStore>>(archetype.length())
        components[ControllerComponent.componentType.index] = ControllerComponent(
            gameTypeId = resolved.gameTypeId,
            minPlayers = resolved.minPlayers,
            maxPlayers = resolved.maxPlayers,
        )
        components[Phase.componentType.index] = Phase.waiting()
        components[InstanceId.componentType.index] = id
        val holder = store.registry.newHolder(archetype, components as Array<Component<EntityStore>>)
        val ref = store.addEntity(holder, AddReason.SPAWN)
        if (ref == null) {
            context.sendMessage(Message.raw("Failed to create minigame instance."))
            return
        }

        // A resolved game turns the bare instance into a fully set-up one:
        // every team the game type describes, spawned in. (Regions are world
        // content — they already exist in the world.)
        if (!resolved.bare) {
            DefinitionStore.spawnInto(store, ref, resolved.toDefinition())
            if (resolved.behaviors.isNotEmpty()) {
                gg.ginco.minigame.behavior.GameBehaviors.activate(world, store, ref, resolved.gameTypeId, resolved.behaviors)
            }
            context.sendMessage(
                Message.raw("Created instance #${id.value} from definition '${resolved.gameTypeId}' " +
                    "(${resolved.teams.size} team(s))."))
        } else {
            context.sendMessage(Message.raw("Created instance #${id.value} (gameType=${resolved.gameTypeId}, phase=Waiting)."))
        }
    }

    /**
     * The instance-world path: spawn the map's temporary world, create the
     * controller inside it, and spawn its teams there.
     *
     * The return point is the sender's position+rotation, mirroring
     * `/instance spawn` — it is where players are put when they leave.
     */
    private fun createInInstanceWorld(
        context: CommandContext,
        world: World,
        store: Store<EntityStore>,
        resolved: ResolvedMinigame.Ready,
    ) {
        val asset = resolved.instanceAsset
        if (!InstancesPlugin.doesInstanceAssetExist(asset)) {
            context.sendMessage(
                Message.raw("'${resolved.gameTypeId}' names instance asset '$asset', which does not exist."))
            return
        }

        // The sender's own entity carries the position; read it here, on the
        // sender's world thread, before hopping to the instance world.
        val senderEntity = context.senderAsPlayerRef()
        val returnPoint = senderEntity?.let {
            store.getComponent(it, TransformComponent.getComponentType())
        }?.let { Transform(Vector3d(it.position), Rotation3f(it.rotation)) } ?: Transform()

        val instanceWorld = Instances.createInInstance(world, asset, returnPoint, resolved.gameTypeId, { targetStore, controllerRef, _, id ->
            DefinitionStore.spawnInto(targetStore, controllerRef, resolved.toDefinition())
        }, resolved.minPlayers, resolved.maxPlayers)

        if (instanceWorld == null) {
            context.sendMessage(Message.raw("Failed to spawn instance world '$asset' for '${resolved.gameTypeId}'."))
            return
        }

        val mapNote = resolved.mapId?.let { "map '$it'" } ?: "default instance"
        context.sendMessage(
            Message.raw("Created instance of '${resolved.gameTypeId}' ($mapNote) in world '${instanceWorld.name}' " +
                "(${resolved.teams.size} team(s))."))
    }
}

/** Moves eligible instances into the countdown phase; [gg.ginco.minigame.system.PhaseSystem] takes over from there. */
class StartCommand : AbstractWorldCommand("start", "minigame.commands.start.desc") {

    private val secondsArg = withDefaultArg(
        "seconds", "minigame.commands.start.seconds.desc",
        ArgTypes.INTEGER, MinigameCommand.DEFAULT_COUNTDOWN_SECONDS, "minigame.commands.start.seconds.default"
    )
    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)
    private val assetArg = withOptionalArg("asset", "minigame.commands.start.asset.desc", ArgTypes.STRING)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val countdownSeconds = secondsArg.get(context).toFloat()
        val targetId = MinigameCommand.targetInstance(context, gameArg)

        // Instances pinned to an instance asset live in their own world, where
        // this store cannot see them and its thread cannot touch them — so an
        // admin standing in the lobby names the asset instead, and each matching
        // instance is started on its own world thread.
        val asset = if (context.provided(assetArg)) context.get(assetArg) else null
        if (asset != null) {
            startInstancesOfAsset(context, asset, countdownSeconds)
            return
        }

        // Scan first, mutate after: store writes are illegal while forEachChunk
        // holds the processing lock ("Store is currently processing"), and any
        // throw here is swallowed by the async-command wrapper (logged only).
        val targets = MinigameCommand.snapshot(store).filter {
            it.phase.kind == Phase.Kind.WAITING && (targetId == null || it.id == targetId)
        }
        for (instance in targets) {
            store.replaceComponent(instance.ref, Phase.componentType, Phase.countdown(countdownSeconds))
        }

        val scope = if (targetId != null) "instance #$targetId" else "all waiting instances"
        context.sendMessage(
            when {
                targets.isNotEmpty() -> Message.raw("Started ${targets.size} instance(s) ($scope) — $countdownSeconds s countdown.")
                targetId != null -> Message.raw("Instance #$targetId does not exist or is not waiting.")
                else -> Message.raw("No instances in Waiting phase. Use /minigame create first.")
            }
        )
    }

    /** Starts every waiting instance of [asset] in its own instance world. */
    private fun startInstancesOfAsset(context: CommandContext, asset: String, countdownSeconds: Float) {
        var started = 0
        for (instanceWorld in Instances.instanceWorlds().filter { Instances.assetNameOf(it) == asset }) {
            started += Instances.runOnWorld(instanceWorld) {
                val store = instanceWorld.entityStore.store
                val waiting = MinigameCommand.snapshot(store).filter { it.phase.kind == Phase.Kind.WAITING }
                for (instance in waiting) {
                    store.replaceComponent(instance.ref, Phase.componentType, Phase.countdown(countdownSeconds))
                }
                waiting.size
            }
        }
        context.sendMessage(
            if (started > 0) Message.raw("Started $started instance(s) of '$asset' — $countdownSeconds s countdown.")
            else Message.raw("No waiting instances of '$asset' are running.")
        )
    }

    private companion object
}

/** Forces live instances into the ending phase with [gg.ginco.minigame.component.EndReason.FORCED]. */
class StopCommand : AbstractWorldCommand("stop", "minigame.commands.stop.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)
    private val assetArg = withOptionalArg("asset", "minigame.commands.stop.asset.desc", ArgTypes.STRING)
    private val minigameArg = withOptionalArg("minigame", "minigame.commands.stop.minigame.desc", ArgTypes.STRING)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        // Inside an instance world, stop means "end this instance": the world,
        // its controller and everyone in it go together. Doing the phase swap
        // first would leave the world running with nobody in it until
        // PhaseSystem's ending timer expired, and a caller in the lobby could
        // not reach the controller at all (its store is another thread).
        if (Instances.isInstanceWorld(world)) {
            teardownInstanceWorld(context, world, MinigameCommand.targetInstance(context, gameArg))
            return
        }

        // Queue-era scope: admins think in MINIGAMES, not per-boot instance ids
        // or asset names — `--minigame=stk` ends every queue world of that game
        // type (all maps), each on its own thread, players sent home by the
        // standard instance teardown.
        val gameTypeId = if (context.provided(minigameArg)) context.get(minigameArg) else null
        if (gameTypeId != null) {
            val queues = QueueFlow.scanForGame(gameTypeId)
            if (queues.isEmpty()) {
                context.sendMessage(Message.raw("No running instances of '$gameTypeId'."))
                return
            }
            for (queue in queues) {
                Instances.runOnWorld(queue.world) {
                    Instances.endInstance(queue.world, queue.controllerRef)
                }
            }
            context.sendMessage(Message.raw(
                "Stopped ${queues.size} instance(s) of '$gameTypeId' — everyone was sent home."))
            return
        }

        // From the lobby, instances that live in their own worlds are named by
        // asset — their ids are per-world and unreachable from here.
        val asset = if (context.provided(assetArg)) context.get(assetArg) else null
        if (asset != null) {
            val worlds = Instances.instanceWorlds().filter { Instances.assetNameOf(it) == asset }
            if (worlds.isEmpty()) {
                context.sendMessage(Message.raw("No running instance of '$asset'."))
                return
            }
            for (instanceWorld in worlds) {
                Instances.runOnWorld(instanceWorld) {
                    Instances.findController(instanceWorld)?.let { Instances.endInstance(instanceWorld, it) }
                }
            }
            context.sendMessage(Message.raw("Stopped ${worlds.size} instance(s) of '$asset'."))
            return
        }

        val targetId = MinigameCommand.targetInstance(context, gameArg)

        // Scan first, mutate after (see StartCommand — store writes are illegal
        // inside the forEachChunk processing window).
        val targets = MinigameCommand.snapshot(store).filter { it.isLive && (targetId == null || it.id == targetId) }
        for (instance in targets) {
            store.replaceComponent(instance.ref, Phase.componentType, Phase.ending(EndReason.FORCED))
            PlayerSet(store, instance.ref).sendMessage(Message.raw("The minigame has been stopped."))
        }

        val scope = if (targetId != null) " (instance #$targetId)" else ""
        context.sendMessage(
            when {
                targets.isNotEmpty() -> Message.raw("Stopped ${targets.size} instance(s)$scope.")
                targetId != null -> Message.raw("Instance #$targetId does not exist or is not running.")
                else -> Message.raw("No running instances.")
            }
        )
    }

    /**
     * Ends the instance world the command was run from: releases/sends home the
     * players, removes the controller and flags the world for removal. The world
     * itself is reaped asynchronously by the builtin's `RemovalSystem`.
     */
    private fun teardownInstanceWorld(context: CommandContext, world: World, targetId: Int?) {
        val controllerRef = Instances.findController(world)
        if (controllerRef == null) {
            context.sendMessage(
                if (targetId != null) Message.raw("Instance #$targetId does not exist or is not running.")
                else Message.raw("No running instances.")
            )
            return
        }

        // The world is being dismantled around the sender, so the reply has to go
        // out before it goes away.
        context.sendMessage(Message.raw("Stopping the minigame in world '${world.name}'…"))
        Instances.endInstance(world, controllerRef)
    }
}

/** Lists all instances with their id, phase and participant count. */
class StatusCommand : AbstractWorldCommand("status", "minigame.commands.status.desc") {

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val lines = MinigameCommand.snapshot(store).map { instance ->
            val players = PlayerSet(store, instance.ref).count()
            "#${instance.id} gameType=${gameTypeOf(store, instance.ref)} phase=${describe(instance.phase)} players=$players"
        }

        context.sendMessage(
            if (lines.isEmpty()) Message.raw("No minigame instances. Create one with /minigame create <gametype>.")
            else Message.raw(lines.joinToString("\n"))
        )
    }

    private fun describe(phase: Phase): String = when (phase.kind) {
        Phase.Kind.COUNTDOWN -> "Countdown(${phase.seconds.toInt()}s)"
        Phase.Kind.PLAYING -> "Playing(${phase.seconds.toInt()}s)"
        Phase.Kind.ENDING -> "Ending(${phase.reason}, ${phase.seconds.toInt()}s)"
        else -> phase.kind.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun gameTypeOf(store: Store<EntityStore>, ref: Ref<EntityStore>): String =
        store.getComponent(ref, ControllerComponent.componentType)?.gameTypeId ?: "?"
}

/** Joins the sender to a joinable instance: `--game=<id>` targets one, otherwise the lowest-id joinable. */
class JoinCommand : AbstractPlayerCommand("join", "minigame.commands.join.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)
    private val assetArg = withOptionalArg("asset", "minigame.commands.join.asset.desc", ArgTypes.STRING)
    private val teamArg = withOptionalArg("team", "minigame.commands.join.team.desc", ArgTypes.STRING)

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        // One minigame at a time: joining another automatically leaves the
        // current one first (queue switch) — roster slot released, membership
        // stripped, no teleport home since they are joining somewhere else.
        if (store.getComponent(ref, InMinigame.componentType) != null) {
            QueueFlow.leaveCurrentMinigame(store, ref, playerRef)
        }

        // A returning player with preserved membership comes first: joining
        // restores their exact instance, role and team.
        if (Membership.restoreIfPreserved(store, ref, playerRef)) {
            val controllerRef = store.getComponent(ref, InMinigame.componentType)?.controllerRef
            PlayerSet(store, controllerRef!!).sendMessage(
                Message.raw("${playerRef.username} rejoined the minigame."))
            return
        }

        // A party of more than one joins together: same instance, same team,
        // all or none — the leader drives it. A single driver keeps the
        // atomicity check honest: checks and writes below share one
        // world-thread command execution, so no interleaving join can take
        // the last team slot between the check and the write.
        val party = Parties.partyOf(playerRef.uuid)
        if (party != null && party.size > 1) {
            if (!party.isLeader(playerRef.uuid)) {
                context.sendMessage(Message.raw("Only the party leader can start a party join."))
                return
            }
            val partyAsset = if (context.provided(assetArg)) context.get(assetArg) else null
            if (partyAsset != null) {
                context.sendMessage(Message.raw("Party joins into instance-asset worlds are not supported yet."))
                return
            }
            val partyTeam = if (context.provided(teamArg)) context.get(teamArg) else null
            joinAsParty(context, store, playerRef, party, MinigameCommand.targetInstance(context, gameArg), partyTeam)
            return
        }

        val targetId = MinigameCommand.targetInstance(context, gameArg)
        val target = if (targetId != null) {
            MinigameCommand.snapshot(store).firstOrNull { it.id == targetId && it.isJoinable }
        } else {
            MinigameCommand.defaultJoinable(store)
        }

        // In-world instance, as before. A sender already linked to a *foreign*
        // controller (they reconnected into the lobby while still a member) is
        // sent back to their instance rather than double-joined.
        if (target != null) {
            val foreignController = Instances.crossWorldControllerOf(store, ref)
            if (foreignController == null) {
                joinInWorld(context, store, ref, playerRef, target)
            } else {
                // The link survived; the membership components did not (a
                // reconnected entity is rebuilt). Re-deriving them happens on
                // arrival, so only the trip is arranged here.
                InstanceRegistry.remember(playerRef.uuid, world.name)
                Instances.markPendingEntry(store, ref, foreignController.store.externalData.world.name,
                    Role.participant())
                Instances.teleportPlayerIn(playerRef, foreignController.store.externalData.world)
                context.sendMessage(Message.raw("Returning you to your minigame…"))
            }
            return
        }

        // No instance in *this* world. An instance the definition pins to an
        // asset runs in its own world, so a player standing in the lobby can
        // only reach it by naming the asset — the worlds are not registered
        // under the instance id, which is per-boot and per-world.
        val asset = if (context.provided(assetArg)) context.get(assetArg) else null
        if (asset != null) {
            joinByAsset(context, store, ref, playerRef, world, asset)
            return
        }

        val message = if (targetId != null) {
            "Instance #$targetId does not exist or is not joinable right now (Waiting or Countdown)."
        } else {
            "No joinable instance right now (Waiting or Countdown)."
        }
        context.sendMessage(Message.raw(message))
    }

    /** Legacy path: controller and player share a store, so tag directly. */
    private fun joinInWorld(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        target: MinigameCommand.InstanceSnapshot,
    ) {
        store.addComponent(ref, InMinigame.componentType, InMinigame(target.ref))
        store.addComponent(ref, Role.componentType, Role.participant())
        // A participant who joins now must not see the spectators who are
        // already watching — spectate hid them from the members of ITS time.
        gg.ginco.minigame.util.Roles.hideSpectatorsFrom(store, target.ref, ref)
        PlayerSet(store, target.ref).sendMessage(Message.raw("${playerRef.username} joined the minigame."))
    }

    /**
     * Joins an instance that lives in its own world, selected by its definition's
     * instance asset rather than by id: this is the lobby-side entry point.
     *
     * The membership cannot be applied here — the controller is in another store
     * — so the player is tagged as *arriving* and teleported; the instance
     * world's own `InstanceEntrySystem` finishes the join once they land.
     */
    private fun joinByAsset(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World,
        asset: String,
    ) {
        val chosen = QueueFlow.pickJoinable(asset)
        if (chosen == null) {
            val running = QueueFlow.scan(asset)
            if (running.isEmpty()) {
                context.sendMessage(Message.raw("No running instance of '$asset' to join."))
            } else {
                val full = running.first()
                context.sendMessage(Message.raw("Instance of '$asset' is full (${full.count}/${full.maxPlayers})."))
            }
            return
        }

        // The sender's own state is captured here, in the lobby store, because it
        // is unreadable once they leave: the uuid keys the roster, and the return
        // world is where they are sent back to.
        val uuid = playerRef.uuid
        val lobbyName = world.name

        // Reuse the player's existing slot/team when they already have one (a
        // reconnect or a second join), so a roster is never duplicated.
        val teamName = if (context.provided(teamArg)) context.get(teamArg) else null

        InstanceRegistry.remember(uuid, lobbyName)
        Instances.markPendingEntry(store, ref, chosen.world.name, Role.participant())

        // The roster must be written BEFORE the transfer: joining is
        // asynchronous, and the membership applied on arrival is derived from
        // whoever the roster lists. A named team is created in the instance if
        // it does not exist yet — the definition's teams are not enough, since a
        // second team for the same instance may not be authored at all.
        var autoTeam: String? = null
        if (teamName != null) {
            onInstanceWorldThread(chosen.world) {
                val controller = Instances.findController(chosen.world) ?: return@onInstanceWorldThread
                Instances.addTeamMember(chosen.world, controller, teamName, uuid)
            }
        } else {
            // Balance: an unnamed team lands the participant on the lightest
            // rostered team of the instance (ties: definition order).
            onInstanceWorldThread(chosen.world) {
                val controller = Instances.findController(chosen.world) ?: return@onInstanceWorldThread
                val tStore = chosen.world.entityStore.store
                val team = Teams.smallest(tStore, controller) ?: return@onInstanceWorldThread
                tStore.getComponent(team.ref, TeamMembers.componentType)?.members?.add(uuid)
                autoTeam = team.data.name
            }
            autoTeam?.let { context.sendMessage(Message.raw("You are on team '$it'.")) }
        }

        Instances.teleportPlayerIn(playerRef, chosen.world)
        context.sendMessage(Message.raw("Joining the $asset minigame…"))
    }

    /** Blocks until [body] has run on [world]'s thread (used for the pre-transfer roster write). */
    private fun onInstanceWorldThread(world: World, body: () -> Unit) {
        Instances.runOnWorld(world, body)
    }

    /**
     * The party join: every member lands in the same instance and on the same
     * team, or nobody joins at all.
     *
     * Atomicity is the point — a group join must never strand half a party
     * inside a full instance while the rest stay out. Every precondition is
     * checked BEFORE the first membership write, and because a command runs
     * as one world-thread execution nothing can interleave between the
     * checks and the writes.
     *
     * Team contract: a named `--team` must exist and fit the whole party — a
     * named team without room rejects the party rather than splitting it
     * across teams (splitting is a policy call the framework refuses to
     * make). Without `--team`, the lightest team that fits the entire party
     * wins (ties: definition order — balanced joins); an instance without
     * teams takes everyone unteamed.
     */
    private fun joinAsParty(
        context: CommandContext,
        store: Store<EntityStore>,
        leaderPlayerRef: PlayerRef,
        party: Parties.Party,
        targetId: Int?,
        teamName: String?,
    ) {
        val target = if (targetId != null) {
            MinigameCommand.snapshot(store).firstOrNull { it.id == targetId && it.isJoinable }
        } else {
            MinigameCommand.defaultJoinable(store)
        }
        if (target == null) {
            context.sendMessage(Message.raw(
                if (targetId != null) "Instance #$targetId does not exist or is not joinable right now."
                else "No joinable instance right now (Waiting or Countdown)."))
            return
        }

        // Members must stand in this world and be unattached — a missing or
        // already-playing member rejects everyone.
        val members = mutableListOf<Pair<Ref<EntityStore>, PlayerRef>>()
        for (uuid in party.memberUuids) {
            val memberRef = PlayerSet.findByUuid(store, uuid)
            val memberPlayerRef = memberRef?.let { store.getComponent(it, PlayerRef.getComponentType()) }
            if (memberRef == null || memberPlayerRef == null) {
                context.sendMessage(Message.raw(
                    "${party.names[uuid] ?: uuid} is not in this world — the whole party must join from the same world."))
                return
            }
            if (store.getComponent(memberRef, InMinigame.componentType) != null) {
                context.sendMessage(Message.raw("${memberPlayerRef.username} is already in a minigame."))
                return
            }
            members.add(memberRef to memberPlayerRef)
        }

        val teams = Teams.of(store, target.ref)
        val chosen = when {
            teams.isEmpty() -> null
            teamName != null -> {
                val team = teams.firstOrNull { it.data.name == teamName }
                if (team == null) {
                    context.sendMessage(Message.raw("No team named '$teamName' in instance #${target.id}."))
                    return
                }
                if (!hasRoomFor(store, team, members.size)) {
                    context.sendMessage(Message.raw(
                        "Team '$teamName' cannot fit a party of ${members.size} — nobody joined."))
                    return
                }
                team
            }
            else -> {
                // Balance: the party goes to the lightest team that fits the
                // WHOLE party at once (ties: definition order). Never split
                // across teams.
                val fitting = teams.filter { hasRoomFor(store, it, members.size) }
                fitting.minByOrNull {
                    store.getComponent(it.ref, TeamMembers.componentType)?.members?.size ?: Int.MAX_VALUE
                }.also { team ->
                    if (team == null) {
                        context.sendMessage(Message.raw(
                            "No team in instance #${target.id} has room for a party of ${members.size} — nobody joined."))
                        return
                    }
                }
            }
        }

        // All checks passed — apply to every member.
        for ((memberRef, _) in members) {
            store.addComponent(memberRef, InMinigame.componentType, InMinigame(target.ref))
            store.addComponent(memberRef, Role.componentType, Role.participant())
            gg.ginco.minigame.util.Roles.hideSpectatorsFrom(store, target.ref, memberRef)
        }
        if (chosen != null) {
            val roster = store.getComponent(chosen.ref, TeamMembers.componentType)?.members
            for ((memberRef, memberPlayerRef) in members) {
                store.addComponent(memberRef, TeamRef.componentType, TeamRef(chosen.ref))
                // Rosters hold uuids: the slot survives disconnects. hasRoomFor
                // already validated capacity against this same roster.
                roster?.add(memberPlayerRef.uuid)
            }
        }
        PlayerSet(store, target.ref).sendMessage(
            Message.raw("${leaderPlayerRef.username}'s party (${members.size} players) joined the minigame."))
    }

    /** True when [team]'s roster can take [n] more players (maxPlayers <= 0 means uncapped). */
    private fun hasRoomFor(store: Store<EntityStore>, team: Teams.TeamSnapshot, n: Int): Boolean {
        val max = team.data.maxPlayers
        // A team without a roster component counts as full — never a partial join.
        val roster = store.getComponent(team.ref, TeamMembers.componentType)?.members ?: return false
        if (max <= 0) return true
        return roster.size + n <= max
    }
}

/** Removes the sender from whatever instance they are in. */
class LeaveCommand : AbstractPlayerCommand("leave", "minigame.commands.leave.desc") {

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        // A member of an instance that runs in its own world: the roster and the
        // controller are in another store, so this cannot go through the local
        // Membership.leave path.
        val foreignController = Instances.crossWorldControllerOf(store, ref)
        if (foreignController != null) {
            leaveCrossWorld(context, store, ref, playerRef, world, foreignController)
            return
        }

        val controllerRef = Membership.leave(store, ref, playerRef)
        if (controllerRef == null) {
            context.sendMessage(Message.raw("You are not in a minigame."))
            return
        }

        if (controllerRef.isValid()) {
            PlayerSet(store, controllerRef).sendMessage(Message.raw("${playerRef.username} left the minigame."))
        }
    }

    /**
     * `/minigame leave` for an instance world: drops the player's roster slot in
     * the instance (on that world's thread), strips their local components, and
     * sends them home.
     *
     * A voluntary leave also cancels any preserved membership, exactly like the
     * in-world path.
     */
    private fun leaveCrossWorld(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World,
        foreignController: Ref<EntityStore>,
    ) {
        val instanceWorld = foreignController.store.externalData.world
        val role = store.getComponent(ref, Role.componentType)
        val username = playerRef.username

        Instances.runOnWorld(instanceWorld) {
            val targetStore = instanceWorld.entityStore.store
            // Only touch the roster if this entity is really in that store;
            // otherwise the membership is already stale and there is nothing to
            // release.
            Membership.releaseIn(instanceWorld, foreignController, playerRef.uuid, username)
        }

        Instances.stripMembership(store, ref)
        InstanceRegistry.forget(playerRef.uuid)
        playerRef.sendMessage(Message.raw("You left the minigame."))
        Instances.teleportPlayerOut(playerRef)
    }
}

/**
 * `/minigame team` — team management within one minigame instance.
 *
 * A team is an entity with [TeamData] + [TeamMembers] + `InMinigame` (linking
 * it to its instance). Players carry `TeamRef` -> team; membership is two-way
 * (the team's [TeamMembers] lists the player too), so game systems can walk
 * either direction and cleanup can drop everything when the instance ends.
 *
 * Team names are unique per instance (not globally): two instances may each
 * have a team named `red`.
 */
class TeamCommand : AbstractCommandCollection("team", "minigame.commands.team.desc") {
    init {
        // Players manage their own team state; `create` overrides admin-only.
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER)
        addSubCommand(TeamCreateCommand())
        addSubCommand(TeamJoinCommand())
        addSubCommand(TeamLeaveCommand())
        addSubCommand(TeamListCommand())
    }
}

/** Creates a team for an instance: `/minigame team create <name> [--game=<id>]`. */
class TeamCreateCommand : AbstractWorldCommand("create", "minigame.commands.team.create.desc") {

    private val nameArg = withRequiredArg("name", "minigame.commands.team.create.name.desc", ArgTypes.STRING)
    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val name = nameArg.get(context)
        val targetId = MinigameCommand.targetInstance(context, gameArg)

        val target = MinigameCommand.snapshot(store)
            .filter { it.isLive && (targetId == null || it.id == targetId) }
            .firstOrNull()
        if (target == null) {
            context.sendMessage(
                if (targetId != null) Message.raw("Instance #$targetId does not exist or is not running.")
                else Message.raw("No running instances. Create one with /minigame create first.")
            )
            return
        }
        if (Teams.findByName(store, target.ref, name) != null) {
            context.sendMessage(Message.raw("Instance #${target.id} already has a team named '$name'."))
            return
        }

        val ref = Teams.createTeam(store, target.ref, TeamData(name = name))
        if (ref == null) {
            context.sendMessage(Message.raw("Failed to create team."))
            return
        }

        context.sendMessage(Message.raw("Created team '$name' for instance #${target.id}."))
    }

}

/** Joins the sender to a team of their own instance: `/minigame team join <name>`. */
class TeamJoinCommand : AbstractPlayerCommand("join", "minigame.commands.team.join.desc") {

    private val nameArg = withRequiredArg("name", "minigame.commands.team.join.name.desc", ArgTypes.STRING)

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val controllerRef = store.getComponent(ref, InMinigame.componentType)?.controllerRef
        if (controllerRef == null) {
            context.sendMessage(Message.raw("You are not in a minigame."))
            return
        }

        val existingTeam = store.getComponent(ref, TeamRef.componentType)?.teamRef
        if (existingTeam != null && existingTeam.isValid()) {
            val name = store.getComponent(existingTeam, TeamData.componentType)?.name ?: "?"
            context.sendMessage(Message.raw("You are already on team '$name'. Use /minigame team leave first."))
            return
        }

        val name = nameArg.get(context)
        val team = Teams.findByName(store, controllerRef, name)
        if (team == null) {
            context.sendMessage(Message.raw("No team named '$name' in your minigame."))
            return
        }
        val maxPlayers = team.data.maxPlayers
        val roster = store.getComponent(team.ref, TeamMembers.componentType)?.members ?: mutableListOf()
        if (maxPlayers in 1..roster.size) {
            context.sendMessage(Message.raw("Team '$name' is full ($maxPlayers player(s))."))
            return
        }

        store.addComponent(ref, TeamRef.componentType, TeamRef(team.ref))
        roster.add(playerRef.uuid) // rosters hold uuids: slots survive disconnects

        // Announce to the team including the joiner (already in the roster).
        for (memberUuid in roster) {
            val member = PlayerSet.findByUuid(store, memberUuid) ?: continue
            val memberPlayer = store.getComponent(member, PlayerRef.getComponentType()) ?: continue
            memberPlayer.sendMessage(Message.raw("${playerRef.username} joined team '$name'."))
        }
    }
}

/** Removes the sender from their team: `/minigame team leave`. */
class TeamLeaveCommand : AbstractPlayerCommand("leave", "minigame.commands.team.leave.desc") {

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val teamRef = store.getComponent(ref, TeamRef.componentType)?.teamRef
        if (teamRef == null) {
            context.sendMessage(Message.raw("You are not on a team."))
            return
        }

        store.removeComponentIfExists(ref, TeamRef.componentType)

        if (teamRef.isValid()) {
            val data = store.getComponent(teamRef, TeamData.componentType)
            val name = data?.name ?: "?"
            val members = store.getComponent(teamRef, TeamMembers.componentType)?.members
            members?.remove(playerRef.uuid)

            // Confirm to the leaver even when they were the last member;
            // announce to whoever remains.
            playerRef.sendMessage(Message.raw("You left team '$name'."))
            for (memberUuid in members ?: emptyList()) {
                val member = PlayerSet.findByUuid(store, memberUuid) ?: continue
                val memberPlayer = store.getComponent(member, PlayerRef.getComponentType()) ?: continue
                memberPlayer.sendMessage(Message.raw("${playerRef.username} left team '$name'."))
            }
        }
    }
}

/** Lists an instance's teams: `/minigame team list [--game=<id>]`. */
class TeamListCommand : AbstractWorldCommand("list", "minigame.commands.team.list.desc") {

    private val gameArg = withOptionalArg("game", "minigame.commands.game.desc", ArgTypes.INTEGER)

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val targetId = MinigameCommand.targetInstance(context, gameArg)
        val target = MinigameCommand.snapshot(store)
            .filter { it.isLive && (targetId == null || it.id == targetId) }
            .firstOrNull()
        if (target == null) {
            context.sendMessage(
                if (targetId != null) Message.raw("Instance #$targetId does not exist or is not running.")
                else Message.raw("No running instances.")
            )
            return
        }

        val teams = Teams.of(store, target.ref)
        context.sendMessage(
            if (teams.isEmpty()) Message.raw("Instance #${target.id} has no teams yet.")
            else Message.raw(
                teams.joinToString(
                    prefix = "Instance #${target.id} teams: ",
                    transform = { "'${it.data.name}' (${it.data.score} pts, ${memberCount(store, it.ref)} player(s))" }
                )
            )
        )
    }

    private fun memberCount(store: Store<EntityStore>, teamRef: Ref<EntityStore>): Int =
        store.getComponent(teamRef, TeamMembers.componentType)?.members?.size ?: 0
}

/**
 * The queue scan/pick/switch plumbing shared by `/minigame queue` and the
 * explicit `--asset` joins. Instance queues live in their own worlds, whose
 * stores cannot be touched from a foreign thread — every read here hops to
 * the instance world's thread (same rule as every command above).
 */
internal object QueueFlow {

    /** One instance world's joinability, gathered on its own world thread. */
    data class Candidate(
        val world: com.hypixel.hytale.server.core.universe.world.World,
        val controllerRef: com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        val id: Int,
        val count: Int,
        val maxPlayers: Int,
        /** Waiting/Countdown — captured on the world's own thread. */
        val joinable: Boolean,
    ) {
        /** Room for at least one more player (`MaxPlayers <= 0` is uncapped). */
        val hasRoom: Boolean get() = maxPlayers <= 0 || count < maxPlayers
    }

    /**
     * Every instance world playing [assetName] (its asset name — the
     * `Instance` of the map asset), with joinability and occupancy on their
     * own threads.
     */
    fun scan(assetName: String): List<Candidate> =
        Instances.instanceWorlds()
            .filter { Instances.assetNameOf(it) == assetName }
            .mapNotNull { world ->
                candidateOf(world, null, assetName)
            }

    /**
     * The queue to put the next player in: the first JOINABLE candidate
     * (Waiting/Countdown) with room. Joins never split queues — full or
     * playing instances are skipped, not entered mid-flight.
     */
    fun pickJoinable(assetName: String): Candidate? =
        scan(assetName).firstOrNull { it.joinable && it.hasRoom }

    /**
     * Game-type-keyed scans: every instance world whose CONTROLLER plays
     * [gameTypeId], optionally narrowed to one map's instance asset (server
     * asset name the world was spawned from); null asset = any map of that
     * minigame.
     */
    fun scanForGame(gameTypeId: String, instanceAsset: String? = null): List<Candidate> =
        Instances.instanceWorlds().mapNotNull { world ->
            candidateOf(world, gameTypeId, instanceAsset)
        }

    fun pickJoinableForGame(gameTypeId: String, instanceAsset: String? = null): Candidate? =
        scanForGame(gameTypeId, instanceAsset).firstOrNull { it.joinable && it.hasRoom }

    private fun candidateOf(
        world: com.hypixel.hytale.server.core.universe.world.World,
        gameTypeId: String?,
        instanceAsset: String?,
    ): Candidate? =
        Instances.runOnWorld(world) {
            val store = world.entityStore.store
            val controller = Instances.findController(world) ?: return@runOnWorld null
            if (gameTypeId != null &&
                store.getComponent(controller, ControllerComponent.componentType)?.gameTypeId != gameTypeId) {
                return@runOnWorld null
            }
            if (instanceAsset != null && Instances.assetNameOf(world) != instanceAsset) {
                return@runOnWorld null
            }
            // Everything below runs ON the world thread: snapshot(phase), the
            // roster-headcount and the controller components are store-bound.
            val snapshot = MinigameCommand.snapshot(store).firstOrNull { it.ref == controller }
                ?: return@runOnWorld null
            Candidate(
                world, controller, snapshot.id,
                PlayerSet(store, controller).all().size,
                store.getComponent(controller, ControllerComponent.componentType)?.maxPlayers ?: 0,
                snapshot.isJoinable,
            )
        }

    /**
     * Queue switch: releas[es] the minigame [ref] is currently in so the join
     * can start fresh — roster slot dropped (cross-world rosters on their own
     * world thread), local membership stripped, preserved membership
     * cancelled, and the old instance's members hear the leave. The player is
     * NOT sent home; the join that triggered the switch places them next.
     */
    fun leaveCurrentMinigame(
        store: com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        ref: com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        playerRef: PlayerRef,
    ) {
        val foreignController = Instances.crossWorldControllerOf(store, ref)
        if (foreignController != null) {
            val instanceWorld = foreignController.store.externalData.world
            Instances.runOnWorld(instanceWorld) {
                Membership.releaseIn(instanceWorld, foreignController, playerRef.uuid, playerRef.username)
            }
            Instances.stripMembership(store, ref)
            InstanceRegistry.forget(playerRef.uuid)
            return
        }

        val controllerRef = Membership.leave(store, ref, playerRef)
        if (controllerRef != null && controllerRef.isValid()) {
            PlayerSet(store, controllerRef).sendMessage(Message.raw("${playerRef.username} left the minigame."))
        }
    }

    /**
     * Dequeue: leaves the minigame [ref] is currently queued in. Only the
     * pre-start phases (Waiting/Countdown) accept a dequeue — once the game
     * is live the exit is [LeaveCommand]'s `/minigame leave`. Sends neither
     * home teleport nor extra announcements beyond the standard leave ones.
     *
     * @return null on success; the sender-facing refusal message otherwise.
     */
    fun dequeue(
        store: com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        ref: com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        playerRef: PlayerRef,
    ): String? {
        val foreignController = Instances.crossWorldControllerOf(store, ref)
        val live: Boolean
        if (foreignController != null) {
            val instanceWorld = foreignController.store.externalData.world
            var phaseLive = false
            Instances.runOnWorld(instanceWorld) {
                val snapshot = MinigameCommand.snapshot(instanceWorld.entityStore.store)
                phaseLive = snapshot.any {
                    it.ref == foreignController &&
                        (it.phase.kind == Phase.Kind.PLAYING || it.phase.kind == Phase.Kind.ENDING)
                }
            }
            if (phaseLive) {
                live = true
            } else {
                Instances.runOnWorld(instanceWorld) {
                    Membership.releaseIn(instanceWorld, foreignController, playerRef.uuid, playerRef.username)
                }
                Instances.stripMembership(store, ref)
                InstanceRegistry.forget(playerRef.uuid)
                live = false
            }
        } else {
            val controllerRef = store.getComponent(ref, InMinigame.componentType)?.controllerRef
                ?: return "You are not queued."
            val phase = MinigameCommand.snapshot(store)
                .firstOrNull { it.ref == controllerRef }?.phase
            if (phase != null && (phase.kind == Phase.Kind.PLAYING || phase.kind == Phase.Kind.ENDING)) {
                return "Your minigame already started — use /minigame leave instead."
            }
            live = false
            Membership.leave(store, ref, playerRef)
            if (controllerRef.isValid()) {
                PlayerSet(store, controllerRef).sendMessage(
                    Message.raw("${playerRef.username} left the minigame."))
            }
            // A queuer already teleported INTO the instance world runs this on
            // that store: send them back through the instance's return point.
            if (Instances.isInstanceWorld(store.externalData.world)) {
                Instances.teleportPlayerOut(playerRef)
            }
        }
        return if (live) "Your minigame already started — use /minigame leave instead." else null
    }

    /**
     * The join write, shared by the queue paths: pre-transfer registration
     * (uuid -> lobby, pending entry), team balance on the instance's world
     * thread, then the transfer. Announces the assigned team when one was
     * chosen by balancing.
     */
    fun queueInto(
        context: com.hypixel.hytale.server.core.command.system.CommandContext,
        store: com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        ref: com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        playerRef: PlayerRef,
        lobbyWorld: com.hypixel.hytale.server.core.universe.world.World,
        target: Candidate,
    ) {
        val uuid = playerRef.uuid
        InstanceRegistry.remember(uuid, lobbyWorld.name)
        Instances.markPendingEntry(store, ref, target.world.name, Role.participant())

        var autoTeam: String? = null
        Instances.runOnWorld(target.world) {
            val tStore = target.world.entityStore.store
            val team = Teams.smallest(tStore, target.controllerRef) ?: return@runOnWorld
            tStore.getComponent(team.ref, TeamMembers.componentType)?.members?.add(uuid)
            autoTeam = team.data.name
        }
        autoTeam?.let { context.sendMessage(Message.raw("You are on team '$it'.")) }

        Instances.teleportPlayerIn(playerRef, target.world)
        val fill = if (target.maxPlayers > 0) " of ${target.maxPlayers}" else ""
        context.sendMessage(Message.raw(
            "Queued into instance #${target.id} (${target.count + 1}$fill players) — the minigame starts when the min players are reached."))
    }
}

/**
 * `/minigame queue <minigame> [<map>]` — the player-facing join.
 *
 * Queues into an existing open queue for that minigame (and map, when one is
 * named) — a joinable instance (Waiting/Countdown) with room under the minigame's
 * `MaxPlayers` — or spawns a fresh queue when none exists; the first queuer pays
 * the world spawn. In the queue every participant lands on the lightest team
 * (balanced joins, `Teams.smallest`), and once the min (players) floor is met the
 * queue starts itself via a standard countdown (`PhaseSystem` auto-start; admins
 * can force-start earlier with `/minigame start`).
 *
 * One minigame at a time: queueing elsewhere automatically leaves the current
 * minigame first. Unknown minigames are refused here — unlike `/minigame
 * create`, which is the admin/testing surface that also accepts unconfigured
 * game type ids.
 */
class QueueCommand : AbstractPlayerCommand("queue", "minigame.commands.queue.desc") {

    private val gameTypeArg = withRequiredArg("minigame", "minigame.commands.create.gametype.desc", ArgTypes.STRING)
    private val mapArg = withOptionalArg("map", "minigame.commands.create.map.desc", ArgTypes.STRING)

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        val gameTypeId = gameTypeArg.get(context)
        val mapId = if (context.provided(mapArg)) context.get(mapArg) else null

        // One minigame at a time: queueing elsewhere switches (leaves the old
        // one first).
        if (store.getComponent(ref, InMinigame.componentType) != null) {
            QueueFlow.leaveCurrentMinigame(store, ref, playerRef)
        }

        val resolved = DefinitionStore.resolve(gameTypeId, mapId)
        when (resolved) {
            is ResolvedMinigame.Failure -> {
                context.sendMessage(Message.raw(resolved.message))
                return
            }
            is ResolvedMinigame.Ready -> {
                if (resolved.bare) {
                    context.sendMessage(Message.raw(
                        "Unknown minigame '$gameTypeId' — see /minigame defs list."))
                    return
                }
            }
        }
        resolved as ResolvedMinigame.Ready

        if (resolved.instanceAsset.isEmpty()) {
            context.sendMessage(Message.raw(
                "Minigame '$gameTypeId' plays in this world (no map asset) — use /minigame create + join."))
            return
        }

        // An existing queue of THIS minigame (and, when named, THIS map):
        // joinable Waiting/Countdown instance with room.
        val existing = QueueFlow.pickJoinableForGame(resolved.gameTypeId, resolved.instanceAsset)
        if (existing != null) {
            QueueFlow.queueInto(context, store, ref, playerRef, world, existing)
            return
        }

        // All queues full or none running: an uncapped queue never fills up —
        // room may also be exhausted by MaxPlayers.
        val running = QueueFlow.scanForGame(resolved.gameTypeId, resolved.instanceAsset)
        if (running.isNotEmpty()) {
            val full = running.first()
            context.sendMessage(Message.raw(
                "Queue for '$gameTypeId' is full (${full.count}/${full.maxPlayers}) — try again shortly."))
            return
        }

        // First queuer: spawn the map's instance world and queue into it
        // (their position becomes the instance's return point, mirroring
        // `/instance spawn`).
        val senderEntity = context.senderAsPlayerRef()
        val returnPoint = senderEntity?.let {
            store.getComponent(it, TransformComponent.getComponentType())
        }?.let { Transform(Vector3d(it.position), Rotation3f(it.rotation)) } ?: Transform()

        val instanceWorld = Instances.createInInstance(world, resolved.instanceAsset, returnPoint, resolved.gameTypeId, { targetStore, controllerRef, _, _ ->
            DefinitionStore.spawnInto(targetStore, controllerRef, resolved.toDefinition())
        }, resolved.minPlayers, resolved.maxPlayers)
        if (instanceWorld == null) {
            context.sendMessage(Message.raw("Failed to start a queue for '$gameTypeId' (${resolved.instanceAsset})."))
            return
        }

        val candidate = QueueFlow.scanForGame(resolved.gameTypeId, resolved.instanceAsset)
            .firstOrNull { it.world == instanceWorld }
        if (candidate == null) {
            // The scan must see the freshly created controller; if not, fall
            // back to teleporting in without the numbered message.
            Instances.teleportPlayerIn(playerRef, instanceWorld)
            context.sendMessage(Message.raw("Queued into '$gameTypeId'."))
            return
        }
        QueueFlow.queueInto(context, store, ref, playerRef, world, candidate)
    }
}


/**
 * `/minigame dequeue` — the counterpart of `/minigame queue`: leaves the
 * queue (any instance still Waiting or Countdown), drops the roster slot and
 * returns the player home when the queue runs in its own instance world.
 * Once the minigame started, live exits go through `/minigame leave`, which
 * has game-side semantics (announcements, stats, preserved membership) that
 * a pre-start exit does not need to trigger.
 */
class DequeueCommand : AbstractPlayerCommand("dequeue", "minigame.commands.dequeue.desc") {

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        QueueFlow.dequeue(store, ref, playerRef)?.let { message ->
            context.sendMessage(Message.raw(message))
            return
        }
        playerRef.sendMessage(Message.raw("You left the queue."))
    }
}
