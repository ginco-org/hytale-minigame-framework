package gg.ginco.minigame.event

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Role

/**
 * Fired when a player's membership in an instance is established (join or
 * preserved-membership restore). Carries the role they were given.
 *
 * Fired on the world thread of the instance's world.
 */
class PlayerJoinedInstanceEvent(
    val player: PlayerRef,
    val playerEntity: Ref<EntityStore>,
    val instance: Ref<EntityStore>,
    val role: Role,
) : com.hypixel.hytale.event.IEvent<Void>

/**
 * Fired when a player's membership in an instance ends (leave, disconnect
 * without release, or instance teardown). Carries the role they had, if any.
 *
 * Fired on the world thread of the instance's world.
 */
class PlayerLeftInstanceEvent(
    val player: PlayerRef,
    val playerEntity: Ref<EntityStore>,
    val instance: Ref<EntityStore>,
    val role: Role?,
) : com.hypixel.hytale.event.IEvent<Void>
