package gg.ginco.minigame.command

import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.EndReason
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.util.PlayerSet
import java.util.function.BiConsumer

/**
 * `/minigame` — minimal instance management for the first vertical slice.
 *
 * Typical flow:
 * ```
 * /minigame create mygame   (admin)  -> instance in Waiting
 * /minigame join            (player) -> tag player with InMinigame + Role.participant()
 * /minigame start --seconds=<secs>  (admin) -> Waiting -> Countdown -> (PhaseSystem) -> Playing
 *                                    (optional args are NAMED in hytale's command
 *                                    system: `--seconds=7` — a bare positional "7"
 *                                    fails to parse and the command never runs)
 * /minigame stop            (admin)  -> Ending(FORCED) -> (PhaseSystem) cleanup
 * /minigame status          (anyone) -> list instances
 * /minigame leave           (player)
 * ```
 *
 * All commands run on the world thread via their base classes, so direct store
 * mutation is safe. Description keys are not backed by translation assets yet;
 * they display as-is until plugin assets are set up.
 */
class MinigameCommand : AbstractCommandCollection("minigame", "minigame.commands.parent.desc") {
    init {
        addSubCommand(CreateCommand())
        addSubCommand(StartCommand())
        addSubCommand(StopCommand())
        addSubCommand(StatusCommand())
        addSubCommand(JoinCommand())
        addSubCommand(LeaveCommand())
    }

    companion object {
        /**
         * Lazily built: [Archetype.of] captures the ComponentType objects at
         * construction time, so it must not run before [gg.ginco.minigame.Components.register]
         * has assigned the companion component types — an eagerly initialised
         * archetype would snapshot nulls and every store scan would silently
         * match nothing (validation then rejects any holder built from it).
         */
        val CONTROLLERS_WITH_PHASE: Archetype<EntityStore> by lazy {
            Archetype.of(ControllerComponent.componentType, Phase.componentType)
        }

        /** Shared scanner shape; avoids Kotlin overload ambiguity between the BiConsumer/BiPredicate forEachChunk variants. */
        fun controllerScanner(body: (ArchetypeChunk<EntityStore>, Int) -> Unit):
                BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> =
            BiConsumer { chunk, _ -> for (i in 0 until chunk.size()) body(chunk, i) }
    }
}

/** Creates a new minigame instance entity in the waiting phase. */
class CreateCommand : AbstractWorldCommand("create", "minigame.commands.create.desc") {

    private val gameTypeArg = withRequiredArg("gametype", "minigame.commands.create.gametype.desc", ArgTypes.STRING)

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val gameTypeId = gameTypeArg.get(context)

        // Build the components FIRST, placed at their ComponentType's slot.
        //
        // Archetype slots are indexed by ComponentType.getIndex() (registry-
        // wide), not by construction order — and Holder/validateComponents
        // compares each array slot against the archetype's slot at the same
        // position. So the component array must be sized to the archetype's
        // slot array with each component stored at its type's index; a plain
        // positional array (e.g. [Controller, Phase]) fails validation with
        // "Invalid component at index 0 expected null but found ...".
        val archetype = MinigameCommand.CONTROLLERS_WITH_PHASE
        @Suppress("UNCHECKED_CAST")
        val components = arrayOfNulls<Component<EntityStore>>(archetype.length())
        components[ControllerComponent.componentType.index] = ControllerComponent(gameTypeId = gameTypeId)
        components[Phase.componentType.index] = Phase.waiting()
        val holder = store.registry.newHolder(archetype, components as Array<Component<EntityStore>>)
        val ref = store.addEntity(holder, AddReason.SPAWN)
        if (ref == null) {
            context.sendMessage(Message.raw("Failed to create minigame instance."))
            return
        }

        context.sendMessage(Message.raw("Created minigame instance (gameType=$gameTypeId, phase=Waiting)."))
    }
}

/** Moves every waiting instance into the countdown phase; [gg.ginco.minigame.system.PhaseSystem] takes over from there. */
class StartCommand : AbstractWorldCommand("start", "minigame.commands.start.desc") {

    private val secondsArg = withDefaultArg(
        "seconds", "minigame.commands.start.seconds.desc",
        ArgTypes.INTEGER, DEFAULT_COUNTDOWN_SECONDS, "minigame.commands.start.seconds.default"
    )

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val countdownSeconds = secondsArg.get(context).toFloat()

        // Scan first, mutate after: store writes are illegal while forEachChunk
        // holds the processing lock ("Store is currently processing"), and any
        // throw here is swallowed by the async-command wrapper (logged only).
        val waiting = mutableListOf<Ref<EntityStore>>()
        store.forEachChunk(MinigameCommand.CONTROLLERS_WITH_PHASE, MinigameCommand.controllerScanner { chunk, i ->
            if (chunk.getComponent(i, Phase.componentType)?.kind == Phase.Kind.WAITING) {
                waiting.add(chunk.getReferenceTo(i))
            }
        })
        for (ref in waiting) {
            store.replaceComponent(ref, Phase.componentType, Phase.countdown(countdownSeconds))
        }

        context.sendMessage(
            if (waiting.isNotEmpty()) Message.raw("Started ${waiting.size} instance(s) — $countdownSeconds s countdown.")
            else Message.raw("No instances in Waiting phase. Use /minigame create first.")
        )
    }

    private companion object {
        const val DEFAULT_COUNTDOWN_SECONDS = 5
    }
}

/**
 * Forces every live instance into [gg.ginco.minigame.component.EndReason.FORCED] ending.
 *
 * Uses the default ending grace period (not `0f`): even a forced stop should let
 * players see the wrap-up before cleanup — and an instant `0f` ending would let
 * [gg.ginco.minigame.system.PhaseSystem] tear the instance down within one tick,
 * leaving no observable Ending state for players (or tests) to catch.
 */
class StopCommand : AbstractWorldCommand("stop", "minigame.commands.stop.desc") {

    init {
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN)
    }

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        // Scan first, mutate after (see StartCommand — store writes are illegal
        // inside the forEachChunk processing window).
        val live = mutableListOf<Ref<EntityStore>>()
        store.forEachChunk(MinigameCommand.CONTROLLERS_WITH_PHASE, MinigameCommand.controllerScanner { chunk, i ->
            when (chunk.getComponent(i, Phase.componentType)?.kind) {
                Phase.Kind.WAITING, Phase.Kind.COUNTDOWN, Phase.Kind.PLAYING ->
                    live.add(chunk.getReferenceTo(i))

                else -> {}
            }
        })
        for (ref in live) {
            store.replaceComponent(ref, Phase.componentType, Phase.ending(EndReason.FORCED))
            PlayerSet(store, ref).sendMessage(Message.raw("The minigame has been stopped."))
        }

        context.sendMessage(
            if (live.isNotEmpty()) Message.raw("Stopped ${live.size} instance(s).")
            else Message.raw("No running instances.")
        )
    }
}

/** Lists all instances with their phase and participant count. */
class StatusCommand : AbstractWorldCommand("status", "minigame.commands.status.desc") {

    override fun execute(context: CommandContext, world: World, store: Store<EntityStore>) {
        val lines = mutableListOf<String>()

        store.forEachChunk(MinigameCommand.CONTROLLERS_WITH_PHASE, MinigameCommand.controllerScanner { chunk, i ->
            val controller = chunk.getComponent(i, ControllerComponent.componentType) ?: return@controllerScanner
            val phase = chunk.getComponent(i, Phase.componentType) ?: return@controllerScanner
            val players = PlayerSet(store, chunk.getReferenceTo(i)).count()
            lines.add("gameType=${controller.gameTypeId} phase=${describe(phase)} players=$players")
        })

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
}

/** Joins the sender to the first instance that is Waiting or in Countdown. */
class JoinCommand : AbstractPlayerCommand("join", "minigame.commands.join.desc") {

    override fun execute(
        context: CommandContext,
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        world: World
    ) {
        if (store.getComponent(ref, InMinigame.componentType) != null) {
            context.sendMessage(Message.raw("You are already in a minigame. Use /minigame leave first."))
            return
        }

        val target = findJoinableInstance(store)
        if (target == null) {
            context.sendMessage(Message.raw("No joinable instance right now (Waiting or Countdown)."))
            return
        }

        store.addComponent(ref, InMinigame.componentType, InMinigame(target))
        store.addComponent(ref, Role.componentType, Role.participant())

        PlayerSet(store, target).sendMessage(Message.raw("${playerRef.username} joined the minigame."))
    }

    private fun findJoinableInstance(store: Store<EntityStore>): Ref<EntityStore>? {
        var target: Ref<EntityStore>? = null
        store.forEachChunk(MinigameCommand.CONTROLLERS_WITH_PHASE, MinigameCommand.controllerScanner { chunk, i ->
            when (chunk.getComponent(i, Phase.componentType)?.kind) {
                Phase.Kind.WAITING, Phase.Kind.COUNTDOWN ->
                    if (target == null) target = chunk.getReferenceTo(i)

                else -> {}
            }
        })
        return target
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
        val inMinigame = store.getComponent(ref, InMinigame.componentType)
        if (inMinigame == null) {
            context.sendMessage(Message.raw("You are not in a minigame."))
            return
        }

        val controllerRef = inMinigame.controllerRef
        store.removeComponentIfExists(ref, InMinigame.componentType)
        store.removeComponentIfExists(ref, Role.componentType)

        if (controllerRef != null && controllerRef.isValid()) {
            PlayerSet(store, controllerRef).sendMessage(Message.raw("${playerRef.username} left the minigame."))
        }
    }
}
