package gg.ginco.minigame.component

import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * Represents the current lifecycle phase of a minigame instance.
 *
 * Phase instances are replaced wholesale via CommandBuffer.setComponent
 * rather than mutated in-place, so [clone] returns `this`.
 */
sealed interface Phase : Component<EntityStore> {

    /** Lobby waiting for players. */
    data object Waiting : Phase {
        override fun clone(): Phase = this
    }

    /** Countdown before game start. */
    data class Countdown(val remainingSeconds: Float) : Phase {
        override fun clone(): Phase = this
    }

    /** Game is actively running. */
    data class Playing(val elapsedSeconds: Float = 0f) : Phase {
        override fun clone(): Phase = this
    }

    /** Game is wrapping up. [remainingSeconds] gives players time to see results. */
    data class Ending(val reason: EndReason, val remainingSeconds: Float = ENDING_DURATION_SECONDS) : Phase {
        override fun clone(): Phase = this
    }

    /** Final cleanup before instance shutdown. */
    data object Cleanup : Phase {
        override fun clone(): Phase = this
    }

    companion object {
        lateinit var componentType: ComponentType<EntityStore, Phase>
    }

    override fun clone(): Phase
}

enum class EndReason {
    TIMEOUT,
    WIN_CONDITION,
    ALL_LEFT,
    FORCED,
}

/** How long the [Phase.Ending] phase lasts before the instance is cleaned up. */
const val ENDING_DURATION_SECONDS = 3f

/**
 * Minimal BuilderCodec for [Phase].
 *
 * Phase is runtime-only — reconstructed on controller creation, never loaded
 * from assets. The default supplier produces a throwaway [Countdown]; the
 * controller sets the real initial value via CommandBuffer after entity creation.
 */
val PhaseCodec: BuilderCodec<Phase> =
    BuilderCodec.builder(Phase::class.java) { Phase.Countdown(0f) }
        .build()
