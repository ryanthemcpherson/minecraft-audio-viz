# Lua Timeout Protection Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prevent infinite-loop Lua patterns from blocking the VJ server event loop by adding instruction-count-based timeout detection.

**Architecture:** Use Lua's `debug.sethook` to install an instruction-counting hook before the sandbox removes `debug`. The hook fires every 1,000 instructions, increments a counter, and raises a Lua error if the counter exceeds 1,000,000. The counter resets before each `calculate()` call. After 3 consecutive timeouts, the pattern is auto-disabled.

**Tech Stack:** Python 3.11+, lupa (Lua runtime), pytest

---

### Task 1: Add constants and test that an infinite-loop pattern times out

**Files:**
- Modify: `vj_server/patterns.py:17-24` (add constants near top of file)
- Test: `vj_server/tests/test_patterns.py`

**Step 1: Write the failing test**

Add a new test class at the end of `vj_server/tests/test_patterns.py`:

```python
class TestLuaTimeout:
    """Tests for instruction-count-based Lua timeout protection."""

    def _make_audio(self) -> AudioState:
        return AudioState(
            bands=[0.5, 0.4, 0.3, 0.2, 0.1],
            amplitude=0.5,
            is_beat=False,
            beat_intensity=0.0,
            frame=1,
            bpm=128.0,
            beat_phase=0.0,
        )

    def test_infinite_loop_returns_empty_entities(self):
        """A pattern with an infinite loop should be caught by the instruction
        limit and return an empty entity list instead of hanging forever."""
        config = PatternConfig(entity_count=16)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        # Inject an infinite-loop calculate function
        pat._lua.execute("""
            function calculate(audio, config, dt)
                while true do end
            end
        """)
        pat._calculate = pat._lua.globals()["calculate"]
        pat._flat_mode = None  # Disable flat_pack wrapper

        audio = self._make_audio()
        entities = pat.calculate_entities(audio)
        assert entities == [], "Infinite loop should return empty entities, not hang"
```

**Step 2: Run test to verify it fails (hangs forever)**

Run: `cd vj_server && python -m pytest tests/test_patterns.py::TestLuaTimeout::test_infinite_loop_returns_empty_entities -v --timeout=10`
Expected: HANGS then times out (or must be killed with Ctrl+C). This confirms the bug exists.

Note: You may need `pip install pytest-timeout` if not already installed. If unavailable, just run the test and kill it after 5 seconds — the hang itself proves the test is correct.

**Step 3: Add constants to `vj_server/patterns.py`**

Add these constants after line 24 (after `logger = logging.getLogger(__name__)`):

```python
# Lua execution timeout protection: instruction-count hook fires every
# LUA_HOOK_INTERVAL instructions. If total count exceeds LUA_INSTRUCTION_LIMIT
# in a single calculate() call, a Lua error is raised.
LUA_HOOK_INTERVAL = 1000
LUA_INSTRUCTION_LIMIT = 1_000_000
MAX_CONSECUTIVE_TIMEOUTS = 3
```

**Step 4: Install the instruction-count hook in `_load_lua()`**

In `vj_server/patterns.py`, method `LuaPattern._load_lua()` (around line 159), the hook must be installed **before** the sandbox removes `debug`. Replace lines 166-177 with:

```python
        # Install instruction-count hook BEFORE sandbox removes debug.
        # This prevents infinite-loop patterns from blocking the event loop.
        self._lua.execute(f"""
            __hook_count = 0
            __hook_limit = {LUA_INSTRUCTION_LIMIT}
            debug.sethook(function()
                __hook_count = __hook_count + 1
                if __hook_count > __hook_limit then
                    error("pattern exceeded instruction limit")
                end
            end, "", {LUA_HOOK_INTERVAL})
        """)

        # Expose a reset function callable from Python before each calculate()
        self._lua.execute("""
            function __reset_hook()
                __hook_count = 0
            end
        """)
        self._reset_hook = self._lua.globals()["__reset_hook"]

        # Sandbox: remove dangerous globals before loading any pattern code
        self._lua.execute("""
            os = nil; io = nil; debug = nil; package = nil
            require = nil; load = nil; loadfile = nil; dofile = nil
            collectgarbage = nil; rawget = nil; rawset = nil
            pcall = nil; xpcall = nil; rawequal = nil; rawlen = nil
            string.dump = nil; string.rep = nil
        """)
```

Also add `self._reset_hook = None` to `__init__()` (around line 143, near the other `self._` assignments):

```python
        self._reset_hook = None
```

**Step 5: Call reset hook before each `calculate()`**

In `vj_server/patterns.py`, method `calculate_entities()` (around line 468), add the reset call just before the Lua call. Find the line:

```python
            result = self._calculate(audio_table, config_table, dt)
```

Add immediately before it:

```python
            # Reset instruction counter before each Lua call
            if self._reset_hook is not None:
                self._reset_hook()
```

**Step 6: Run test to verify it passes**

Run: `cd vj_server && python -m pytest tests/test_patterns.py::TestLuaTimeout::test_infinite_loop_returns_empty_entities -v --timeout=10`
Expected: PASS (should complete in < 1 second, not hang)

**Step 7: Run full test suite to verify no regressions**

Run: `cd vj_server && python -m pytest tests/test_patterns.py -v`
Expected: All tests PASS

**Step 8: Commit**

```bash
git add vj_server/patterns.py vj_server/tests/test_patterns.py
git commit -m "feat(patterns): add Lua instruction-count timeout protection

Install debug.sethook before sandbox to count instructions during
calculate(). Raises Lua error if a pattern exceeds 1M instructions,
preventing infinite loops from blocking the event loop."
```

---

### Task 2: Add consecutive-timeout auto-disable logic

**Files:**
- Modify: `vj_server/patterns.py` (calculate_entities method)
- Test: `vj_server/tests/test_patterns.py`

**Step 1: Write the failing test**

Add to `TestLuaTimeout` class in `vj_server/tests/test_patterns.py`:

```python
    def test_auto_disable_after_consecutive_timeouts(self):
        """After MAX_CONSECUTIVE_TIMEOUTS consecutive timeouts, the pattern
        should auto-disable (set _calculate to None)."""
        from vj_server.patterns import MAX_CONSECUTIVE_TIMEOUTS

        config = PatternConfig(entity_count=16)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        # Inject infinite loop
        pat._lua.execute("""
            function calculate(audio, config, dt)
                while true do end
            end
        """)
        pat._calculate = pat._lua.globals()["calculate"]
        pat._flat_mode = None

        audio = self._make_audio()

        # Each call should return empty (timeout caught)
        for i in range(MAX_CONSECUTIVE_TIMEOUTS):
            entities = pat.calculate_entities(audio)
            assert entities == [], f"Call {i+1} should return empty"

        # After MAX_CONSECUTIVE_TIMEOUTS, pattern should be disabled
        assert pat._calculate is None, "Pattern should be auto-disabled"

    def test_successful_call_resets_timeout_counter(self):
        """A successful calculate() should reset the consecutive timeout counter."""
        config = PatternConfig(entity_count=8)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        audio = self._make_audio()

        # Normal call should work
        entities = pat.calculate_entities(audio)
        assert len(entities) == 8

        # Verify internal counter is 0 (no timeouts)
        assert pat._consecutive_timeouts == 0
```

**Step 2: Run tests to verify they fail**

Run: `cd vj_server && python -m pytest tests/test_patterns.py::TestLuaTimeout -v --timeout=30`
Expected: FAIL — `_consecutive_timeouts` attribute doesn't exist yet

**Step 3: Add consecutive timeout tracking**

In `vj_server/patterns.py`, `LuaPattern.__init__()`, add after the other `self._` assignments:

```python
        self._consecutive_timeouts = 0
```

In `calculate_entities()`, modify the existing `except` block (around line 577). The current code is:

```python
        except Exception as e:
            logger.error(f"Lua pattern error ({self._pattern_key}): {e}")
            return []
```

Replace it with:

```python
        except Exception as e:
            is_timeout = "instruction limit" in str(e)
            if is_timeout:
                self._consecutive_timeouts += 1
                logger.warning(
                    "Lua pattern timeout (%s): %d/%d consecutive",
                    self._pattern_key,
                    self._consecutive_timeouts,
                    MAX_CONSECUTIVE_TIMEOUTS,
                )
                if self._consecutive_timeouts >= MAX_CONSECUTIVE_TIMEOUTS:
                    logger.error(
                        "Lua pattern auto-disabled (%s): exceeded instruction limit %d consecutive times",
                        self._pattern_key,
                        MAX_CONSECUTIVE_TIMEOUTS,
                    )
                    self._calculate = None
            else:
                logger.error("Lua pattern error (%s): %s", self._pattern_key, e)
            return []
```

Also add a reset on success. Right after the `return entities` line at the end of `calculate_entities()` (before the except), add:

```python
            self._consecutive_timeouts = 0
            return entities
```

(Replace the existing bare `return entities` — the counter reset goes immediately before it.)

**Step 4: Run tests to verify they pass**

Run: `cd vj_server && python -m pytest tests/test_patterns.py::TestLuaTimeout -v --timeout=30`
Expected: All 3 tests PASS

**Step 5: Run full test suite**

Run: `cd vj_server && python -m pytest tests/test_patterns.py -v`
Expected: All tests PASS

**Step 6: Commit**

```bash
git add vj_server/patterns.py vj_server/tests/test_patterns.py
git commit -m "feat(patterns): auto-disable patterns after consecutive timeouts

Track consecutive instruction-limit timeouts per pattern. After 3
consecutive timeouts, set _calculate to None to prevent further
attempts. Counter resets on any successful calculate() call."
```

---

### Task 3: Remove the TODO comment and verify existing patterns still work

**Files:**
- Modify: `vj_server/patterns.py` (remove stale TODO comment)
- Test: run existing test suite

**Step 1: Remove the stale TODO**

The old comment block (lines 166-170 in original, now shifted) that said:

```python
        # NOTE: LuaRuntime is not thread-safe, so we cannot use a thread-based
        # timeout for calculate(). If a pattern infinite-loops, it will block
        # the event loop. TODO: investigate signal-based or instruction-count
        # hooks for Lua execution timeout.
```

This is now resolved. It was replaced by the hook installation code in Task 1. Verify it's gone — if any remnant remains, delete it.

**Step 2: Run full test suite**

Run: `cd vj_server && python -m pytest tests/ -v`
Expected: All tests PASS

**Step 3: Spot-check that a few real patterns still work normally**

Run a quick Python snippet to verify the Galaxy pattern (one of the heaviest) completes without timeout:

```bash
cd vj_server && python -c "
from vj_server.patterns import LuaPattern, PatternConfig, AudioState
pat = LuaPattern('galaxy', PatternConfig(entity_count=64))
audio = AudioState(bands=[0.8,0.6,0.5,0.3,0.2], amplitude=0.7, is_beat=True, beat_intensity=0.8, frame=100, bpm=128.0, beat_phase=0.5)
for i in range(100):
    entities = pat.calculate_entities(audio)
print(f'Galaxy: {len(entities)} entities, no timeout - OK')
"
```

Expected: `Galaxy: 64 entities, no timeout - OK`

**Step 4: Commit**

```bash
git add vj_server/patterns.py
git commit -m "chore(patterns): remove resolved TODO for Lua timeout protection"
```
