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
