package gg.ginco.minigame.component

import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * Represents the current lifecycle phase of a minigame instance.
 *
 * ## Why one concrete class instead of sealed subtypes
 *
 * The server ECS validates every stored component instance by **exact class
 * equality** with the class passed to `registerComponent`
 * (`Archetype.validateComponents`). A sealed interface with data-object /
 * data-class subtypes can therefore never pass validation ("expected interface
 * ...Phase but found class ...Phase$Waiting") — no subtype instance is ever
 * storable. So the phase state lives in this single concrete class: [kind]
 * carries the lifecycle state, [seconds] and [reason] carry the per-kind
 * payload. Construct via the companion factories ([waiting], [countdown],
 * [playing], [ending], [cleanup]).
 *
 * A side benefit: every transition swaps the *same* component type, so the
 * archetype stays stable and in-place replacement during chunk iteration is
 * safe (see [gg.ginco.minigame.system.PhaseSystem]).
 *
 * Phase instances are replaced wholesale via `store.replaceComponent` rather
 * than mutated in-place, but [clone] still deep-copies (ECS may snapshot
 * components), and data equality makes assertions readable.
 */
data class Phase(
    val kind: Kind,

    /** Phase clock: remaining seconds for [Kind.COUNTDOWN]/[Kind.ENDING], elapsed seconds for [Kind.PLAYING]. */
    val seconds: Float = 0f,

    /** Why the instance is wrapping up; only meaningful for [Kind.ENDING]. */
    val reason: EndReason = EndReason.FORCED,
) : Component<EntityStore> {

    enum class Kind { WAITING, COUNTDOWN, PLAYING, ENDING, CLEANUP }

    override fun clone(): Phase = copy()

    companion object {
        lateinit var componentType: ComponentType<EntityStore, Phase>

        /** Lobby waiting for players. */
        fun waiting(): Phase = Phase(Kind.WAITING)

        /** Countdown before game start; [seconds] counts down to zero. */
        fun countdown(seconds: Float): Phase = Phase(Kind.COUNTDOWN, seconds)

        /** Game is actively running; [seconds] counts elapsed play time. */
        fun playing(elapsedSeconds: Float = 0f): Phase = Phase(Kind.PLAYING, elapsedSeconds)

        /** Game is wrapping up; [seconds] gives players time to see results. */
        fun ending(reason: EndReason, seconds: Float = ENDING_DURATION_SECONDS): Phase =
            Phase(Kind.ENDING, seconds, reason)

        /** Final cleanup before instance shutdown. */
        fun cleanup(): Phase = Phase(Kind.CLEANUP)
    }
}

enum class EndReason {
    TIMEOUT,
    WIN_CONDITION,
    ALL_LEFT,
    FORCED,
}

/** How long the [Phase.Kind.ENDING] phase lasts before the instance is cleaned up. */
const val ENDING_DURATION_SECONDS = 3f

/**
 * Minimal BuilderCodec for [Phase].
 *
 * Phase is runtime-only — reconstructed on controller creation, never loaded
 * from assets. The default supplier produces a throwaway [Phase.Kind.COUNTDOWN]
 * phase; the controller sets the real initial value via CommandBuffer after
 * entity creation.
 */
val PhaseCodec: BuilderCodec<Phase> =
    BuilderCodec.builder(Phase::class.java) { Phase.countdown(0f) }
        .build()
