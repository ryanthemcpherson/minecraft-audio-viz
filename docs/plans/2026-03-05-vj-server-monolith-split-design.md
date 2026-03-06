# VJ Server Monolith Split Design

**Goal:** Decompose `vj_server/vj_server.py` (6505 lines, 133 methods) into 3-4 focused modules using mixin classes, and add graceful shutdown as part of the refactor.

**Approach:** Code organization only. VJServer inherits from DJManagerMixin, StageManagerMixin, and RelayMixin. All state stays on `self`. Zero public API changes.

## Module Structure

```
vj_server/
  vj_server.py      (~800 lines)  Core: init, run, audio pipeline, lifecycle, graceful shutdown
  models.py          (~500 lines)  Data classes: DjAudioFrame, DJConnection, ConnectCode, etc.
  dj_manager.py      (~1800 lines) DJManagerMixin: DJ connections, auth, queue, coordinator
  stage_manager.py   (~1400 lines) StageManagerMixin: patterns, zones, transitions, scenes
  relay.py           (~1800 lines) RelayMixin: browser/MC/DJ WebSocket handlers, broadcasts
  ...existing files unchanged...
```

## VJServer Class

```python
from vj_server.dj_manager import DJManagerMixin
from vj_server.stage_manager import StageManagerMixin
from vj_server.relay import RelayMixin

class VJServer(DJManagerMixin, StageManagerMixin, RelayMixin):
    def __init__(self, ...):   # all state init stays here
    async def run(self): ...   # lifecycle + graceful shutdown
    async def _main_loop(self): ...
```

## Module Boundaries

### models.py (Pure data, no behavior coupling)

- `DjAudioFrame`, `ZoneStatus`, `VizStateBroadcast` (msgspec structs)
- `ConnectCode`, `DJConnection`, `ZonePatternState` (dataclasses)
- `DJAuthConfig` (auth config with verify methods)
- HTTP handler classes (`MultiDirectoryHandler`, `_make_directory_handler`)
- `run_http_server()` helper

### dj_manager.py (DJManagerMixin)

DJ lifecycle, authentication, queue management, coordinator integration.

Methods:
- `_handle_dj_connection` (the auth/code_auth/dj_auth message router)
- Queue: `_set_active_dj`, `_set_active_dj_locked`, `_auto_switch_dj`, `_auto_switch_dj_locked`
- Approval: `_approve_pending_dj`, `_deny_pending_dj`, `_reorder_dj_queue`
- Roster: `_get_dj_roster`, `_dj_profile_dict`, `_hydrate_dj_profile`
- Properties: `active_dj`, `_get_active_dj`, `_get_active_dj_safe`
- Auth: `_check_auth_rate_limit`, `_process_dj_heartbeat`
- Coordinator: `_init_coordinator`, `_coordinator_heartbeat_loop`, `_coordinator_create_show`
- Connect codes: `_cleanup_expired_codes`, `_broadcast_connect_codes`
- Presets: `_apply_named_preset`
- Banners: `_load_banner_profiles`, `_save_banner_profiles`, `_process_logo_image`
- Streams: `_build_stream_route_message`, `_broadcast_stream_routes`

### stage_manager.py (StageManagerMixin)

Pattern engine, zone management, transitions, effects, scenes.

Methods:
- Zone state: `_get_zone_state`, all zone property accessors
- Patterns: `_set_pattern_for_zone`, `_calculate_entities_for_zone`, `_calculate_entities`
- Transitions: `_blend_entities`, `_smoothstep`, `_lerp`, `_lerp_angle`, `_get_sibling_id`
- Effects: `_apply_effects`
- Hot-reload: `_pattern_hot_reload_loop`, `_broadcast_pattern_list`, `_get_pattern_scripts`
- Bitmap: `_relay_bitmap_frame`, `_handle_stage_zone_configs`, `_switch_zone_to_bitmap/block`
- Rehydration: `_rehydrate_zone_states_from_active_stage`, `_auto_init_bitmap_zones`
- Parity: `_run_parity_check`
- Scenes: `_sanitize_scene_name`, `_save_scene_to_file`, `_load_scene_from_file`, `_delete_scene_file`, `_list_scenes`, `_apply_scene_state`
- State queries: `_get_zone_patterns_dict`, `_get_bitmap_zones_dict`, `_get_all_patterns_list`, `_capture_current_state`

### relay.py (RelayMixin)

All WebSocket I/O, broadcasting, Minecraft connection, voice relay.

Methods:
- `_handle_browser_client` (the admin panel message router)
- Minecraft: `connect_minecraft`, `_minecraft_reconnect_loop`, `_update_minecraft`, `_update_minecraft_zone`
- Browser: `_broadcast_to_browsers`, `_browser_heartbeat_loop`, `_broadcast_dj_roster`, `_broadcast_minecraft_status`
- DJ broadcast: `_broadcast_to_djs`, `_broadcast_pattern_change`, `_broadcast_pattern_sync_to_djs`, `_broadcast_config_sync_to_djs`, `_broadcast_config_to_browsers`, `_broadcast_preset_to_djs`, `_broadcast_effect_trigger`
- Viz state: `_broadcast_viz_state`, `_send_with_timeout`
- Voice: `_relay_voice_audio`, `_forward_voice_config`, `_broadcast_voice_status`
- MC info: `_send_dj_info_to_minecraft`, `_send_banner_config_to_minecraft`
- Ableton Link: `_link_sync_loop`

### vj_server.py (VJServer core)

Orchestration, audio pipeline, lifecycle, graceful shutdown.

Methods:
- `__init__()` (all state initialization)
- `run()`, `_main_loop()`, `stop()`, `cleanup()`
- Audio: `_handle_dj_frame`, `_stabilize_bpm`, `_apply_clock_resync`, `_calculate_sync_confidence`, `_get_effective_delay_ms`, `_read_delayed_frame`
- Visual: `_enhance_visual_state`, `_apply_phase_beat_assist`
- Health: `get_health_stats`, `_update_live_profile`

## Graceful Shutdown

Added to VJServer core:

1. **Signal handling in `run()`** — Register SIGINT/SIGTERM that set `_running = False`
2. **Connection draining** — Send `{"type": "server_shutdown"}` to all DJs and browsers before closing
3. **Task cancellation with timeout** — `asyncio.wait_for()` on background tasks (5s max)
4. **State persistence** — Auto-save scene state on shutdown for restart recovery

## What Doesn't Change

- Public API: `VJServer.__init__()`, `run()`, `stop()`, `cleanup()`, `connect_minecraft()`
- `cli.py` imports (re-exported from `vj_server.py` or updated import path)
- All existing tests
- No behavior changes
