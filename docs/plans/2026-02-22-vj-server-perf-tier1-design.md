# VJ Server Performance Optimization — Tier 1 Design

**Date**: 2026-02-22
**Goal**: Reduce frame processing latency and increase general headroom in the VJ server with minimal-effort, drop-in changes.

## Problem Statement

The VJ server runs a 60 FPS main loop that processes audio frames, executes Lua patterns, serializes entities to JSON, and broadcasts to Minecraft + browser clients. All computation is single-threaded. Profiling instrumentation already exists (`calc_ms`, `mc_ms`, `broadcast_ms`).

The three highest-impact, lowest-effort optimizations target:

1. **JSON serialization** — ~100+ `json.loads`/`json.dumps` calls per frame cycle
2. **Lua runtime** — pattern `calculate()` blocks the event loop, may be running PUC Lua instead of LuaJIT
3. **Event loop** — stdlib asyncio has measurable overhead vs optimized alternatives

## Change 1: Switch `json` to `msgspec`

### What

Replace stdlib `json.loads`/`json.dumps` with `msgspec.json.decode`/`msgspec.json.encode` across the VJ server. Define `msgspec.Struct` schemas for hot-path messages.

### Why

- `msgspec` with structs: **4-9x faster** than stdlib `json`, **2-4x faster** than `orjson`
- Returns `bytes` directly — ideal for `websockets` which accepts `bytes`
- Built-in validation at decode time (no separate schema check needed)
- Also supports MessagePack if we ever want binary frames

### How

**Phase A — Drop-in replacement for unstructured messages:**

Replace `import json` with `import msgspec.json` and swap calls:

```python
# Before
import json
msg = json.dumps({"type": "pong"})
data = json.loads(raw)

# After
import msgspec.json
msg = msgspec.json.encode({"type": "pong"})  # returns bytes
data = msgspec.json.decode(raw)              # accepts str or bytes
```

This is a mechanical find-and-replace. `websockets` accepts both `str` and `bytes`, so returning `bytes` from `encode()` works without changes to send calls.

**Phase B — Struct schemas for hot-path messages:**

Define structs for the highest-frequency messages (audio frames, batch updates):

```python
import msgspec

class DjAudioFrame(msgspec.Struct):
    type: str
    bands: list[float]
    peak: float
    beat: bool
    bpm: float
    seq: int
    i_bass: float = 0.0
    i_kick: bool = False
    tempo_confidence: float = 0.0
    beat_phase: float = 0.0
    beat_intensity: float = 0.0

_frame_decoder = msgspec.json.Decoder(DjAudioFrame)

# In _handle_dj_frame:
frame = _frame_decoder.decode(raw_bytes)  # fastest path, with validation
```

### Files changed

- `vj_server/vj_server.py` — all `json.loads`/`json.dumps` calls (~100+)
- `vj_server/viz_client.py` — JSON calls in WebSocket communication
- `vj_server/auth.py` — JSON for config file I/O (low frequency, but consistency)
- `vj_server/config.py` — JSON usage
- `vj_server/coordinator_client.py` — JSON for API calls
- `vj_server/metrics.py` — JSON for health endpoint
- `vj_server/pyproject.toml` — add `msgspec>=0.18.0` dependency

### Risk

Low. `msgspec.json.encode/decode` is API-compatible with stdlib for dict/list/primitive inputs. The only behavioral difference is `encode()` returns `bytes` instead of `str` — `websockets.send()` accepts both.

---

## Change 2: Switch lupa to LuaJIT runtime

### What

Change the lupa import in `patterns.py` to explicitly request the LuaJIT 2.1 runtime instead of the default PUC Lua.

### Why

- LuaJIT is **3-10x faster** than PUC Lua for numeric loops
- Pattern `calculate()` functions are tight numeric loops over entities — exactly what LuaJIT optimizes best
- `lupa` ships separate extension modules for PUC Lua and LuaJIT; the default `from lupa import LuaRuntime` picks whichever was compiled first (typically PUC Lua from pip wheels)

### How

```python
# In patterns.py, line ~99
# Before
from lupa import LuaRuntime

# After
try:
    from lupa.luajit21 import LuaRuntime
except ImportError:
    try:
        from lupa.luajit20 import LuaRuntime
    except ImportError:
        from lupa import LuaRuntime  # fallback to whatever is available
```

Add a log line after the import to confirm which runtime was loaded:

```python
import logging
logger = logging.getLogger(__name__)
logger.info(f"Lua runtime: {LuaRuntime}")
```

### Files changed

- `vj_server/patterns.py` — import change (~3 lines)

### Risk

Minimal. LuaJIT is a strict superset of Lua 5.1 and largely compatible with 5.2+. All 28 patterns use basic Lua (tables, loops, math) — no Lua 5.3+ features like integers or bitwise operators. Fallback import chain ensures the server still starts if LuaJIT isn't available.

### Verification

- Run all 28 patterns and compare entity output with PUC Lua vs LuaJIT (should be identical)
- Check `calc_ms` metric before/after to measure improvement

---

## Change 3: Install `uvloop` as event loop policy

### What

Use `uvloop` as a drop-in replacement for the stdlib `asyncio` event loop on Linux/macOS.

### Why

- **2-4x faster** I/O operations (WebSocket send/recv, sleep scheduling)
- Single line of code to enable
- The VJ server runs on Linux (192.168.1.204) where uvloop is fully supported
- Reduces event loop overhead on every `await` — which happens hundreds of times per frame cycle

### How

```python
# In cli.py, before asyncio.run():
import sys
if sys.platform != "win32":
    try:
        import uvloop
        asyncio.set_event_loop_policy(uvloop.EventLoopPolicy())
        logger.info("Using uvloop event loop")
    except ImportError:
        logger.info("uvloop not available, using default asyncio loop")
```

### Files changed

- `vj_server/cli.py` — add uvloop policy (~5 lines)
- `vj_server/pyproject.toml` — add `uvloop>=0.19.0` as optional dependency

### Dependency note

`uvloop` is Linux/macOS only. It should be an optional dependency so the server still works on Windows for development:

```toml
[project.optional-dependencies]
fast = ["uvloop>=0.19.0; sys_platform != 'win32'"]
link = ["aalink>=0.1"]
full = ["aalink>=0.1", "uvloop>=0.19.0; sys_platform != 'win32'"]
```

Install with: `pip install -e ".[fast]"` or `pip install -e ".[full]"`

### Risk

None. uvloop is battle-tested (used by uvicorn, Sanic, etc.). Platform guard + try/except ensures zero impact on Windows dev.

---

## Expected Impact

| Change | Latency reduction | Headroom gain | Effort |
|-|-|-|-|
| msgspec | 60-80% less time in JSON | Significant at scale | Medium (mechanical replacement) |
| LuaJIT | 3-10x faster pattern calc | Large | Trivial (import change) |
| uvloop | 2-4x faster I/O scheduling | Moderate | Trivial (5 lines) |

Combined: frame processing time should drop **50-70%**, freeing substantial headroom for more zones, entities, and DJs.

## Verification Plan

1. Before any changes, run the VJ server with a DJ connected and record baseline `calc_ms_avg`, `mc_ms_avg`, `broadcast_ms_avg` from the health endpoint
2. Apply changes one at a time, re-measure after each
3. Run all 28 patterns to confirm identical visual output
4. Load test with multiple browser clients to verify broadcast performance

## Implementation Order

1. LuaJIT import (smallest change, biggest potential impact on calc_ms)
2. uvloop (5 lines, immediate I/O improvement)
3. msgspec Phase A (mechanical replacement, biggest code diff)
4. msgspec Phase B (struct schemas for hot-path messages — optional, can defer)

## Future Work (Tier 2)

Not in scope for this design, but noted for follow-up:

- **ProcessPoolExecutor for Lua** — offload pattern calc from event loop thread
- **Flat array returns from Lua** — reduce bridge overhead per entity
- **mypyc compilation** — compile entity interpolation hot paths
