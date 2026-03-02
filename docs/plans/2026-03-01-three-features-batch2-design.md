# Three Features Design (Batch 2) — Beat-Sync, Latency, Recording

**Date**: 2026-03-01
**Features**: Beat-Sync Fine-Tuning, Latency Monitoring, Recording & Replay

---

## Feature 1: Beat-Sync Fine-Tuning

### Goal

In-game controls to manually override BPM, adjust phase offset, and tune beat detection thresholds — so visualizations snap perfectly to the music even when automatic detection drifts.

### Current State

- BPM, beat phase, and confidence arrive from DJ client FFT (read-only in plugin)
- `BeatProjectionUtil` synthesizes beats using hardcoded constants (PHASE_EDGE_WINDOW=0.12, MIN_PHASE_ASSIST_CONFIDENCE=0.60)
- `BeatEventManager` triggers effects with hardcoded thresholds (KICK=0.4, SNARE=0.35)
- No manual override exists

### Design

**`BeatSyncConfig`** — per-zone overridable settings:

| Field | Type | Default | Purpose |
|-|-|-|-|
| manualBpm | double | 0 (auto) | Override auto-detected BPM |
| phaseOffset | double | 0.0 | Shift beat phase ±0.5 |
| beatThresholdMultiplier | double | 1.0 | Scale beat sensitivity (0.5=more, 2.0=less) |
| projectionEnabled | boolean | true | Toggle BeatProjectionUtil synthesis |

**Commands:**
- `/av beatsync bpm <value|auto>` — set manual BPM or return to auto
- `/av beatsync phase <offset>` — adjust phase offset (-0.5 to 0.5)
- `/av beatsync sensitivity <multiplier>` — scale beat threshold
- `/av beatsync status` — show current sync state

**Integration:** `BitmapPatternManager.updateAudioState()` applies BeatSyncConfig overrides (phase offset, manual BPM) to incoming AudioState before forwarding to patterns.

**Persistence:** `beatsync.yml`, loaded on enable.

### New Files

| File | Purpose |
|-|-|
| `beatsync/BeatSyncConfig.java` | Per-zone config: manual BPM, phase offset, sensitivity |
| `beatsync/BeatSyncManager.java` | CRUD, persistence, audio state modification |
| `beatsync/BeatSyncConfigTest.java` | Tests for config defaults and override logic |

### Modified Files

| File | Change |
|-|-|
| `AudioVizPlugin.java` | Init BeatSyncManager |
| `AudioVizCommand.java` | Add `/av beatsync` commands |
| `BitmapPatternManager.java` | Apply BeatSyncConfig overrides in `updateAudioState()` |

---

## Feature 2: Latency Monitoring

### Goal

Track end-to-end latency across the pipeline (DJ → Plugin → Render) and surface it in metrics scoreboard and `/av latency` command.

### Current State

- DJ client sends `ts` (Unix timestamp) and `seq` (sequence number) on every audio frame
- `VizWebSocketServer` tracks `lastPongTime` and has `createPingMessage()` with timestamp
- `BitmapRenderTimer` tracks per-zone render duration (nanosecond precision)
- VJ server has placeholder fields for latency metrics in `VizStateBroadcast`
- `MetricsDisplay` shows render time but no network latency

### Design

**`LatencyTracker`** — three segments with rolling window (100 samples):

| Segment | Measurement |
|-|-|
| Network (DJ → Plugin) | `localMs - (frame.ts * 1000)` with clock offset correction |
| Processing (queue → tick) | Enqueue timestamp to tick consumption |
| Render (tick → entity update) | Already tracked by `BitmapRenderTimer` |

Stats per segment: avg, p95, max, jitter (stddev).

**Clock skew:** On first frame, compute `clockOffset = localMs - (frame.ts * 1000)`, subtract from subsequent. Negative values clamped to 0.

**`/av latency` output:**
```
--- MCAV Latency ---
Network:    12ms avg / 18ms p95
Processing: 2ms avg / 4ms p95
Render:     3.2ms avg / 5.1ms p95
Total:      17ms avg / 27ms p95
Jitter:     ±3ms
Frames:     47/50 FPS (6% drop)
```

**MetricsDisplay:** Add `Latency: 17ms` line to scoreboard.

### New Files

| File | Purpose |
|-|-|
| `latency/LatencyTracker.java` | Rolling window stats, clock offset, per-segment tracking |
| `latency/LatencyTrackerTest.java` | Tests for rolling stats, p95, jitter, clock offset clamping |

### Modified Files

| File | Change |
|-|-|
| `AudioVizPlugin.java` | Init LatencyTracker |
| `AudioVizCommand.java` | Add `/av latency` command |
| `MetricsDisplay.java` | Add latency line to scoreboard |
| `MessageHandler.java` | Record network latency on audio frame receive |
| `BitmapPatternManager.java` | Record processing latency in tick loop |

---

## Feature 3: Recording & Replay

### Goal

Record visualization sessions (audio state per tick) and replay without a live DJ — for showcasing, debugging, and offline demos.

### Current State

- `BitmapPatternManager` ticks at 20 TPS with `AudioState` per zone
- `AudioState` holds 5 bands, amplitude, beat, beatIntensity, tempoConfidence, beatPhase
- Entity updates flow through `EntityPoolManager.batchUpdateEntities()`
- No recording infrastructure exists

### Design

**What gets recorded:** AudioState snapshots per tick (NOT entity positions). On replay, audio state feeds the pattern engine which re-renders. ~50 bytes/frame, 5 min = ~300KB.

**`RecordingFrame`** — single tick snapshot:
- audioState: bands[5], amplitude, beat, beatIntensity, beatPhase, bpm, tempoConfidence
- tickIndex: frame counter

**`Recording`** — complete session:
- Header: name, patternId per zone, startTimestamp, tickRate, frameCount
- Frames: packed binary array of RecordingFrame

**Storage:** Binary files in `plugins/AudioViz/recordings/`. Not YAML — too verbose for frame data.

**`RecordingManager`:**
- `startRecording(name)` — captures AudioState each tick
- `stopRecording()` → saves to binary file
- `startPlayback(name)` — loads file, feeds AudioState at 20 TPS
- `stopPlayback()` — restores live audio feed
- During playback, live DJ audio frames are ignored

**Commands:**
- `/av recording start <name>` — begin recording
- `/av recording stop` — stop and save
- `/av recording play <name>` — replay a recording
- `/av recording list` — list saved recordings
- `/av recording delete <name>` — delete a recording

**Cross-feature interaction:**
- ConnectionStateListener: playback sets "replaying" flag, staleness detection skipped
- MetricsDisplay: shows "Recording" or "Playing: name"
- SequenceManager: independent (sequences can run during playback)

### New Files

| File | Purpose |
|-|-|
| `recording/RecordingFrame.java` | Single frame record (AudioState snapshot) |
| `recording/Recording.java` | Header + frames, binary serialization |
| `recording/RecordingManager.java` | Start/stop recording, playback, file I/O |
| `recording/RecordingFrameTest.java` | Tests for serialization round-trip |
| `recording/RecordingTest.java` | Tests for header, frame count, duration |

### Modified Files

| File | Change |
|-|-|
| `AudioVizPlugin.java` | Init RecordingManager |
| `AudioVizCommand.java` | Add `/av recording` commands |
| `MetricsDisplay.java` | Show recording/playback status |
| `BitmapPatternManager.java` | Hook recording capture in tick, accept playback audio override |
| `ConnectionStateListener.java` | Skip staleness during playback |

---

## Implementation Order

1. **Beat-Sync Fine-Tuning** (Small, ~2-3 hours) — modifies audio pipeline, others may depend on override mechanism
2. **Latency Monitoring** (Small, ~2-3 hours) — independent, enriches existing metrics
3. **Recording & Replay** (Medium, ~4-6 hours) — builds on audio pipeline, uses override pattern from beat-sync

## Testing Strategy

- Beat-Sync: unit test config defaults/overrides, phase offset clamping, BPM override math
- Latency: unit test rolling window (avg, p95, jitter), clock offset clamping, negative latency handling
- Recording: unit test frame serialization round-trip, header parsing, duration calculation, binary format integrity
