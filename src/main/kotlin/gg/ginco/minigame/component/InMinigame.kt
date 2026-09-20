package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

class InMinigame(var controllerRef: Ref<EntityStore>? = null) : Component<EntityStore> {

    override fun clone(): InMinigame = InMinigame(controllerRef)

    companion object {
        lateinit var componentType: ComponentType<EntityStore, InMinigame>
    }
}
