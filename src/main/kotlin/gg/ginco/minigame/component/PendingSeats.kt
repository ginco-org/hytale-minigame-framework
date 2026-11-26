package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import java.util.UUID

/**
 * Queued players that have a seat in an instance but no team yet.
 *
 * Team assignment is deferred until the countdown starts so a queue does not
 * silently pre-decide teams in join order (which made the first queuer always
 * land on the first-defined team). [gg.ginco.minigame.util.Teams.assignQueued]
 * consumes this list at Waiting→Countdown.
 */
class PendingSeats(
    val uuids: MutableList<UUID> = mutableListOf(),
) : Component<EntityStore> {

    override fun clone(): PendingSeats = PendingSeats(uuids.toMutableList())

    companion object {
        lateinit var componentType: ComponentType<EntityStore, PendingSeats>
    }
}
