# RESOLVED — was: hytest wiring + first integration tests (thread thr_vvvkaqc6ne-1)

Everything in the original handoff below is done; the blocker is fixed and
`./gradlew hytaleTest` is green (10 tests, stable across repeated runs).

## Update (supervision session): harness mount replaced by real plugin lifecycle

hytest now ships the plugin under test's manifest on the merged test jar with
hytest attached as a `SubPlugins` entry — the server loads the REAL
`gg.ginco.minigame.Plugin` (identity, data dir, `setup()`/`start()`), sharing
one classloader with the test classes. `MinigameTestHarness` no longer mounts
the slice; it only grants admin and exposes store accessors. See hytest commit
"Load the plugin under test as a real plugin via the SubPlugins manifest".

Also fixed on this branch: `StopCommand` used `ending(FORCED, 0f)`, so
PhaseSystem tore the instance down within one tick — the Ending(FORCED) state
was unobservable and `stopForcesEndingThenCleanupReleasesPlayers` raced it
(flake). Forced stop now uses the default 3s grace period and notifies members.

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
2. RESOLVED — the merged jar now carries the plugin under test's manifest with
   hytest as a sub-plugin; the real plugin lifecycle runs (see top).
3. No per-run server log file; `-PhytestLogs` maps to `showServerLogs`, but the
   server logs almost nothing to stdout anyway.
4. Gotcha for test authors: a test that neither uses `succeedWhen` nor calls
   `t.succeed()` at the end will time out — assertions don't complete the
   test's `done` future on success.
5. Gotcha: `succeedWhen` is ASYNCHRONOUS — it returns immediately and polls
   in the background. Any assertion placed AFTER a `succeedWhen` block runs
   immediately, racing the condition. All post-state assertions belong
   INSIDE the polled block; only `t.succeed()` goes after it.
6. Gotcha: one server boots per suite (worlds are per-test, plugin statics
   are shared). Per-boot counters (e.g. instance ids) advance across tests —
   assert relative properties, never absolute values like "first instance
   is #1".
7. Gotcha: `SimulatedPlayer.runCommand` fails the future when the ROOT
   command doesn't resolve (typo protection); permission denials and runtime
   argument rejections still complete normally — assert them via
   `receivedMessages()`.
8. Gotcha: `playerRef.transform` can lag `TransformComponent` mutations —
   read positions from the store's TransformComponent on the world thread.

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
9. Marker types are open string ids (`RegionMarker.type`), NOT a fixed
   vocabulary — `/minigame region add <any-id>`; events carry `type: String`.
   Only `spawn` is reserved (framework start-of-game teleports).
10. Disconnect pipeline ordering (verified in bytecode): `Universe.removePlayer`
    dispatches `PlayerDisconnectEvent` BEFORE removing the entity — so the
    framework's release handler still sees full membership, and must run
    BEFORE the server's own removal task (sync on the world thread, else
    `world.execute` keeps FIFO order). Post-disconnect the player ENTITY is
    gone: assert releases via the TEAM ROSTER, never via `refOf(staleRef)`.
11. Register event listeners in `Plugin.setup()`, not `start()` — start-time
    registration does not feed the bus's `hasListener()` gate and the event
    is silently skipped.
12. `succeedWhen` is async (see 5) — the polished alternative is hytest's
    `assertEventually(timeout) { }` which BLOCKS until the condition holds;
    prefer it whenever the test must continue after the wait.
13. The merged test jar now bundles the plugin's runtimeClasspath jars — any
    new runtime dependency (e.g. kotlinx-serialization) just works in tests.
14. Definitions live in the plugin data dir which is FRESH PER RUN (workdir
    is a temp dir) but SHARED ACROSS TESTS within one server boot. Tests that
    save definitions must use unique game-type ids, or later tests creating
    same-named instances will pick the definition up (correct behaviour!).
15. Definition files: serialized via hytale-codec generated codecs
    (@SerializableObject on the definition classes -> MinigameDefinitionCodec;
    decode through RawJsonReader, encode through Codec.encode + BSON JSON
    writer). The codec lib consumes ginco-org/hytale-codec@main as a
    composite build (settings.gradle.kts). The asset-pack backend swap only
    touches DefinitionStore's file I/O.
16. Membership rule (product decision): PRESERVED until the minigame ends.
    Disconnect keeps the team-roster uuid slot + a PreservedMembers record on
    the controller; `/minigame join` restores instance/role/team via
    Membership.restoreIfPreserved. Voluntary leave still releases. Team
    rosters are UUID-keyed (entity refs die on disconnect).
17. RECURRING PITFALL (third occurrence): never write to the store inside a
    forEachChunk consumer — the processing lock makes writes throw and
    CommandBuffer-less systems swallow the failure. Scan into snapshots,
    mutate after. restoreIfPreserved originally did writes in the consumer
    and silently failed.
18. CI integration is ~30 min by measurement, not by accident: the server's
    world-removal pipeline costs ~30s per per-test world on 2-core runners
    (5s "waiting for loading chunks" bounded timeout — fake channels never
    ACK — plus ~25s chunk-save phase). Both workflows now run with
    `concurrency: ci-${{ github.ref }}` + `cancel-in-progress: true` (new
    pushes cancel superseded runs), forward server logs
    (`-PhytestLogs` + showStandardStreams), and the runtime emits per-test
    "PASSED in Xms" lines so regressions are attributable. Iterate locally;
    let CI confirm.

