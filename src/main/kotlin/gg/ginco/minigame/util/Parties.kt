package gg.ginco.minigame.util

import com.hypixel.hytale.server.core.universe.PlayerRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Parties: groups of players that join a minigame together and land on the
 * same team.
 *
 * Library, not behaviour — the framework provides the plumbing (state,
 * lifecycle, leader-driven group join with atomic rejection); what a party
 * MEANS for scoring or win conditions stays consumer-side.
 *
 * ## Model
 *
 * A party is runtime-only, uuid-keyed state (like team rosters and preserved
 * membership): it survives disconnects because a disconnect destroys the
 * entity, not the uuid. The leader is a member uuid; the invited set holds
 * uuids with a standing invitation. Names are carried alongside for messages
 * and `party list` — a disconnected member's name is the last known one.
 *
 * ## Lifecycle rules (deliberately minimal; deferred ideas in the README)
 *
 * - Only the leader invites; only the leader drives a party join
 *   (`/minigame join` with a party of more than one).
 * - Invitations do not expire and are per-player: a newer invite replaces an
 *   older one.
 * - The leader leaving disbands the party (no leader transfer yet).
 * - Joining a minigame does not consume the party — members stay party
 *   members while playing.
 *
 * Thread-safety: commands run on world threads and party members may sit in
 * different worlds, so all state is concurrent-map backed and compound
 * operations synchronize on the party itself.
 */
object Parties {

    /** A live party. Treat as read-only; mutate through [Parties] operations. */
    class Party internal constructor(
        /** Fresh UUID identifying this party (not a player uuid). */
        val id: UUID,
        val leaderUuid: UUID,
        /** Last known member names, for messages and `party list`. */
        val names: MutableMap<UUID, String>,
    ) {
        internal val membersMut = mutableSetOf<UUID>()
        internal val invitedMut = mutableSetOf<UUID>()

        val leaderName: String get() = names[leaderUuid] ?: leaderUuid.toString()
        val memberUuids: Set<UUID> get() = synchronized(this) { membersMut.toSet() }
        val invitedUuids: Set<UUID> get() = synchronized(this) { invitedMut.toSet() }

        /** Member count including the leader. */
        val size: Int get() = synchronized(this) { membersMut.size }

        fun isLeader(uuid: UUID): Boolean = uuid == leaderUuid
    }

    private val parties = ConcurrentHashMap<UUID, Party>()

    /** player uuid -> party id; a player is a member of at most one party. */
    private val memberIndex = ConcurrentHashMap<UUID, UUID>()

    /** player uuid -> party id of the standing invitation, if any. */
    private val inviteIndex = ConcurrentHashMap<UUID, UUID>()

    /** Creates a party led by [leader], or null when they are already in one. */
    fun create(leader: PlayerRef): Party? {
        val id = UUID.randomUUID()
        val party = Party(id, leader.uuid, ConcurrentHashMap(mapOf(leader.uuid to leader.username)))
        party.membersMut.add(leader.uuid)
        // The index insert is the membership claim: losing the race means the
        // leader was already in a party, and nothing was published.
        if (memberIndex.putIfAbsent(leader.uuid, id) != null) return null
        parties[id] = party
        return party
    }

    /** The party [uuid] is a member of, or null. */
    fun partyOf(uuid: UUID): Party? = memberIndex[uuid]?.let { parties[it] }

    /** The party [uuid] has a standing invitation to, or null. */
    fun invitedTo(uuid: UUID): Party? = inviteIndex[uuid]?.let { parties[it] }

    /**
     * Records an invitation. Fails (returns false) when the inviter is not
     * the leader of their party, or the invitee is already in a party.
     */
    fun invite(inviterUuid: UUID, invitee: PlayerRef): Boolean {
        val party = partyOf(inviterUuid) ?: return false
        if (!party.isLeader(inviterUuid)) return false
        if (memberIndex.containsKey(invitee.uuid)) return false
        synchronized(party) {
            party.invitedMut.add(invitee.uuid)
            party.names[invitee.uuid] = invitee.username
        }
        inviteIndex[invitee.uuid] = party.id
        return true
    }

    /**
     * Accepts [playerRef]'s standing invitation. Returns the joined party,
     * or null when they have none.
     */
    fun accept(playerRef: PlayerRef): Party? {
        val partyId = inviteIndex.remove(playerRef.uuid) ?: return null
        val party = parties[partyId] ?: return null
        synchronized(party) {
            party.invitedMut.remove(playerRef.uuid)
            party.membersMut.add(playerRef.uuid)
            party.names[playerRef.uuid] = playerRef.username
        }
        memberIndex[playerRef.uuid] = partyId
        return party
    }

    /** What a [leave] did — enough for the command layer to message everyone. */
    data class LeaveOutcome(
        val disbanded: Boolean,
        /** Members that were in the party besides the leaver (pre-leave, so a disband can still notify them). */
        val otherMembers: Set<UUID>,
        /** Last known names keyed by uuid (includes the leaver). */
        val names: Map<UUID, String>,
    )

    /**
     * Removes [uuid] from their party. The leader leaving disbands the party
     * for everyone; a member leaving keeps the party alive. Returns null
     * when [uuid] is in no party.
     */
    fun leave(uuid: UUID): LeaveOutcome? {
        val partyId = memberIndex.remove(uuid) ?: return null
        val party = parties[partyId] ?: return null
        data class Result(val disbanded: Boolean, val others: Set<UUID>)
        val result = synchronized(party) {
            // Snapshot before mutating: a disband empties the set, but the
            // others still need to be told.
            val others = party.membersMut.filter { it != uuid }.toSet()
            party.membersMut.remove(uuid)
            val disband = party.leaderUuid == uuid
            if (disband) {
                for (member in party.membersMut) memberIndex.remove(member)
                for (invited in party.invitedUuids) if (inviteIndex[invited] == partyId) inviteIndex.remove(invited)
                parties.remove(partyId)
            }
            Result(disband, others)
        }
        if (inviteIndex[uuid] == partyId) inviteIndex.remove(uuid)
        return LeaveOutcome(
            disbanded = result.disbanded,
            otherMembers = result.others,
            names = party.names.toMap(),
        )
    }

    /** Drops every party and invitation; test-only so one test cannot leak into the next. */
    fun clear() {
        parties.clear()
        memberIndex.clear()
        inviteIndex.clear()
    }
}
