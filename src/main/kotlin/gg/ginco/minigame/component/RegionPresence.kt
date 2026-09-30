package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * Bookkeeping for the region system: the region ids a player was inside as
 * of the last region tick. Enter/exit events are the diff of this list
 * against the current overlap — it is framework state, not a public API;
 * consumers react to [gg.ginco.minigame.event.PlayerEnterRegionEvent] /
 * [gg.ginco.minigame.event.PlayerExitRegionEvent] instead.
 */
class RegionPresence(
    val inside: MutableList<String> = mutableListOf()
) : Component<EntityStore> {

    override fun clone(): RegionPresence = RegionPresence(inside.toMutableList())

    companion object {
        lateinit var componentType: ComponentType<EntityStore, RegionPresence>
    }
}
