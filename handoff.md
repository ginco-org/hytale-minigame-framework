# RESOLVED — was: hytest wiring + first integration tests (thread thr_vvvkaqc6ne-1)

Everything in the original handoff below is done; the blocker is fixed and
`./gradlew hytaleTest` is **9/9 green**.

## What closed the blocker (sealed-interface components)

`Phase`/`Role` are now single concrete data classes (`kind: Kind` enum +
payload fields, companion factory functions) — the ECS validates stored
components by exact class, so sealed subtypes could never pass. Commit on
this branch.

## Two more bugs found behind it (both silent, both real)

1. **Framework — writes inside `forEachChunk` consumers.** The store holds its
   processing lock during the scan; `store.replaceComponent` etc. throw
   "Store is currently processing" there. Systems must write through the
   `CommandBuffer` the ECS passes to the consumer; commands must collect
   refs in the scan and mutate after it. Fixed in `PhaseSystem` (now uses the
   buffer) and `Start`/`StopCommand` (collect-then-mutate). Any throw inside a
   command is swallowed by `AbstractAsyncCommand.runAsync` (logged only,
   future completes normally) — that is why these were invisible no-ops.
2. **hytest — `HytaleTestHelper.runOnWorld` rethrew the `CompletionException`
   wrapper** instead of the task's cause, so `succeedWhen`'s
   `catch (AssertionError) → keep polling` never matched and every polled
   assertion failed the test on its first miss. Fixed in hytest
   (commit `fce52d2` there).

## Test-invocation note

Optional args in hytale's command system are NAMED: `/minigame start
--seconds=7`. A positional `7` fails to parse and the command never runs
(future completes normally). Tests use `--seconds=`.

## Harness limitations (updated)

1. RESOLVED — command text is now assertable: hytest's `FakeChannelConnection`
   records `ServerMessage` raw text (bounded, thread-safe), exposed as
   `SimulatedPlayer.receivedMessages()` / `drainReceivedMessages()` /
   `sawMessage(fragment)` (hytest commit `ed0ff35`). Capture is synchronous
   with command execution — text is readable right after `runCommand(...).join()`.
   `MinigameTestHarness`'s never-wired `recordOutput`/`drainOutput` helpers were
   removed in favor of the real API; see `commandResponsesCarryExpectedText`.
2. Merged jar carries only hytest's manifest — plugin under test must bootstrap
   itself (see `MinigameTestHarness`).
3. No per-run server log file; `-PhytestLogs` maps to `showServerLogs`, but the
   server logs almost nothing to stdout anyway.
4. Gotcha for test authors: a test that neither uses `succeedWhen` nor calls
   `t.succeed()` at the end will time out — assertions don't complete the
   test's `done` future on success.

---

# Original handoff (kept for reference)

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

## The blocker (RESOLVED — see top)

`Phase` and `Role` were **sealed interfaces**; the server ECS validates every
stored component instance by exact class equality, so no subtype instance could
ever be stored. Redesigned as single concrete classes.
