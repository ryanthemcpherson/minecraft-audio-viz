# Audio Processing & Beat Detection

Technical documentation for the MCAV audio analysis pipeline.

## System Overview

The audio processing system captures real-time audio on a DJ's machine, performs FFT analysis and beat detection locally, then streams the results to a central VJ server which runs visualization patterns and forwards entity updates to Minecraft and browser clients.

```text
DJ CLIENT (Tauri/Rust)
  WASAPI Loopback --> Circular Buffer --> FFT (rustfft) --> Bands + Beat + BPM
                                                              |
                                          WebSocket (~60fps)  |
                                          dj_audio_frame      v
VJ SERVER (Python)
  Multi-DJ Management --> Pattern Engine --> Entity Positions (normalized 0-1)
                                              |
                             +----------------+----------------+
                             v                                 v
                    Minecraft Plugin                    Browser Preview
                    (WebSocket :8765)                   (WebSocket :8766)
                    Display Entities                    Three.js 3D
```

## DJ Client Audio Capture

The DJ Client is a Tauri desktop app (Rust backend, web frontend) that captures system audio and streams analysis results to the VJ server.

### WASAPI Loopback Capture

Audio is captured via Windows Audio Session API (WASAPI) loopback using the `cpal` crate.

| Parameter | Value |
|-----------|-------|
| API | WASAPI shared-mode loopback |
| Sample Rate | 48,000 Hz |
| Channels | Mono (multi-channel averaged on capture) |
| Sample Formats | F32, I16, U16 (auto-detected) |
| Buffer Size | 2 seconds (96,000 samples circular buffer) |

### Threading Architecture

```text
CPAL Audio Stream           Real-time callback (cannot block)
  multichannel --> mono --> circular buffer (96k samples)
                              |
Analysis Thread             Dedicated OS thread (~10ms loop)
  Read 1024 samples --> Hann window + FFT --> Band extraction + AGC
  --> Beat detection + BPM --> Update AnalysisResult
                              |
                              v  Arc<Mutex<AnalysisResult>>
Bridge Task (tokio async)   ~60fps (16ms interval)
  Read analysis --> JSON --> Send via WebSocket
```

## FFT Analysis

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

!!! note
    Sub-bass (20-40 Hz) is excluded because a 1024-sample FFT at 48 kHz cannot accurately resolve frequencies below ~47 Hz.

### Band Calculation Pipeline

```text
1024 samples --> Hann Window --> FFT --> Magnitude Spectrum
  --> Sum magnitudes per band bin range
  --> Divide by bin count (energy average)
  --> Per-band AGC normalization
  --> Envelope following (attack/release)
  --> Output: 5 values in 0.0-1.0 range
```

### Per-Band AGC (Automatic Gain Control)

Each band maintains an independent running maximum for normalization:

```text
if band_value > band_max:
    band_max = band_value                    # Instant peak capture
else:
    band_max *= 0.997                        # Slow decay (~3s to halve at 60fps)
    band_max = max(band_max, 0.001)          # Floor to prevent division by zero

normalized = min(band_value / band_max, 1.0)
```

This ensures quiet bands aren't suppressed by loud ones, and the visualization adapts to different volume levels automatically.

### Envelope Following (Attack/Release)

Asymmetric smoothing applied after normalization:

```text
if raw > current:
    smoothed = current + (raw - current) * 0.35    # Attack: fast rise
else:
    smoothed = current + (raw - current) * 0.08    # Release: slow decay
```

The attack/release asymmetry creates punchy response to transients with smooth natural decay.

## Beat Detection

Beat detection runs client-side on the bass band with adaptive thresholding.

### Algorithm

```text
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

### BPM Estimation

BPM is estimated from inter-beat intervals:

1. Record timestamps of detected beats (keep last 20)
2. Calculate intervals between consecutive beats
3. Filter outliers outside 0.2-2.0 seconds (30-300 BPM range)
4. Average valid intervals
5. Convert: `BPM = 60 / average_interval`
6. Clamp final result to 60-200 BPM

Default BPM is 120 when insufficient beat history exists.

## End-to-End Data Flow

```text
 1. WASAPI captures system audio (48kHz mono)
 2. Samples pushed to 2-second circular buffer
 3. Analysis thread reads 1024 samples every ~10ms
 4. Hann window --> FFT --> 5-band extraction --> AGC --> envelope
 5. Beat detection on bass band --> BPM estimation
 6. AnalysisResult shared via Arc<Mutex>
 7. Bridge task reads at ~60fps, builds JSON message
 8. dj_audio_frame sent via WebSocket to VJ Server (:9000)
 9. VJ Server constructs AudioState from frame
10. Pattern engine calculates entity positions (normalized 0-1)
11. batch_update sent to Minecraft (:8765) and browsers (:8766)
12. Minecraft plugin converts to world coordinates via ZoneManager
```

## Latency Budget

| Stage | Typical Latency |
|-------|-----------------|
| WASAPI loopback capture | 10-20 ms |
| FFT analysis (1024-pt) | < 1 ms |
| WebSocket transmission | 1-5 ms (LAN) |
| VJ Server processing | < 1 ms |
| Minecraft entity update | 0-50 ms (tick-aligned) |
| **Total end-to-end** | **~30-70 ms** |

## Threading Model

| Thread/Task | Purpose | Timing |
|-------------|---------|--------|
| CPAL audio callback | Capture to circular buffer | Real-time (hardware-driven) |
| Analysis thread | FFT + beat detection | ~10 ms loop |
| Bridge task (async) | Read analysis, send WebSocket | ~16 ms (60fps) |
| Writer task (async) | Drain message queue to socket | Continuous |
| Reader task (async) | Listen for server messages | Continuous |
| Heartbeat task (async) | Send periodic heartbeats | Every 2 seconds |
