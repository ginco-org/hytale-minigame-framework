package gg.ginco.minigame.component

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

class TeamData(
    var name: String = "",
    var color: Int = 0xFFFFFF,
    var score: Int = 0,
    var maxPlayers: Int = -1
) : Component<EntityStore> {

    companion object {
        lateinit var componentType: ComponentType<EntityStore, TeamData>

        val CODEC: BuilderCodec<TeamData> = BuilderCodec.builder(TeamData::class.java, ::TeamData)
            .append(KeyedCodec("Name", Codec.STRING), { t, v -> t.name = v }, { t -> t.name }).add()
            .append(KeyedCodec("Color", Codec.INTEGER), { t, v -> t.color = v }, { t -> t.color }).add()
            .append(KeyedCodec("Score", Codec.INTEGER), { t, v -> t.score = v }, { t -> t.score }).add()
            .append(KeyedCodec("MaxPlayers", Codec.INTEGER), { t, v -> t.maxPlayers = v }, { t -> t.maxPlayers }).add()
            .build()
    }

    override fun clone(): TeamData = TeamData(name, color, score, maxPlayers)
}

class TeamRef(
    var teamRef: Ref<EntityStore>? = null
) : Component<EntityStore> {

    companion object {
        lateinit var componentType: ComponentType<EntityStore, TeamRef>

        val CODEC: BuilderCodec<TeamRef> = BuilderCodec.builder(TeamRef::class.java, ::TeamRef).build()
    }

    override fun clone(): TeamRef = TeamRef(teamRef)
}

class TeamMembers(
    val members: MutableList<Ref<EntityStore>> = mutableListOf()
) : Component<EntityStore> {

    companion object {
        lateinit var componentType: ComponentType<EntityStore, TeamMembers>
    }

    override fun clone(): TeamMembers = TeamMembers(members.toMutableList())
}
