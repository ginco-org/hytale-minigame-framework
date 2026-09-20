package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

sealed interface Role : Component<EntityStore> {

    data object Participant : Role {
        override fun clone(): Participant = Participant
    }

    data object Spectator : Role {
        override fun clone(): Spectator = Spectator
    }

    data class Disqualified(val reason: String = "") : Role {
        override fun clone(): Disqualified = Disqualified(reason)
    }

    companion object {
        lateinit var componentType: ComponentType<EntityStore, Role>
    }
}
