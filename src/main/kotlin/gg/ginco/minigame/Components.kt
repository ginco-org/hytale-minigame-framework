package gg.ginco.minigame

import com.hypixel.hytale.component.ComponentRegistryProxy
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.Bounds
import gg.ginco.minigame.component.CaptureZone
import gg.ginco.minigame.component.ControllerComponent
import gg.ginco.minigame.component.DeathZone
import gg.ginco.minigame.component.Goal
import gg.ginco.minigame.component.InMinigame
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.RegionName
import gg.ginco.minigame.component.RegionTeamOwner
import gg.ginco.minigame.component.Role
import gg.ginco.minigame.component.SafeZone
import gg.ginco.minigame.component.SpawnPoint
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
        Phase.componentType = registry.registerComponent(Phase::class.java) { Phase.Countdown(0f) }
        Role.componentType = registry.registerComponent(Role::class.java) { Role.Participant }

        ControllerComponent.componentType = registry.registerComponent(ControllerComponent::class.java) { ControllerComponent() }
        InMinigame.componentType = registry.registerComponent(InMinigame::class.java) { InMinigame() }

        TeamData.componentType = registry.registerComponent(TeamData::class.java) { TeamData() }
        TeamRef.componentType = registry.registerComponent(TeamRef::class.java) { TeamRef() }
        TeamMembers.componentType = registry.registerComponent(TeamMembers::class.java) { TeamMembers() }

        Bounds.componentType = registry.registerComponent(Bounds::class.java) { Bounds() }
        RegionName.componentType = registry.registerComponent(RegionName::class.java) { RegionName() }
        SpawnPoint.componentType = registry.registerComponent(SpawnPoint::class.java) { SpawnPoint() }
        Goal.componentType = registry.registerComponent(Goal::class.java) { Goal() }
        DeathZone.componentType = registry.registerComponent(DeathZone::class.java) { DeathZone() }
        SafeZone.componentType = registry.registerComponent(SafeZone::class.java) { SafeZone() }
        CaptureZone.componentType = registry.registerComponent(CaptureZone::class.java) { CaptureZone() }
        RegionTeamOwner.componentType = registry.registerComponent(RegionTeamOwner::class.java) { RegionTeamOwner() }
    }
}
