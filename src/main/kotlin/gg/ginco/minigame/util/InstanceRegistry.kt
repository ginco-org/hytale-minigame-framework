package gg.ginco.minigame.util

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers, per player uuid, which lobby world they were pulled out of when
 * they joined an instance world.
 *
 * The builtin keeps the *instance's* `WorldReturnPoint` (where the spawning
 * admin stood) and a per-entity override recorded at teleport time, but nothing
 * maps "this player's lobby" back to a world the framework can query later — a
 * player who joined from a world that has since been unloaded would strand. This
 * is the framework's own small record of the last known good lobby, so
 * `/minigame leave` and instance teardown can always find a world to send
 * someone home to.
 *
 * Deliberately uuid-keyed and runtime-only, like every other framework link:
 * the player entity is rebuilt on every world switch. Entries are removed when a
 * player is returned, and replaced on every new join.
 */
object InstanceRegistry {

    private data class Entry(val lobbyWorldName: String)

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** Records where [uuid] came from on the way into an instance world. */
    fun remember(uuid: UUID, lobbyWorldName: String) {
        entries[uuid] = Entry(lobbyWorldName)
    }

    /** The world name [uuid] should be returned to, or null when unknown. */
    fun lobbyOf(uuid: UUID): String? = entries[uuid]?.lobbyWorldName

    /** Forgets [uuid] (called once they are back in a normal world). */
    fun forget(uuid: UUID) {
        entries.remove(uuid)
    }

    /** Drops every record; test-only so one test cannot leak into the next. */
    fun clear() {
        entries.clear()
    }
}
