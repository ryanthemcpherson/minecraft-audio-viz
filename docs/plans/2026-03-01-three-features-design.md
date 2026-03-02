# Three Features Design — Sequencing, Metrics, Connection State

**Date**: 2026-03-01
**Features**: Multi-Stage Sequencing, In-Game Performance Metrics, DJ Connection State Indicator

---

## Feature 1: Multi-Stage Sequencing

### Goal

Allow server admins to define ordered playlists of pattern assignments that rotate automatically across zones/stages, with loop and shuffle playback modes.

### Data Model

```
Sequence
  ├── name: String
  ├── steps: List<SequenceStep>
  ├── mode: LOOP | SHUFFLE | ONCE
  ├── defaultStepDuration: int (ticks, default 600 = 30s)
  ├── defaultTransition: String ("crossfade")
  └── defaultTransitionDuration: int (ticks, default 20 = 1s)

SequenceStep
  ├── zonePatterns: Map<String zoneName, String patternId>
  ├── durationTicks: int (0 = use sequence default)
  ├── transitionId: String (null = use sequence default)
  └── transitionDuration: int (0 = use sequence default)
```

### Playback Engine

`SequencePlayer` — one per active sequence, ticked from main thread:
- Counts down current step duration
- On step complete: advances index (or shuffles/loops), calls `BitmapPatternManager.setPattern()` with configured transition for each zone in the step
- Shuffle: Fisher-Yates shuffle of step indices, no immediate repeat of last step
- ONCE mode: stops after final step, zones keep last pattern
- Zone sync: all zone pattern switches in a step fire on the same tick

### Commands

- `/av sequence start <name> [stage]` — start a sequence on a stage (or all active zones)
- `/av sequence stop [stage]` — stop active sequence
- `/av sequence skip` — skip to next step
- `/av sequence list` — list available sequences
- `/av sequence create <name>` — create empty sequence (then edit via GUI or YAML)

### Persistence

- Saved to `plugins/AudioViz/sequences.yml`
- Format mirrors Stage persistence: YAML with sequence name as top-level key
- Loaded on plugin enable, hot-reloadable via `/av sequence reload`

### GUI (stretch)

- In-game inventory menu for building sequences (pick patterns per zone per step)
- Not required for v1 — YAML editing + commands is sufficient

### New Files

| File | Purpose |
|-|-|
| `sequence/Sequence.java` | Data model — name, steps, mode, defaults |
| `sequence/SequenceStep.java` | Single step — zone→pattern map, duration, transition overrides |
| `sequence/SequencePlayer.java` | Playback engine — tick, advance, shuffle, loop |
| `sequence/SequenceManager.java` | CRUD, persistence, active players, command handling |
| `sequence/PlaybackMode.java` | Enum: LOOP, SHUFFLE, ONCE |

### Modified Files

| File | Change |
|-|-|
| `AudioVizPlugin.java` | Initialize SequenceManager, register in tick loop |
| `AudioVizCommand.java` | Add `/av sequence` subcommands |
| `BitmapPatternManager.java` | No changes needed — existing `setPattern()` API is sufficient |

---

## Feature 2: In-Game Performance Metrics

### Goal

Give server admins real-time visibility into MCAV system health without leaving the game.

### Display

Scoreboard sidebar toggled per-player via `/av metrics`:

```
--- MCAV Metrics ---
DJ: Connected (128 BPM)
Audio FPS: 47/50
Entities: 312/500
Active Zones: 3
Render: 4.2ms/tick
Sequences: 1 playing
```

### Implementation

- `MetricsDisplay` collects stats from existing managers each update cycle
- Updates every 20 ticks (1 second) — uses Bukkit scoreboard API
- Scoreboard objective created per-player on toggle, removed on toggle-off or disconnect
- Permission: `audioviz.metrics` (default: op)

### Data Sources

| Metric | Source |
|-|-|
| DJ connection + BPM | `VizWebSocketServer` — connected client count, latest `AudioState.bpm` |
| Audio FPS | `BitmapPatternManager` — frames received in last second |
| Entity count | `EntityPoolManager` — total active / max pool size |
| Active zones | `ZoneManager` — count of activated zones |
| Render time | `BitmapPatternManager` — avg render duration from `BitmapRenderTiming` |
| Sequences | `SequenceManager` — count of active players |

### New Files

| File | Purpose |
|-|-|
| `metrics/MetricsDisplay.java` | Scoreboard management, stat collection, per-player toggle |

### Modified Files

| File | Change |
|-|-|
| `AudioVizPlugin.java` | Initialize MetricsDisplay, schedule update task |
| `AudioVizCommand.java` | Add `/av metrics` toggle |

---

## Feature 3: DJ Connection State Indicator

### Goal

Automatic visual feedback in-game when DJ connection state changes, so admins and players know the system is alive.

### Behavior

| Event | Visual | Message |
|-|-|-|
| DJ connects | Green particle burst around active zones | Action bar: "DJ connected (preset)" |
| DJ disconnects | Red particle flash on zone borders | Action bar: "DJ disconnected" |
| Audio stale (>3s no frames) | Zones dim to 30% brightness | Action bar: "Audio signal lost" |
| Audio resumes | Brightness restores smoothly | Action bar: "Audio signal restored" |

### Implementation

- `ConnectionStateListener` hooks into `VizWebSocketServer` connection events
- Audio staleness: `BitmapPatternManager` tracks `lastFrameTimestamp` per zone, checks each tick
- Dimming: uses existing `EffectsProcessor.setBrightness()` — smooth ramp over 20 ticks
- Particles: uses existing `ParticleVisualizationManager` for burst effects
- Action bar messages: `player.sendActionBar()` to all players within zone render distance

### Staleness Detection

```
if (System.currentTimeMillis() - lastFrameMs > 3000) {
    if (!stale) { stale = true; rampBrightnessTo(0.3); sendActionBar("Audio signal lost"); }
} else {
    if (stale) { stale = false; rampBrightnessTo(1.0); sendActionBar("Audio signal restored"); }
}
```

### New Files

| File | Purpose |
|-|-|
| `connection/ConnectionStateListener.java` | WebSocket event hooks, staleness detection, visual feedback |

### Modified Files

| File | Change |
|-|-|
| `AudioVizPlugin.java` | Initialize ConnectionStateListener |
| `VizWebSocketServer.java` | Fire connect/disconnect events to listener |
| `BitmapPatternManager.java` | Track lastFrameTimestamp, call staleness check in tick loop |

---

## Implementation Order

1. **DJ Connection State** (Small, ~1-2 hours) — quick win, immediate polish
2. **In-Game Metrics** (Small, ~2-3 hours) — quick win, admin QoL
3. **Multi-Stage Sequencing** (Medium-Large, ~6-8 hours) — flagship feature

Features 1 and 2 can be built in parallel. Feature 3 depends on no other feature but benefits from the metrics display being available (sequences metric).

## Testing Strategy

- Connection State: unit test staleness detection logic (pure timer math), manual test particles
- Metrics: unit test stat collection, manual test scoreboard display
- Sequencing: unit test SequencePlayer (step advancement, loop, shuffle, zone sync), unit test persistence serialization, manual test in-game
