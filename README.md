# Hytale Minigame Framework

- instances
- assets as config
- teams
- phases
- spectator/disqualification state
- stats
- abstract, portable gamerules

- minigame instance entity
  - phase
  - config
  - instanceRef
- region/marker entity
    - ingame visual editor for region and position markers
- team entity

- parties? (group of players that wants to join games together and be in one team)
- global stats
- reconnection/leaving logic

## Status

First vertical slice implemented:

- All components registered runtime-only in `Plugin.setup()` via `Components`
  (instances/teams/markers are transient; durable config will move to assets later)
- `PhaseSystem` (ECS `TickingSystem`) drives `Waiting -> Countdown -> Playing -> Ending -> cleanup`
  per world tick and releases players when an instance is removed
- `/minigame` command tree: `create <gametype>`, `start [secs]`, `stop`, `status`,
  `join`, `leave` (admin commands gated to `hytale:Admin`)

Verified: plugin loads, registers components/systems/commands and shuts down cleanly
on a live server (`run/HytaleServer.jar --assets Assets.zip --bare`).

Not yet verified live: phase ticking and join/leave with a connected client —
the local server jar is 0.5.3 (worldgen crashes with the current Assets.zip);
use `./gradlew runServer` (requires OAuth device login) to get a matching 0.6.8 server.

### Next up

- team assignment (`TeamData` / `TeamMembers`) on join
- region marker placement + `Bounds` zone checks against player positions
- disconnect/leave handling beyond `/minigame leave`


## Testing

Integration tests run inside a real, authenticated Hytale server (0.6.8) via the
[`hytest`](../hytest) harness, consumed as a local composite build — mirroring
hytest's own `example/`:

- `settings.gradle.kts` `includeBuild`s the local hytest checkout (`~/projects/hytest`)
  and pins `gg.ginco.hygradle.settings` to 0.5.0 (which provides `provisionServer`).
- `build.gradle.kts` applies `gg.ginco.hytest` and `gg.ginco.hygradle`; tests live in
  the `hytaleTest` source set (`src/hytaleTest/kotlin`).

Run them with:

```sh
./gradlew hytaleTest              # boots the server, runs every @HytaleTest
./gradlew -PhytestLogs hytaleTest # forward server stdout into the build log
./gradlew hytaleTest --tests 'gg.ginco.minigame.MinigameLifecycleTest.createPutsInstanceInWaiting'
```

The first run provisions the authenticated server build into
`~/.gradle/caches/hygradle/server/` (non-interactive while the OAuth token cache
in `~/.local/share/hygradle/token.json` is valid).

Notes:

- hytest deploys a single merged `Hytest.jar` whose manifest entrypoint is
  `HytestPlugin`, so the framework's own `Plugin` class is **not** loaded during
  tests; `MinigameTestHarness` (in the `hytaleTest` source set) registers the
  slice — components, `PhaseSystem`, `/minigame` — exactly like `Plugin` does.
- Commands are executed via `SimulatedPlayer.runCommand` (real `CommandManager`
  path, admin group granted through `HytalePermissionsProvider`), and assertions
  read the ECS store on the world thread (`t.runOnWorld`); phase transitions are
  awaited with `t.succeedWhen { }`, never sleeps.
