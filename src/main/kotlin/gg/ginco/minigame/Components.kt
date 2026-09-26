package gg.ginco.minigame

import com.hypixel.hytale.component.ComponentRegistryProxy
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Bounds
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.InstanceId
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.RegionMarker
import gg.ginco.minigame.component.RegionPresence
import gg.ginco.minigame.component.PreservedMembers
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.component.TeamRef

/**
 * Registers all framework components with the server's [EntityStore] registry
 * and binds each [ComponentType] to its component's companion field.
 *
 * Everything is registered runtime-only (supplier, no codec): minigame instances,
 * teams and markers are transient and intentionally do not survive a restart.
 * Durable per-game config will move to assets ("assets as config", see README)
 * rather than persisted entities — the hand-written CODECs in the component
 * companions are kept for that future step.
 */
object Components {

    fun register(registry: ComponentRegistryProxy<EntityStore>) {
        Phase.componentType = registry.registerComponent(Phase::class.java) { Phase.countdown(0f) }
        Role.componentType = registry.registerComponent(Role::class.java) { Role.participant() }

        ControllerComponent.componentType = registry.registerComponent(ControllerComponent::class.java) { ControllerComponent() }
        InMinigame.componentType = registry.registerComponent(InMinigame::class.java) { InMinigame() }
        InstanceId.componentType = registry.registerComponent(InstanceId::class.java) { InstanceId() }
        RegionPresence.componentType = registry.registerComponent(RegionPresence::class.java) { RegionPresence() }
        PreservedMembers.componentType = registry.registerComponent(PreservedMembers::class.java) { PreservedMembers() }

        TeamData.componentType = registry.registerComponent(TeamData::class.java) { TeamData() }
        TeamRef.componentType = registry.registerComponent(TeamRef::class.java) { TeamRef() }
        TeamMembers.componentType = registry.registerComponent(TeamMembers::class.java) { TeamMembers() }

        // Regions: geometry + an open type id. The framework fixes no marker
        // vocabulary — plugins choose their own ids and attach the meaning.
        Bounds.componentType = registry.registerComponent(Bounds::class.java) { Bounds() }
        RegionMarker.componentType = registry.registerComponent(RegionMarker::class.java) { RegionMarker() }
    }
}
