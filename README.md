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
  - `stop` — forced stop with the default ending grace period: bare or
  `--game=<id>` inside the local world, `--asset=<asset>` for one asset's
  instance worlds, and `--minigame=<gametype>` for every queue world of one
  minigame (all admin-only; players are sent home by the teardown)
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
  - `party create|invite <name>|accept|leave|list` — parties: groups that
    join together (see below)
  - disconnect semantics: **membership is preserved until the minigame
    ends** — rosters are uuid-keyed and the instance keeps a preserved-
    membership record (role included), so a returning player who runs
    `/minigame join` is restored into their exact instance, role and team.
    Voluntary `/minigame leave` still releases everything immediately
  - `queue <minigame> [<map>]` — the player-facing join: enters an open
    queue for that minigame (team-balanced; the first queuer spawns the map)
    or spawns one; the queue auto-starts a countdown at the game's
    `MinPlayers` floor (`PhaseSystem`), capped by `MaxPlayers`; queueing
    elsewhere switches queues; `dequeue` leaves a pre-start queue (live
    exits go through `leave`); `/minigame start` remains the admin
    force-start that ignores floors
  - `defs list|reload|save [--game=<id>]` — durable minigame definitions:
    JSON in the plugin data directory (`definitions/<gameTypeId>.json`),
    loaded at boot; `defs save` captures a live instance's teams and markers
    (the in-game editor write path) and `create <gametype>` then spawns new
    instances fully set-up from the definition
  - definitions also come from **asset packs**: the framework owns the
    `Minigame` (teams, behaviors, maps) and `MinigameMap` (instance asset)
    asset types under `Server/Minigames` and `Server/MinigameMaps` (see
    `gg/ginco/minigame/asset/Assets.kt`). A minigame plugin bundles its own
    asset pack (jar manifest `IncludesAssetPack: true`) and ships JSON — the
    framework compiles one definition per minigame (data-dir definitions
    shadow asset-derived ones per game type id). `/minigame create <minigame>
    [<map>]` picks the map: named, or a random playable one when omitted

## Parties

A party is a group of players that joins a minigame together and lands on
the same team. The framework provides the plumbing — state, lifecycle,
leader-driven group join — not game rules.

- State is uuid-keyed and runtime-only (like team rosters and preserved
  membership), so it survives disconnects; a disconnect destroys the
  entity, not the uuid.
- The leader invites (`party invite <name>`, target resolved in the
  inviter's world) and the leader drives the join: `/minigame join` with a
  party of more than one joins **everyone** into the same instance and the
  same team — all or nothing. Every precondition (everyone present and
  unattached, a team with room for the whole party) is checked before the
  first membership write; a full team rejects the whole party rather than
  splitting it, because splitting is a policy call the framework refuses
  to make.
- The leader leaving disbands the party; joining does not consume it.

Deferred ideas (deliberately not in v1): leader transfer on leader leave,
  invitation expiry, cross-world invites, party joins into instance-asset
  worlds (explicitly rejected for now), party-wide leave of a minigame.

## Instance worlds

A definition may name an **instance asset** (`instanceAsset`), the name of a
template world authored with the server's builtin `/instance edit new|save`.
When it does, the minigame no longer runs in the lobby:

- `create <gametype>` spawns (or reuses) the builtin instance world for that
  asset — `InstancesPlugin.spawnInstance(asset, sourceWorld, transform)` at
  the creating admin's position — and creates the controller and teams in
  **that world's** store. The template world folder is **copied** per spawn,
  so each game gets its own chunk store; every `create` therefore yields a
  fresh world (`instance-<asset>-<hash>`).
- `join [--asset=<name>] [--team=<name>]` moves the player in with
  `teleportPlayerToInstance`, which returns a future; the player's entity is
  rebuilt in the instance world's store.
- `start`/`stop` take `--asset=<name>` to target the instance living in
  another world, and `stop` inside an instance world ends that instance.
- Ending an instance world (explicit `stop`, phase end, or the last player
  leaving) tears the world down with `safeRemoveInstance`; players are sent
  back to the world they joined from (`WorldReturnPoint`), not the lobby.

Without an `instanceAsset` the old in-world behaviour is unchanged.

Cross-world membership is restored by the instance's own roster, which is
uuid-keyed and survives the entity rebuild: the instance world runs an
`InstanceEntrySystem` that, on arrival, applies `InMinigame`/`Role`/`TeamRef`
and writes the player into their team. The lobby store is never read from
another world's thread (ECS refuses foreign-thread store access), so
everything the destination needs travels in `PendingInstanceEntry`.

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

Region storage is the server's builtin **TriggerVolumes** module (no bespoke
resource, no framework commands). Builders author markers with the vanilla
point/volume tools — a box-shaped `VolumeEntry` or a `PointEntry` tagged
`minigame=<type>` in the world's `TriggerVolumeManager`/`PointManager` is all
a region is — and the builtin's ticking system does the per-tick spatial
crossing detection that the framework used to hand-roll. The programmatic
path for tests/tools is `Regions.addRegion(world, type, …)`. The framework's typed events are a thin
adapter over the builtin `TriggerVolumeEvent` (`system/RegionSystem.kt`), so
consumers can also listen to `TriggerVolumeEvent` directly if they prefer the
raw surface.

Marker type ids are **open** — `region add capture-point-a`, `region add
lasertag-base`, anything. `Regions.regionsOf(world, type)` / `Regions.regionAt
(world, type, position)` are the query surface for plugin code. One reserved
id exists: `spawn` markers are the kind the framework acts on itself (teleport
targets when an instance starts, read from spawn **volumes and Points** via
`Regions.spawnPointsOf(world)`).

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

Lifetime stats: when an instance's game ends, its counters and per-player
counters merge additively into a per-game-type ledger
(`<dataDir>/stats/<gameTypeId>.json`) — read it via
`LifetimeStats.load(gameTypeId)`. Atomic writes; merge is additive.
- Integration tests: 72 `@HytaleTest` methods run inside a real server
  via [hytest](https://github.com/ginco-org/hytest) (`./gradlew hytaleTest`);
  hytest loads the real plugin from the merged test jar (SubPlugins manifest),
  so nothing about the framework is stubbed or re-mounted in the harness.

Verified on a live server: plugin loads and registers components/systems/
commands, phase ticking drives the full lifecycle, join/leave guards, targeted
and untargeted instance commands, and command response text assertions.

### Next up

- team assignment (`TeamData` / `TeamMembers`) on join
- region marker placement via the builtin TriggerVolumes/Points tools
- disconnect/leave handling beyond `/minigame leave`


## Packaging: one framework per server

The framework ships as two artifacts per `v*` tag:

| Artifact | Made for | Where |
|---|---|---|
| **Thin jar** (`gg.ginco:hytale-minigame-framework`) | minigame plugin *builds* | Maven Central |
| **Fat plugin jar** (`hytale-minigame-framework-x.y.z.jar`) | server operators | GitHub Releases |

Each plugin jar gets its **own `PluginClassLoader`** — whatever a plugin
bundles is *private to it*. So minigame plugins must NOT bundle the
framework: two games each carrying a copy would mean two frameworks with
split static state (separate registries, instance ids, stats) and
duplicate `/minigame` commands. The framework must exist **once per
server**, installed in `mods/` from its GitHub Release jar.

Cross-plugin class access works through the server's plugin bridge
(`PluginBridgeClassLoader`): when a class isn't in the plugin's own jar or
the server classpath, the bridge searches **declared hard dependencies
first**, then optional ones, then everything else (see
`PluginManager.java` in the shared source).

A minigame plugin therefore:

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.hytale.com/release")
}
dependencies {
    implementation("gg.ginco:hytale-minigame-framework:0.4.0")   // compile-time
    compileOnly("com.hypixel.hytale:Server:0.7.2")               // server API
}
hytale {
    depends("gg.ginco:MinigameFramework", ">=0.4")               // runtime
    bundleDependencies = false
}
```

- `depends(...)` makes the server refuse to load the game when the
  framework plugin isn't installed, and guarantees load order (the
  framework enables and starts first).
- kotlin-stdlib and hytale-codec needed by the game resolve from the
  framework's loader via the bridge too — no bundling needed for any of
  it. (Caveat: bridge resolution checks the **server classpath before**
  the bridge, so if the server itself ever ships a different
  kotlin-stdlib version, plugins bind to that one first.)
- `hytale { subPlugin { ... } }` with `bundleDependencies = true` remains
  a legitimate layout for **single-game servers** (the whole stack in one
  jar, no operator install step) — but never mix it with a second game
  plugin doing the same. Verified both layouts on real 0.6.8 servers.

A real two-plugin boot (framework thin-consumer) is documented in
`save-the-kweebecs`' README.

## Testing

Integration tests run inside a real, authenticated Hytale server (0.7.2) via the
[`hytest`](https://github.com/ginco-org/hytest) harness, consumed from Maven
Central (`gg.ginco.hytest:<gradle-plugin|runtime|junit5>`): `settings.gradle.kts`
resolves the plugin via `mavenCentral()`, `build.gradle.kts` applies
`gg.ginco.hytest` (plus `gg.ginco.hygradle` for server provisioning); tests
live in the `hytaleTest` source set (`src/hytaleTest/kotlin`).

Run them with:

```sh
./gradlew hytaleTest              # boots the server, runs every @HytaleTest
./gradlew -PhytestFast hytaleTest # fast tier: exclude the instance-world suite
./gradlew -PhytestLogs hytaleTest # forward server stdout into the build log
./gradlew hytaleTest --tests 'gg.ginco.minigame.MinigameLifecycleTest.createPutsInstanceInWaiting'
```

The first run provisions the authenticated server build into
`~/.gradle/caches/hygradle/server/` (non-interactive while the OAuth token cache
in `~/.local/share/hygradle/token.json` is valid).

### Suite tiers

`MinigameInstanceWorldTest` is the expensive tier: every instance world pays
create + join + settle + stop + teardown, and issue #1's timing analysis
measured ~30–60s per test there — the suite's wall clock. Two tiers off the
same test sources:

- **Fast** (`-PhytestFast`): everything except `MinigameInstanceWorldTest` —
  definitions, behaviors, lifecycle, teams, parties, spawn, stats, regions,
  disconnects. CI runs this tier on every main push.
- **Full** (no flag): the complete suite including instance worlds; the CI
  nightly schedule and `suite: full` dispatches run it. The instance tier is
  itself batched — one lifecycle walkthrough world plus a two-world
  multi-create test — so the full suite keeps the world count (and with it the
  per-world teardown tax) at 3, not the earlier 6.

The split is a Gradle Test-task name filter (see `build.gradle.kts`): hytest's
custom JUnit engine ignores engine discovery selectors, but Gradle applies the
patterns as a launcher post-discovery filter, pruning the descriptor tree, so
only the fast subset is ever dispatched to the server.

Notes:

- hytest deploys a single merged `Hytest.jar` whose manifest entrypoint is
  `HytestPlugin`, so the framework's own `Plugin` class is **not** loaded during
  tests; `MinigameTestHarness` (in the `hytaleTest` source set) registers the
  slice — components, `PhaseSystem`, `/minigame` — exactly like `Plugin` does.
- Commands are executed via `SimulatedPlayer.runCommand` (real `CommandManager`
  path, admin group granted through `HytalePermissionsProvider`), and assertions
  read the ECS store on the world thread (`t.runOnWorld`); phase transitions are
  awaited with `t.succeedWhen { }`, never sleeps.
