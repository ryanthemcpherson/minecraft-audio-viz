 MCAV Refactoring Architecture

This document describes three major refactoring efforts for the Minecraft Audio Visualizer (MCAV) project. Each section is self-contained and can be executed independently, though executing them in the recommended order minimizes merge conflicts.

**Recommended execution order:** Part 1 -> Part 2 -> Part 3

---

## Table of Contents

- [Part 1: Decompose app_capture.py](#part-1-decompose-app_capturepy)
- [Part 2: Decompose vj_server.py](#part-2-decompose-vj_serverpy)
- [Part 3: Wire RendererBackend into the Message Pipeline](#part-3-wire-rendererbackend-into-the-message-pipeline)
- [Global Risk Matrix](#global-risk-matrix)

---

## Part 1: Decompose app_capture.py

### Current State

`audio_processor/app_capture.py` is approximately **2,329 lines** and contains at least 7 distinct responsibilities:

1. **PRESETS dict** (lines 88-131) -- Pre-tuned audio settings for genres (auto, edm, chill, rock)
2. **AppAudioFrame dataclass** (line 134) -- Audio frame data structure
3. **AubioBeatDetector class** (lines 144-287) -- Production-grade beat detection wrapping the aubio C library
4. **AppAudioCapture class** (lines ~290-600) -- WASAPI audio capture using pycaw, with energy-based beat detection fallback
5. **MultiDirectoryHandler / run_http_server** (lines 790-823) -- HTTP static file server
6. **AppCaptureAgent class** (lines 826-~2200) -- The monolith (~1,400 lines) containing AGC, band generation, effects engine, WebSocket broadcast, pattern management, timeline/cue playback, control message handling, and auto-calibration
7. **Legacy CLI main()** (lines ~2200-2329) -- Standalone entry point (superseded by cli.py)

### External Callers

Files that import from `app_capture.py`:

| File | Imports |
|------|---------|
| `audio_processor/cli.py` | `AppCaptureAgent`, `AppAudioCapture` |
| `audio_processor/tests/test_beat_detection.py` | `AppAudioCapture`, `AubioBeatDetector` |
| `audio_processor/tests/test_protocol_contract.py` | `AppCaptureAgent` |

### Known Coupling Issues

- **Private attribute mutation:** `AppCaptureAgent._apply_preset()` at line 1185 directly mutates `self.capture._beat_threshold` and `self.capture._bass_weight` (private attributes of `AppAudioCapture`). This must be replaced with public setter methods or a configuration object.
- **Auto-calibration** at line 1296 also mutates `self.capture._beat_threshold` directly.
- **Duplicate code:** `_apply_effects()` (lines 1087-1162) is duplicated nearly identically in `vj_server.py`. Both should import from a shared module.

### Proposed Module Structure

```
audio_processor/
    app_capture.py          # FACADE: re-exports all public names for backward compat
    audio_frame.py          # AppAudioFrame dataclass
    presets.py              # PRESETS dict (move from app_capture + merge with config.py)
    aubio_detector.py       # AubioBeatDetector class
    audio_capture.py        # AppAudioCapture class (WASAPI capture + energy beat detection)
    http_server.py          # MultiDirectoryHandler + run_http_server()
    band_generator.py       # AGC, band smoothing, band normalization, auto-calibration
    effects_engine.py       # _apply_effects(), _trigger_effect() (shared with vj_server)
    broadcast_server.py     # WebSocket broadcast fan-out (shared concern with vj_server)
    capture_agent.py        # Slim AppCaptureAgent orchestrator
```

### Public Interfaces Per Module

#### audio_frame.py (~20 LOC)

```python
@dataclass
class AppAudioFrame:
    timestamp: float
    peak: float
    channels: List[float]
    is_beat: bool
    beat_intensity: float
```

#### presets.py (~80 LOC)

```python
# Merged from app_capture.PRESETS and config.AudioConfig
PRESETS: Dict[str, dict]  # Genre presets (auto, edm, chill, rock)

def get_preset(name: str) -> dict: ...
def list_presets() -> List[str]: ...
```

**Note:** Currently `app_capture.py` has its own `PRESETS` dict (lines 88-131) and `config.py` has a separate `PRESETS` dict using `AudioConfig` dataclass. These should be unified in this module. The `config.AudioConfig` dataclass can remain in `config.py` but the preset lookup should live here.

#### aubio_detector.py (~150 LOC)

```python
class AubioBeatDetector:
    def __init__(self, sample_rate=44100, hop_size=512, buf_size=1024): ...
    def process(self, samples: np.ndarray) -> Tuple[bool, float, float]: ...
    # Returns (is_beat, bpm, confidence)

    # Internal methods:
    def _correct_octave_errors(self, bpm, reference_bpm) -> float: ...
    def _estimate_tempo_from_histogram(self, bpm_values) -> float: ...
    def _analyze_beat_intervals(self) -> Tuple[float, float]: ...
```

#### audio_capture.py (~350 LOC)

```python
class AppAudioCapture:
    def __init__(self, app_name: str): ...
    def start(self) -> bool: ...
    def stop(self): ...
    def get_audio_data(self) -> Optional[np.ndarray]: ...

    # NEW public setters (replace private attr mutation):
    def set_beat_threshold(self, value: float): ...
    def set_bass_weight(self, value: float): ...

    @property
    def beat_threshold(self) -> float: ...
    @property
    def bass_weight(self) -> float: ...
```

#### http_server.py (~40 LOC)

```python
class MultiDirectoryHandler(http.server.SimpleHTTPRequestHandler):
    directory_map: Dict[str, str] = {}
    def translate_path(self, path: str) -> str: ...

def run_http_server(port: int, directory: str): ...
```

#### band_generator.py (~300 LOC)

Extracts the AGC system, band smoothing (spring physics), band normalization, per-band sensitivity, and auto-calibration from `AppCaptureAgent`.

```python
class BandGenerator:
    def __init__(self, band_count: int = 5): ...
    def update(self, peak: float, raw_bands: Optional[List[float]], is_beat: bool) -> List[float]: ...
    def apply_preset(self, preset: dict): ...
    def set_agc_max_gain(self, value: float): ...
    def set_attack(self, value: float): ...
    def set_release(self, value: float): ...
    def set_band_sensitivity(self, band: int, value: float): ...

    @property
    def smoothed_bands(self) -> List[float]: ...
    @property
    def agc_gain(self) -> float: ...

class AutoCalibrator:
    def __init__(self, band_generator: BandGenerator): ...
    def update(self, energy: float, is_beat: bool): ...
    def enable(self): ...
    def disable(self): ...
```

#### effects_engine.py (~120 LOC)

Shared between `app_capture` and `vj_server`. Eliminates the current code duplication.

```python
class EffectsEngine:
    def __init__(self): ...
    def trigger(self, effect_type: str, intensity: float, duration_ms: int): ...
    def cancel(self, effect_type: str): ...
    def apply(self, entities: List[dict], bands: List[float], frame: int) -> List[dict]: ...
    def cleanup_expired(self): ...

    @property
    def active_effects(self) -> Dict[str, dict]: ...
    @property
    def has_blackout(self) -> bool: ...
    @property
    def has_freeze(self) -> bool: ...
```

#### broadcast_server.py (~80 LOC)

WebSocket fan-out broadcast shared by both `capture_agent.py` and `vj_server/`.

```python
class BroadcastServer:
    def __init__(self): ...
    async def add_client(self, websocket): ...
    async def remove_client(self, websocket): ...
    async def broadcast(self, message: dict): ...
    async def broadcast_raw(self, raw_json: str): ...

    @property
    def client_count(self) -> int: ...
```

#### capture_agent.py (~400 LOC)

The slim orchestrator. Composes all of the above modules.

```python
class AppCaptureAgent:
    def __init__(self, app_name, minecraft_host, ..., **kwargs): ...
    async def start(self): ...
    def stop(self): ...

    # Delegates:
    # self.capture -> AudioCapture (audio_capture.py)
    # self.bands -> BandGenerator (band_generator.py)
    # self.effects -> EffectsEngine (effects_engine.py)
    # self.broadcast -> BroadcastServer (broadcast_server.py)
    # self.pattern -> current pattern instance
    # self.timeline -> TimelineEngine (optional)
```

### Backward Compatibility Facade

The original `app_capture.py` becomes a thin re-export module:

```python
# audio_processor/app_capture.py (FACADE - backward compat)
from audio_processor.audio_frame import AppAudioFrame
from audio_processor.aubio_detector import AubioBeatDetector
from audio_processor.audio_capture import AppAudioCapture
from audio_processor.capture_agent import AppCaptureAgent
from audio_processor.presets import PRESETS

__all__ = [
    'AppAudioFrame',
    'AubioBeatDetector',
    'AppAudioCapture',
    'AppCaptureAgent',
    'PRESETS',
]
```

### Dependency Diagram

```
capture_agent.py
    |
    +-- audio_capture.py (WASAPI via pycaw)
    |       |
    |       +-- audio_frame.py
    |       +-- aubio_detector.py (optional, depends on aubio C library)
    |
    +-- band_generator.py
    |       |
    |       +-- presets.py
    |       +-- numpy
    |
    +-- effects_engine.py  <-- SHARED with vj_server
    |
    +-- broadcast_server.py  <-- SHARED with vj_server
    |       |
    |       +-- websockets
    |
    +-- http_server.py (stdlib only)
    |
    +-- patterns.py (existing, unchanged)
    +-- timeline/ (existing, unchanged)
    +-- python_client/viz_client.py (existing, unchanged)
```

### Implementation Phases

#### Phase 1: Extract Pure Data (Low Risk)

1. Create `audio_frame.py` with `AppAudioFrame` dataclass.
2. Create `presets.py` with `PRESETS` dict. Keep `config.py` `AudioConfig` as-is for now; `presets.py` imports from `config` where needed.
3. Update `app_capture.py` to import from new modules.
4. Run existing tests: `pytest audio_processor/tests/test_beat_detection.py`

**Estimated diff:** +120 / -100 LOC (net +20)

#### Phase 2: Extract AubioBeatDetector (Low Risk)

1. Move `AubioBeatDetector` to `aubio_detector.py`.
2. Update imports in `app_capture.py`, `test_beat_detection.py`.
3. Verify optional import guard (`HAS_AUBIO`) still works.

**Estimated diff:** +160 / -150 LOC (net +10)

#### Phase 3: Extract AppAudioCapture (Medium Risk)

1. Move `AppAudioCapture` to `audio_capture.py`.
2. Add public `set_beat_threshold()` and `set_bass_weight()` methods.
3. Replace all `self.capture._beat_threshold` mutations in `AppCaptureAgent` with `self.capture.set_beat_threshold()`.
4. Replace all `self.capture._bass_weight` mutations similarly.
5. Update `test_beat_detection.py` imports.

**Estimated diff:** +380 / -340 LOC (net +40)
**Risk:** Medium -- private attribute access is scattered in `_apply_preset()` (line 1185), `_auto_calibrate()` (line 1296), `_apply_audio_setting()` (line 1339), and `get_state` snapshot (line 1448).

#### Phase 4: Extract Effects Engine and Broadcast Server (Medium Risk)

1. Create `effects_engine.py` with `EffectsEngine` class.
2. Create `broadcast_server.py` with `BroadcastServer` class.
3. Replace `AppCaptureAgent._apply_effects()` with `self.effects.apply()`.
4. Replace `AppCaptureAgent._trigger_effect()` with `self.effects.trigger()`.
5. Replace manual broadcast client management with `self.broadcast`.
6. **Also update `vj_server.py`** to import from `effects_engine.py` -- eliminates the duplicated `_apply_effects()` method.

**Estimated diff:** +250 / -200 LOC (net +50)
**Risk:** Medium -- the effects engine touches the hot path (called every frame at ~60 Hz).

#### Phase 5: Extract Band Generator (High Risk)

1. Create `band_generator.py` with `BandGenerator` and `AutoCalibrator`.
2. Move AGC state (`_agc_*`), smoothed band state (`_smoothed_bands`, `_smooth_attack`, `_smooth_release`), band simulation state (`_prev_bands`, `_band_velocities`, `_band_energy`, `_band_drift`), band normalization, and per-band sensitivity from `AppCaptureAgent`.
3. Move `_auto_calibrate()` to `AutoCalibrator`.
4. This is the largest extraction -- approximately 400 lines of tightly coupled state.

**Estimated diff:** +350 / -300 LOC (net +50)
**Risk:** High -- this is the core audio processing pipeline. Numeric precision differences or state ordering changes could cause audible/visible artifacts. Requires side-by-side A/B testing.

#### Phase 6: Slim Down AppCaptureAgent (Low Risk)

1. Move slimmed-down `AppCaptureAgent` to `capture_agent.py`.
2. Convert `app_capture.py` to facade module with re-exports.
3. Delete legacy `main()` function from `app_capture.py` (CLI is in `cli.py`).
4. Verify `cli.py` still works via `audioviz` and `audioviz --test`.

**Estimated diff:** +420 / -1400 LOC (net -980)

### Rollback Strategy

Each phase is a separate commit. To rollback any phase:

1. `git revert <phase-N-commit>` reverts cleanly because each phase only modifies imports and moves code.
2. The facade in `app_capture.py` ensures that even partial rollback leaves all external callers working.
3. If Phase 5 (band generator) causes audio artifacts, revert it independently -- all other phases remain valid.

### Risk Assessment

| Phase | Risk | Mitigation |
|-------|------|------------|
| 1 (data) | Low | Pure data move, no logic changes |
| 2 (aubio) | Low | Self-contained class, optional dependency |
| 3 (capture) | Medium | Replace private attr mutation with public API |
| 4 (effects) | Medium | Hot path code; run perf benchmark before/after |
| 5 (bands) | High | Core audio pipeline; A/B test with recording |
| 6 (slim) | Low | Only moves code and creates facade |

---

## Part 2: Decompose vj_server.py

### Current State

`audio_processor/vj_server.py` is approximately **1,905 lines** and contains at least 12 distinct responsibilities:

1. **ConnectCode dataclass** (lines 89-120) -- Temporary auth codes for DJ client
2. **DJConnection dataclass** (lines 123-168) -- Connected DJ state with clock sync
3. **DJAuthConfig dataclass** (lines 171-217) -- Credential management
4. **MultiDirectoryHandler / run_http_server** (lines 220-254) -- HTTP server (duplicated from app_capture)
5. **VJServer class** containing:
   - DJ connection management (~200 LOC)
   - DJ authentication (credential + code-based) (~150 LOC)
   - Clock synchronization (NTP-style) (~40 LOC)
   - Browser client handler with ~30 message types in a 356-line if/elif chain (~400 LOC)
   - Minecraft forwarding logic (~80 LOC)
   - Pattern/preset management (~100 LOC)
   - Effects engine (~100 LOC, duplicated from app_capture)
   - Broadcast fan-out to browsers + DJs (~100 LOC)
   - Health metrics and heartbeat (~60 LOC)
   - Main visualization loop (~150 LOC)
   - Timeline engine integration (~100 LOC)

### External Callers

| File | Imports |
|------|---------|
| `audio_processor/cli.py` | `VJServer`, `DJAuthConfig` |

### Known Bugs

- **`cli.py` line 492:** `auth_config = DJAuthConfig.from_dict(auth_data)` calls a method that does not exist on `DJAuthConfig`. The class only has `DJAuthConfig.load(filepath)`. This is a runtime crash waiting to happen. The fix is to either add `from_dict()` to `DJAuthConfig` or change the call to use `DJAuthConfig.load()`.

### Proposed Package Structure

```
audio_processor/
    vj_server/
        __init__.py         # Re-exports VJServer, DJAuthConfig, etc.
        server.py           # Slim VJServer orchestrator
        dj_manager.py       # DJ connection lifecycle, auth, clock sync
        dj_models.py        # ConnectCode, DJConnection, DJAuthConfig dataclasses
        browser_dispatch.py # Dispatch table for browser message handling
        mc_forwarder.py     # Minecraft forwarding logic
        viz_loop.py         # Main visualization tick loop
        state.py            # ServerState dataclass (consolidated mutable state)
        health.py           # Connection health metrics and heartbeat
        timeline_handler.py # Timeline message handling (conditional on HAS_TIMELINE)
    vj_server.py            # FACADE: re-exports for backward compat
```

Modules shared with `app_capture` decomposition:

- `effects_engine.py` (from Part 1 Phase 4)
- `broadcast_server.py` (from Part 1 Phase 4)
- `http_server.py` (from Part 1)

### Public Interfaces Per Module

#### dj_models.py (~120 LOC)

```python
@dataclass
class ConnectCode:
    code: str
    created_at: float
    expires_at: float
    used: bool
    def is_valid(self) -> bool: ...
    @staticmethod
    def generate(ttl_minutes: int = 30) -> 'ConnectCode': ...

@dataclass
class DJConnection:
    dj_id: str
    dj_name: str
    websocket: Any
    # ... all existing fields
    def update_fps(self): ...

@dataclass
class DJAuthConfig:
    djs: Dict[str, dict]
    vj_operators: Dict[str, dict]
    @classmethod
    def load(cls, filepath: str) -> 'DJAuthConfig': ...
    @classmethod
    def from_dict(cls, data: dict) -> 'DJAuthConfig': ...  # NEW - fixes cli.py bug
    def verify_dj(self, dj_id: str, key: str) -> Optional[dict]: ...
    def verify_vj(self, vj_id: str, key: str) -> Optional[dict]: ...
```

#### state.py (~80 LOC)

Consolidates all mutable server state into a single dataclass to make state dependencies explicit.

```python
@dataclass
class ServerState:
    # DJ management
    djs: Dict[str, DJConnection]
    active_dj_id: Optional[str]
    dj_queue: List[str]
    dj_lock: asyncio.Lock

    # Connect codes
    connect_codes: Dict[str, ConnectCode]

    # Pattern
    pattern_name: str
    pattern_config: PatternConfig
    current_pattern: Any  # VisualizationPattern

    # Effects
    effects: EffectsEngine
    band_sensitivity: List[float]

    # Counters
    frame_count: int
    running: bool

    # Toggle state
    blackout: bool
    freeze: bool
```

#### dj_manager.py (~250 LOC)

```python
class DJManager:
    def __init__(self, state: ServerState, auth_config: DJAuthConfig, require_auth: bool): ...
    async def handle_connection(self, websocket): ...
    async def handle_frame(self, dj: DJConnection, data: dict): ...
    async def set_active_dj(self, dj_id: str): ...
    async def kick_dj(self, dj_id: str): ...
    def get_roster(self) -> List[dict]: ...

    # Internal:
    async def _authenticate(self, websocket) -> Optional[DJConnection]: ...
    async def _clock_sync(self, dj: DJConnection, websocket): ...
    async def _auto_switch_dj(self): ...
```

#### browser_dispatch.py (~350 LOC)

Replaces the 356-line if/elif chain with a dispatch table.

```python
# Type alias for handler functions
MessageHandler = Callable[[dict, websocket], Awaitable[None]]

class BrowserDispatch:
    def __init__(self, state: ServerState, dj_manager: DJManager, mc: McForwarder): ...

    # Dispatch table (populated in __init__):
    _handlers: Dict[str, MessageHandler]

    async def dispatch(self, msg_type: str, data: dict, websocket) -> None: ...

    # Individual handlers become methods:
    async def _handle_set_pattern(self, data, ws): ...
    async def _handle_set_active_dj(self, data, ws): ...
    async def _handle_kick_dj(self, data, ws): ...
    async def _handle_generate_connect_code(self, data, ws): ...
    async def _handle_set_entity_count(self, data, ws): ...
    async def _handle_set_preset(self, data, ws): ...
    async def _handle_trigger_effect(self, data, ws): ...
    # ... etc for all ~30 message types
```

The dispatch table initialization:

```python
def __init__(self, ...):
    self._handlers = {
        'ping': self._handle_ping,
        'pong': self._handle_pong,
        'get_state': self._handle_get_state,
        'set_pattern': self._handle_set_pattern,
        'set_active_dj': self._handle_set_active_dj,
        'kick_dj': self._handle_kick_dj,
        'generate_connect_code': self._handle_generate_connect_code,
        'get_connect_codes': self._handle_get_connect_codes,
        'revoke_connect_code': self._handle_revoke_connect_code,
        'get_dj_roster': self._handle_get_dj_roster,
        'set_entity_count': self._handle_set_entity_count,
        'set_block_count': self._handle_set_entity_count,  # alias
        'set_zone': self._handle_set_zone,
        'set_preset': self._handle_set_preset,
        'set_band_sensitivity': self._handle_set_band_sensitivity,
        'set_audio_setting': self._handle_set_audio_setting,
        'get_zones': self._handle_get_zones,
        'get_zone': self._handle_get_zone,
        'trigger_effect': self._handle_trigger_effect,
        # Timeline handlers (conditional)
        'timeline_play': self._handle_timeline_play,
        'timeline_pause': self._handle_timeline_pause,
        'timeline_stop': self._handle_timeline_stop,
        'timeline_seek': self._handle_timeline_seek,
        'load_show': self._handle_load_show,
        'save_show': self._handle_save_show,
        'list_shows': self._handle_list_shows,
        'new_show': self._handle_new_show,
        'create_demo_show': self._handle_create_demo_show,
        'fire_cue': self._handle_fire_cue,
        'arm_cue': self._handle_arm_cue,
        'add_cue': self._handle_add_cue,
        'update_cue': self._handle_update_cue,
        'delete_cue': self._handle_delete_cue,
        # Forwarded to Minecraft
        'set_render_mode': self._handle_mc_forward,
        'set_zone_config': self._handle_mc_forward,
        'set_renderer_backend': self._handle_mc_forward,
        'get_renderer_capabilities': self._handle_mc_forward,
        'renderer_capabilities': self._handle_mc_forward,
        'set_particle_viz_config': self._handle_mc_forward,
        'set_particle_config': self._handle_mc_forward,
        'set_particle_effect': self._handle_mc_forward,
        'set_hologram_config': self._handle_mc_forward,
        'set_entity_glow': self._handle_mc_forward,
        'set_entity_brightness': self._handle_mc_forward,
    }
```

#### mc_forwarder.py (~100 LOC)

```python
class McForwarder:
    def __init__(self, viz_client_getter: Callable[[], Optional[VizClient]]): ...
    async def forward(self, data: dict, reply_ws=None): ...
    async def init_pool(self, zone: str, count: int, material: str = "SEA_LANTERN"): ...
    async def cleanup_zone(self, zone: str): ...
    async def set_visible(self, zone: str, visible: bool): ...
    async def send_batch(self, zone: str, entities: List[dict], audio_meta: dict): ...
```

#### viz_loop.py (~200 LOC)

The main visualization tick loop extracted from VJServer.

```python
class VizLoop:
    def __init__(self, state: ServerState, mc: McForwarder, broadcast: BroadcastServer): ...
    async def tick(self): ...
    async def run(self, interval: float = 1/60): ...
    def stop(self): ...
```

#### health.py (~80 LOC)

```python
class HealthMonitor:
    def __init__(self): ...
    def record_dj_connect(self): ...
    def record_dj_disconnect(self): ...
    def record_browser_connect(self): ...
    def record_browser_disconnect(self): ...
    def record_mc_reconnect(self): ...
    def get_stats(self) -> dict: ...
    async def heartbeat_loop(self, broadcast_clients: Set, interval: float = 15.0): ...
```

#### timeline_handler.py (~120 LOC)

```python
class TimelineHandler:
    """Handles all timeline-related messages. Only active if HAS_TIMELINE is True."""
    def __init__(self, state: ServerState, broadcast: BroadcastServer): ...
    async def handle_play(self, data, ws): ...
    async def handle_pause(self, data, ws): ...
    async def handle_stop(self, data, ws): ...
    async def handle_seek(self, data, ws): ...
    async def handle_load_show(self, data, ws): ...
    async def handle_save_show(self, data, ws): ...
    async def handle_list_shows(self, data, ws): ...
    async def handle_new_show(self, data, ws): ...
    async def handle_fire_cue(self, data, ws): ...
    async def handle_arm_cue(self, data, ws): ...
    async def handle_add_cue(self, data, ws): ...
    async def handle_update_cue(self, data, ws): ...
    async def handle_delete_cue(self, data, ws): ...
```

### Backward Compatibility Facade

```python
# audio_processor/vj_server.py (FACADE)
from audio_processor.vj_server.server import VJServer
from audio_processor.vj_server.dj_models import DJAuthConfig, DJConnection, ConnectCode

__all__ = ['VJServer', 'DJAuthConfig', 'DJConnection', 'ConnectCode']
```

### Dependency Diagram

```
server.py (VJServer orchestrator)
    |
    +-- dj_manager.py
    |       +-- dj_models.py
    |       +-- state.py
    |
    +-- browser_dispatch.py
    |       +-- state.py
    |       +-- dj_manager.py
    |       +-- mc_forwarder.py
    |       +-- timeline_handler.py (optional)
    |
    +-- mc_forwarder.py
    |       +-- python_client/viz_client.py
    |
    +-- viz_loop.py
    |       +-- state.py
    |       +-- mc_forwarder.py
    |       +-- broadcast_server.py (shared, from Part 1)
    |       +-- effects_engine.py (shared, from Part 1)
    |       +-- patterns.py (existing)
    |
    +-- health.py
    |
    +-- http_server.py (shared, from Part 1)
```

### Implementation Phases

#### Phase 1: Extract Data Models (Low Risk)

1. Create `vj_server/` package directory.
2. Create `dj_models.py` with `ConnectCode`, `DJConnection`, `DJAuthConfig`.
3. **Add `DJAuthConfig.from_dict()` class method** -- fixes the `cli.py:492` bug.
4. Create `state.py` with `ServerState` dataclass.
5. Create `__init__.py` with re-exports.
6. Convert `vj_server.py` (top-level) to facade.
7. Verify `cli.py` VJ server startup still works.

**Estimated diff:** +280 / -200 LOC (net +80)

```python
# The fix for the from_dict bug:
@classmethod
def from_dict(cls, data: dict) -> 'DJAuthConfig':
    """Create from a parsed JSON dict (fixes cli.py compatibility)."""
    return cls(
        djs=data.get('djs', {}),
        vj_operators=data.get('vj_operators', {})
    )
```

#### Phase 2: Extract Dispatch Table (Medium Risk)

1. Create `browser_dispatch.py` with dispatch table pattern.
2. Move each `elif msg_type == '...'` block into a named method.
3. Replace the 356-line if/elif chain with `self._dispatch.dispatch(msg_type, data, websocket)`.
4. Create `mc_forwarder.py` for Minecraft forwarding.
5. Create `timeline_handler.py` for timeline messages.

**Estimated diff:** +500 / -400 LOC (net +100)
**Risk:** Medium -- must verify every message type is mapped correctly. Missing a mapping results in silent message drops.

**Mitigation:** Add a catch-all handler that logs unknown message types:
```python
async def dispatch(self, msg_type, data, ws):
    handler = self._handlers.get(msg_type)
    if handler:
        await handler(data, ws)
    else:
        logger.warning(f"Unhandled browser message type: {msg_type}")
```

#### Phase 3: Extract DJ Manager (Medium Risk)

1. Create `dj_manager.py` with `DJManager` class.
2. Move authentication flow (credential + code-based), clock sync, DJ lifecycle from `VJServer`.
3. Move `_handle_dj_connection()`, `_handle_dj_frame()`, `_set_active_dj()`, `_auto_switch_dj()`.

**Estimated diff:** +280 / -250 LOC (net +30)
**Risk:** Medium -- the DJ lock (`_dj_lock`) must be correctly shared between `DJManager` and `BrowserDispatch` (both modify DJ state).

#### Phase 4: Extract Viz Loop and Health (Low Risk)

1. Create `viz_loop.py` with main visualization tick.
2. Create `health.py` with health metrics.
3. Slim down `server.py` to pure orchestration (~200 LOC).

**Estimated diff:** +300 / -280 LOC (net +20)

### Rollback Strategy

1. Each phase is a separate commit.
2. The facade `vj_server.py` ensures `cli.py` always works regardless of internal restructuring.
3. If the dispatch table (Phase 2) introduces message handling regressions, revert to inline if/elif with a single `git revert`.
4. The `DJAuthConfig.from_dict()` fix (Phase 1) can be cherry-picked independently since it is a pure addition.

### Risk Assessment

| Phase | Risk | Mitigation |
|-------|------|------------|
| 1 (models) | Low | Pure data extraction + bug fix |
| 2 (dispatch) | Medium | Audit all message types; add catch-all logging |
| 3 (DJ manager) | Medium | Lock sharing across modules; integration test |
| 4 (viz loop) | Low | Straightforward extraction |

---

## Part 3: Wire RendererBackend into the Message Pipeline

### Current State

The `RendererBackend` interface and its implementations (`DisplayEntitiesBackend`, `ParticlesBackend`) exist in `com.audioviz.render` but are **not connected to the actual message processing pipeline**. All rendering currently goes directly through `EntityPoolManager`:

**Direct EntityPoolManager calls that should route through RendererBackend:**

| Location | Call | Line |
|----------|------|------|
| `MessageQueue.processTick()` | `plugin.getEntityPoolManager().batchUpdateEntities(...)` | 165 |
| `MessageHandler.handleInitPool()` | `plugin.getEntityPoolManager().initializeBlockPool(...)` | 121 |
| `MessageHandler.handleBatchUpdate()` | `pool.batchUpdateEntities(...)` | 216 |
| `MessageHandler.handleUpdateEntity()` | `pool.updateEntityPosition(...)` | 268 |
| `MessageHandler.handleUpdateEntity()` | `pool.updateEntityTransformation(...)` | 273 |
| `MessageHandler.handleUpdateEntity()` | `pool.setEntityVisible(...)` | 277 |
| `MessageHandler.handleUpdateEntity()` | `pool.updateTextContent(...)` | 281 |
| `MessageHandler.handleUpdateEntity()` | `pool.updateBlockMaterial(...)` | 286 |
| `MessageHandler.handleSetVisible()` | `pool.setEntityVisible(...)` | 316, 320 |
| `MessageHandler.handleCleanupZone()` | `plugin.getEntityPoolManager().cleanupZone(...)` | 337 |
| `MessageHandler.handleSetZoneConfig()` | `plugin.getEntityPoolManager().initializeBlockPool(...)` | 434 |
| `MessageHandler.handleSetZoneConfig()` | `plugin.getEntityPoolManager().setZoneBrightness(...)` | 439 |
| `MessageHandler.handleSetZoneConfig()` | `plugin.getEntityPoolManager().setZoneInterpolation(...)` | 444 |
| `MessageHandler.handleSetEntityGlow()` | `pool.setEntityGlow(...)` | 477, 480 |
| `MessageHandler.handleSetEntityBrightness()` | `pool.setEntityBrightness(...)` | ~510 |
| `MessageHandler.handleGetZone()` | `plugin.getEntityPoolManager().getEntityCount(...)` | 99 |

Additionally, `MessageQueue.extractEntityUpdates()` at line 178 has a render mode check:
```java
if (!plugin.getParticleVisualizationManager().shouldRenderEntities(zoneName)) {
    return;
}
```
This can be removed after migration because `ParticlesBackend.updateFrame()` is intentionally a no-op.

### RendererRegistry Gap

`RendererRegistry` currently only tracks backend **type selection** per zone. It does NOT:
- Own backend instances
- Provide `getBackendForZone(String zoneName)` to return the actual `RendererBackend` implementation
- Manage backend lifecycle (creation, teardown)

### What Exists (Unchanged)

```
RendererBackend (interface)
    +-- DisplayEntitiesBackend (wraps EntityPoolManager)
    +-- ParticlesBackend (wraps ParticleVisualizationManager)

RendererBackendType (enum: DISPLAY_ENTITIES, PARTICLES, HOLOGRAM)

RendererRegistry (type selection only, no instance management)
```

### Target Architecture

```
RendererRegistry (upgraded)
    |
    +-- EnumMap<RendererBackendType, RendererBackend> backendInstances
    +-- getBackendForZone(String) -> RendererBackend
    +-- initialize(RendererBackendType, ...) -> void
    +-- teardownZone(String) -> void
    |
    +-- DisplayEntitiesBackend (wraps EntityPoolManager)
    +-- ParticlesBackend (wraps ParticleVisualizationManager)

MessageHandler
    |
    +-- Uses RendererRegistry.getBackendForZone() instead of plugin.getEntityPoolManager()
    |
    +-- instanceof escape hatches for display-entity-specific operations
    |   (glow, brightness, material changes, text content)

MessageQueue
    |
    +-- processTick() calls backend.updateFrame() instead of poolManager.batchUpdateEntities()
    +-- processAudioInfo() calls backend.updateAudioState() instead of direct particle manager
    +-- Removes shouldRenderEntities() check (ParticlesBackend.updateFrame() is no-op)
```

### RendererRegistry Upgrade

Add backend instance management to the existing `RendererRegistry`:

```java
public class RendererRegistry {
    // EXISTING fields (unchanged):
    private final Set<RendererBackendType> supportedBackends;
    private final Map<String, RendererBackendType> activeBackendByZone;
    private final Map<String, RendererBackendType> fallbackBackendByZone;

    // NEW: Backend instance storage
    private final EnumMap<RendererBackendType, RendererBackend> backendInstances =
        new EnumMap<>(RendererBackendType.class);

    // NEW: Register a backend instance (called during plugin onEnable)
    public void registerBackend(RendererBackend backend) {
        backendInstances.put(backend.getType(), backend);
    }

    // NEW: Get the active backend instance for a zone
    public RendererBackend getBackendForZone(String zoneName) {
        RendererBackendType effectiveType = getEffectiveBackend(zoneName);
        RendererBackend backend = backendInstances.get(effectiveType);
        if (backend != null) {
            return backend;
        }
        // Fallback to display entities if requested backend not registered
        return backendInstances.get(RendererBackendType.DISPLAY_ENTITIES);
    }

    // NEW: Get all registered backend instances
    public Collection<RendererBackend> getAllBackends() {
        return Collections.unmodifiableCollection(backendInstances.values());
    }

    // NEW: Convenience for checking if a backend has an instance
    public boolean hasBackendInstance(RendererBackendType type) {
        return backendInstances.containsKey(type);
    }
}
```

### Handler Migration Audit

Each `MessageHandler` method needs assessment:

| Handler | Migration Strategy |
|---------|--------------------|
| `handleInitPool` | Route through `backend.initialize(zone, count, material)` |
| `handleBatchUpdate` | Route through `backend.updateFrame(zoneName, updates)` |
| `handleUpdateEntity` | **Escape hatch** -- single-entity updates need `DisplayEntitiesBackend.getPoolManager()` |
| `handleSetVisible` | Route through `backend.setVisible(zoneName, visible)` |
| `handleCleanupZone` | Route through `backend.teardown(zoneName)` + registry cleanup |
| `handleSetZoneConfig` | Route `initializeBlockPool` through `backend.initialize(...)`, keep brightness/interpolation as escape hatch |
| `handleSetEntityGlow` | **Escape hatch** -- display-entity-specific, use `instanceof DisplayEntitiesBackend` |
| `handleSetEntityBrightness` | **Escape hatch** -- display-entity-specific, use `instanceof DisplayEntitiesBackend` |
| `handleAudioState` | Route through `backend.updateAudioState(audioState)` |
| `handleSetRenderMode` | Already uses `RendererRegistry` |
| `handleSetRendererBackend` | Already uses `RendererRegistry` |

### instanceof Escape Hatch Pattern

For display-entity-specific operations that have no equivalent in the generic interface:

```java
private JsonObject handleSetEntityGlow(JsonObject message) {
    String zoneName = message.get("zone").getAsString();
    boolean glow = message.get("glow").getAsBoolean();

    RendererBackend backend = plugin.getRendererRegistry().getBackendForZone(zoneName);

    if (backend instanceof DisplayEntitiesBackend deb) {
        EntityPoolManager pool = deb.getPoolManager();
        if (message.has("entities")) {
            JsonArray entities = message.getAsJsonArray("entities");
            for (JsonElement elem : entities) {
                pool.setEntityGlow(zoneName, elem.getAsString(), glow);
            }
        } else {
            for (String entityId : pool.getEntityIds(zoneName)) {
                pool.setEntityGlow(zoneName, entityId, glow);
            }
        }
    } else {
        // Glow is not supported by this backend -- silently ignore
        plugin.getLogger().fine(
            "Glow not supported by backend: " + backend.getType().key()
        );
    }

    JsonObject response = new JsonObject();
    response.addProperty("type", "glow_updated");
    response.addProperty("zone", zoneName);
    response.addProperty("glow", glow);
    return response;
}
```

### MessageQueue.processTick() Migration

The hot path migration in `processTick()`:

**Before (line 165):**
```java
plugin.getEntityPoolManager().batchUpdateEntities(entry.getKey(), entry.getValue());
```

**After:**
```java
RendererBackend backend = plugin.getRendererRegistry().getBackendForZone(entry.getKey());
backend.updateFrame(entry.getKey(), entry.getValue());
```

**Remove shouldRenderEntities check (line 178):**

The check `plugin.getParticleVisualizationManager().shouldRenderEntities(zoneName)` in `extractEntityUpdates()` becomes unnecessary because `ParticlesBackend.updateFrame()` is intentionally a no-op. The entities are still parsed but discarded by the backend. This is acceptable because JSON parsing already happened on the dedicated parser thread -- the entity extraction cost is minimal compared to the savings of removing the mode check on every batch.

### processAudioInfo() Migration

**Before (line 267):**
```java
plugin.getParticleVisualizationManager().updateAudioState(audioState);
```

**After:**
```java
// Update audio state for ALL registered backends (particles need it, display entities ignore it)
for (RendererBackend backend : plugin.getRendererRegistry().getAllBackends()) {
    backend.updateAudioState(audioState);
}
```

### Plugin Initialization

In `AudioVizPlugin.onEnable()`:

```java
// After creating EntityPoolManager and ParticleVisualizationManager:
RendererRegistry registry = getRendererRegistry();
registry.registerBackend(new DisplayEntitiesBackend(getEntityPoolManager()));
registry.registerBackend(new ParticlesBackend(getParticleVisualizationManager()));
```

### Implementation Steps

#### Step 1: Upgrade RendererRegistry (Low Risk)

Add `backendInstances` EnumMap, `registerBackend()`, `getBackendForZone()`, `hasBackendInstance()`, and `getAllBackends()` to `RendererRegistry`.

**Estimated diff:** +40 LOC in RendererRegistry.java

#### Step 2: Register Backends in Plugin Initialization (Low Risk)

Add two `registry.registerBackend()` calls in `AudioVizPlugin.onEnable()`.

**Estimated diff:** +5 LOC in AudioVizPlugin.java

#### Step 3: Migrate MessageQueue.processTick() (Medium Risk)

Replace `plugin.getEntityPoolManager().batchUpdateEntities()` with `backend.updateFrame()`.

**Estimated diff:** +5 / -3 LOC in MessageQueue.java
**Risk:** Medium -- this is the hot path called every tick (20 TPS). Performance regression would be visible immediately. The overhead is one HashMap lookup + one virtual method dispatch per zone per tick.

#### Step 4: Remove shouldRenderEntities() Check (Low Risk)

Remove the `shouldRenderEntities()` check in `extractEntityUpdates()`.

**Estimated diff:** -4 LOC in MessageQueue.java

#### Step 5: Migrate processAudioInfo() (Low Risk)

Replace direct `particleVisualizationManager.updateAudioState()` with broadcast to all backends.

**Estimated diff:** +5 / -2 LOC in MessageQueue.java

#### Step 6: Migrate MessageHandler.handleInitPool() (Low Risk)

Replace `plugin.getEntityPoolManager().initializeBlockPool()` with `backend.initialize()`.

**Estimated diff:** +4 / -2 LOC in MessageHandler.java

#### Step 7: Migrate MessageHandler.handleBatchUpdate() (Medium Risk)

Replace direct `pool.batchUpdateEntities()` with `backend.updateFrame()`. Keep particle spawning as-is (it is separate from the backend contract).

**Estimated diff:** +8 / -5 LOC in MessageHandler.java

#### Step 8: Migrate handleSetVisible() and handleCleanupZone() (Low Risk)

Replace direct pool calls with `backend.setVisible()` and `backend.teardown()`.

**Estimated diff:** +10 / -12 LOC in MessageHandler.java

#### Step 9: Add instanceof Escape Hatches (Low Risk)

For `handleSetEntityGlow()`, `handleSetEntityBrightness()`, `handleSetZoneConfig()` (brightness/interpolation), and `handleUpdateEntity()` -- wrap existing code in `if (backend instanceof DisplayEntitiesBackend deb)`.

**Estimated diff:** +30 / -5 LOC in MessageHandler.java

### Updating Existing Tests

The test files in `minecraft_plugin/src/test/` need updates:

| Test File | Changes Needed |
|-----------|---------------|
| `RendererBackendTest.java` | Add test for `RendererRegistry.getBackendForZone()` |
| `MessageHandlerTest.java` | Mock `RendererRegistry` instead of (or in addition to) `EntityPoolManager` |
| `MessageQueueTest.java` | Verify `processTick()` routes through backend, not pool directly |

### Rollback Strategy

1. Steps 1-2 are additive (no behavior change) -- safe to keep even if later steps are reverted.
2. Steps 3-5 (MessageQueue) can be reverted as a unit by restoring direct `EntityPoolManager` calls.
3. Steps 6-9 (MessageHandler) can be reverted independently per handler.
4. If performance regression is detected in Step 3, revert only the MessageQueue changes while keeping the registry upgrade.

### Risk Assessment

| Step | Risk | Mitigation |
|------|------|------------|
| 1-2 (registry upgrade) | Low | Additive, no behavior change |
| 3 (processTick hot path) | Medium | Benchmark before/after; one extra map lookup per tick |
| 4 (remove check) | Low | ParticlesBackend.updateFrame() is proven no-op |
| 5 (audio state) | Low | Broadcast is O(n) where n = 2-3 backends |
| 6 (initPool) | Low | One-time operation per zone |
| 7 (batchUpdate) | Medium | Second hot path; must verify entity update fidelity |
| 8 (visible/cleanup) | Low | Infrequent operations |
| 9 (escape hatches) | Low | instanceof pattern is well-understood; Java 17 pattern matching |

---

## Global Risk Matrix

| Refactoring | Total Estimated LOC Change | Highest Risk Phase | Dependencies |
|-------------|---------------------------|-------------------|--------------|
| Part 1 (app_capture) | +1680 / -2490 (net -810) | Phase 5 (band generator) | None |
| Part 2 (vj_server) | +1360 / -1130 (net +230) | Phase 2 (dispatch table) | Part 1 Phase 4 (shared effects/broadcast) |
| Part 3 (RendererBackend) | +107 / -33 (net +74) | Step 3 (hot path) | None |

### Execution Order Rationale

1. **Part 1 first** because Phase 4 produces `effects_engine.py` and `broadcast_server.py` which Part 2 depends on.
2. **Part 2 second** because it consumes the shared modules from Part 1.
3. **Part 3 last** because it is entirely in the Java plugin and has zero dependency on the Python refactoring. It can also be done in parallel with Parts 1 and 2 by a different developer.

### Cross-Cutting Concerns

- **Test Coverage:** Both Python files currently have minimal test coverage. Each extraction phase should include at least one new test that validates the extracted module in isolation.
- **Performance:** The audio hot path (60 Hz Python, 20 TPS Java) must not regress. Benchmark entity update latency before and after Part 3 Step 3.
- **Backward Compatibility:** Both Python modules use facade re-export patterns to ensure zero breakage for existing callers during transition.
