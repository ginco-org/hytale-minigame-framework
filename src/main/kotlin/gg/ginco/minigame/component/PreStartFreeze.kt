package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.protocol.FlyMode
import com.hypixel.hytale.protocol.MovementSettings
import com.hypixel.hytale.server.core.entity.Frozen
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager
import com.hypixel.hytale.server.core.modules.physics.component.Velocity
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * Marks a participant this framework froze because their instance has not
 * started yet (WAITING or COUNTDOWN phase).
 *
 * [Frozen] alone cannot be used as the marker: a minigame is free to freeze a
 * player for its own reasons, and the framework must not thaw that freeze when
 * the countdown ends. Only entities carrying this marker are thawed by
 * [gg.ginco.minigame.system.PlayerFreezeSystem].
 *
 * Player movement is client-authoritative, so the marker also zeroes the
 * player's [MovementSettings] and resends them; `Frozen` alone stops NPC AI but
 * does not by itself stop a player from walking. The changed values are saved
 * here and restored on thaw, leaving a game's custom movement settings intact.
 */
class PreStartFreeze(
    /** True when this framework added [Frozen]; false if the player was already frozen. */
    val ownsFrozen: Boolean = true,
    val movementLock: MovementLock? = null,
) : Component<EntityStore> {

    override fun clone(): PreStartFreeze = PreStartFreeze(ownsFrozen, movementLock)

    /** The [MovementSettings] values this framework overwrote, for exact restore. */
    data class MovementLock(
        private val baseSpeed: Float,
        private val jumpForce: Float,
        private val swimJumpForce: Float,
        private val fallJumpForce: Float,
        private val horizontalFlySpeed: Float,
        private val verticalFlySpeed: Float,
        private val fly: FlyMode,
        private val airSpeedMultiplier: Float,
        private val comboAirSpeedMultiplier: Float,
        private val climbSpeed: Float,
        private val climbSpeedLateral: Float,
        private val climbUpSprintSpeed: Float,
        private val climbDownSprintSpeed: Float,
    ) {
        fun zero(settings: MovementSettings) {
            settings.baseSpeed = 0f
            settings.jumpForce = 0f
            settings.swimJumpForce = 0f
            settings.fallJumpForce = 0f
            settings.horizontalFlySpeed = 0f
            settings.verticalFlySpeed = 0f
            settings.fly = FlyMode.Disabled
            settings.airSpeedMultiplier = 0f
            settings.comboAirSpeedMultiplier = 0f
            settings.climbSpeed = 0f
            settings.climbSpeedLateral = 0f
            settings.climbUpSprintSpeed = 0f
            settings.climbDownSprintSpeed = 0f
        }

        fun restore(settings: MovementSettings) {
            settings.baseSpeed = baseSpeed
            settings.jumpForce = jumpForce
            settings.swimJumpForce = swimJumpForce
            settings.fallJumpForce = fallJumpForce
            settings.horizontalFlySpeed = horizontalFlySpeed
            settings.verticalFlySpeed = verticalFlySpeed
            settings.fly = fly
            settings.airSpeedMultiplier = airSpeedMultiplier
            settings.comboAirSpeedMultiplier = comboAirSpeedMultiplier
            settings.climbSpeed = climbSpeed
            settings.climbSpeedLateral = climbSpeedLateral
            settings.climbUpSprintSpeed = climbUpSprintSpeed
            settings.climbDownSprintSpeed = climbDownSprintSpeed
        }

        companion object {
            fun of(settings: MovementSettings): MovementLock = MovementLock(
                settings.baseSpeed,
                settings.jumpForce,
                settings.swimJumpForce,
                settings.fallJumpForce,
                settings.horizontalFlySpeed,
                settings.verticalFlySpeed,
                settings.fly,
                settings.airSpeedMultiplier,
                settings.comboAirSpeedMultiplier,
                settings.climbSpeed,
                settings.climbSpeedLateral,
                settings.climbUpSprintSpeed,
                settings.climbDownSprintSpeed,
            )
        }
    }

    companion object {
        lateinit var componentType: ComponentType<EntityStore, PreStartFreeze>

        /** Freezes [player] for the pre-start wait; safe to call repeatedly. */
        fun apply(store: Store<EntityStore>, player: Ref<EntityStore>) {
            if (store.getComponent(player, componentType) != null) return
            val alreadyFrozen = store.getComponent(player, Frozen.getComponentType()) != null
            val playerRef = store.getComponent(player, PlayerRef.getComponentType())
            val movement = store.getComponent(player, MovementManager.getComponentType())
            val lock = movement?.getSettings()?.let(MovementLock::of)
            if (movement != null && playerRef != null && lock != null) {
                lock.zero(movement.getSettings())
                movement.update(playerRef.packetHandler)
            }
            store.getComponent(player, Velocity.getComponentType())?.setZero()
            store.ensureComponent(player, Frozen.getComponentType())
            store.addComponent(player, componentType, PreStartFreeze(!alreadyFrozen, lock))
        }

        /** Thaws [player] if — and only if — this framework froze them. */
        fun clear(store: Store<EntityStore>, player: Ref<EntityStore>) {
            val marker = store.getComponent(player, componentType) ?: return
            val playerRef = store.getComponent(player, PlayerRef.getComponentType())
            val movement = store.getComponent(player, MovementManager.getComponentType())
            if (movement != null && playerRef != null) {
                marker.movementLock?.restore(movement.getSettings())
                movement.update(playerRef.packetHandler)
            }
            store.removeComponentIfExists(player, componentType)
            if (marker.ownsFrozen) {
                store.removeComponentIfExists(player, Frozen.getComponentType())
            }
        }
    }
}
