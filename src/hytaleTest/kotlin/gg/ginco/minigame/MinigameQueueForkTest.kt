package gg.ginco.minigame

import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig
import com.hypixel.hytale.server.core.universe.world.World
import gg.ginco.minigame.component.PendingInstanceEntry
import gg.ginco.minigame.component.Phase
import gg.ginco.minigame.component.TeamMembers
import gg.ginco.minigame.hytaletest.MinigameTestHarness
import gg.ginco.minigame.hytaletest.Players
import gg.ginco.minigame.util.Instances
import gg.ginco.minigame.util.Phases
import gg.ginco.minigame.util.Teams
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the playtest fork: queuers 1..5 landed in instance #1, the SIXTH got a
 * fresh instance #2 — sequential queues, well below the min-players floor.
 * Whatever breaks one queue world must not fork the queue for the queuers
 * after it, and every queuer's pending marker must name the SAME world.
 */
class MinigameQueueForkTest {

    private fun player(t: HytaleTestHelper, name: String): SimulatedPlayer = Players.admin(t, name)

    private fun pendingMarkerWorldOf(t: HytaleTestHelper, p: SimulatedPlayer): String? {
        var name: String? = null
        t.runOnWorld {
            val store = MinigameTestHarness.storeOf(t.world)
            val ref = MinigameTestHarness.refOf(p.ref())
            name = store.getComponent(ref, PendingInstanceEntry.componentType)?.worldName
        }
        return name
    }

    @HytaleTest(timeoutSeconds = 240)
    fun sixSequentialQueuersShareOneInstance(t: HytaleTestHelper) {
        val n = next.incrementAndGet()
        val asset = MinigameTestHarness.createInstanceAsset("fork6-$n")
        // No MinPlayers floor in the harness asset: nobody auto-starts, the
        // whole queue stays Waiting — the reported scenario's shape.
        val game = MinigameTestHarness.createGameAsset(
            "fork6game-$n",
            maps = listOf("fork6map-$n" to asset.name),
            teams = listOf("Kweebecs", "Trorks"),
            defaultMap = "fork6map-$n",
        )
        try {
            val queuers = (1..6).map { idx -> player(t, "Queuer$idx") }
            for ((idx, queuer) in queuers.withIndex()) {
                println("FORK6: queuer ${idx + 1} dispatching queue command")
                queuer.runCommand("minigame queue ${game.name}").join()
                println("FORK6: queuer ${idx + 1} command returned")
                val markerWorld = pendingMarkerWorldOf(t, queuer)
                println("FORK6: queuer ${idx + 1} marker world = $markerWorld")
                t.assertNotNull(markerWorld, "queuer ${idx + 1} must carry a pending marker")
            }
            println("FORK6: all queued — asserting shared world")

            var world: World? = null
            t.assertEventually(30) {
                world = MinigameTestHarness.instanceWorldOf(asset.name)
                t.assertNotNull(world, "the shared queue world should be loaded")
            }
            val shared = world!!

            for ((idx, queuer) in queuers.withIndex()) {
                t.assertEquals(shared.name, pendingMarkerWorldOf(t, queuer),
                    "queuer ${idx + 1} must point at the FIRST queue world, not a fork")
            }

            // Exactly ONE instance world of this game asset exists.
            t.assertEquals(1, listOf(asset.name).count { MinigameTestHarness.instanceWorldOf(it) != null },
                "no parallel queue world may be spawned")

            // Queued players hold teamless seats; no team is chosen in join
            // order. Starting the countdown assigns all six, balanced 3/3.
            var removalConditions = -1
            Instances.runOnWorld(shared) {
                removalConditions = InstanceWorldConfig.ensureAndGet(shared.worldConfig)
                    .removalConditions.size
                val store = shared.entityStore.store
                val controller = Instances.findController(shared)!!
                t.assertEquals(6, Teams.pendingSeats(store, controller).size,
                    "all six queuers should be teamless pending seats")
                for (team in Teams.of(store, controller)) {
                    t.assertEquals(0,
                        store.getComponent(team.ref, TeamMembers.componentType)?.members?.size,
                        "team '${team.data.name}' must stay empty while Waiting")
                }
                Phases.set(store, controller, Phase.countdown(10f))
            }
            t.assertEquals(0, removalConditions,
                "a queue world must not carry the builtin empty-world removal conditions")

            var sizes: List<Int>? = null
            t.succeedWhen {
                Instances.runOnWorld(shared) {
                    val store = shared.entityStore.store
                    val controller = Instances.findController(shared)!!
                    sizes = Teams.of(store, controller).map {
                        store.getComponent(it.ref, TeamMembers.componentType)?.members?.size ?: 0
                    }
                }
                t.assertEquals(listOf(3, 3), sizes?.sorted(),
                    "the countdown should assign six queuers evenly, got $sizes")
            }

            Instances.runOnWorld(shared) {
                Instances.findController(shared)?.let { Instances.endInstance(shared, it) }
            }
            t.succeed()
        } finally {
            MinigameTestHarness.removeAssetPack(asset)
        }
    }

    companion object {
        private val next = AtomicInteger()
    }
}
