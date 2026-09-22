package gg.ginco.minigame.hytaletest

import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.permissions.PermissionsModule
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.InstanceId
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.command.MinigameCommand

/**
 * Test-side helpers for the minigame framework suite.
 *
 * Unlike earlier revisions this harness does **not** mount the framework:
 * since hytest ships the plugin under test's manifest on the merged test jar
 * (with hytest attached as a sub-plugin), the server loads the real
 * [gg.ginco.minigame.Plugin] — components, [PhaseSystem] and the
 * `/minigame` command tree are registered by the plugin's own
 * `setup()`/`start()` at server boot. Nothing about the framework is stubbed
 * or re-registered here.
 *
 * What remains is test plumbing: granting simulated players the admin
 * permission group the subcommands gate on, and store accessors for
 * assertions. Store scans require the world thread — call them from inside
 * `t.runOnWorld { }` (the test body runs off-thread per the hytest contract).
 */
object MinigameTestHarness {

    // ---- permissions -------------------------------------------------------

    /** Adds the player to `hytale:Admin` (the group the admin subcommands gate on). */
    fun grantAdmin(player: PlayerRef) {
        adminProvider()?.addUserToGroup(player.uuid, HytalePermissionsProvider.GROUP_ADMIN)
    }

    fun hasAdmin(player: PlayerRef): Boolean =
        PermissionsModule.get().hasPermission(player.uuid, "ginco:minigame.create", true)

    private fun adminProvider(): HytalePermissionsProvider? =
        PermissionsModule.get().firstPermissionProvider as? HytalePermissionsProvider

    // ---- store access ------------------------------------------------------

    fun storeOf(world: World): Store<EntityStore> = world.entityStore.store

    fun phaseOf(store: Store<EntityStore>, ref: Ref<EntityStore>): Phase? =
        store.getComponent(ref, Phase.componentType)

    fun controllerOf(store: Store<EntityStore>, ref: Ref<EntityStore>): ControllerComponent? =
        store.getComponent(ref, ControllerComponent.componentType)

    fun inMinigameOf(store: Store<EntityStore>, ref: Ref<EntityStore>): InMinigame? =
        store.getComponent(ref, InMinigame.componentType)

    fun roleOf(store: Store<EntityStore>, ref: Ref<EntityStore>): Role? =
        store.getComponent(ref, Role.componentType)

    fun instanceIdOf(store: Store<EntityStore>, ref: Ref<EntityStore>): Int? =
        store.getComponent(ref, InstanceId.componentType)?.value

    /** Every controller entity in [store]. */
    fun controllers(store: Store<EntityStore>): List<Ref<EntityStore>> {
        val out = mutableListOf<Ref<EntityStore>>()
        val consumer =
            java.util.function.BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
                for (i in 0 until chunk.size()) {
                    if (chunk.getComponent(i, ControllerComponent.componentType) != null) {
                        out.add(chunk.getReferenceTo(i))
                    }
                }
            }
        store.forEachChunk(MinigameCommand.CONTROLLERS, consumer)
        return out
    }

    /** The controller entity created with [gameTypeId], or null. */
    fun controllerOfGameType(store: Store<EntityStore>, gameTypeId: String): Ref<EntityStore>? =
        controllers(store).firstOrNull { controllerOf(store, it)?.gameTypeId == gameTypeId }

    /** Entity ref for a simulated player (fails the test if the player is not in a world). */
    fun refOf(player: PlayerRef): Ref<EntityStore> = player.reference ?: error("player is not in a world")
}
