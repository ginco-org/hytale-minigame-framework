package gg.ginco.minigame.hytaletest

import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.ComponentRegistryProxy
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandManager
import com.hypixel.hytale.server.core.permissions.PermissionsModule
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.Components
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.command.MinigameCommand
import gg.ginco.minigame.system.PhaseSystem
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mounts the minigame framework's vertical slice inside a hytest server.
 *
 * ## Why the tests need this
 *
 * `gg.ginco.hytest` merges `hytest-runtime` + main output + `hytaleTest` classes
 * into a single jar and deploys it as `mods/Hytest.jar`. That jar carries
 * hytest-runtime's `manifest.json`, whose `Main` is
 * `gg.ginco.hytest.runtime.HytestPlugin` — so the server instantiates *that*
 * plugin. `gg.ginco.minigame.Plugin` is never constructed: its `setup()` never
 * runs and its `start()` never runs. Measured on the live harness:
 * `CommandManager.get().resolveCommand("minigame")` is `null` and
 * `Phase.componentType` is uninitialized.
 *
 * So the tests mount the slice themselves, mirroring `Plugin` exactly:
 *
 * | `Plugin` does | harness does |
 * |---|---|
 * | `Components.register(entityStoreRegistry)` | [Components.register] on a [ComponentRegistryProxy] over `EntityStore.REGISTRY` |
 * | `entityStoreRegistry.registerSystem(PhaseSystem())` | `proxy.registerSystem(PhaseSystem())` |
 * | `commandRegistry.registerCommand(MinigameCommand())` | `CommandManager.get().register(MinigameCommand())` |
 *
 * Registration happens on the world thread and once per server run (hytest boots
 * one server for the whole suite; each test gets a fresh world that shares the
 * registry). Registration must complete before the command tree is registered
 * because `MinigameCommand`'s static initializer reads the component types.
 *
 * ## Permissions and output capture
 *
 * The admin subcommands require `hytale:Admin`; [grantAdmin] adds the simulated
 * player to that group through the server's real [HytalePermissionsProvider].
 * Command output is captured via [Message.getRawText] on messages the player
 * receives (`Context.sendMessage` → `PlayerRef.sendMessage` → `PacketHandler`),
 * so tests can assert on what the commands answered without a game client.
 *
 * Nothing about the framework itself is stubbed.
 */
object MinigameTestHarness {

    private val commandsRegistered = AtomicBoolean(false)
    private val sliceRegistered = AtomicBoolean(false)

    /** Raw text of every message the framework sent to any simulated player since the last drain. */
    private val output = CopyOnWriteArrayList<String>()

    fun install(world: World) {
        ensureSliceRegistered(world)
        ensureCommandsRegistered()
    }

    /**
     * Registers components + [PhaseSystem] on the world thread and **blocks**
     * until it has run. Must complete before [ensureCommandsRegistered]:
     * `MinigameCommand`'s static initializer reads `Phase.componentType` /
     * `ControllerComponent.componentType` to build its archetype, so the types
     * must exist by then or the class poisons itself for the whole run.
     */
    private fun ensureSliceRegistered(world: World) {
        if (sliceRegistered.get()) return
        val done = java.util.concurrent.CompletableFuture<Unit>()
        world.execute {
            if (sliceRegistered.compareAndSet(false, true)) {
                val proxy = ComponentRegistryProxy<EntityStore>(CopyOnWriteArrayList(), EntityStore.REGISTRY)
                Components.register(proxy)
                proxy.registerSystem(PhaseSystem())
            }
            done.complete(Unit)
        }
        // The test body runs off the world thread (hytest contract), so joining
        // here cannot self-deadlock; it just waits for the next world tick.
        done.join()
    }

    fun ensureCommandsRegistered() {
        if (!commandsRegistered.compareAndSet(false, true)) return
        CommandManager.get().register(MinigameCommand())
        // Mirrors PermissionsModule.refreshVirtualGroups(): publish the
        // group -> permission map for every registered command, including the
        // ones just added, so hytale:Admin actually grants them.
        PermissionsModule.get().virtualGroups = CommandManager.get().createVirtualPermissionGroups()
    }

    // ---- players -----------------------------------------------------------

    /** Adds the player to `hytale:Admin` (the group the admin subcommands gate on). */
    fun grantAdmin(player: PlayerRef) {
        adminProvider()?.addUserToGroup(player.uuid, HytalePermissionsProvider.GROUP_ADMIN)
    }

    fun hasAdmin(player: PlayerRef): Boolean =
        PermissionsModule.get().hasPermission(player.uuid, "ginco:minigame.create", true)

    private fun adminProvider(): HytalePermissionsProvider? =
        PermissionsModule.get().firstPermissionProvider as? HytalePermissionsProvider

    // ---- command output capture ---------------------------------------------

    fun recordOutput(message: Message) {
        output.add(message.rawText)
    }

    fun lastOutputContains(fragment: String): Boolean = output.any { it.contains(fragment) }

    fun drainOutput(): List<String> {
        val snapshot = output.toList()
        output.clear()
        return snapshot
    }

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

    /**
     * Every controller entity in [store]. `forEachChunk` requires the store
     * thread, so call this from inside `t.runOnWorld { }`.
     */
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
        store.forEachChunk(MinigameCommand.CONTROLLERS_WITH_PHASE, consumer)
        return out
    }

    /** Entity ref for a simulated player (fails the test if the player is not in a world). */
    fun refOf(player: PlayerRef): Ref<EntityStore> = player.reference ?: error("player is not in a world")
}