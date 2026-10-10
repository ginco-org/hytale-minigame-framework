package gg.ginco.minigame.util

import com.hypixel.hytale.component.*
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Role
import java.util.function.BiConsumer

/**
 * Bulk-operation helper for all players in a minigame instance.
 *
 * NOT a component — a short-lived utility scoped to one Store + controller.
 *
 * Only entities with a [PlayerRef] count: team entities also carry
 * `InMinigame` (linking them to their instance) but are not players.
 */
class PlayerSet(
    private val store: Store<EntityStore>,
    private val controllerRef: Ref<EntityStore>
) {
    fun all(): List<Ref<EntityStore>> {
        val result = mutableListOf<Ref<EntityStore>>()
        val consumer = BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val inMinigame = chunk.getComponent(i, InMinigame.componentType) ?: continue
                if (inMinigame.controllerRef != controllerRef) continue
                if (chunk.getComponent(i, PlayerRef.getComponentType()) == null) continue
                result.add(chunk.getReferenceTo(i))
            }
        }
        store.forEachChunk(Archetype.of(InMinigame.componentType), consumer)
        return result
    }

    fun withRole(role: Role): List<Ref<EntityStore>> {
        val result = mutableListOf<Ref<EntityStore>>()
        val consumer = BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val inMinigame = chunk.getComponent(i, InMinigame.componentType) ?: continue
                if (inMinigame.controllerRef == controllerRef) {
                    val entityRole = chunk.getComponent(i, Role.componentType) ?: continue
                    if (entityRole == role) {
                        result.add(chunk.getReferenceTo(i))
                    }
                }
            }
        }
        store.forEachChunk(Archetype.of(InMinigame.componentType, Role.componentType), consumer)
        return result
    }

    fun sendMessage(message: Message) {
        for (ref in all()) {
            val playerRef = store.getComponent(ref, PlayerRef.getComponentType()) ?: continue
            playerRef.sendMessage(message)
        }
    }

    fun count(): Int = all().size

    fun isEmpty(): Boolean = all().isEmpty()

    fun isNotEmpty(): Boolean = !isEmpty()

    companion object {
        /** The player entity carrying [uuid], or null (players not in a world have none). */
        fun findByUuid(store: Store<EntityStore>, uuid: java.util.UUID): Ref<EntityStore>? {
            var found: Ref<EntityStore>? = null
            val consumer = BiConsumer { chunk: ArchetypeChunk<EntityStore>, buffer: CommandBuffer<EntityStore> ->
                for (i in 0 until chunk.size()) {
                    val player = chunk.getComponent(i, PlayerRef.getComponentType()) ?: continue
                    if (player.uuid == uuid) found = chunk.getReferenceTo(i)
                }
            }
            store.forEachChunk(Archetype.of(PlayerRef.getComponentType()), consumer)
            return found
        }

        /**
         * The player entity + PlayerRef carrying [username], or null. Names
         * are unique among connected players; the scan is this store's, so
         * the caller can only find players in its own world.
         */
        fun findByUsername(store: Store<EntityStore>, username: String): Pair<Ref<EntityStore>, PlayerRef>? {
            var found: Pair<Ref<EntityStore>, PlayerRef>? = null
            val consumer = BiConsumer { chunk: ArchetypeChunk<EntityStore>, buffer: CommandBuffer<EntityStore> ->
                for (i in 0 until chunk.size()) {
                    val player = chunk.getComponent(i, PlayerRef.getComponentType()) ?: continue
                    if (player.username == username) found = chunk.getReferenceTo(i) to player
                }
            }
            store.forEachChunk(Archetype.of(PlayerRef.getComponentType()), consumer)
            return found
        }
    }
}
