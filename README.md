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

Second vertical slice implemented:

- All components registered runtime-only in `Plugin.setup()` via `Components`
  (instances/teams/markers are transient; durable config will move to assets later)
- `PhaseSystem` (ECS `TickingSystem`) drives `Waiting -> Countdown -> Playing -> Ending -> cleanup`
  per world tick and releases players when an instance is removed
- `/minigame` command tree (admin commands gated to `hytale:Admin`):
  - `create <gametype>` — creates an instance with a per-boot numeric id (#1, #2, ...)
  - `start [--game=<id>] [--seconds=<secs>]` — starts all Waiting instances or one
  - `stop [--game=<id>]` — forced stop with the default ending grace period
  - `status` — `#<id> gameType=... phase=... players=...` per instance
  - `join [--game=<id>]` — joins a specific instance or the lowest-id joinable
  - `leave`
  - `team create <name> [--game=<id>]`, `team join <name>`, `team leave`,
    `team list [--game=<id>]` — teams are per-instance entities (`TeamData` +
    `TeamMembers` + `InMinigame` -> instance); players carry `TeamRef` -> team;
    names are unique per instance, not globally; instance cleanup releases
    teams and memberships
  - `region add <type> [--game=<id>]`, `region list [--game=<id>]`,
    `region clear [--type=<type>]` — in-game marker creation at your position
  - disconnect semantics: **membership is preserved until the minigame
    ends** — rosters are uuid-keyed and the instance keeps a preserved-
    membership record (role included), so a returning player who runs
    `/minigame join` is restored into their exact instance, role and team.
    Voluntary `/minigame leave` still releases everything immediately
  - `defs list|reload|save [--game=<id>]` — durable minigame definitions:
    JSON in the plugin data directory (`definitions/<gameTypeId>.json`),
    loaded at boot; `defs save` captures a live instance's teams and markers
    (the in-game editor write path) and `create <gametype>` then spawns new
    instances fully set-up from the definition
  - disconnect semantics: **membership is preserved until the minigame
    ends** — rosters are uuid-keyed and the instance keeps a preserved-
    membership record (role included), so a returning player who runs
    `/minigame join` is restored into their exact instance, role and team.
    Voluntary `/minigame leave` still releases everything immediately
  - `defs list|reload|save [--game=<id>]` — durable minigame definitions:
    JSON in the plugin data directory (`definitions/<gameTypeId>.json`),
    loaded at boot; `defs save` captures a live instance's teams and markers
    (the in-game editor write path) and `create <gametype>` then spawns new
    instances fully set-up from the definition

## Regions are data, not behaviour

The framework DETECTS region boundary crossings and fires
`PlayerEnterRegionEvent` / `PlayerExitRegionEvent` (every instance phase;
listeners run on the world thread). It deliberately attaches no meaning to
them — killing players in death zones, scoring goals, capture progress are
**consuming-plugin logic** written as event listeners:

```kotlin
eventRegistry.register(PlayerEnterRegionEvent::class.java) { e ->
    if (e.type != "death") return@register            // your own type ids
    val phase = entityStore.store.getComponent(e.instance, Phase.componentType)
    if (phase?.kind != Phase.Kind.PLAYING) return@register   // play-only rule
    entityStore.store.replaceComponent(
        e.playerEntity, Role.componentType, Role.disqualified("died in a death zone"))
}
```

Marker type ids are **open** — `region add capture-point-a`, `region add
lasertag-base`, anything. `Regions.markersOf(store, instance, type)` /
`markerAt(...)` are the query surface for plugin code. One reserved id
exists: `spawn` markers are the kind the framework acts on itself (teleport
targets when an instance starts).

Definitions are the durability layer for all of this: markers and teams
placed in-game can be captured with `defs save` and reload on every boot
(plain JSON in the data directory; the Hytale asset-pack backend may replace
the file I/O later — the schema is already its shape).

Role mechanics too: `Roles.spectate/unspectate` apply the mechanical
spectator state (Creative + flight + hidden from instance members — 0.6.8
has no spectator game mode), and `Roles.disqualify` swaps in
`Role.disqualified(reason)`. The framework applies mechanics; WHEN they
apply stays consumer-side. Mechanics are entity state — re-apply after a
preserved-membership restore.

Stats are the same shape: `Stats.add/addForPlayer/total/breakdown` over
arbitrary consumer-chosen stat ids, scoped to an instance, per-player
counters uuid-keyed (they survive disconnects via preserved membership),
released with the instance. `/minigame stats [--game=<id>]` prints totals
and per-player breakdowns by name.
- Integration tests: 52 `@HytaleTest` methods run inside a real server
  via [hytest](https://github.com/ginco-org/hytest) (`./gradlew hytaleTest`);
  hytest loads the real plugin from the merged test jar (SubPlugins manifest),
  so nothing about the framework is stubbed or re-mounted in the harness.

Verified on a live server: plugin loads and registers components/systems/
commands, phase ticking drives the full lifecycle, join/leave guards, targeted
and untargeted instance commands, and command response text assertions.

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
