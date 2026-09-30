package gg.ginco.minigame.region

import com.hypixel.hytale.component.Resource
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import gg.ginco.jellyparty.codec.annotations.SerializableObject

/**
 * One placed region marker: an open [type] id, an axis-aligned box, and a
 * stable [id]. Type ids are arbitrary, consumer-chosen strings ("spawn",
 * "goal", "capture-point-a", ...) — the framework fixes no vocabulary and
 * attaches no meaning.
 *
 * Regions are WORLD content: they live in the instance world's chunk store
 * (via [RegionResource]), are saved and loaded with the world, and are
 * shared by every minigame instance running in that world. They are not
 * instance children and not definition data.
 */
@SerializableObject
data class RegionData(
    var id: String = "",
    var type: String = "",
    var minX: Double = 0.0,
    var minY: Double = 0.0,
    var minZ: Double = 0.0,
    var maxX: Double = 0.0,
    var maxY: Double = 0.0,
    var maxZ: Double = 0.0,
)

/**
 * The world-persisted collection of region markers
 * (`Resource<ChunkStore>`): saved and loaded with the instance world, so
 * authored arenas survive restarts without any plugin-side file I/O.
 */
@SerializableObject
class RegionResource(
    var regions: MutableList<RegionData> = mutableListOf(),
) : Resource<ChunkStore> {

    override fun clone(): RegionResource =
        RegionResource(regions.map { it.copy() }.toMutableList())
}
