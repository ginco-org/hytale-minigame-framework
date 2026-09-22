package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * A player's role within the minigame they are in.
 *
 * One concrete class (not sealed subtypes): the server ECS validates stored
 * components by exact class equality with the class passed to
 * `registerComponent`, so subtype instances could never be stored — same
 * constraint as [Phase]. [kind] carries the state, [reason] the
 * disqualification payload. Construct via the companion factories
 * ([participant], [spectator], [disqualified]).
 */
data class Role(
    val kind: Kind,

    /** Why the player was disqualified; only meaningful for [Kind.DISQUALIFIED]. */
    val reason: String = "",
) : Component<EntityStore> {

    enum class Kind { PARTICIPANT, SPECTATOR, DISQUALIFIED }

    override fun clone(): Role = copy()

    companion object {
        lateinit var componentType: ComponentType<EntityStore, Role>

        /** Plays the minigame. */
        fun participant(): Role = Role(Kind.PARTICIPANT)

        /** Watches the minigame. */
        fun spectator(): Role = Role(Kind.SPECTATOR)

        /** Barred from further play; [reason] explains why. */
        fun disqualified(reason: String = ""): Role = Role(Kind.DISQUALIFIED, reason)
    }
}
