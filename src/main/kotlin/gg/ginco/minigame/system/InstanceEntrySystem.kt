package gg.ginco.minigame.system

import com.hypixel.hytale.component.Archetype
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.RemoveReason
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.system.tick.TickingSystem
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.entity.Frozen
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.PendingInstanceEntry
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.PreStartFreeze
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.util.Instances
import gg.ginco.minigame.util.PlayerSet
import gg.ginco.minigame.util.SpawnPlacement
import java.util.function.BiConsumer

/**
 * Completes a `/minigame join` that crossed into an instance world.
 *
 * `JoinCommand` can only start the transfer — at command time the player's
 * entity is still in the lobby store, and reading the instance world's store
 * from the lobby thread is illegal. It tags the entity with
 * [PendingInstanceEntry] and teleports; this system, running in the instance
 * world's own store, sees the player arrive (the entity is rebuilt here, its
 * components intact) and finishes the job where the controller is actually
 * readable.
 *
 * Runs in every world but does nothing outside instance worlds: the entry
 * marker is only honoured when an in-world controller exists to join.
 */
class InstanceEntrySystem : TickingSystem<EntityStore>() {

    override fun tick(dt: Float, systemIndex: Int, store: Store<EntityStore>) {
        val world = store.externalData.world
        if (!Instances.isInstanceWorld(world)) return

        // Scan first, mutate after: store writes are illegal while forEachChunk
        // holds the processing lock.
        val arriving = mutableListOf<Pair<Ref<EntityStore>, PendingInstanceEntry>>()
        val consumer = BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> { chunk, _ ->
            for (i in 0 until chunk.size()) {
                val entry = chunk.getComponent(i, PendingInstanceEntry.componentType) ?: continue
                if (entry.worldName != world.name) continue
                arriving.add(chunk.getReferenceTo(i) to entry)
            }
        }
        store.forEachChunk(Archetype.of(PendingInstanceEntry.componentType), consumer)
        if (arriving.isEmpty()) return

        val controllerRef = Instances.findController(world) ?: return
        for ((ref, entry) in arriving) {
            // Membership is rebuilt from the controller's rosters, not carried
            // over: the player may have disconnected and rejoined across the
            // transfer, and a stale ref would point at a dead entity.
            // A player hopped into the instance with their entity intact; a
            // previous pre-start freeze must not follow them in (the phase
            // check below re-applies it when this instance is still waiting).
            store.removeComponentIfExists(ref, PreStartFreeze.componentType)
            store.removeComponentIfExists(ref, Frozen.getComponentType())

            val role = entry.role()
            Instances.applyMembership(store, ref, controllerRef, role)
            store.removeComponent(ref, PendingInstanceEntry.componentType)

            // The builtin instance transfer always lands on the WORLD spawn;
            // move straight to the authored team spawn so the start-of-game
            // placement in PhaseSystem has nothing left to move.
            val phase = store.getComponent(controllerRef, Phase.componentType)
            if (phase != null && phase.kind != Phase.Kind.ENDING && phase.kind != Phase.Kind.CLEANUP) {
                SpawnPlacement.place(store, controllerRef, ref)
            }
            if ((phase?.kind == Phase.Kind.WAITING || phase?.kind == Phase.Kind.COUNTDOWN) &&
                role.kind == Role.Kind.PARTICIPANT
            ) {
                PreStartFreeze.apply(store, ref)
            }

            val playerRef = store.getComponent(ref, PlayerRef.getComponentType()) ?: continue
            PlayerSet(store, controllerRef).sendMessage(Message.raw("${playerRef.username} joined the minigame."))
        }
    }
}
