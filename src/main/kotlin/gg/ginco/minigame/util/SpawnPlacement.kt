package gg.ginco.minigame.util

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.math.vector.Rotation3f
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportSystems
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.component.TeamRef

/**
 * Places players on the authored spawn markers of an instance world.
 *
 * The builtin `Hytale:Instances` entry path always lands a player on the
 * **world's** spawn provider (`InstancesPlugin.teleportPlayerToInstance`), not
 * on a minigame's `SPAWN_*` markers. Without this helper the only authored
 * placement happens in `PhaseSystem` at Countdown→Playing, so every player
 * visibly lands on the map's default spawn and is teleported a second time when
 * the game starts. `InstanceEntrySystem` now calls [place] on arrival, and
 * `PhaseSystem` skips players already standing on their marker.
 */
object SpawnPlacement {

    /**
     * Puts [player] on the next free spawn of their team's pool (generic
     * `spawn` pool when the team has none). Returns false when the world has no
     * markers or the player is already standing on the target.
     *
     * Must run on the instance store's thread.
     */
    fun place(
        store: Store<EntityStore>,
        controllerRef: Ref<EntityStore>,
        player: Ref<EntityStore>,
    ): Boolean {
        val key = poolKey(store, player)
        val spawns = poolOf(store, key)
        if (spawns.isEmpty()) return false
        // Pick the marker farthest from same-pool players already placed:
        // entry order (and PlayerSet's archetype order) must not stack two
        // teammates on the same marker.
        val occupied = PlayerSet(store, controllerRef).all()
            .filter { it != player && poolKey(store, it) == key }
            .mapNotNull { other ->
                store.getComponent(other, TransformComponent.getComponentType())?.position
            }
        val target = spawns.maxByOrNull { spawn ->
            occupied.minOfOrNull { it.distanceSquared(spawn.position) } ?: Double.MAX_VALUE
        } ?: return false
        return teleport(store, player, target)
    }

    /**
     * Places every player in the instance. Used by `PhaseSystem` when the game
     * starts; players `InstanceEntrySystem` already placed on arrival stand on
     * a marker and are left untouched, so the start does not re-teleport them.
     *
     * Must run on the instance store's thread.
     */
    fun placeAll(store: Store<EntityStore>, controllerRef: Ref<EntityStore>) {
        for (player in PlayerSet(store, controllerRef).all()) {
            val spawns = poolOf(store, poolKey(store, player))
            if (spawns.isEmpty() || isOnAnySpawn(store, player, spawns)) continue
            place(store, controllerRef, player)
        }
    }

    private fun poolKey(store: Store<EntityStore>, player: Ref<EntityStore>): String {
        val teamRef = store.getComponent(player, TeamRef.componentType)?.teamRef
        val teamName = teamRef
            ?.takeIf { it.isValid() }
            ?.let { store.getComponent(it, TeamData.componentType)?.name }
            ?.takeUnless { it.isEmpty() }
        return teamName?.let(Regions::spawnTypeFor) ?: Regions.SPAWN_TYPE
    }

    private fun isOnAnySpawn(
        store: Store<EntityStore>,
        player: Ref<EntityStore>,
        spawns: List<Regions.SpawnPoint>,
    ): Boolean {
        val position = store.getComponent(player, TransformComponent.getComponentType())?.position
            ?: return false
        return spawns.any { position.distanceSquared(it.position) < 0.01 }
    }

    private fun poolOf(store: Store<EntityStore>, key: String): List<Regions.SpawnPoint> {
        val world = store.externalData.world
        if (key != Regions.SPAWN_TYPE) {
            val teamSpawns = Regions.spawnPointsOf(world, key)
            if (teamSpawns.isNotEmpty()) return teamSpawns
        }
        return Regions.spawnPointsOf(world)
    }

    private fun teleport(
        store: Store<EntityStore>,
        player: Ref<EntityStore>,
        target: Regions.SpawnPoint,
    ): Boolean {
        val transform = store.getComponent(player, TransformComponent.getComponentType()) ?: return false
        // Entry placement and the start-of-game pass both target this player;
        // the second one must be a no-op or the client sees a rubber-band.
        if (transform.position.distanceSquared(target.position) < 0.01) return false
        transform.teleportPosition(target.position)
        target.rotation?.let { transform.teleportRotation(it) }
        val rotation: Rotation3f = target.rotation ?: transform.rotation
        store.ensureComponent(player, PendingTeleport.getComponentType())
        store.getComponent(player, PlayerRef.getComponentType())?.let { playerRef ->
            TeleportSystems.queueAndSendClientTeleport(playerRef, target.position, rotation, rotation, true)
        }
        return true
    }
}
