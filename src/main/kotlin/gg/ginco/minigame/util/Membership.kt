package gg.ginco.minigame.util

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.PreservedMember
import gg.ginco.minigame.component.PreservedMembers
import gg.ginco.minigame.component.RegionPresence
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.component.TeamRef

/**
 * Instance-membership lifecycle.
 *
 * Two distinct exits:
 * - **`/minigame leave`** — voluntary: the player gives up membership
 *   (roster slot, team link, preserved record) immediately.
 * - **Disconnect** — involuntary: membership is PRESERVED until the
 *   minigame ends. The player's entity is destroyed by the server, so the
 *   durable state is uuid-keyed: the team roster keeps the player's uuid and
 *   the instance carries a [PreservedMembers] record with the role they had.
 *   A returning player is restored via [restoreIfPreserved] (used by
 *   `/minigame join`).
 *
 * All functions here must run on the instance's world thread.
 */
object Membership {

    /** Announces [message] to every currently-connected member of [controllerRef]. */
    fun announce(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, message: Message) {
        PlayerSet(store, controllerRef).sendMessage(message)
    }

    /**
     * Disconnect handler: preserves the departing player's membership (team
     * roster slot + a [PreservedMembers] record with their role) and tells
     * the connected members. Runs synchronously when already on the world
     * thread (server-initiated disconnects), else hops to it — the event
     * fires BEFORE the server removes the entity, which is the only moment
     * the player's role is still readable.
     */
    fun handleDisconnect(playerRef: PlayerRef) {
        val ref = playerRef.reference ?: return // entity already gone
        val world = ref.store.externalData.world

        fun preserveAndAnnounce() {
            val store = ref.store
            val controllerRef = store.getComponent(ref, InMinigame.componentType)?.controllerRef
                ?: return@preserveAndAnnounce
            if (!controllerRef.isValid()) return@preserveAndAnnounce

            // Preserve the role for restoration on rejoin.
            val role = store.getComponent(ref, Role.componentType)
            val members = store.getComponent(controllerRef, PreservedMembers.componentType)?.members
                ?: mutableListOf<PreservedMember>().also {
                    store.addComponent(controllerRef, PreservedMembers.componentType, PreservedMembers(it))
                }
            if (members.none { it.uuid == playerRef.uuid }) {
                members.add(PreservedMember(playerRef.uuid, playerRef.username,
                    role?.kind?.name ?: Role.Kind.PARTICIPANT.name, role?.reason ?: ""))
            }

            Membership.announce(store, controllerRef,
                Message.raw("${playerRef.username} disconnected."))
        }

        if (world.isInThread()) {
            preserveAndAnnounce()
        } else {
            world.execute(::preserveAndAnnounce)
        }
    }

    /**
     * Restores a returning player into their preserved membership, if one
     * exists for [playerRef]'s uuid in a live instance: rebuilds `InMinigame`
     * (same instance), `Role` (as recorded) and `TeamRef` (the team whose
     * roster holds the uuid). The roster slot itself survives the
     * disconnect, so it is not touched here.
     *
     * Returns true when membership was restored.
     */
    private data class Candidate(val controllerRef: Ref<EntityStore>, val entry: PreservedMember, val preservedList: MutableList<PreservedMember>)

    fun restoreIfPreserved(store: Store<EntityStore>, ref: Ref<EntityStore>, playerRef: PlayerRef): Boolean {
        // Already in a game (any game): nothing to restore.
        if (store.getComponent(ref, InMinigame.componentType) != null) return false

        // Scan first: collect candidates (writes are illegal while the
        // store's processing lock is held — see PhaseSystem).
        val candidates = mutableListOf<Candidate>()
        val consumer = java.util.function.BiConsumer {
            chunk: com.hypixel.hytale.component.ArchetypeChunk<EntityStore>,
            buffer: com.hypixel.hytale.component.CommandBuffer<EntityStore> ->
            for (i in 0 until chunk.size()) {
                val controllerRef = chunk.getReferenceTo(i)
                val phase = chunk.getComponent(i, Phase.componentType) ?: continue
                // Restore only while the instance can still be played — an
                // ending/cleanup instance is about to release everything.
                if (phase.kind != Phase.Kind.WAITING && phase.kind != Phase.Kind.COUNTDOWN &&
                    phase.kind != Phase.Kind.PLAYING) continue

                val preservedComponent = chunk.getComponent(i, PreservedMembers.componentType) ?: continue
                val entry = preservedComponent.members.firstOrNull { it.uuid == playerRef.uuid } ?: continue
                candidates.add(Candidate(controllerRef, entry, preservedComponent.members))
            }
        }
        store.forEachChunk(
            com.hypixel.hytale.component.Archetype.of(
                gg.ginco.minigame.component.ControllerComponent.componentType,
                Phase.componentType,
                PreservedMembers.componentType,
            ),
            consumer,
        )

        // Post-scan: rebuild the membership (writes legal here).
        val candidate = candidates.firstOrNull() ?: return false
        val (controllerRef, entry, preservedList) = candidate

        store.addComponent(ref, InMinigame.componentType, InMinigame(controllerRef))
        val roleKind = Role.Kind.entries.firstOrNull { it.name == entry.roleKind }
            ?: Role.Kind.PARTICIPANT
        store.addComponent(ref, Role.componentType, Role(roleKind, entry.reason))
        // TeamRef: the team whose roster holds this uuid (rosters keep uuids,
        // so the slot survived the disconnect).
        for (team in Teams.of(store, controllerRef)) {
            val roster = store.getComponent(team.ref, TeamMembers.componentType) ?: continue
            if (playerRef.uuid in roster.members) {
                store.addComponent(ref, TeamRef.componentType, TeamRef(team.ref))
                break
            }
        }
        preservedList.remove(entry)
        return true
    }

    /**
     * Voluntary release (used by `/minigame leave`): drops the team roster
     * slot, the preserved record and all membership components.
     * Returns the controller entity the player was released from, or null
     * when they were not in one.
     */
    fun leave(store: Store<EntityStore>, ref: Ref<EntityStore>, playerRef: PlayerRef): Ref<EntityStore>? {
        val controllerRef = store.getComponent(ref, InMinigame.componentType)?.controllerRef

        val teamRef = store.getComponent(ref, TeamRef.componentType)?.teamRef
        if (teamRef != null && teamRef.isValid()) {
            store.getComponent(teamRef, TeamMembers.componentType)?.members?.remove(playerRef.uuid)
        }
        store.removeComponentIfExists(ref, InMinigame.componentType)
        store.removeComponentIfExists(ref, Role.componentType)
        store.removeComponentIfExists(ref, TeamRef.componentType)
        store.removeComponentIfExists(ref, RegionPresence.componentType)

        // A voluntary leave cancels any preserved membership for this uuid.
        if (controllerRef != null && controllerRef.isValid()) {
            store.getComponent(controllerRef, PreservedMembers.componentType)
                ?.members?.removeAll { it.uuid == playerRef.uuid }
        }
        return controllerRef
    }
}
