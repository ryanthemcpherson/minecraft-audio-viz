# Audio Processing & Beat Detection System

Technical documentation for the minecraft-audio-viz audio analysis pipeline.

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Audio Capture](#audio-capture)
3. [Beat Detection Algorithm](#beat-detection-algorithm)
4. [Frequency Band Generation](#frequency-band-generation)
5. [Auto-Gain Control (AGC)](#auto-gain-control-agc)
6. [Auto-Calibration System](#auto-calibration-system)
7. [FFT Integration](#fft-integration)
8. [Presets & Tuning](#presets--tuning)
9. [Data Flow](#data-flow)
10. [Performance Considerations](#performance-considerations)

---

## System Overview

The audio processing system transforms real-time audio input into visualization data for Minecraft and browser-based displays. The pipeline consists of:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           AUDIO INPUT LAYER                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│  Windows WASAPI (pycaw)          │  Optional FFT (pyaudiowpatch)            │
│  - Per-application capture       │  - Real frequency analysis               │
│  - Peak level metering           │  - True band separation                  │
│  - Channel separation            │  - Onset detection enhancement           │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SIGNAL PROCESSING LAYER                              │
├─────────────────────────────────────────────────────────────────────────────┤
│  Auto-Gain Control (AGC)         │  Band Generation                         │
│  - Rolling energy history        │  - 5 frequency bands                     │
│  - Adaptive normalization        │  - Per-band smoothing                    │
│  - Attack/release dynamics       │  - Spring physics simulation             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          BEAT DETECTION LAYER                                │
├─────────────────────────────────────────────────────────────────────────────┤
│  1. Onset Strength Signal (OSS)  │  4. Beat Prediction                      │
│  2. Adaptive Thresholding        │  5. Tempo Confidence                     │
│  3. Tempo Estimation (IOI)       │  6. Phase Tracking                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            OUTPUT LAYER                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│  WebSocket → Minecraft Plugin    │  WebSocket → Browser Preview             │
│  - Entity positions              │  - Same entity data                      │
│  - Audio state for sensors       │  - Band levels                           │
│  - Beat triggers                 │  - Real-time visualization               │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Audio Capture

### WASAPI Per-Application Capture

The system uses Windows Audio Session API (WASAPI) via `pycaw` to capture audio from specific applications:

```python
class AppAudioCapture:
    def __init__(self, app_name: str = "spotify"):
        self.app_name = app_name.lower()
        self._session = None
        self._meter = None
```

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `find_session()` | Locates audio session by process name |
| `get_peak()` | Returns current peak level (0.0-1.0) |
| `get_channel_peaks()` | Returns per-channel levels (stereo) |
| `get_frame()` | Returns complete audio frame with beat detection |

**Audio Frame Structure:**

```python
@dataclass
class AppAudioFrame:
    timestamp: float      # Unix timestamp
    peak: float           # Peak level (0-1)
    channels: List[float] # Per-channel levels [L, R]
    is_beat: bool         # Beat detected this frame
    beat_intensity: float # Beat strength (0-1)
```

---

## Beat Detection Algorithm

The beat detection system implements a multi-stage approach based on research from Percival & Tzanetakis (2014) and modern real-time beat tracking systems.

### Stage 1: Onset Strength Signal (OSS)

The onset strength signal detects sudden increases in energy, which typically correspond to drum hits or note onsets.

```python
# Half-wave rectified spectral flux (only positive changes)
bass_flux = max(0, bass_energy - self._prev_bass_energy)
full_flux = max(0, energy - self._prev_energy)

# Combined onset strength (bass-weighted for kick detection)
onset_strength = (bass_flux * 0.7) + (full_flux * 0.3)
```

**Why Half-Wave Rectification?**
- Only positive energy changes matter for onset detection
- Negative changes (energy decreases) are ignored
- This prevents false triggers during decay phases

**Bass Weighting (70/30 split):**
- Kick drums dominate the bass frequencies (60-150 Hz)
- Most music genres anchor beats on bass/kick
- Full spectrum captures snare hits and other transients

### Stage 2: Adaptive Thresholding

Static thresholds fail across different songs and genres. The system uses a dynamic threshold based on recent onset statistics:

```python
recent_onsets = self._onset_history[-60:]  # Last ~1 second at 60fps
mean_onset = statistics.mean(recent_onsets)
std_onset = statistics.stdev(recent_onsets)

# Dynamic threshold: mean + 1.2 standard deviations
threshold = mean_onset + std_onset * 1.2

# Minimum floor to prevent over-triggering in quiet sections
threshold = max(threshold, 0.02)
```

**Threshold Formula:**
```
threshold = μ + (σ × 1.2)
```

Where:
- `μ` = mean onset strength over ~1 second
- `σ` = standard deviation of onset strengths
- `1.2` = sensitivity multiplier (lower = more beats detected)

### Stage 3: Peak Picking (Local Maximum Detection)

A beat is only registered if the current frame is a local maximum:

```python
if len(self._onset_history) >= 5:
    window = self._onset_history[-5:]
    current = window[-1]
    is_local_max = current >= max(window[:-1])
    is_onset = current > threshold and is_local_max
```

This prevents multiple triggers for a single beat that spans several frames.

### Stage 4: Tempo Estimation via Inter-Onset Intervals (IOI)

The system estimates tempo by analyzing the time intervals between detected beats:

```python
if len(self._beat_times) >= 4:
    intervals = []
    for i in range(1, len(self._beat_times)):
        interval = self._beat_times[i] - self._beat_times[i-1]
        # Only consider reasonable intervals (60-200 BPM range)
        if 0.3 < interval < 1.0:
            intervals.append(interval)

    if len(intervals) >= 3:
        # Median for robustness against outliers
        median_interval = statistics.median(intervals)
        new_tempo = 60.0 / median_interval

        # Exponential smoothing
        alpha = 0.15
        self._estimated_tempo = (1 - alpha) * self._estimated_tempo + alpha * new_tempo
```

**Why Median Instead of Mean?**
- Outliers (missed beats, double-triggers) heavily skew the mean
- Median provides robust center estimation
- Example: intervals [0.5, 0.5, 0.5, 2.0] → mean=0.875, median=0.5

**BPM Calculation:**
```
BPM = 60 / median_interval_seconds
```

### Stage 5: Tempo Confidence

Confidence is calculated from the consistency of beat intervals:

```python
if len(intervals) >= 3:
    interval_std = statistics.stdev(intervals)
    # Lower std = more consistent = higher confidence
    self._tempo_confidence = max(0, min(1, 1.0 - interval_std * 5))
```

| Interval Std Dev | Confidence | Interpretation |
|------------------|------------|----------------|
| 0.00 | 1.0 | Perfect consistency |
| 0.10 | 0.5 | Moderate consistency |
| 0.20+ | 0.0 | Poor/inconsistent |

### Stage 6: Beat Prediction (Gap Filling)

When tempo confidence is high, the system can predict expected beats to fill gaps where detection failed:

```python
if self._tempo_confidence > 0.5 and not is_beat:
    beat_period = 60.0 / self._estimated_tempo
    time_since_last = current_time - self._last_beat_time

    # Calculate phase position
    expected_beats = time_since_last / beat_period
    fractional = expected_beats - int(expected_beats)

    # If near a beat boundary (within 10% of period)
    if fractional < 0.1 or fractional > 0.9:
        # Only predict if some onset activity present
        if onset_strength > mean_onset * 0.8:
            is_beat = True
            intensity = 0.6  # Lower intensity for predicted beats
```

**Phase Tracking Visualization:**
```
Time →  |-------|-------|-------|-------|
Beats   ↓       ↓       ?       ↓
                        ↑
                  Predicted (weak onset detected near expected time)
```

---

## Frequency Band Generation

When FFT is unavailable, the system generates synthetic frequency bands from peak energy:

### Band Configuration

| Band | Name | Frequency Range | Characteristics |
|------|------|-----------------|-----------------|
| 0 | Sub-Bass | 20-60 Hz | Sustained, powerful, slow decay |
| 1 | Bass | 60-250 Hz | Kick drums, bass instruments |
| 2 | Low-Mid | 250-500 Hz | Body, warmth |
| 3 | Mid | 500-2000 Hz | Vocals, lead instruments |
| 4 | Upper-Mid | 2000-4000 Hz | Presence, clarity |
| 5 | High | 4000-20000 Hz | Air, sparkle, transients |

### Band Generation Pipeline

```
┌─────────────────┐
│   Raw Peak      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Auto-Gain      │ ← Rolling history normalization
│  Control        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Transient      │ ← Peak delta detection
│  Detection      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Per-Band       │ ← Different weights per frequency
│  Calculation    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Phase          │ ← Organic movement via sine waves
│  Modulation     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Adaptive       │ ← Per-band history normalization
│  Normalization  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Energy Decay   │ ← Peak hold with per-band decay rates
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Beat Response  │ ← Per-band beat boost
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Temporal       │ ← Attack/release smoothing
│  Smoothing      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Spring         │ ← Physical simulation for natural motion
│  Physics        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Output         │ ← Soft ceiling compression
│  Clamping       │
└─────────────────┘
```

### Temporal Smoothing (Attack/Release)

```python
if target > self._smoothed_bands[i]:
    smooth_factor = self._smooth_attack   # Fast (0.35)
else:
    smooth_factor = self._smooth_release  # Slow (0.08)

self._smoothed_bands[i] += (target - self._smoothed_bands[i]) * smooth_factor
```

**Exponential Moving Average (EMA):**
```
new_value = old_value + (target - old_value) × α
```

Where `α` is the smoothing factor:
- Higher α = faster response
- Attack > Release creates punchy response with smooth decay

### Spring Physics Simulation

Adds natural, organic motion to band levels:

```python
spring = 50.0 + (i * 10)   # Spring constant (50-100)
damping = 6.0 + (i * 0.5)  # Damping factor (6-8.5)
dt = 0.016                  # Time step (~60fps)

displacement = self._smoothed_bands[i] - self._prev_bands[i]
force = spring * displacement - damping * self._band_velocities[i]

self._band_velocities[i] += force * dt
new_value = self._prev_bands[i] + self._band_velocities[i] * dt
```

**Spring Equation:**
```
F = -kx - cv
```
Where:
- `k` = spring constant (stiffness)
- `x` = displacement from equilibrium
- `c` = damping coefficient
- `v` = velocity

---

## Auto-Gain Control (AGC)

AGC ensures consistent output levels regardless of source volume:

```python
def _update_agc(self, peak: float) -> float:
    # Add to rolling history
    self._energy_history.append(peak)

    # Calculate statistics
    rolling_p90 = sorted(self._energy_history)[int(len(self._energy_history) * 0.9)]
    reference = max(rolling_p90, rolling_avg * 1.2, 0.05)

    # Calculate ideal gain
    ideal_gain = self._agc_target / reference  # Target = 0.85
    ideal_gain = clamp(ideal_gain, self._agc_min_gain, self._agc_max_gain)

    # Attack/release smoothing
    if ideal_gain > self._agc_gain:
        self._agc_gain += (ideal_gain - self._agc_gain) * 0.15  # Attack
    else:
        self._agc_gain += (ideal_gain - self._agc_gain) * 0.008 # Release

    return min(1.0, peak * self._agc_gain)
```

**AGC Parameters:**

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `agc_target` | 0.85 | Target output level (85% of range) |
| `agc_min_gain` | 1.0 | Never reduce below input |
| `agc_max_gain` | 8.0 | Maximum boost multiplier |
| `agc_attack` | 0.15 | Fast response to loud signals |
| `agc_release` | 0.008 | Slow reduction to prevent pumping |

**Why 90th Percentile?**
- Ignores occasional loud spikes
- More stable than using maximum
- Prevents gain from being pulled down by rare peaks

---

## Auto-Calibration System

The auto-calibration system adapts parameters based on detected music characteristics:

### Music Analysis

```python
def _auto_calibrate(self, energy: float, is_beat: bool):
    # 1. Track energy variance
    self._music_variance = min(1.0, energy_stdev / max(0.01, energy_mean) * 2)

    # 2. Estimate tempo from beat intervals
    if intervals:
        avg_interval_frames = statistics.median(intervals)
        self._estimated_bpm = 60 * 60 / avg_interval_frames  # At 60fps
```

### Parameter Adjustment Rules

| BPM Range | Attack | Release | Music Type |
|-----------|--------|---------|------------|
| > 150 | 0.6 | 0.12 | Fast (EDM, D&B) |
| 110-150 | 0.4 | 0.08 | Medium (House, Pop) |
| < 110 | 0.25 | 0.05 | Slow (Chill, Ambient) |

**Beat Threshold Adjustment:**
```python
target_threshold = 1.1 + self._music_variance * 0.5  # Range: 1.1 to 1.6
```
- High variance music → Higher threshold (prevent false positives)
- Low variance music → Lower threshold (more sensitivity)

---

## FFT Integration

When available, real FFT analysis provides true frequency separation:

### HybridAnalyzer

The `HybridAnalyzer` class provides:
- Real-time FFT via WASAPI loopback
- Configurable FFT size (512-4096 samples)
- Low-latency mode option
- Fallback to synthetic bands

**Latency Comparison:**

| Mode | FFT Size | FFT Latency | Hop Interval | Total |
|------|----------|-------------|--------------|-------|
| Normal | 2048 | ~43ms | ~11ms | ~55ms |
| Low-Latency | 1024 | ~21ms | ~5ms | ~26ms |

### Band Mapping (FFT)

```
FFT Bins → Frequency Bands
├── Bins 1-4:   Sub-Bass (20-60 Hz)
├── Bins 5-16:  Bass (60-250 Hz)
├── Bins 17-32: Low-Mid (250-500 Hz)
├── Bins 33-64: Mid (500-2000 Hz)
├── Bins 65-128: Upper-Mid (2000-4000 Hz)
└── Bins 129+:  High (4000+ Hz)
```

---

## Presets & Tuning

### Available Presets

```python
PRESETS = {
    "auto": {
        "attack": 0.35,
        "release": 0.08,
        "beat_threshold": 1.3,
        "agc_max_gain": 8.0,
        "beat_sensitivity": 1.0,
        "bass_weight": 0.7,
        "auto_calibrate": True
    },
    "edm": {
        "attack": 0.7,           # Punchy response
        "release": 0.15,         # Quick decay
        "beat_threshold": 1.1,   # More beats detected
        "agc_max_gain": 10.0,    # Higher dynamic range
        "beat_sensitivity": 1.5, # Stronger beat response
        "bass_weight": 0.85,     # Heavy kick focus
        "auto_calibrate": False
    },
    "chill": {
        "attack": 0.25,          # Smooth response
        "release": 0.05,         # Gentle decay
        "beat_threshold": 1.6,   # Fewer beats
        "agc_max_gain": 6.0,
        "beat_sensitivity": 0.7,
        "bass_weight": 0.5,      # Balanced
        "auto_calibrate": False
    },
    "rock": {
        "attack": 0.5,
        "release": 0.12,
        "beat_threshold": 1.3,
        "agc_max_gain": 8.0,
        "beat_sensitivity": 1.2,
        "bass_weight": 0.65,     # Drum-focused
        "auto_calibrate": False
    }
}
```

### Per-Band Sensitivity

Each frequency band can be individually adjusted:

```python
"band_sensitivity": [1.0, 1.0, 1.0, 1.0, 1.0, 1.0]
#                    Sub  Bass Low  Mid  High Air
```

EDM Example:
```python
"band_sensitivity": [1.5, 1.3, 0.8, 0.9, 1.2, 1.0]
# Boosts sub-bass and bass for kick detection
```

---

## Data Flow

### Per-Frame Processing

```python
# 1. Capture audio frame
frame = self.capture.get_frame()

# 2. Try FFT analysis (if available)
fft_result = self.fft_analyzer.analyze() if self.fft_analyzer else None

# 3. Generate bands (FFT or synthetic)
if fft_result and self._using_fft:
    bands = fft_result.bands
else:
    bands = self._generate_bands(frame.peak, frame.channels, frame.is_beat)

# 4. Auto-calibrate (if enabled)
if self._auto_calibrate_enabled:
    self._auto_calibrate(frame.peak, frame.is_beat)

# 5. Calculate entity positions
audio_state = AudioState(
    bands=bands,
    amplitude=frame.peak,
    is_beat=frame.is_beat,
    beat_intensity=frame.beat_intensity,
    frame=self._frame_count
)
entities = self._current_pattern.calculate_entities(audio_state)

# 6. Send to outputs
await self.viz_client.batch_update_fast(zone, entities, audio=audio_data)
await self._broadcast_state(entities, bands, frame)
```

### Audio State Structure

```python
class AudioState:
    bands: List[float]      # 5 frequency band levels (0-1)
    amplitude: float        # Overall amplitude (0-1)
    is_beat: bool           # Beat detected this frame
    beat_intensity: float   # Beat strength (0-1)
    frame: int              # Frame counter
```

---

## Performance Considerations

### Target Frame Rate: 60 FPS (16.67ms per frame)

**Processing Budget:**

| Component | Time Budget | Actual |
|-----------|-------------|--------|
| Audio capture | 1ms | ~0.5ms |
| FFT (if enabled) | 5ms | 2-4ms |
| Band generation | 2ms | ~1ms |
| Beat detection | 1ms | ~0.5ms |
| Pattern calculation | 3ms | 1-2ms |
| WebSocket send | 2ms | ~1ms |
| **Total** | ~14ms | ~8ms |

### Optimization Techniques

1. **Rolling Windows**: Fixed-size deques prevent memory growth
2. **Vectorized Math**: NumPy for FFT operations
3. **Fire-and-Forget**: `batch_update_fast()` doesn't wait for response
4. **Median Filtering**: O(n log n) but more robust than iterative
5. **Early Exit**: Skip processing for silent/inactive sections

### Memory Usage

| Buffer | Size | Purpose |
|--------|------|---------|
| `_onset_history` | 256 frames | ~4 seconds for beat detection |
| `_beat_times` | 10 seconds | Tempo estimation window |
| `_energy_history` | 90 frames | AGC normalization |
| `_band_histories` | 45×6 frames | Per-band normalization |

---

## References

1. Percival, G., & Tzanetakis, G. (2014). "Streamlined Tempo Estimation Based on Autocorrelation and Cross-correlation With Pulses"
2. Bello, J. P., et al. (2005). "A Tutorial on Onset Detection in Music Signals"
3. Dixon, S. (2006). "Onset Detection Revisited"
4. Böck, S., & Schedl, M. (2011). "Enhanced Beat Tracking With Context-Aware Neural Networks"
