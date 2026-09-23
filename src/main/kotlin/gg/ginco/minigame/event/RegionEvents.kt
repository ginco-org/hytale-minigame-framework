package gg.ginco.minigame.event

import com.hypixel.hytale.event.IEvent
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.util.RegionKind

/**
 * Fired when a player enters a region marker of their minigame instance.
 *
 * The framework only DETECTS and REPORTS region transitions — it attaches no
 * behaviour to them. What an entry means (score a point, eliminate the player,
 * teleport them home, open a door) is decided by the consuming plugin in its
 * listener. Events fire in every instance phase; gate on
 * [instanceRef]'s `Phase` yourself if your rule is play-only.
 *
 * Listeners run synchronously on the world thread during the region system's
 * tick: mutating store/entity state is allowed, blocking is not.
 *
 * @param player       the player that crossed the boundary
 * @param playerEntity the player's entity ref in the world store
 * @param instance     the minigame instance (controller entity) owning the region
 * @param marker       the marker entity crossed (carries its `Bounds` + kind component)
 * @param kind         the marker vocabulary — data, not behaviour: consumers
 *   decide what a goal/death/safe/capture crossing means
 */
class PlayerEnterRegionEvent(
    val player: PlayerRef,
    val playerEntity: Ref<EntityStore>,
    val instance: Ref<EntityStore>,
    val marker: Ref<EntityStore>,
    val kind: RegionKind,
) : IEvent<Void> {

    override fun toString(): String =
        "PlayerEnterRegionEvent(player=${player.username}, instance=$instance, marker=$marker, kind=${kind.kindName})"
}

/**
 * Fired when a player leaves a region marker; same payload semantics as
 * [PlayerEnterRegionEvent]. Exit events fire while the marker still exists —
 * markers removed while occupied drop the player's tracked presence silently.
 */
class PlayerExitRegionEvent(
    val player: PlayerRef,
    val playerEntity: Ref<EntityStore>,
    val instance: Ref<EntityStore>,
    val marker: Ref<EntityStore>,
    val kind: RegionKind,
) : IEvent<Void> {

    override fun toString(): String =
        "PlayerExitRegionEvent(player=${player.username}, instance=$instance, marker=$marker, kind=${kind.kindName})"
}
