# Audio Presets

Audio presets configure beat detection sensitivity, attack/release dynamics, and per-band weighting. Presets are applied server-side and can be switched via the admin panel or WebSocket API.

## Available Presets

| Preset | Style | Attack | Release | Beat Threshold | Beat Sensitivity | Bass Weight |
|--------|-------|--------|---------|----------------|------------------|-------------|
| **auto** | Balanced default | 0.35 | 0.08 | 1.3 | 1.0 | 0.7 |
| **edm** | Punchy, heavy bass | 0.7 | 0.15 | 1.1 | 1.5 | 0.85 |
| **chill** | Smooth, fewer beats | 0.25 | 0.05 | 1.6 | 0.7 | 0.5 |
| **rock** | Drum-focused | 0.5 | 0.12 | 1.3 | 1.2 | 0.65 |
| **hiphop** | 808 bass focus | 0.6 | 0.1 | 1.2 | 1.3 | 0.8 |
| **classical** | Very smooth, minimal beats | 0.2 | 0.04 | 1.8 | 0.5 | 0.4 |

## Per-Band Sensitivity

Each preset adjusts individual band responsiveness. Values above 1.0 boost the band, below 1.0 attenuate it.

| Preset | Bass | Low-Mid | Mid | High-Mid | High |
|--------|------|---------|-----|----------|------|
| auto | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 |
| edm | 1.5 | 0.8 | 0.9 | 1.2 | 1.0 |
| chill | 0.9 | 1.0 | 1.1 | 1.2 | 1.3 |
| rock | 1.2 | 1.0 | 1.0 | 0.9 | 0.8 |
| hiphop | 1.4 | 0.9 | 1.0 | 1.1 | 0.9 |
| classical | 0.8 | 1.0 | 1.2 | 1.3 | 1.4 |

## Parameter Reference

| Parameter | Range | Description |
|-----------|-------|-------------|
| `attack` | 0.0-1.0 | How fast bands rise (higher = snappier) |
| `release` | 0.0-1.0 | How fast bands fall (higher = faster decay) |
| `beat_threshold` | 0.5-3.0 | Multiplier over average for beat detection (lower = more beats) |
| `beat_sensitivity` | 0.0-2.0 | Overall beat response strength |
| `bass_weight` | 0.0-1.0 | Weight of bass in beat detection |
| `agc_max_gain` | 1.0-10.0 | Maximum automatic gain boost |
| `auto_calibrate` | bool | Self-tune parameters based on music |

## Preset Details

### auto (Default)

The `auto` preset is the recommended starting point. It uses balanced settings with auto-calibration enabled, meaning it will self-tune parameters based on the music being played.

```python
AudioConfig(
    attack=0.35, release=0.08,
    beat_threshold=1.3, beat_sensitivity=1.0,
    bass_weight=0.7, agc_max_gain=8.0,
    band_sensitivity=[1.0, 1.0, 1.0, 1.0, 1.0],
    auto_calibrate=True,
)
```

### edm

Optimized for electronic dance music with fast attack for punchy beats, lower beat threshold for more frequent beat detection, and heavy bass focus for EDM kicks.

```python
AudioConfig(
    attack=0.7, release=0.15,
    beat_threshold=1.1, beat_sensitivity=1.5,
    bass_weight=0.85, agc_max_gain=10.0,
    band_sensitivity=[1.5, 0.8, 0.9, 1.2, 1.0],
    auto_calibrate=False,
)
```

### chill

Smooth response for ambient, lo-fi, and downtempo music. Slower attack, higher beat threshold (fewer beats detected), and balanced frequency emphasis.

```python
AudioConfig(
    attack=0.25, release=0.05,
    beat_threshold=1.6, beat_sensitivity=0.7,
    bass_weight=0.5, agc_max_gain=6.0,
    band_sensitivity=[0.9, 1.0, 1.1, 1.2, 1.3],
    auto_calibrate=False,
)
```

### rock

Drum-focused preset for rock and metal. Moderate attack/release with emphasis on bass and low-mid frequencies where drums and guitar riffs live.

### hiphop

Strong 808 bass focus with punchy attack. Emphasizes the sub-bass and bass frequencies that drive hip-hop and trap beats.

### classical

Very smooth response with minimal beat detection. High beat threshold means only the strongest transients trigger beats. Emphasizes mid and high frequencies where orchestral instruments shine.

## Setting a Preset

### Via Admin Panel

Select the preset from the dropdown in the admin panel at `http://localhost:8081`.

### Via WebSocket

Send a `set_preset` message:

```json
{
    "type": "set_preset",
    "preset": "edm"
}
```

### Custom Settings via WebSocket

Send custom parameters instead of a named preset:

```json
{
    "type": "set_preset",
    "preset": {
        "attack": 0.5,
        "release": 0.1,
        "beat_threshold": 1.2
    }
}
```

## Adding a New Preset

Presets are defined in both the Rust DJ client and the VJ server. Add to both for full support.

**VJ Server** (`vj_server/config.py`):

```python
"mypreset": AudioConfig(
    attack=0.5, release=0.1,
    beat_threshold=1.3, beat_sensitivity=1.0,
    bass_weight=0.7, agc_max_gain=8.0,
    band_sensitivity=[1.0, 1.0, 1.0, 1.0, 1.0],
    auto_calibrate=False,
)
```

**DJ Client** (`dj_client/src-tauri/src/audio/fft.rs`):

```rust
AudioPreset {
    name: "mypreset",
    attack: 0.5,
    release: 0.1,
    beat_threshold: 1.3,
    ..
}
```
