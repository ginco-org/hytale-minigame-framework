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
    val teamRefs: MutableList<Ref<EntityStore>> = mutableListOf(),
    /** Auto-start (and guard) floor: Waiting → Countdown once this many players
     *  are in; `<= 0` disables auto-start (admin start only). */
    var minPlayers: Int = 0,
    /** Join cap: `<= 0` means uncapped; joins into a full instance are refused. */
    var maxPlayers: Int = 0,
) : Component<EntityStore> {

    override fun clone(): ControllerComponent = ControllerComponent(
        gameTypeId,
        config?.clone(),
        teamRefs.toMutableList(),
        minPlayers,
        maxPlayers,
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
