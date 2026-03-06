# VJ Server Monolith Split Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Decompose `vj_server/vj_server.py` (6505 lines) into focused modules using mixin classes, and add graceful shutdown.

**Architecture:** Extract data classes into `models.py`, then create three mixin classes (`DJManagerMixin`, `StageManagerMixin`, `RelayMixin`) in separate files. VJServer inherits from all three. All state stays on `self`. Finally, add graceful shutdown logic to the core. Each extraction is independently testable.

**Tech Stack:** Python 3.11+, asyncio, websockets, msgspec, pytest

---

### Task 1: Extract models.py (data classes and helpers)

**Files:**
- Create: `vj_server/models.py`
- Modify: `vj_server/vj_server.py`

**What to extract** from `vj_server/vj_server.py` into `vj_server/models.py`:

Everything between lines 76 and 717 (before the VJServer class):
- Module-level constants: `_USE_ASYNC_LUA` setup (lines 76-93)
- Msgspec structs: `DjAudioFrame` (104-119), `ZoneStatus` (124-130), `VizStateBroadcast` (132-159)
- Helper functions: `_json_str()` (168-175), `_sanitize_name()` (178-183), `_clamp_finite()` (186-193), `_sanitize_audio_frame()` (196-237), `_sanitize_entities()` (239-387)
- Dataclasses: `ConnectCode` (389-418), `DJConnection` (420-519), `ZonePatternState` (521-543)
- `DJAuthConfig` (545-627)
- HTTP handlers: `_make_directory_handler()` (629-660), `MultiDirectoryHandler` (662-691), `run_http_server()` (693-717)

**Step 1: Create `vj_server/models.py`**

Copy all the above code into the new file. Include the necessary imports at the top (only the ones these classes/functions actually use — `asyncio`, `dataclasses`, `json`, `logging`, `time`, `msgspec`, etc.).

**Step 2: Update `vj_server/vj_server.py`**

Replace the extracted code block (lines 76-717) with imports from models:

```python
from vj_server.models import (
    _USE_ASYNC_LUA,
    DjAudioFrame,
    ZoneStatus,
    VizStateBroadcast,
    ConnectCode,
    DJConnection,
    ZonePatternState,
    DJAuthConfig,
    MultiDirectoryHandler,
    run_http_server,
    _json_str,
    _sanitize_name,
    _clamp_finite,
    _sanitize_audio_frame,
    _sanitize_entities,
)
```

Remove the original definitions but keep all standard library imports in vj_server.py that VJServer methods still need.

**Step 3: Update `vj_server/cli.py`**

Change line 147 from:
```python
from vj_server.vj_server import DJAuthConfig, VJServer
```
to:
```python
from vj_server.vj_server import VJServer
from vj_server.models import DJAuthConfig
```

**Step 4: Run tests**

Run: `cd vj_server && python -m pytest tests/ -v --timeout=30`
Expected: ALL tests pass (147+)

**Step 5: Verify import works**

Run: `cd C:/Users/Ryan/Desktop/minecraft-audio-viz && python -c "from vj_server.vj_server import VJServer; from vj_server.models import DJAuthConfig, DJConnection; print('OK')"`
Expected: `OK`

**Step 6: Commit**

```bash
git add vj_server/models.py vj_server/vj_server.py vj_server/cli.py
git commit -m "refactor(vj-server): extract data classes and helpers to models.py

Move DjAudioFrame, DJConnection, ConnectCode, ZonePatternState,
DJAuthConfig, and utility functions to vj_server/models.py.
VJServer imports them; no behavior changes."
```

---

### Task 2: Extract dj_manager.py (DJManagerMixin)

**Files:**
- Create: `vj_server/dj_manager.py`
- Modify: `vj_server/vj_server.py`

**What to extract** — these methods move from VJServer into `class DJManagerMixin`:

DJ Connection & Auth:
- `_handle_dj_connection` (1334-1924)
- `_process_dj_heartbeat` (1263-1302)
- `_check_auth_rate_limit` (1303-1333)

DJ Properties & Roster:
- `active_dj` property (1054-1062)
- `_get_active_dj` (1063-1066)
- `_get_active_dj_safe` (1067-1073)
- `_dj_profile_dict` (1074-1086)
- `_hydrate_dj_profile` (1087-1116)
- `_get_dj_roster` (1117-1166)

Queue Management:
- `_set_active_dj` (2253-2257)
- `_set_active_dj_locked` (2258-2320)
- `_auto_switch_dj` (2690-2694)
- `_auto_switch_dj_locked` (2695-2714)
- `_approve_pending_dj` (3967-4054)
- `_deny_pending_dj` (4055-4088)
- `_reorder_dj_queue` (4089-4104)

Presets & Streams:
- `_apply_named_preset` (2321-2334)
- `_build_stream_route_message` (2335-2367)
- `_broadcast_stream_routes` (2368-2377)
- `_send_dj_info_to_minecraft` (2378-2409)
- `_send_banner_config_to_minecraft` (2410-2436)

Banners:
- `_load_banner_profiles` (2437-2469)
- `_save_banner_profiles` (2470-2499)
- `_process_logo_image` (2500-2533)

Coordinator:
- `_init_coordinator` (4105-4138)
- `_coordinator_heartbeat_loop` (4139-4148)
- `_coordinator_create_show` (4149-4173)
- `_cleanup_expired_codes` (4174-4181)
- `_broadcast_connect_codes` (4182-4205)

**Step 1: Create `vj_server/dj_manager.py`**

```python
"""DJ connection management, authentication, and queue logic."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import TYPE_CHECKING, Any, Dict, List, Optional

if TYPE_CHECKING:
    from vj_server.models import DJConnection

logger = logging.getLogger(__name__)


class DJManagerMixin:
    """Mixin providing DJ connection, auth, queue, and coordinator methods."""

    # ... paste all extracted methods here, preserving exact indentation ...
```

The `TYPE_CHECKING` import pattern avoids circular imports. Add real imports only for modules actually used in method bodies (e.g., `from vj_server.models import ConnectCode` if methods create ConnectCode instances).

**Step 2: Remove extracted methods from VJServer in `vj_server.py`**

Delete the method definitions listed above from the VJServer class body. Leave all other methods (audio pipeline, lifecycle, etc.) in place.

**Step 3: Wire up mixin inheritance**

In `vj_server.py`, add the import and update the class declaration:

```python
from vj_server.dj_manager import DJManagerMixin

class VJServer(DJManagerMixin):
    ...
```

**Step 4: Run tests**

Run: `cd vj_server && python -m pytest tests/ -v --timeout=30`
Expected: ALL tests pass

**Step 5: Commit**

```bash
git add vj_server/dj_manager.py vj_server/vj_server.py
git commit -m "refactor(vj-server): extract DJ management into DJManagerMixin

Move DJ connection handling, auth, queue management, roster,
coordinator integration, and banner logic to dj_manager.py.
VJServer inherits DJManagerMixin; no behavior changes."
```

---

### Task 3: Extract stage_manager.py (StageManagerMixin)

**Files:**
- Create: `vj_server/stage_manager.py`
- Modify: `vj_server/vj_server.py`

**What to extract** — these methods move into `class StageManagerMixin`:

Zone State:
- `_get_zone_state` (923-937)
- All zone property accessors: `_current_pattern`, `_pattern_name`, `_transitioning`, `_transition_start`, `_transition_duration`, `_old_pattern`, `_old_pattern_name`, `_transition_pending_resize`, `_minecraft_pool_size`, `_last_entities` (getters and setters, 938-1018)
- `_get_zone_patterns_dict` (1020-1026)
- `_get_bitmap_zones_dict` (1027-1038)
- `_get_all_patterns_list` (1039-1053)

Pattern Engine:
- `_set_pattern_for_zone` (5272-5366)
- `_calculate_entities_for_zone` (5415-5473)
- `_calculate_entities` (5475-5554)
- `_blend_entities` (5560-5659)
- `_smoothstep` (5555-5559)
- `_lerp` (5673-5677)
- `_lerp_angle` (5678-5682) [static]
- `_get_sibling_id` (5660-5672) [static]
- `_apply_effects` (5209-5271)

Hot Reload:
- `_pattern_hot_reload_loop` (5048-5190)
- `_broadcast_pattern_list` (5192-5208)
- `_get_pattern_scripts` (4344-4360)

Bitmap & Zone Rendering:
- `_relay_bitmap_frame` (5368-5370)
- `_handle_stage_zone_configs` (5372-5413)
- `_switch_zone_to_bitmap` (4630-4671)
- `_switch_zone_to_block` (4673-4693)
- `_rehydrate_zone_states_from_active_stage` (4695-4802)
- `_auto_init_bitmap_zones` (4467-4492)
- `_run_parity_check` (4494-4628)

Scenes:
- `_capture_current_state` (2534-2551)
- `_sanitize_scene_name` (2552-2558) [static]
- `_save_scene_to_file` (2559-2571)
- `_load_scene_from_file` (2572-2584)
- `_delete_scene_file` (2585-2595)
- `_list_scenes` (2596-2619)
- `_apply_scene_state` (2620-2688)

**Step 1: Create `vj_server/stage_manager.py`**

```python
"""Pattern engine, zone management, transitions, effects, and scene persistence."""

from __future__ import annotations

import asyncio
import json
import logging
import math
import time
from pathlib import Path
from typing import TYPE_CHECKING, Any, Dict, List, Optional

from vj_server.models import ZonePatternState
from vj_server.patterns import (
    AudioState,
    LuaPattern,
    PatternConfig,
    get_pattern,
    get_recommended_entity_count,
    list_patterns,
    refresh_pattern_cache,
)

if TYPE_CHECKING:
    pass

logger = logging.getLogger(__name__)


class StageManagerMixin:
    """Mixin providing pattern, zone, transition, effect, and scene methods."""

    # ... paste all extracted methods here ...
```

**Step 2: Remove extracted methods from VJServer, wire up inheritance**

```python
from vj_server.dj_manager import DJManagerMixin
from vj_server.stage_manager import StageManagerMixin

class VJServer(DJManagerMixin, StageManagerMixin):
    ...
```

**Step 3: Run tests**

Run: `cd vj_server && python -m pytest tests/ -v --timeout=30`
Expected: ALL tests pass

**Step 4: Commit**

```bash
git add vj_server/stage_manager.py vj_server/vj_server.py
git commit -m "refactor(vj-server): extract pattern/stage management into StageManagerMixin

Move pattern engine, zone management, transitions, effects,
hot-reload, bitmap rendering, and scene persistence to
stage_manager.py. VJServer inherits StageManagerMixin."
```

---

### Task 4: Extract relay.py (RelayMixin)

**Files:**
- Create: `vj_server/relay.py`
- Modify: `vj_server/vj_server.py`

**What to extract** — these methods move into `class RelayMixin`:

Browser:
- `_handle_browser_client` (2715-3888)
- `_browser_heartbeat_loop` (4971-5046)
- `_broadcast_to_browsers` (3940-3948)
- `_broadcast_dj_roster` (3890-3938)
- `_broadcast_minecraft_status` (3950-3965)
- `_broadcast_config_to_browsers` (4267-4284)

DJ Broadcasting:
- `_broadcast_to_djs` (4335-4342)
- `_broadcast_pattern_change` (4206-4228)
- `_broadcast_pattern_sync_to_djs` (4229-4250)
- `_broadcast_config_sync_to_djs` (4251-4266)
- `_broadcast_preset_to_djs` (4286-4314)
- `_broadcast_effect_trigger` (4315-4334)

Viz State:
- `_send_with_timeout` (4361-4372)
- `_broadcast_viz_state` (4374-4466)

Minecraft:
- `connect_minecraft` (4804-4902)
- `_minecraft_reconnect_loop` (4904-4970)
- `_update_minecraft` (5683-5744)
- `_update_minecraft_zone` (5746-5824)

Voice:
- `_relay_voice_audio` (5825-5858)
- `_forward_voice_config` (5859-5868)
- `_broadcast_voice_status` (5869-5872)

Ableton Link:
- `_link_sync_loop` (5873-5922)

**Step 1: Create `vj_server/relay.py`**

```python
"""WebSocket relay, broadcasting, Minecraft connection, and voice handling."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import TYPE_CHECKING, Any, Dict, List, Optional, Set

from vj_server.models import _json_str

if TYPE_CHECKING:
    pass

logger = logging.getLogger(__name__)


class RelayMixin:
    """Mixin providing browser/MC/DJ WebSocket handling and broadcasting."""

    # ... paste all extracted methods here ...
```

**Step 2: Remove extracted methods from VJServer, wire up inheritance**

```python
from vj_server.dj_manager import DJManagerMixin
from vj_server.stage_manager import StageManagerMixin
from vj_server.relay import RelayMixin

class VJServer(DJManagerMixin, StageManagerMixin, RelayMixin):
    ...
```

**Step 3: Run tests**

Run: `cd vj_server && python -m pytest tests/ -v --timeout=30`
Expected: ALL tests pass

**Step 4: Verify the slimmed-down vj_server.py**

At this point, `vj_server.py` should contain only:
- Imports
- `VJServer.__init__()` (~200 lines)
- Audio pipeline methods (~200 lines): `_handle_dj_frame`, `_stabilize_bpm`, `_apply_clock_resync`, `_calculate_sync_confidence`, `_get_effective_delay_ms`, `_read_delayed_frame`, `_apply_phase_beat_assist`, `_enhance_visual_state`
- Health methods (~130 lines): `get_health_stats`, `_update_live_profile`
- Lifecycle (~200 lines): `run`, `_main_loop`, `stop`, `cleanup`
- Top-level `main()` and validators (~100 lines)

Total: ~800-900 lines. Verify with: `wc -l vj_server/vj_server.py`

**Step 5: Commit**

```bash
git add vj_server/relay.py vj_server/vj_server.py
git commit -m "refactor(vj-server): extract relay/broadcast into RelayMixin

Move browser client handler, Minecraft relay, DJ broadcasting,
voice relay, and Ableton Link sync to relay.py.
VJServer inherits RelayMixin. Core is now ~800 lines."
```

---

### Task 5: Add graceful shutdown

**Files:**
- Modify: `vj_server/vj_server.py` (run/cleanup methods)

**Step 1: Add shutdown notification to `run()`**

In the `finally` block of `run()`, before cancelling tasks and closing servers, add connection draining:

```python
        try:
            await self._main_loop()
        finally:
            logger.info("Shutting down VJ server...")

            # Notify all connected clients of shutdown
            shutdown_msg = json.dumps({"type": "server_shutdown"})
            drain_tasks = []
            for ws in list(self._broadcast_clients):
                drain_tasks.append(self._send_with_timeout(ws, shutdown_msg, timeout=2.0))
            async with self._dj_lock:
                for dj in self._djs.values():
                    if dj.websocket:
                        drain_tasks.append(
                            self._send_with_timeout(dj.websocket, shutdown_msg, timeout=2.0)
                        )
            if drain_tasks:
                await asyncio.gather(*drain_tasks, return_exceptions=True)
                logger.info("Notified %d clients of shutdown", len(drain_tasks))

            # Cancel background tasks with timeout
            tasks_to_cancel = [
                t for t in [
                    self._mc_reconnect_task,
                    self._browser_heartbeat_task,
                    getattr(self, '_pattern_hot_reload_task', None),
                    self._coordinator_heartbeat_task,
                    self._link_task,
                    getattr(self, '_health_log_task', None),
                ] if t is not None and not t.done()
            ]
            for task in tasks_to_cancel:
                task.cancel()
            if tasks_to_cancel:
                await asyncio.wait(tasks_to_cancel, timeout=5.0)
                logger.info("Cancelled %d background tasks", len(tasks_to_cancel))

            # Disable Link
            if self._link is not None:
                try:
                    self._link.enabled = False
                except Exception:
                    pass

            # Close servers
            dj_server.close()
            broadcast_server.close()
            if metrics_server:
                metrics_server.close()
                await metrics_server.wait_closed()
            await dj_server.wait_closed()
            await broadcast_server.wait_closed()
            logger.info("VJ server shutdown complete")
```

This replaces the existing repetitive cancel/await pattern in `run()` with a cleaner loop.

**Step 2: Register signal handlers in `run()` directly**

Add near the top of `run()`, after starting servers:

```python
        # Register signal handlers for graceful shutdown
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, self.stop)
            except NotImplementedError:
                # Windows doesn't support add_signal_handler
                signal.signal(sig, lambda s, f: self.stop())
```

Add `import signal` to the imports if not already present.

**Step 3: Run tests**

Run: `cd vj_server && python -m pytest tests/ -v --timeout=30`
Expected: ALL tests pass

**Step 4: Commit**

```bash
git add vj_server/vj_server.py
git commit -m "feat(vj-server): add graceful shutdown with client notification

On SIGINT/SIGTERM, send server_shutdown message to all connected
DJs and browsers before closing. Cancel background tasks with 5s
timeout. Register signal handlers directly in run()."
```

---

### Task 6: Clean up imports and final verification

**Files:**
- Modify: `vj_server/vj_server.py` (trim unused imports)
- Modify: `vj_server/__init__.py` (add re-exports if needed)

**Step 1: Clean up vj_server.py imports**

After extraction, `vj_server.py` likely has many unused imports. Remove any that are no longer needed (they moved to the mixin files). Use ruff to identify them:

Run: `cd vj_server && python -m ruff check vj_server.py --select F401`

Fix any unused imports.

**Step 2: Check all mixin files for missing imports**

Run: `cd vj_server && python -m ruff check dj_manager.py stage_manager.py relay.py models.py`

Fix any issues.

**Step 3: Ensure backward-compatible imports from vj_server.py**

If any external code imports `DJAuthConfig` from `vj_server.vj_server`, add a re-export:

```python
# Re-export for backward compatibility
from vj_server.models import DJAuthConfig  # noqa: F401
```

**Step 4: Run full test suite**

Run: `cd vj_server && python -m pytest tests/ -v --timeout=60`
Expected: ALL 147+ tests pass

**Step 5: Verify file sizes**

Run: `wc -l vj_server/vj_server.py vj_server/models.py vj_server/dj_manager.py vj_server/stage_manager.py vj_server/relay.py`

Expected approximate sizes:
- vj_server.py: ~800-900 lines
- models.py: ~500 lines
- dj_manager.py: ~1500-1800 lines
- stage_manager.py: ~1400-1600 lines
- relay.py: ~1500-1800 lines

**Step 6: Spot-check import chain works end-to-end**

```bash
cd C:/Users/Ryan/Desktop/minecraft-audio-viz && python -c "
from vj_server.vj_server import VJServer
from vj_server.models import DJAuthConfig, DJConnection, ConnectCode
from vj_server.dj_manager import DJManagerMixin
from vj_server.stage_manager import StageManagerMixin
from vj_server.relay import RelayMixin
assert issubclass(VJServer, DJManagerMixin)
assert issubclass(VJServer, StageManagerMixin)
assert issubclass(VJServer, RelayMixin)
print('All imports and inheritance OK')
"
```

**Step 7: Commit**

```bash
git add vj_server/
git commit -m "chore(vj-server): clean up imports after monolith split

Remove unused imports from vj_server.py, fix lint in new modules,
ensure backward-compatible re-exports for DJAuthConfig."
```
