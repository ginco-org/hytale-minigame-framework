package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * A region marker's identity: an arbitrary, plugin-chosen type id
 * ("spawn", "goal", "capture-point-a", "lasertag-base", ...).
 *
 * The framework fixes NO vocabulary. Marker entities are `Bounds` +
 * `InMinigame` -> instance + this component; crossings are reported as
 * events carrying [type], and meaning is attached by consuming plugins.
 * One reserved id exists: `"spawn"` markers are the teleport targets used
 * when an instance starts ([gg.ginco.minigame.util.Regions.SPAWN_TYPE]).
 */
class RegionMarker(
    var type: String = "",
) : Component<EntityStore> {

    override fun clone(): RegionMarker = RegionMarker(type)

    companion object {
        lateinit var componentType: ComponentType<EntityStore, RegionMarker>
    }
}
