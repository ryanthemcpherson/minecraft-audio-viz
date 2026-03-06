# Lua Timeout Protection Design

**Goal:** Prevent infinite-loop Lua patterns from blocking the VJ server event loop.

**Problem:** The VJ server executes user-authored Lua patterns via lupa. A pattern with an infinite loop (accidental or malicious) blocks the async event loop indefinitely. The existing code has a TODO at `vj_server/patterns.py:168` acknowledging this gap.

## Approach

Use Lua's `debug.sethook` with instruction counting. A hook fires every 1,000 instructions and increments a counter. If the counter exceeds 1,000,000, the hook raises a Lua `error()`. The counter resets before each `calculate()` call.

### Why instruction counting

- `LuaRuntime` is not thread-safe, so thread-based timeouts require locks and risk deadlocks
- Signal-based timeouts are platform-dependent and don't work well with async Python
- Instruction counting is deterministic, cross-platform, and adds negligible overhead (~67% relative, but absolute cost is 0.09ms vs 16ms frame budget)

## Components

### 1. Hook installation

In `LuaPattern.__init__()`, after creating `LuaRuntime` but **before** sandboxing removes `debug`:

```lua
__hook_count = 0
__hook_limit = 1000000
debug.sethook(function()
    __hook_count = __hook_count + 1
    if __hook_count > __hook_limit then
        error("pattern exceeded instruction limit")
    end
end, "", 1000)
```

### 2. Counter reset

A Lua function `__reset_hook()` sets `__hook_count = 0`. Called from Python before each `calculate()` invocation via `self._reset_hook()`.

### 3. Error handling

`calculate_entities()` already wraps Lua calls in try/except. The timeout surfaces as `lupa.LuaError`. On timeout:

1. Log a warning with pattern name
2. Return empty entities for that frame
3. Increment a consecutive-timeout counter
4. After 3 consecutive timeouts, disable the pattern and log an error
5. Counter resets on any successful `calculate()` call

### 4. Constants

Module-level in `patterns.py`:

```python
LUA_HOOK_INTERVAL = 1000        # instructions between hook calls
LUA_INSTRUCTION_LIMIT = 1_000_000  # max instructions per calculate()
MAX_CONSECUTIVE_TIMEOUTS = 3    # auto-disable after this many
```

## What doesn't change

- Sandbox list (still remove `debug` from pattern access after hook is set)
- Pattern Lua API — no pattern files need modification
- No new dependencies
- Existing error handling structure in `calculate_entities()`

## Performance

Benchmarked on Galaxy pattern (realistic workload):
- Without hook: ~0.054ms per calculate()
- With hook @1000 interval: ~0.091ms per calculate()
- Well within 16ms frame budget even at 60fps
