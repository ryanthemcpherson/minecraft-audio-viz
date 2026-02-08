# Audio Processing & Beat Detection System

Technical documentation for the minecraft-audio-viz audio analysis pipeline.

---

## Table of Contents

1. [System Overview](#system-overview)
2. [DJ Client Audio Capture](#dj-client-audio-capture)
3. [Client-Side FFT Analysis](#client-side-fft-analysis)
4. [Beat Detection](#beat-detection)
5. [WebSocket Protocol](#websocket-protocol)
6. [VJ Server](#vj-server)
7. [Visualization Patterns](#visualization-patterns)
8. [Presets & Tuning](#presets--tuning)
9. [Data Flow](#data-flow)
10. [Performance](#performance)

---

## System Overview

The audio processing system captures real-time audio on a DJ's machine, performs FFT analysis and beat detection locally, then streams the results to a central VJ server which runs visualization patterns and forwards entity updates to Minecraft and browser clients.

```
┌──────────────────────────────────────────────────────────────────┐
│                     DJ CLIENT (Tauri/Rust)                        │
│                                                                  │
│  WASAPI Loopback ─→ Circular Buffer ─→ FFT (rustfft) ─→ Bands   │
│  (cpal, 48kHz)      (96k samples)      (1024-pt Hann)   Beat    │
│                                                          BPM     │
└──────────────────────────┬───────────────────────────────────────┘
                           │ WebSocket (~60fps)
                           │ dj_audio_frame
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                     VJ SERVER (Python)                            │
│                                                                  │
│  Multi-DJ Management ─→ Pattern Engine ─→ Entity Positions       │
│  Auth / Connect Codes   28 patterns       Normalized (0-1)       │
│  Preset Application     AudioState                               │
└──────────────┬──────────────────────────────┬────────────────────┘
               │                              │
               ▼                              ▼
┌──────────────────────┐       ┌──────────────────────────┐
│  Minecraft Plugin    │       │  Browser Preview         │
│  (WebSocket :8765)   │       │  (WebSocket :8766)       │
│  Display Entities    │       │  Three.js 3D             │
└──────────────────────┘       └──────────────────────────┘
```

---

## DJ Client Audio Capture

The DJ Client is a Tauri desktop app (Rust backend, web frontend) that captures system audio and streams analysis results to the VJ server.

### WASAPI Loopback Capture

Audio is captured via Windows Audio Session API (WASAPI) loopback using the `cpal` crate. This captures the default audio output device, meaning any audio playing on the system is captured.

| Parameter | Value |
|-----------|-------|
| API | WASAPI shared-mode loopback |
| Sample Rate | 48,000 Hz |
| Channels | Mono (multi-channel averaged on capture) |
| Sample Formats | F32, I16, U16 (auto-detected) |
| Buffer Size | 2 seconds (96,000 samples circular buffer) |

### Threading Architecture

The capture system uses dedicated OS threads to avoid blocking the audio callback or the async runtime:

```
┌─────────────────────────────┐
│  CPAL Audio Stream          │  Real-time callback (cannot block)
│  multichannel → mono → buf  │
└─────────────┬───────────────┘
              │ Circular buffer (96k samples)
              ▼
┌─────────────────────────────┐
│  Analysis Thread            │  Dedicated OS thread
│  Every ~10ms:               │
│  1. Read 1024 samples       │
│  2. Hann window + FFT       │
│  3. Band extraction + AGC   │
│  4. Beat detection + BPM    │
│  5. Update AnalysisResult   │
└─────────────┬───────────────┘
              │ Arc<Mutex<AnalysisResult>>
              ▼
┌─────────────────────────────┐
│  Bridge Task (tokio async)  │  ~60fps (16ms interval)
│  Read analysis → JSON       │
│  Send via WebSocket         │
└─────────────────────────────┘
```

### Application Enumeration

The client can list active audio applications via WASAPI session enumeration (COM → `IAudioSessionManager2` → iterate sessions). This is used for UI display purposes; actual audio capture remains system-wide loopback.

---

## Client-Side FFT Analysis

All FFT analysis runs in the DJ Client's Rust analysis thread, producing 5 normalized frequency bands at ~100 Hz update rate.

### FFT Configuration

| Parameter | Value |
|-----------|-------|
| Algorithm | Radix-2 (rustfft crate) |
| Window Function | Hann |
| FFT Size | 1024 samples |
| Frequency Resolution | 46.875 Hz/bin (48000 / 1024) |
| Nyquist Frequency | 24,000 Hz |

### 5-Band Frequency Decomposition

| Band | Name | Frequency Range | FFT Bins | Characteristics |
|------|------|-----------------|----------|-----------------|
| 0 | Bass | 40-250 Hz | ~1-5 | Kick drums, bass guitar, toms |
| 1 | Low-Mid | 250-500 Hz | ~5-11 | Snare body, vocals, warmth |
| 2 | Mid | 500-2000 Hz | ~11-43 | Vocals, lead instruments |
| 3 | High-Mid | 2000-6000 Hz | ~43-128 | Presence, snare crack, clarity |
| 4 | High | 6000-20000 Hz | ~128-512 | Hi-hats, cymbals, air |

> **Note:** Sub-bass (20-40 Hz) is excluded because a 1024-sample FFT at 48 kHz cannot accurately resolve frequencies below ~47 Hz.

### Band Calculation Pipeline

```
1024 samples → Hann Window → FFT → Magnitude Spectrum
    → Sum magnitudes per band bin range
    → Divide by bin count (energy average)
    → Per-band AGC normalization
    → Envelope following (attack/release)
    → Output: 5 values in 0.0-1.0 range
```

### Per-Band AGC (Automatic Gain Control)

Each band maintains an independent running maximum for normalization:

```
if band_value > band_max:
    band_max = band_value                    # Instant peak capture
else:
    band_max *= 0.997                        # Slow decay (~3s to halve at 60fps)
    band_max = max(band_max, 0.001)          # Floor to prevent division by zero

normalized = min(band_value / band_max, 1.0)
```

This ensures quiet bands aren't suppressed by loud ones, and the visualization adapts to different volume levels automatically.

### Envelope Following (Attack/Release)

Asymmetric smoothing applied after normalization for responsive but smooth visuals:

```
if raw > current:
    smoothed = current + (raw - current) * 0.35    # Attack: fast rise
else:
    smoothed = current + (raw - current) * 0.08    # Release: slow decay
```

The attack/release asymmetry creates punchy response to transients with smooth natural decay.

### Peak Calculation

```
peak = max(smoothed_bands[0..5])
```

---

## Beat Detection

Beat detection runs client-side on the bass band with adaptive thresholding.

### Algorithm

```
1. Maintain 60-frame history of bass band values

2. Calculate adaptive threshold:
   average = mean(bass_history)
   threshold = average * beat_threshold      # Default: 1.3

3. Fire beat when ALL conditions met:
   - bass > threshold                        # Above adaptive threshold
   - bass > 0.2                              # Minimum energy gate
   - cooldown == 0                           # Minimum 8 frames since last beat

4. On beat:
   beat_intensity = min((bass - threshold) / max(average, 0.01), 1.0)
   cooldown = 8                              # ~133ms at 60fps
```

### Why Bass-Only Detection?

Kick drums dominate the bass frequencies (40-250 Hz) and anchor the rhythmic foundation in virtually all modern music genres. Detecting beats on the bass band provides the most reliable and musically meaningful beat signal.

### BPM Estimation

BPM is estimated from inter-beat intervals:

1. Record timestamps of detected beats (keep last 20)
2. Calculate intervals between consecutive beats
3. Filter outliers outside 0.2-2.0 seconds (30-300 BPM range)
4. Average valid intervals
5. Convert: `BPM = 60 / average_interval`
6. Clamp final result to 60-200 BPM

Default BPM is 120 when insufficient beat history exists.

---

## WebSocket Protocol

### Authentication

DJs authenticate with the VJ server using one of two methods:

**Connect Code** (primary - for multi-DJ events):
```json
{
  "type": "code_auth",
  "code": "BEAT-7K3M",
  "dj_name": "DJ Spark"
}
```

Connect codes use the format `WORD-XXXX` where WORD is one of 24 memorable words (BEAT, BASS, DROP, WAVE, KICK, SYNC, etc.) and XXXX is 4 random characters (excluding confusables O/0/I/1/L). Codes expire after 30 minutes and are single-use.

**Direct Credentials** (for private/testing):
```json
{
  "type": "dj_auth",
  "dj_id": "tauri_dj",
  "dj_key": "",
  "dj_name": "Local DJ"
}
```

### Audio Frame Message

Sent from DJ Client to VJ Server at ~60 fps:

```json
{
  "type": "dj_audio_frame",
  "seq": 42,
  "bands": [0.8, 0.6, 0.5, 0.3, 0.2],
  "peak": 0.8,
  "beat": true,
  "beat_i": 0.75,
  "bpm": 128.0,
  "ts": 1707234567.891
}
```

| Field | Type | Description |
|-------|------|-------------|
| `seq` | integer | Monotonic frame counter |
| `bands` | float[5] | Normalized frequency bands (0.0-1.0) |
| `peak` | float | Maximum of bands |
| `beat` | boolean | Beat detected this frame |
| `beat_i` | float | Beat intensity (0.0-1.0) |
| `bpm` | float | Estimated tempo |
| `ts` | float | Unix timestamp (seconds.ms) |

### Handshake Sequence

```
Client                              Server
  │                                    │
  │──── code_auth / dj_auth ──────────>│
  │                                    │
  │<──── auth_success ─────────────────│
  │<──── clock_sync_request ───────────│
  │                                    │
  │──── clock_sync_response ──────────>│
  │                                    │
  │  [Connection established]          │
  │                                    │
  │──── dj_audio_frame (60fps) ──────>│
  │──── dj_heartbeat (every 2s) ─────>│
  │<──── heartbeat_ack ────────────────│
```

### Server-to-Client Messages

| Message | Payload | Purpose |
|---------|---------|---------|
| `auth_success` | `dj_id`, `dj_name`, `is_active` | Confirm authentication |
| `auth_error` | `error` | Authentication failure |
| `status_update` | `is_active` | DJ active/inactive toggle |
| `clock_sync_request` | `server_time` | RTT calculation |
| `heartbeat_ack` | `server_time` | Latency measurement |
| `pattern_sync` | `pattern`, `config` | Current pattern info |
| `config_sync` | `entity_count`, `zone` | Zone configuration |
| `preset_sync` | `preset` | Active audio preset |
| `effect_triggered` | `effect` | Particle effect fired |

---

## VJ Server

The VJ Server (`audio_processor/vj_server.py`) is the central hub that receives audio frames from DJs, runs the pattern engine, and broadcasts visualization data to Minecraft and browser clients.

### Multi-DJ Management

- Multiple DJs can connect simultaneously
- Only one DJ is "active" at a time (their audio drives the visualization)
- VJ admin can switch active DJ, manage queue, and trigger effects
- DJs are tracked with connection health, frame rate, and latency metrics

### Message Forwarding

Certain messages from the admin panel are forwarded directly to the Minecraft plugin:

- `set_zone_config`, `set_render_mode`, `set_renderer_backend`
- `set_particle_effect`, `set_particle_config`
- `init_pool`, `cleanup_zone`
- `set_entity_glow`, `set_entity_brightness`
- `set_hologram_config`, `set_particle_viz_config`

### Server-Side Processing

When the VJ server receives a `dj_audio_frame`, it:

1. Updates the DJ's connection state (latency, frame count, bands)
2. If this DJ is active, constructs an `AudioState` from the frame
3. Runs the current visualization pattern's `calculate_entities()`
4. Sends `batch_update` to Minecraft with entity positions
5. Broadcasts the same data to browser preview clients

---

## Visualization Patterns

Patterns transform audio state into entity positions for rendering in Minecraft.

### AudioState

```python
class AudioState:
    bands: List[float]       # 5 frequency band levels (0-1)
    amplitude: float         # Overall amplitude (0-1)
    is_beat: bool            # Beat detected this frame
    beat_intensity: float    # Beat strength (0-1)
    frame: int               # Frame counter
```

### PatternConfig

```python
class PatternConfig:
    entity_count: int = 16   # Number of visualization entities
    zone_size: float = 10.0  # Size of visualization zone
    beat_boost: float = 1.5  # Scale multiplier on beat frames
    base_scale: float = 0.2  # Base entity scale
    max_scale: float = 1.0   # Maximum entity scale
```

### Available Patterns (28)

**Original:**

| ID | Class | Description |
|----|-------|-------------|
| spectrum | StackedTower | Vertical bar equalizer |
| ring | ExpandingSphere | Expanding/contracting sphere |
| wave | DNAHelix | Double helix wave |
| explode | Supernova | Explosive burst on beat |
| columns | FloatingPlatforms | Levitating platforms |
| orbit | AtomModel | Orbiting particles |
| matrix | Fountain | Upward particle fountain |
| heartbeat | BreathingCube | Pulsing cube |

**Immersive:**

| ID | Class | Description |
|----|-------|-------------|
| mushroom | Mushroom | Growing mushroom shape |
| skull | Skull | Skull formation |
| sacred | SacredGeometry | Sacred geometry shapes |
| vortex | Vortex | Spinning vortex tunnel |
| pyramid | Pyramid | Reactive pyramid |
| galaxy | GalaxySpiral | Spiral galaxy arms |
| laser | LaserArray | Laser beam array |

**Geometric/Abstract:**

| ID | Class | Description |
|----|-------|-------------|
| mandala | Mandala | Symmetric mandala |
| tesseract | Tesseract | 4D hypercube projection |
| crystal | CrystalGrowth | Growing crystal structure |

**Cosmic/Space:**

| ID | Class | Description |
|----|-------|-------------|
| blackhole | BlackHole | Gravitational lensing effect |
| nebula | Nebula | Nebula cloud formation |
| wormhole | WormholePortal | Portal tunnel |

**Organic/Nature:**

| ID | Class | Description |
|----|-------|-------------|
| aurora | Aurora | Northern lights curtain |
| ocean | OceanWaves | Ocean wave simulation |
| fireflies | Fireflies | Floating firefly swarm |

**Spectrum Analyzers:**

| ID | Class | Description |
|----|-------|-------------|
| bars | SpectrumBars | Classic bar equalizer |
| tubes | SpectrumTubes | Cylindrical spectrum |
| circle | SpectrumCircle | Circular spectrum display |

### Adding a Pattern

Extend `VisualizationPattern` in `audio_processor/patterns.py`:

```python
class MyPattern(VisualizationPattern):
    def calculate_entities(self, audio_state: AudioState, config: PatternConfig) -> List[EntityData]:
        # Return list of entity positions (0-1 normalized coordinates)
        ...
```

Register in `get_pattern()` and `list_patterns()`.

---

## Presets & Tuning

Audio presets configure the beat detection sensitivity, attack/release dynamics, and per-band weighting. Presets are applied server-side and can be switched via the admin panel.

### Available Presets

| Preset | Attack | Release | Beat Threshold | Beat Sensitivity | Bass Weight | Style |
|--------|--------|---------|----------------|------------------|-------------|-------|
| **auto** | 0.35 | 0.08 | 1.3 | 1.0 | 0.7 | Balanced default, auto-calibration on |
| **edm** | 0.7 | 0.15 | 1.1 | 1.5 | 0.85 | Punchy, heavy bass focus |
| **chill** | 0.25 | 0.05 | 1.6 | 0.7 | 0.5 | Smooth, fewer beats |
| **rock** | 0.5 | 0.12 | 1.3 | 1.2 | 0.65 | Drum-focused |
| **hiphop** | 0.6 | 0.1 | 1.2 | 1.3 | 0.8 | 808 bass focus |
| **classical** | 0.2 | 0.04 | 1.8 | 0.5 | 0.4 | Very smooth, minimal beats |

### Per-Band Sensitivity

Each preset can adjust individual band responsiveness:

| Preset | Bass | Low-Mid | Mid | High-Mid | High |
|--------|------|---------|-----|----------|------|
| auto | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 |
| edm | 1.5 | 0.8 | 0.9 | 1.2 | 1.0 |
| chill | 0.9 | 1.0 | 1.1 | 1.2 | 1.3 |
| rock | 1.2 | 1.0 | 1.0 | 0.9 | 0.8 |
| hiphop | 1.4 | 0.9 | 1.0 | 1.1 | 0.9 |
| classical | 0.8 | 1.0 | 1.2 | 1.3 | 1.4 |

### Parameter Reference

| Parameter | Range | Description |
|-----------|-------|-------------|
| `attack` | 0.0-1.0 | How fast bands rise (higher = snappier) |
| `release` | 0.0-1.0 | How fast bands fall (higher = faster decay) |
| `beat_threshold` | 0.5-3.0 | Multiplier over average for beat detection (lower = more beats) |
| `beat_sensitivity` | 0.0-2.0 | Overall beat response strength |
| `bass_weight` | 0.0-1.0 | Weight of bass in beat detection |
| `agc_max_gain` | 1.0-10.0 | Maximum automatic gain boost |
| `auto_calibrate` | bool | Self-tune parameters based on music |

---

## Data Flow

### End-to-End Frame Flow

```
1. WASAPI captures system audio (48kHz mono)
         │
2. Samples pushed to 2-second circular buffer
         │
3. Analysis thread reads 1024 samples every ~10ms
         │
4. Hann window → FFT → 5-band extraction → AGC → envelope
         │
5. Beat detection on bass band → BPM estimation
         │
6. AnalysisResult shared via Arc<Mutex>
         │
7. Bridge task reads at ~60fps, builds JSON message
         │
8. dj_audio_frame sent via WebSocket to VJ Server (:9000)
         │
9. VJ Server constructs AudioState from frame
         │
10. Pattern engine calculates entity positions (normalized 0-1)
         │
11. batch_update sent to Minecraft (:8765) and browsers (:8766)
         │
12. Minecraft plugin converts to world coordinates via ZoneManager
```

### Minecraft batch_update Message

```json
{
  "type": "batch_update",
  "zone": "main",
  "entities": [
    {
      "id": "block_0",
      "x": 0.5,
      "y": 0.8,
      "z": 0.5,
      "scale": 0.6,
      "band": 0,
      "visible": true
    }
  ],
  "bands": [0.8, 0.6, 0.4, 0.3, 0.2],
  "amplitude": 0.75,
  "is_beat": true,
  "beat_intensity": 0.9
}
```

Entity positions use normalized coordinates (0-1) which the Minecraft plugin's ZoneManager converts to world coordinates based on the configured zone dimensions.

---

## Performance

### Latency Budget

| Stage | Typical Latency |
|-------|-----------------|
| WASAPI loopback capture | 10-20 ms |
| FFT analysis (1024-pt) | < 1 ms |
| WebSocket transmission | 1-5 ms (LAN) |
| VJ Server processing | < 1 ms |
| Minecraft entity update | 0-50 ms (tick-aligned) |
| **Total end-to-end** | **~30-70 ms** |

### DJ Client Threading Model

| Thread/Task | Purpose | Timing |
|-------------|---------|--------|
| CPAL audio callback | Capture → circular buffer | Real-time (hardware-driven) |
| Analysis thread | FFT + beat detection | ~10 ms loop |
| Bridge task (async) | Read analysis → WebSocket send | ~16 ms (60fps) |
| Writer task (async) | Drain message queue to socket | Continuous |
| Reader task (async) | Listen for server messages | Continuous |
| Heartbeat task (async) | Send periodic heartbeats | Every 2 seconds |

### Key Design Decisions

- **Dedicated threads for audio**: CPAL's real-time audio callback cannot block, so FFT runs in a separate OS thread rather than in the callback
- **Arc<Mutex> over channels**: Analysis results are polled (latest-wins), not queued, so a shared mutex is simpler than a channel
- **60fps bridge rate**: Matches typical display refresh and Minecraft's internal timing without overwhelming the network
- **Per-band AGC**: Prevents loud bass from drowning out treble, maintaining visual balance across the frequency spectrum
