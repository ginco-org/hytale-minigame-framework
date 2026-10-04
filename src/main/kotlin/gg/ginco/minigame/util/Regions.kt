package gg.ginco.minigame.util

import com.hypixel.hytale.assetstore.AssetRegistry
import com.hypixel.hytale.builtin.points.PointsPlugin
import com.hypixel.hytale.builtin.triggervolumes.EntityTargetType
import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent
import com.hypixel.hytale.builtin.triggervolumes.manager.TriggerVolumeManager
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry
import com.hypixel.hytale.builtin.triggervolumes.shape.BoxShape
import com.hypixel.hytale.math.vector.Rotation3f
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import org.joml.Vector3d
import java.util.EnumSet

/**
 * The region surface, now backed by the server's builtin **TriggerVolumes**
 * module instead of the framework's own chunk-store resource.
 *
 * A "region" is a builtin trigger volume tagged with [TAG] = the region's open
 * type id. The builtin does the hard part — per-tick spatial crossing
 * detection, the ENTER/EXIT/TICK lifecycle, per-entity tracking — and reports
 * it as [TriggerVolumeEvent]; the adapter in
 * [gg.ginco.minigame.system.RegionSystem] re-dispatches those as the
 * framework's typed events, filtered by this tag.
 *
 * One reserved type id: [SPAWN_TYPE] volumes (and points, see [spawnPointsOf])
 * are the teleport targets used when an instance starts. Everything else is
 * consumer vocabulary.
 *
 * Regions are WORLD content: the builtin manager is a `Resource<EntityStore>`
 * created and persisted per world by the builtin module itself (registered as
 * `"TriggerVolumeData"` with `TriggerVolumeManager.CODEC`; points as
 * `"PointData"`), so authored arenas survive world saves and are shared by
 * every minigame instance running in that world — the same guarantee the
 * framework's own resource gave, without the hand-rolled bookkeeping.
 */
object Regions {
    /** The tag key whose value is a region's type id. */
    const val TAG = "minigame"

    const val SPAWN_TYPE = "spawn"

    /** Half-extent of the box a `/minigame region add` volume stores around the sender's position. */
    const val HALF_EXTENT = 2.0

    // ---- internals ---------------------------------------------------------

    internal fun managerOf(world: World): TriggerVolumeManager? =
        world.entityStore.store.getResource(TriggerVolumesPlugin.get().managerResourceType)

    private fun typeTagIndex(): Int = AssetRegistry.getOrCreateTagIndex(TAG)

    /** The region type id a volume belongs to, or null when it is not a framework region. */
    internal fun typeOf(volume: VolumeEntry): String? = volume.rawTags[TAG]

    /** Every framework volume in [world] (optionally of [type]), by id. */
    internal fun volumeEntries(world: World, type: String? = null): List<VolumeEntry> =
        (managerOf(world)?.getVolumesByTag(typeTagIndex()) ?: emptyList())
            .filter { type == null || typeOf(it) == type }
            .sortedBy { it.id }

    // ---- public surface ----------------------------------------------------

    /** Immutable view of one box-shaped region: its type id and world-space AABB. */
    data class RegionInfo(
        val id: String,
        val type: String,
        val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double,
    ) {
        fun contains(position: Vector3d): Boolean =
            position.x >= minX && position.x <= maxX &&
                position.y >= minY && position.y <= maxY &&
                position.z >= minZ && position.z <= maxZ

        fun center(): Vector3d = Vector3d(
            (minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5)
    }

    /**
     * Every region in [world] (optionally of [type]), sorted by id. Non-box
     * volumes are skipped: the framework's regions are boxes and consumers read
     * the AABB off [RegionInfo].
     */
    fun regionsOf(world: World, type: String? = null): List<RegionInfo> =
        volumeEntries(world, type).mapNotNull { entry ->
            val box = entry.shape as? BoxShape ?: return@mapNotNull null
            val origin = entry.position
            RegionInfo(
                entry.id, typeOf(entry)!!,
                origin.x + box.min.x, origin.y + box.min.y, origin.z + box.min.z,
                origin.x + box.max.x, origin.y + box.max.y, origin.z + box.max.z,
            )
        }.sortedBy { it.id }

    /** The first region of [type] containing [position], or null. */
    fun regionAt(world: World, type: String, position: Vector3d): RegionInfo? =
        regionsOf(world, type).firstOrNull { it.contains(position) }

    /**
     * Adds a region of [type] as a box-flavoured trigger volume covering
     * ([minX..maxX], [minY..maxY], [minZ..maxZ]). Returns its generated id.
     *
     * The volume carries no effects or rules: the builtin's spatial lifecycle
     * fires ENTER/EXIT regardless (verified headless), so a report needs
     * nothing bolted on.
     */
    fun addRegion(world: World, type: String, minX: Double, minY: Double, minZ: Double,
                  maxX: Double, maxY: Double, maxZ: Double): String {
        val manager = managerOf(world) ?: error("no TriggerVolumeManager in world '${world.name}'")
        val center = Vector3d((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5)
        val id = manager.generateUniqueVolumeId()
        val entry = VolumeEntry(
            id, world.name.lowercase(), center,
            BoxShape(
                Vector3d(minX - center.x, minY - center.y, minZ - center.z),
                Vector3d(maxX - center.x, maxY - center.y, maxZ - center.z),
            ),
            ArrayList(), EnumSet.of(EntityTargetType.PLAYER), true,
        )
        entry.setName(type)
        entry.setTags(mapOf(TAG to type))
        // Keep the volume's chunk sampled even where no player stands, so the
        // builtin's spatial tick still sees it in a headless world whose chunks
        // are not flagged TICKING.
        entry.setKeepLoaded(true)
        manager.register(id, entry)
        return id
    }

    /** Removes regions of [type] (all types when null). Returns how many were removed. */
    fun removeRegions(world: World, type: String? = null): Int {
        val manager = managerOf(world) ?: return 0
        val targets = volumeEntries(world, type).map { it.id }
        for (id in targets) manager.unregister(id)
        return targets.size
    }

    /** A spawn teleport target: a position and an optional facing. */
    data class SpawnPoint(val position: Vector3d, val rotation: Rotation3f?) {
        /** Applies this target to [transform] (world thread). */
        fun applyTo(transform: TransformComponent) {
            transform.teleportPosition(position)
            rotation?.let { transform.teleportRotation(it) }
        }
    }

    /**
     * Every spawn target in [world], from BOTH backends:
     *
     * - **TriggerVolume** volumes tagged [TAG]=[SPAWN_TYPE] (the output of
     *   `/minigame region add spawn`) carry a box; the target is its centre,
     *   with no facing.
     * - **Point** entries tagged [TAG]=[SPAWN_TYPE] carry an explicit position
     *   and full rotation — the richer marker when a facing matters.
     *
     * Volumes first, then points, each by id, so player i -> target i % n is
     * stable across a world's lifetime.
     */
    fun spawnPointsOf(world: World): List<SpawnPoint> {
        val out = mutableListOf<SpawnPoint>()
        for (region in regionsOf(world, SPAWN_TYPE)) out.add(SpawnPoint(region.center(), null))

        val pointManager = world.entityStore.store.getResource(
            PointsPlugin.get().managerResourceType) ?: return out
        pointManager.getPointsWithTags(TAG, SPAWN_TYPE)
            .sortedBy { it.id }
            .forEach { point ->
                // PointEntry stores degrees; its transform hands back radians.
                out.add(SpawnPoint(Vector3d(point.position), point.transform.rotation))
            }
        return out
    }

    /** The name the builtin resolves `TriggerVolumeEvent.worldName` against, for [world]. */
    internal fun eventWorldName(world: World): String = world.name.lowercase()

    /** The PlayerRef a crossing event's entity carries, or null for non-players. */
    internal fun playerEntityOf(
        store: com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        ref: com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
    ): PlayerRef? = store.getComponent(ref, PlayerRef.getComponentType())
}
