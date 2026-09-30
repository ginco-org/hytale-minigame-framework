package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Role

/**
 * A player on their way into an instance world.
 *
 * `/minigame join` cannot finish its job in one step: the source world's store
 * is the only store the joining entity is readable in at command time, and the
 * membership it wants to apply belongs to the *instance* world's store. So the
 * join decides the target, records it here, and starts the transfer; the
 * [gg.ginco.minigame.system.InstanceEntrySystem] running inside the instance
 * world completes it: the entity is rebuilt in that store (components survive
 * the hop) where the bridge is finally readable next to the controller it
 * targets.
 *
 * The target is remembered as a *name*, not a `World` reference: the world can
 * be torn down before the player gets there, and holding the object would keep
 * it alive past removal.
 */
class PendingInstanceEntry(
    /** Name of the instance world the player was sent to. */
    var worldName: String = "",
    /** Role to apply on arrival (a restored member keeps the role they had). */
    var roleKind: String = Role.Kind.PARTICIPANT.name,
    var roleReason: String = "",
) : Component<EntityStore> {

    override fun clone(): PendingInstanceEntry = PendingInstanceEntry(worldName, roleKind, roleReason)

    fun role(): Role {
        val kind = Role.Kind.entries.firstOrNull { it.name == roleKind } ?: Role.Kind.PARTICIPANT
        return Role(kind, roleReason)
    }

    companion object {
        lateinit var componentType: ComponentType<EntityStore, PendingInstanceEntry>
    }
}
