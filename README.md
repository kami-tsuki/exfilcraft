# ExfilCraft

A Minecraft (Paper/Spigot) plugin blending instanced extraction-style raid sessions with persistent, expandable player bunkers.

> Current maturity: Phase 1 hardened bunker core + Phase 2 raid MVP (Version spec snapshot: 2025-08-24.5)

---
## Contents
- [Key Concept](#key-concept)
- [Feature Overview](#feature-overview)
- [Architecture Summary](#architecture-summary)
- [Data Model](#data-model)
- [Installation & Build](#installation--build)
- [Configuration](#configuration)
- [Primary Commands](#primary-commands)
- [Bunkers](#bunkers)
- [Raids](#raids)
- [Roadmap](#roadmap)
- [Changelog](#changelog)
- [Contributing](#contributing)
- [License](#license)

---
## Key Concept
Players own **Bunkers**: protected 3D modular cubes (16×16×16) expanded over time via XP costs. They queue for short, instanced **Raids** in temporary worlds to gather progression (future economy) and safely extract back to their bunker via an extraction point.

---
## Feature Overview
| Pillar | Status | Notes |
|--------|--------|-------|
| Bunkers (allocation, horizontal expansion, invites) | Stable | Vertical expansion planned (T1) |
| Bunker Protection | Partial | Event listeners for fluids/pistons/explosions pending (T3) |
| Raid Sessions (queue, world gen, extraction, compass, boss bar) | MVP | Refactor + reward hook pending |
| Teams | Runtime only | Persistence migration planned (T8) |
| Player Profiles | Implemented | Economy & reward updates pending |
| Metrics / Observability | Missing | Counters & timers spec defined (T7) |
| Test Harness | Missing | MockBukkit introduction (T6) |
| Economy / Progression | Not started | Will follow reward hook & metrics |

---
## Architecture Summary
**Core Services**
- `ConfigService`: YAML config access + dynamic raid template loading.
- `DatabaseService`: SQLite connection + migrations (1..4 applied) for profiles & bunker schema.
- `BunkerService`: Allocation, invite flow, horizontal cube expansion, teleport, XP cost logic.
- `RaidService` (monolith): Player/template queue, dynamic world creation, spawn safety, extraction unlock, boss bar updates, admin controls.
- `ProfileService`: Player profile CRUD & starter kit flag.
- `TeamService`: In-memory team & invite tracking (no DB yet).

**Planned Decomposition (T4)** splits `RaidService` into queue, world, extraction, and registry sub-services.

---
## Data Model (SQLite)
| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `player_profile` | Player meta progression | uuid (PK), total_xp, currency, unlocks, last_raid_ts |
| `bunker` | Root bunker record | id (PK), owner_uuid UNIQUE, cube_size, cubes_count |
| `bunker_member` | Membership (non-owner) | (bunker_id, member_uuid) |
| `bunker_invite` | Pending invites | expires_at, accepted |
| `bunker_cube` | Spatial cubes | (bunker_id, dx, dy, dz) primary key |

Planned tables: `team`, `team_member`, `raid_outcome`, metrics counters (ephemeral or persisted).

---
## Installation & Build
Prerequisites: JDK 17+, Maven, Paper/Spigot server 1.21.x.

```bash
# Build shaded jar
mvn -q clean package
# Copy plugin
cp target/ExfilCraft-1.0-SNAPSHOT-shaded.jar /path/to/server/plugins/
# First run generates config.yml and data.db
```

To update, replace the jar and (optionally) reload. A full restart recommended after schema migrations.

---
## Configuration
Primary keys (see `config.yml` for full list):
```
bunker:
  worldName: exfil_bunkers
  expansionCubeSize: 16
  expansionCooldownMinutes: 10
  maxCubes: 512
  rules:
    initialHorizontalLimit: 9
raid:
  minPlayers: 1
  maxConcurrentSessions: 10
  durationMinutes: 10
  extraction:
    channelSeconds: 10
    radius: 3.0
  queue:
    autoStart: true
communications:
  raid:
    bossBar: true
```
Raid templates are defined under `raid.templates` with per-template overrides (environment, sizeChunks, duration, structure requirements, extraction open timing, etc.).

---
## Primary Commands
| Command | Description |
|---------|-------------|
| `/bunker` | Info; auto-create bunker on first run |
| `/bunker warp` | Teleport to your bunker (from main world) |
| `/bunker extend [dir]` | Expand bunker (n/s/e/w) horizontally (vertical planned) |
| `/bunker invite <player>` | Invite a player (60m expiry) |
| `/bunker accept <player>` | Accept invite from owner/member |
| `/raid start` | Open raid template GUI & queue |
| `/raid status` | Show current raid or queue status |
| `/raid queue status|leave|available` | Queue management & diagnostics |
| (Admins) `/exfil admin ...` | Force start/end raids, maintenance ops |

Planned: `/raid leave` graceful exit (T9); test harness commands for metrics.

---
## Bunkers
- Each bunker begins as one 16³ cube at configured start Y (default 64).
- Expansion cost = `baseXp * currentCubeCount` (default base 100).
- Cooldown between expansions: 10 minutes.
- Horizontal expansion within adjacency (face-touch) implemented. Vertical expansion & global dimension cap enforcement pending (T1/T2).
- Protection (partial): world border + custom generation. Additional event-level guards to be added.

### Upcoming Bunker Improvements
| Task | Goal |
|------|------|
| T1 Vertical Expansion | Add dy layers up/down respecting 8×8×8 limit |
| T2 Bounds Enforcement | Reject expansions exceeding axis limits |
| T3 Full Protection Listeners | Cancel fluid, piston, pearl, hopper, explosion boundary violations |

---
## Raids
- Temporary world per session (auto-deleted after cleanup delay, default 5s).
- Spawn safety sampling (radial + square fallback) & spawn protection timer.
- Extraction unlock: time-based or conditional (Dragon defeat) based on template.
- Extraction requires channel time inside radius; leaving resets progress.
- Boss bar displays global time remaining & phase highlighting.
- Extraction teleports player to bunker (removing extraction compass) and future reward hook will credit XP/currency.

### Raid Templates
Configurable fields per template: `environment`, `sizeChunks`, `durationMinutes`, `unlockCondition`, required structures, night forcing, dragon presence, center structure requirements, extraction overrides, spawn protection overrides.

### Upcoming Raid Improvements
| Task | Goal |
|------|------|
| T4 Service Decomposition | Maintainability & lower cyclomatic complexity |
| T5 Reward Hook | Persist XP/currency gains on extraction |
| T9 /raid leave | Controlled early exit & penalty logic |
| T7 Metrics | Real-time observability for balancing |

---
## Roadmap
**Milestone A – Phase1 Hardening**: T1, T2, T3, T6 (vertical expansion, full protection, initial tests).

**Milestone B – Raid Modularization**: T4, T5, T9 (service split, reward system, leave command).

**Milestone C – Metrics & Persistence**: T7, T8, T10 (metrics registry, team DB schema, structured logger).

**Milestone D – Progression Kickoff**: Economy accrual, unlock & itemization loops.

---
## Changelog (Human View)
Latest semantic version corresponds to internal spec revision date stamp (not semantic versioning yet). See `PROJECT_GOALS.ai` for machine-readable log.

| Date Version | Highlights |
|--------------|-----------|
| 2025-08-24.5 | Added state machines, protection matrix, decisions log, complexity & quality gates | 
| 2025-08-24.4 | Machine-readable backlog, metrics/events/test plan scaffolding |
| 2025-08-24.3 | Gap matrix, architecture plan, updated risks, schema snapshot |
| Earlier (MVP) | Core bunker allocation, horizontal expansion, raid session lifecycle, extraction compass |

---
## Contributing
1. Fork & create feature branch.
2. Keep changes atomic; update `PROJECT_GOALS.ai` if adding new domain concepts.
3. Run `mvn clean package` before PR.
4. Add/extend unit tests once harness (T6) is merged.
5. Document new config keys in README & ConfigService javadoc.

**Style**: Prefer small focused services; avoid expanding `RaidService` further—move logic into planned sub-services.

---
## License
MIT License © 2025 tsuki. See [LICENSE](LICENSE).

---
## Quick FAQ
**Q: Why another raid plugin?**  Intent is extraction-loop gameplay (risk vs reward) with persistent bunker progression.

**Q: Are worlds reused?**  No; each raid gets a fresh temp world to ensure deterministic cleanup and isolation.

**Q: Vertical bunker layers?**  Scheduled (T1) – stored in existing `bunker_cube` table via dy offset.

**Q: Performance concerns?**  World gen attempts capped; ocean-heavy worlds retried; future metrics will monitor latencies.

---
## Support / Issues
Open a GitHub issue (pending public repo) or attach logs referencing timeline + session ID where applicable.

