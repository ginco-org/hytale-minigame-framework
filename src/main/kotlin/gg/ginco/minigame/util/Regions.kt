package gg.ginco.minigame.util

import com.hypixel.hytale.component.ComponentRegistryProxy
import com.hypixel.hytale.component.ResourceType
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import gg.ginco.minigame.region.RegionData
import gg.ginco.minigame.region.RegionResource
import gg.ginco.minigame.region.RegionResourceCodec
import org.joml.Vector3d

/**
 * The region marker surface: world-persisted regions in the instance world's
 * chunk store.
 *
 * One reserved type id: [SPAWN_TYPE] markers are the teleport targets used
 * when an instance starts. Everything else is consumer vocabulary.
 */
object Regions {
    const val SPAWN_TYPE = "spawn"

    private lateinit var resourceType: ResourceType<ChunkStore, RegionResource>

    /** Registers the world-persisted region resource. Called from plugin setup. */
    fun register(chunkStoreRegistry: ComponentRegistryProxy<ChunkStore>) {
        resourceType = chunkStoreRegistry.registerResource(
            RegionResource::class.java,
            "MinigameRegions",
            RegionResourceCodec,
        )
    }

    private fun resourceOf(world: World): RegionResource =
        world.chunkStore.store.getResource(resourceType)

    /** Immutable view of one region marker. */
    data class MarkerSnapshot(
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

    /** Every region marker in [world] (optionally of [type]), sorted by id. */
    fun markersOf(world: World, type: String? = null): List<MarkerSnapshot> {
        val out = mutableListOf<MarkerSnapshot>()
        for (r in resourceOf(world).regions) {
            if (type == null || r.type == type) {
                out.add(MarkerSnapshot(r.id, r.type, r.minX, r.minY, r.minZ, r.maxX, r.maxY, r.maxZ))
            }
        }
        return out.sortedBy { it.id }
    }

    /** The first marker of [type] containing [position], or null. */
    fun markerAt(world: World, type: String, position: Vector3d): MarkerSnapshot? =
        markersOf(world, type).firstOrNull { it.contains(position) }

    /**
     * Adds a marker of [type] with explicit bounds. Returns its generated id
     * (unique per world; adding an existing id replaces that marker).
     */
    fun addMarker(world: World, type: String, minX: Double, minY: Double, minZ: Double,
                  maxX: Double, maxY: Double, maxZ: Double): String {
        val id = type + "-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        resourceOf(world).regions.add(
            RegionData(id, type, minX, minY, minZ, maxX, maxY, maxZ))
        return id
    }

    /** Removes markers of [type] (all types when null). Returns how many were removed. */
    fun removeMarkers(world: World, type: String? = null): Int {
        val resource = resourceOf(world)
        val before = resource.regions.size
        resource.regions.removeAll { type == null || it.type == type }
        return before - resource.regions.size
    }

    /** The type id of the region marker with [id], or null when unknown. */
    fun typeOf(world: World, id: String): String? =
        resourceOf(world).regions.firstOrNull { it.id == id }?.type
}
