package gg.ginco.minigame.component

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import org.joml.Vector3d

// ---------------------------------------------------------------------------
// Bounds — axis-aligned bounding box
// ---------------------------------------------------------------------------

class Bounds(
    var min: Vector3d = Vector3d(),
    var max: Vector3d = Vector3d()
) : Component<EntityStore> {

    fun contains(pos: Vector3d): Boolean =
        pos.x >= min.x && pos.x <= max.x &&
        pos.y >= min.y && pos.y <= max.y &&
        pos.z >= min.z && pos.z <= max.z

    fun center(): Vector3d = Vector3d(
        (min.x + max.x) * 0.5,
        (min.y + max.y) * 0.5,
        (min.z + max.z) * 0.5
    )

    override fun clone(): Bounds = Bounds(Vector3d(min), Vector3d(max))

    companion object {
        lateinit var componentType: ComponentType<EntityStore, Bounds>

        val CODEC: BuilderCodec<Bounds> = BuilderCodec.builder(Bounds::class.java, ::Bounds)
            .append(KeyedCodec("MinX", Codec.DOUBLE), { t, v -> t.min = Vector3d(v, t.min.y, t.min.z) }, { t -> t.min.x }).add()
            .append(KeyedCodec("MinY", Codec.DOUBLE), { t, v -> t.min = Vector3d(t.min.x, v, t.min.z) }, { t -> t.min.y }).add()
            .append(KeyedCodec("MinZ", Codec.DOUBLE), { t, v -> t.min = Vector3d(t.min.x, t.min.y, v) }, { t -> t.min.z }).add()
            .append(KeyedCodec("MaxX", Codec.DOUBLE), { t, v -> t.max = Vector3d(v, t.max.y, t.max.z) }, { t -> t.max.x }).add()
            .append(KeyedCodec("MaxY", Codec.DOUBLE), { t, v -> t.max = Vector3d(t.max.x, v, t.max.z) }, { t -> t.max.y }).add()
            .append(KeyedCodec("MaxZ", Codec.DOUBLE), { t, v -> t.max = Vector3d(t.max.x, t.max.y, v) }, { t -> t.max.z }).add()
            .build()
    }
}

// ---------------------------------------------------------------------------
// RegionName — human-readable / lookup identifier
// ---------------------------------------------------------------------------

class RegionName(var name: String = "") : Component<EntityStore> {

    override fun clone(): RegionName = RegionName(name)

    companion object {
        lateinit var componentType: ComponentType<EntityStore, RegionName>

        val CODEC: BuilderCodec<RegionName> = BuilderCodec.builder(RegionName::class.java, ::RegionName)
            .append(KeyedCodec("Name", Codec.STRING), { t, v -> t.name = v }, { t -> t.name }).add()
            .build()
    }
}

// ---------------------------------------------------------------------------
// Region marker components — each is its own ComponentType for query filtering
// ---------------------------------------------------------------------------

/** Marks a spawn region. */
class SpawnPoint : Component<EntityStore> {
    override fun clone(): SpawnPoint = SpawnPoint()

    companion object {
        lateinit var componentType: ComponentType<EntityStore, SpawnPoint>

        val CODEC: BuilderCodec<SpawnPoint> = BuilderCodec.builder(SpawnPoint::class.java, ::SpawnPoint).build()
    }
}

/** Marks a goal / destination region. */
class Goal : Component<EntityStore> {
    override fun clone(): Goal = Goal()

    companion object {
        lateinit var componentType: ComponentType<EntityStore, Goal>

        val CODEC: BuilderCodec<Goal> = BuilderCodec.builder(Goal::class.java, ::Goal).build()
    }
}

/** Kills / eliminates entities on entry. */
class DeathZone : Component<EntityStore> {
    override fun clone(): DeathZone = DeathZone()

    companion object {
        lateinit var componentType: ComponentType<EntityStore, DeathZone>

        val CODEC: BuilderCodec<DeathZone> = BuilderCodec.builder(DeathZone::class.java, ::DeathZone).build()
    }
}

/** No damage / PvP allowed inside. */
class SafeZone : Component<EntityStore> {
    override fun clone(): SafeZone = SafeZone()

    companion object {
        lateinit var componentType: ComponentType<EntityStore, SafeZone>

        val CODEC: BuilderCodec<SafeZone> = BuilderCodec.builder(SafeZone::class.java, ::SafeZone).build()
    }
}

/** Capture-point style region. */
class CaptureZone : Component<EntityStore> {
    override fun clone(): CaptureZone = CaptureZone()

    companion object {
        lateinit var componentType: ComponentType<EntityStore, CaptureZone>

        val CODEC: BuilderCodec<CaptureZone> = BuilderCodec.builder(CaptureZone::class.java, ::CaptureZone).build()
    }
}

// ---------------------------------------------------------------------------
// RegionTeamOwner — optional link from a region to a team entity
// ---------------------------------------------------------------------------

class RegionTeamOwner(var teamRef: Ref<EntityStore>? = null) : Component<EntityStore> {

    override fun clone(): RegionTeamOwner = RegionTeamOwner(teamRef)

    companion object {
        lateinit var componentType: ComponentType<EntityStore, RegionTeamOwner>

        val CODEC: BuilderCodec<RegionTeamOwner> = BuilderCodec.builder(RegionTeamOwner::class.java, ::RegionTeamOwner).build()
    }
}
