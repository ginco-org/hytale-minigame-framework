package gg.ginco.minigame.component

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stable numeric handle of a minigame instance, assigned by
 * `/minigame create` from a per-boot counter starting at 1.
 *
 * Players use it to target a specific instance (`/minigame join --game=2`),
 * because "first joinable" is ambiguous the moment two instances exist. Ids
 * are runtime-only (like every framework component) and intentionally do not
 * survive a restart.
 */
class InstanceId(var value: Int = 0) : Component<EntityStore> {

    override fun clone(): InstanceId = InstanceId(value)

    companion object {
        lateinit var componentType: ComponentType<EntityStore, InstanceId>

        private val counter = AtomicInteger(0)

        /** Allocates the next per-boot instance id (1, 2, 3, ...). */
        fun allocate(): InstanceId = InstanceId(counter.incrementAndGet())
    }
}
