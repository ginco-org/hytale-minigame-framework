package gg.ginco.minigame.hytaletest

import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer

/** Small facade the tests use to spawn admin-privileged simulated players. */
object Players {
    fun admin(t: HytaleTestHelper, name: String = "Alice"): SimulatedPlayer {
        val p = t.spawnSimulatedPlayer(name)
        MinigameTestHarness.grantAdmin(p.ref())
        return p
    }
}