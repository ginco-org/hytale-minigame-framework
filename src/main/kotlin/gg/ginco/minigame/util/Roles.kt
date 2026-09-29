package gg.ginco.minigame.util

import com.hypixel.hytale.protocol.GameMode
import com.hypixel.hytale.server.core.entity.entities.Player
import com.hypixel.hytale.server.core.entity.entities.player.HiddenPlayersManager
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Role

/**
 * Mechanical effects of the framework's role vocabulary.
 *
 * The framework owns what its roles MEAN mechanically; consumers own WHEN
 * they apply. Nothing here triggers automatically on role changes — call
 * [Spectator.enable] when your game makes someone a spectator, and
 * [Disqualification.disqualify] when your rules eliminate a player.
 *
 * All functions must run on the world thread.
 */
object Roles {

    /**
     * Makes [playerRef] a mechanical spectator: Creative game mode (0.6.8 has
     * no spectator mode — flight is the stand-in), flight enabled, and hidden
     * from every other member of their minigame instance.
     *
     * Note: these are entity mechanics — they do not survive a
     * disconnect/rejoin (the player entity is rebuilt fresh). Consumers that
     * preserve a spectator role across rejoins re-apply this after
     * [Membership.restoreIfPreserved].
     */
    fun spectate(store: Store<EntityStore>, ref: Ref<EntityStore>, playerRef: PlayerRef) {
        setGameMode(store, ref, GameMode.Creative)
        setFlying(store, ref, true)
        setHiddenFromMembers(store, ref, playerRef, hidden = true)
    }

    /** Reverses [spectate]: Adventure, no flight, visible to instance members again. */
    fun unspectate(store: Store<EntityStore>, ref: Ref<EntityStore>, playerRef: PlayerRef) {
        setGameMode(store, ref, GameMode.Adventure)
        setFlying(store, ref, false)
        setHiddenFromMembers(store, ref, playerRef, hidden = false)
    }

    /**
     * Disqualifies [playerRef] within their instance: swaps their role to
     * `Role.disqualified([reason])`. Announcement, further consequences
     * (elimination effects, conversion to spectator) stay consumer-side.
     * No-op when the player is not in a minigame.
     */
    fun disqualify(store: Store<EntityStore>, ref: Ref<EntityStore>, reason: String) {
        if (store.getComponent(ref, InMinigame.componentType) == null) return
        store.replaceComponent(ref, Role.componentType, Role.disqualified(reason))
    }

    private fun setGameMode(store: Store<EntityStore>, ref: Ref<EntityStore>, mode: GameMode) {
        Player.setGameMode(ref, mode, store)
    }

    private fun setFlying(store: Store<EntityStore>, ref: Ref<EntityStore>, flying: Boolean) {
        store.getComponent(ref, Player.getComponentType())?.setFlying(ref, flying, store)
    }

    /** Hides/shows [playerRef] to every other member of their instance. */
    private fun setHiddenFromMembers(
        store: Store<EntityStore>,
        ref: Ref<EntityStore>,
        playerRef: PlayerRef,
        hidden: Boolean,
    ) {
        val controllerRef = store.getComponent(ref, InMinigame.componentType)?.controllerRef ?: return

        for (member in PlayerSet(store, controllerRef).all()) {
            if (member == ref) continue // never hide the spectator from themselves
            val memberPlayerRef = store.getComponent(member, PlayerRef.getComponentType()) ?: continue
            // The manager is cached on the PlayerRef — constructing a fresh
            // HiddenPlayersManager would write to a throwaway set.
            val manager = memberPlayerRef.hiddenPlayersManager
            if (hidden) manager.hidePlayer(playerRef.uuid) else manager.showPlayer(playerRef.uuid)
        }
    }
}
