# Handoff — hytest wiring + first integration tests (thread thr_vvvkaqc6ne-1)

Branch: `bb/wire-hytest-into-the-minigame-framework-and-writ-thr_vvvkaqc6ne` (worktree; not pushed beyond that).

## What was done

1. **Composite build wiring** (`settings.gradle.kts`, `build.gradle.kts`)
   - `pluginManagement`/`dependencyResolutionManagement` `includeBuild` the local hytest checkout (unpublished by design), same pattern as hytest's `example/`.
   - `gg.ginco.hygradle.settings` bumped **0.4.0 → 0.5.0**; `gg.ginco.hygradle` applied (versionless — the version comes from the settings plugin; the portal POM for the project-plugin variant is empty).
   - `gg.ginco.hytest` applied → `hytaleTest` source set + `hytaleTest` task; `provisionServer` provisions server 0.6.8 (auth was cached, non-interactive).
   - `./gradlew tasks` shows `hytaleTest`, `provisionServer`, `createHytaleTestPlugin`.

2. **Test harness mount** — `src/hytaleTest/kotlin/gg/ginco/minigame/hytaletest/MinigameTestHarness.kt`
   - hytest merges runtime + main + test classes into one `Hytest.jar` whose manifest `Main` is `HytestPlugin`; **`gg.ginco.minigame.Plugin` is never constructed** in tests. The harness replicates `Plugin.setup()/start()`: `Components.register` + `PhaseSystem` on a `ComponentRegistryProxy(EntityStore.REGISTRY)` (world thread, joined before returning), then `CommandManager.get().register(MinigameCommand())` + virtual-groups refresh. Ordering matters: `MinigameCommand`'s companion archetype needs the component types.
   - Admin permissions granted via `HytalePermissionsProvider.addUserToGroup`; store accessors for Phase/Controller/InMinigame/Role; `controllers(store)` scans `CONTROLLERS_WITH_PHASE` on the world thread.

3. **Integration tests** — `src/hytaleTest/kotlin/gg/ginco/minigame/MinigameLifecycleTest.kt` (9 tests, real `CommandManager.handleCommand` via `SimulatedPlayer.runCommand`, no sleeps — `t.succeedWhen`/`t.runOnWorld` only):
   - `createPutsInstanceInWaiting`, `joinAndLeaveTagAndUntagPlayer`, `joinGuards`, `startDrivesWaitingToCountdownToPlaying`, `stopForcesEndingThenCleanupReleasesPlayers`, `stopWithoutInstancesIsANoop`, `statusRunsAndStoreItReportsMatches`, `leaveWithoutInstanceIsRejected`, `joinDuringCountdownIsAllowed`.
   - Command **text** output is not capturable: hytest's `FakeChannelConnection.write()` discards packets (see limitations), so guards assert observable store state.

4. **Framework fixes made** (`src/main/kotlin/gg/ginco/minigame/command/MinigameCommands.kt`)
   - `CONTROLLERS_WITH_PHASE` is now `by lazy` — an eager archetype snapshots component types (possibly uninitialised) at class-init time.
   - `CreateCommand` now builds the holder's component array **indexed by `ComponentType.getIndex()`** sized to `archetype.length()`. Positional arrays (`[Controller, Phase]`) can never work: archetype slots are registry-indexed and `validateComponents` compares positionally against those slots.

## The blocker: framework bug (NOT hytest)

`Phase` and `Role` are **sealed interfaces**. The server ECS (`Holder.init` →
`Archetype.validateComponents`) validates every stored component instance by
**exact class equality** with the class passed to `registerComponent`:

```
java.lang.IllegalStateException: Invalid component at index 195
  expected interface gg.ginco.minigame.component.Phase
  but found class gg.ginco.minigame.component.Phase$Waiting
  at com.hypixel.hytale.component.Archetype.validateComponents(Archetype.java:187)
```

Reproduced three ways: direct `store.registry.newHolder(arch, …)` on the world
thread in a probe test, and through the real `/minigame create` command (which
swallows the exception internally and completes with **no entity created** —
`cmdErr=none, controllers=0`). Since `Phase.Waiting`, `Countdown`, `Playing`,
`Ending`, `Role.Participant` etc. are all subclasses of the registered
interface, **no instance can ever pass validation**. `AddReason`-supplied
defaults fail identically (the `Phase` default supplier produces `Countdown`).

**Fix direction (out of scope here, would redesign the component API):** make
`Phase`/`Role` single concrete classes carrying an enum + payload (e.g.
`class Phase(val kind: Kind, val seconds: Float)`), or register one component
type per concrete subtype. The 6 failing tests should go green immediately
after; the harness and assertions are already in place.

## Test result (honest)

`./gradlew hytaleTest`: **2 / 9 pass** — `stopWithoutInstancesIsANoop`,
`leaveWithoutInstanceIsRejected` (paths that never create components).
The other 7 fail with the root cause above (first failure surfaces as
`exactly one instance should exist (expected 1, got 0)`; probe evidence pins it
to the validation bug). Not forced green per instruction.

## Harness limitations found (reported, not patched)

1. `FakeChannelConnection` discards all written packets → command response text
   is unassertable without a hytest change (a capture list in `write()` would fix it).
2. hytest's merged jar carries only its own manifest, so the plugin under test
   is never loaded; tests must bootstrap it (done in `MinigameTestHarness`).
   Consider a `hytest { pluginMain = … }` option that appends the plugin to the
   merged manifest.
3. Server stdout only reaches the build log with `-PhytestLogs`; there is no
   per-run server log file kept in the work dir.

## Not finished / next steps

- Decide + implement the `Phase`/`Role` redesign, then re-run `hytaleTest` (expect 9/9).
- `Manifest.json` was regenerated by `createHytaleTestPlugin` during runs (hytest
  overwrites it); reverted via `git checkout` and added `/META-INF/`, `/gg/`,
  `/kotlin/`, `/org/` to `.gitignore` so build droppings stay out of `git status`.
- `./gradlew build -x test` re-run before commit; hytest/hygradle were never modified.
