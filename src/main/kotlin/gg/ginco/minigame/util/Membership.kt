package gg.ginco.minigame.util

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.RegionPresence
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.component.TeamRef

/**
 * Instance-membership operations shared by `/minigame leave` and the
 * disconnect path.
 *
 * Disconnection is a leave: a player who vanishes mid-game must not keep
 * their membership, team roster slot or region presence (preserving state
 * across a rejoin is a future feature — today a returning player rejoins
 * like anyone else). Both paths strip exactly the same set; only the
 * announcement differs.
 */
object Membership {

    /**
     * Releases [ref] from whatever instance they are in: team roster entry,
     * `InMinigame`, `Role`, `TeamRef` and `RegionPresence`. Must run on the
     * instance's world thread. Returns the controller entity the player was
     * released from, or null when they were not in one.
     */
    fun release(store: Store<EntityStore>, ref: Ref<EntityStore>): Ref<EntityStore>? {
        val controllerRef = store.getComponent(ref, InMinigame.componentType)?.controllerRef

        val teamRef = store.getComponent(ref, TeamRef.componentType)?.teamRef
        if (teamRef != null && teamRef.isValid()) {
            store.getComponent(teamRef, TeamMembers.componentType)?.members?.remove(ref)
        }
        store.removeComponentIfExists(ref, InMinigame.componentType)
        store.removeComponentIfExists(ref, Role.componentType)
        store.removeComponentIfExists(ref, TeamRef.componentType)
        store.removeComponentIfExists(ref, RegionPresence.componentType)
        return controllerRef
    }

    /**
     * Disconnect handler: releases the departing player from their instance
     * and tells the remaining members.
     *
     * Runs synchronously when already on the world thread (server-initiated
     * disconnects), else hops to it — the release must land BEFORE the
     * server's own removal task, because the entity (with its membership
     * components) disappears in that removal.
     */
    fun handleDisconnect(playerRef: PlayerRef) {
        val ref = playerRef.reference ?: return // entity already gone
        val world = ref.store.externalData.world

        fun releaseAndAnnounce() {
            val controllerRef = release(ref.store, ref) ?: return
            if (controllerRef.isValid()) {
                PlayerSet(ref.store, controllerRef)
                    .sendMessage(Message.raw("${playerRef.username} disconnected."))
            }
        }

        if (world.isInThread()) {
            releaseAndAnnounce()
        } else {
            world.execute(::releaseAndAnnounce)
        }
    }
}
