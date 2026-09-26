package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import java.util.UUID

/**
 * A member who is currently disconnected, preserved on the instance
 * (controller entity) until the minigame ends. Keyed by [uuid] — the player
 * entity is destroyed on disconnect, so this record plus the team roster
 * (which also holds uuids) are what let a returning player be restored into
 * their exact membership.
 */
data class PreservedMember(
    val uuid: UUID,
    val name: String,
    /** Role.Kind name of the role the player had when they left. */
    val roleKind: String,
    val reason: String,
)

/**
 * Preserved memberships of one instance. Lives on the controller entity so
 * it is released together with the instance — "membership lasts until the
 * minigame has ended".
 */
class PreservedMembers(
    val members: MutableList<PreservedMember> = mutableListOf()
) : Component<EntityStore> {

    override fun clone(): PreservedMembers = PreservedMembers(members.toMutableList())

    companion object {
        lateinit var componentType: ComponentType<EntityStore, PreservedMembers>
    }
}
