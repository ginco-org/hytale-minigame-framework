package gg.ginco.minigame.component

import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import org.bson.BsonDocument

class ControllerComponent(
    var gameTypeId: String = "",
    var config: BsonDocument? = null,
    val teamRefs: MutableList<Ref<EntityStore>> = mutableListOf()
) : Component<EntityStore> {

    override fun clone(): ControllerComponent = ControllerComponent(
        gameTypeId,
        config?.clone(),
        teamRefs.toMutableList()
    )

    companion object {
        lateinit var componentType: ComponentType<EntityStore, ControllerComponent>
    }
}

/**
 * Minimal BuilderCodec for [ControllerComponent].
 *
 * Runtime-only — the controller entity is created and populated at runtime
 * when a minigame instance starts. The codec provides a default supplier
 * so ECS can construct the component; the real values are set afterward.
 */
val ControllerComponentCodec: BuilderCodec<ControllerComponent> =
    BuilderCodec.builder(ControllerComponent::class.java, ::ControllerComponent).build()
