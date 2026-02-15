# WebSocket Protocol Reference

MCAV uses WebSocket for all real-time communication between components. Protocol schemas are defined in `protocol/schemas/` and serve as the source of truth for all cross-runtime messages.

Every message requires a `type` field. The `v` (version) field is optional and defaults to `1.0.0`.

## Message Index

### Authentication

| Message | Direction | Description |
|---------|-----------|-------------|
| `dj_auth` | DJ to VJ Server | Direct credential authentication |
| `code_auth` | DJ to VJ Server | Connect-code authentication |

### Audio

| Message | Direction | Description |
|---------|-----------|-------------|
| `dj_audio_frame` | DJ to VJ Server | Audio analysis frame (~60fps) |
| `audio_state` | VJ Server to Clients | Current audio state broadcast |

### Visualization

| Message | Direction | Description |
|---------|-----------|-------------|
| `batch_update` | VJ Server to MC/Browser | Entity position updates |
| `set_pattern` | Admin to VJ Server | Change visualization pattern |
| `set_preset` | Admin to VJ Server | Change audio preset |
| `set_visible` | Admin to MC | Toggle entity visibility |
| `init_pool` | Admin to MC | Initialize entity pool |
| `trigger_effect` | Admin to MC | Trigger particle effect |

### Zone Management

| Message | Direction | Description |
|---------|-----------|-------------|
| `get_zones` | Client to MC | Request zone list |
| `zones` | MC to Client | Zone list response |
| `set_zone_config` | Admin to MC | Update zone configuration |

### Renderer

| Message | Direction | Description |
|---------|-----------|-------------|
| `set_render_mode` | Admin to MC | Switch render mode |
| `set_renderer_backend` | Admin to MC | Switch renderer backend |
| `get_renderer_capabilities` | Client to MC | Query renderer capabilities |
| `renderer_capabilities` | MC to Client | Renderer capabilities response |
| `set_hologram_config` | Admin to MC | Configure hologram settings |

### Health

| Message | Direction | Description |
|---------|-----------|-------------|
| `dj_heartbeat` | DJ to VJ Server | Periodic heartbeat |
| `ping` | Any | Connection health check |
| `pong` | Any | Ping response |
| `state_broadcast` | VJ Server to Browsers | Full state update |
| `error` | Any | Error notification |

### Voice

| Message | Direction | Description |
|---------|-----------|-------------|
| `subscribe_voice` | Client to VJ Server | Subscribe to voice stream |
| `unsubscribe_voice` | Client to VJ Server | Unsubscribe from voice stream |
| `subscribe_voice_ack` | VJ Server to Client | Voice subscription confirmation |

## Authentication

### Connect Code (code_auth)

Primary authentication method for multi-DJ events. Connect codes use the format `WORD-XXXX` where WORD is one of 24 memorable words (BEAT, BASS, DROP, etc.) and XXXX is 4 random alphanumeric characters (excluding confusables O/0/I/1/L). Codes expire after 30 minutes and are single-use.

```json
{
    "type": "code_auth",
    "code": "BEAT-7K3M",
    "dj_name": "DJ Spark"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | `"code_auth"` | Yes | Message type |
| `code` | string | Yes | Connect code (pattern: `^[A-Z]{4}-[A-Z2-9]{4}$`) |
| `dj_name` | string | Yes | Display name for the DJ |

### Direct Credentials (dj_auth)

For private/testing use:

```json
{
    "type": "dj_auth",
    "dj_id": "tauri_dj",
    "dj_key": "",
    "dj_name": "Local DJ"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | `"dj_auth"` | Yes | Message type |
| `dj_id` | string | Yes | DJ identifier |
| `dj_name` | string | Yes | Display name |
| `dj_key` | string | Yes | Authentication key |
| `direct_mode` | boolean | No | Direct Minecraft connection mode |

### Handshake Sequence

```text
Client                              Server
  |                                    |
  |---- code_auth / dj_auth --------->|
  |                                    |
  |<---- auth_success ----------------|
  |<---- clock_sync_request ----------|
  |                                    |
  |---- clock_sync_response --------->|
  |                                    |
  |  [Connection established]          |
  |                                    |
  |---- dj_audio_frame (60fps) ------>|
  |---- dj_heartbeat (every 2s) ----->|
  |<---- heartbeat_ack ---------------|
```

## Audio Frame (dj_audio_frame)

Sent from DJ Client to VJ Server at ~60 fps:

```json
{
    "type": "dj_audio_frame",
    "seq": 42,
    "ts": 1707234567.891,
    "bands": [0.8, 0.6, 0.5, 0.3, 0.2],
    "peak": 0.8,
    "beat": true,
    "beat_i": 0.75,
    "bpm": 128.0,
    "tempo_conf": 0.85,
    "beat_phase": 0.25,
    "i_bass": 0.7,
    "i_kick": true
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `seq` | integer | Yes | Monotonic frame counter |
| `ts` | float | Yes | Unix timestamp (seconds.ms) |
| `bands` | float[5] | Yes | Normalized frequency bands (0.0-1.0) |
| `peak` | float | Yes | Maximum of bands |
| `beat` | boolean | Yes | Beat detected this frame |
| `beat_i` | float | Yes | Beat intensity (0.0-1.0) |
| `bpm` | float | Yes | Estimated tempo |
| `tempo_conf` | float | No | BPM confidence (0.0-1.0) |
| `beat_phase` | float | No | Current position in beat cycle (0.0-1.0) |
| `i_bass` | float | No | Instantaneous bass level |
| `i_kick` | boolean | No | Instantaneous kick detection |

## Batch Update (batch_update)

Sent from VJ Server to Minecraft plugin and browser clients:

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
            "rotation": 45.0,
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

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `zone` | string | Yes | Target visualization zone |
| `entities` | EntityUpdate[] | Yes | Array of entity position updates |
| `particles` | ParticleSpawn[] | No | Array of particle spawn requests |
| `bands` | float[5] | No | Current frequency bands |
| `amplitude` | float | No | Overall amplitude |
| `is_beat` | boolean | No | Beat detected |
| `beat_intensity` | float | No | Beat strength |

### EntityUpdate

| Field | Type | Required | Range | Description |
|-------|------|----------|-------|-------------|
| `id` | string | Yes | -- | Unique entity identifier |
| `x` | float | No | 0-1 | X position (normalized) |
| `y` | float | No | 0-1 | Y position (normalized) |
| `z` | float | No | 0-1 | Z position (normalized) |
| `scale` | float | No | 0-4 | Entity scale |
| `rotation` | float | No | -- | Rotation in degrees |
| `band` | integer | No | 0-4 | Frequency band for coloring |
| `visible` | boolean | No | -- | Visibility toggle |
| `text` | string | No | -- | Text content (for text displays) |
| `material` | string | No | -- | Block material override |

## Shared Types

### AudioState

```json
{
    "bands": [0.8, 0.6, 0.5, 0.3, 0.2],
    "amplitude": 0.8,
    "is_beat": true,
    "beat_intensity": 0.75,
    "bpm": 128.0,
    "frame": 42
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `bands` | float[5] | Yes | Normalized frequency bands (0-1) |
| `amplitude` | float | Yes | Overall amplitude |
| `is_beat` | boolean | Yes | Beat detected |
| `beat_intensity` | float | Yes | Beat strength |
| `bpm` | float | No | Estimated BPM |
| `frame` | integer | No | Frame counter |

## Control Messages

### set_pattern

```json
{
    "type": "set_pattern",
    "pattern": "galaxy"
}
```

### set_preset

Named preset:

```json
{
    "type": "set_preset",
    "preset": "edm"
}
```

Custom settings:

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

## Frequency Bands

All band arrays use this 5-element mapping:

| Index | Name | Frequency Range | Typical Content |
|-------|------|-----------------|-----------------|
| 0 | Bass | 40-250 Hz | Kick drums, bass guitar |
| 1 | Low-Mid | 250-500 Hz | Snare body, vocals |
| 2 | Mid | 500-2000 Hz | Vocals, guitars |
| 3 | High-Mid | 2-6 kHz | Presence, clarity |
| 4 | High | 6-20 kHz | Hi-hats, cymbals |
