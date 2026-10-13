package gg.ginco.minigame.event

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Role
import java.util.UUID

/**
 * Fired when a player's membership in an instance is ESTABLISHED: an in-world
 * join, a party member application, a cross-world arrival
 * (InstanceEntrySystem → Instances.applyMembership), or a restored
 * membership. Carries the role they were given.
 *
 * Fired on the world thread of the instance's world. Listeners run
 * synchronously and may write to the store — the dispatch happens after the
 * membership write is complete.
 */
class PlayerJoinedInstanceEvent(
    val player: PlayerRef,
    val playerEntity: Ref<EntityStore>,
    val instance: Ref<EntityStore>,
    val role: Role,
) : com.hypixel.hytale.event.IEvent<Void> {

    companion object {
        /**
         * Dispatches on the server's event bus. Caller must be on the instance
         * world's thread with the membership already applied.
         */
        fun dispatch(player: PlayerRef, playerEntity: Ref<EntityStore>, instance: Ref<EntityStore>, role: Role) {
            HytaleServer.get().eventBus
                .dispatchFor(PlayerJoinedInstanceEvent::class.java)
                .dispatch(PlayerJoinedInstanceEvent(player, playerEntity, instance, role))
        }
    }
}

/**
 * Fired when a player's membership in an instance ENDS: a voluntary
 * `/minigame leave`, a cross-world release (queue switch, dequeue), or an
 * instance teardown. It deliberately does NOT fire when a member merely
 * disconnects — that path PRESERVES the membership (roster slot + preserved
 * record) until the minigame ends; a consumer after the drop-out signal
 * should listen for the server's PlayerDisconnectEvent and check membership
 * itself.
 *
 * [player] and [playerEntity] are set when the player's entity is still in
 * the dispatching store (a live leave has both; a cross-world release fires
 * from the instance world, where the leaving player is by definition absent).
 * [uuid] and [username] always travel, and [role] carries the role they were
 * released from when it was readable.
 *
 * Fired on the world thread of the instance's world; same listener rules as
 * [PlayerJoinedInstanceEvent].
 */
class PlayerLeftInstanceEvent(
    val uuid: UUID,
    val username: String,
    val player: PlayerRef?,
    val playerEntity: Ref<EntityStore>?,
    val instance: Ref<EntityStore>,
    val role: Role?,
)
 : com.hypixel.hytale.event.IEvent<Void> {

    companion object {
        /** See [PlayerJoinedInstanceEvent.dispatch] for threading. */
        fun dispatch(
            uuid: UUID,
            username: String,
            instance: Ref<EntityStore>,
            role: Role?,
            player: PlayerRef? = null,
            playerEntity: Ref<EntityStore>? = null,
        ) {
            HytaleServer.get().eventBus
                .dispatchFor(PlayerLeftInstanceEvent::class.java)
                .dispatch(PlayerLeftInstanceEvent(uuid, username, player, playerEntity, instance, role))
        }
    }
}

