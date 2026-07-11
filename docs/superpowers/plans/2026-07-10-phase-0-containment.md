# MCAV Phase 0 Containment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Contain the known v1 security and distribution risks without beginning the MCAV v2 rewrite: eliminate remote Lua execution, contain static files on Windows, make Minecraft WebSocket authentication fail closed, fix browser and service authentication fail-open cases, and quarantine unsupported updater/Docker/demo release paths.

**Architecture:** Keep the current v1 runtime topology intact, but narrow every exposed trust boundary. The VJ server remains the authoritative renderer during containment. Minecraft WebSocket servers bind to loopback by default and use one shared-secret handshake contract. Browser OAuth state is single-use and mandatory. Legacy distribution paths remain in source history but cannot update clients or publish unsupported artifacts.

**Tech Stack:** Python 3.12 in WSL, pytest, Rust 1.93/Cargo, Tauri 2, TypeScript/React/Vitest, Java 21/JUnit 5/Mockito, Paper, Fabric, JSON Schema, GitHub Actions, Docker Compose.

## Global Constraints

- This plan implements only Phase 0 from `docs/superpowers/specs/2026-07-10-mcav-v2-platform-design.md`. Phase 1 requires its own approved design and implementation plan.
- Before implementation, invoke `superpowers:using-git-worktrees`, `superpowers:subagent-driven-development`, and `superpowers:test-driven-development`. Before any completion claim, invoke `superpowers:verification-before-completion`.
- Preserve the existing dirty worktree. In particular, do not edit or stage the current untracked `vj_server/tests/test_relay.py`, coordinator service/test work, admin-panel modules, `.codex/`, `.superpowers/`, or `AGENTS.md`.
- Implement and verify in the clean linked worktree `.worktrees/phase0-containment`; run every relative PowerShell command from that worktree. The original workspace remains an untouched reference for the user's untracked work.
- Never run `git add .` or `git add -A`. Every commit in this plan lists its exact paths.
- Use WSL-native Python only. Each independent Python package gets its own ignored WSL venv at `<package>/.venv`; never use the Windows-native root `.venv`.
- Do not log shared secrets, OAuth state, bearer tokens, or updater signing material.
- No new runtime dependency is needed for containment. Prefer standard-library constant-time comparison and path handling.
- Preserve source files that are useful migration references. `dj_client/src-tauri/src/patterns.rs` and `dj_client/src/hooks/useAutoUpdate.ts` become uncompiled/inert tombstones rather than being deleted.
- A non-loopback bind with an empty Minecraft WebSocket secret must keep only the WebSocket listener offline; it must not crash the Minecraft server.
- Existing loopback-only development remains usable without a shared secret.
- Minecraft WebSocket listeners are native-service endpoints, not browser endpoints. Reject any connection carrying an `Origin` header before sending the welcome message, including on loopback, to prevent cross-site WebSocket access from a malicious page.
- All security and release-gating checks fail closed. No `continue-on-error`, `|| true`, empty-secret equality, missing OAuth-state bypass, or report-only green gate is permitted on those paths.
- Commit each task only after its focused tests pass. If a focused test exposes an adjacent bug in the same trust boundary, fix it in that task before committing.

---

### Task 0: Establish an isolated execution baseline

**Files:**

- Do not modify tracked files in this task.

- [ ] **Step 1: Capture the current repository state without cleaning it**

Run from PowerShell:

```powershell
git status --short
git diff --check
git branch --show-current
```

Expected: the previously identified untracked user files remain present in the original workspace; the committed specification, audit, and this plan are ahead of upstream; `git diff --check` exits 0.

- [ ] **Step 2: Create or select the implementation branch without touching untracked files**

Follow `superpowers:using-git-worktrees` and create a clean linked worktree from the reviewed planning commit:

```powershell
$OriginalWorkspace = (Get-Location).Path
git worktree add .worktrees/phase0-containment -b fix/phase-0-containment
Set-Location .worktrees/phase0-containment
git status --short
git -C $OriginalWorkspace status --short
```

Expected: the linked worktree is clean on `fix/phase-0-containment`; the original workspace still lists exactly the pre-existing untracked paths. If the clean baseline needs an untracked file, stop and resolve its ownership with the user instead of copying or committing it implicitly.

- [ ] **Step 3: Prepare WSL-native Python and lockfile-backed Node environments**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server; uv venv --python 3.12 .venv; uv pip install --python .venv/bin/python -e . pytest pytest-asyncio pytest-timeout pytest-cov ruff pip-audit bandit'
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/community_bot; uv venv --python 3.12 .venv; uv pip install --python .venv/bin/python -e ".[dev]" pip-audit'
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/coordinator; uv venv --python 3.12 .venv; uv pip install --python .venv/bin/python -e ".[dev]" pip-audit'
npm ci
npm --prefix site ci
npm --prefix dj_client ci
npm --prefix worker ci
```

Expected: each `.venv/bin/python --version` reports Python 3.12; the venv and `node_modules` directories remain ignored by Git; all npm installs honor the committed lockfiles without changing them.

- [ ] **Step 4: Record the pre-change focused baseline**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server && .venv/bin/python -m pytest tests/test_dj_manager.py tests/test_models.py tests/test_config.py -o addopts="" -q'
npm --prefix site test -- src/lib/__tests__/auth.test.ts
cargo test --manifest-path dj_client/src-tauri/Cargo.toml --locked
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
Push-Location minecraft_plugin; .\mvnw.cmd test -q; Pop-Location
Push-Location minecraft_mod; .\gradlew.bat test; Pop-Location
```

Expected: capture exact pre-existing failures before adding containment tests. Do not repair unrelated untracked work in this task.

---

### Task 1: Remove remote Lua delivery and the compiled DJ-client Lua runtime

**Files:**

- Modify: `vj_server/dj_manager.py`
- Modify: `vj_server/stage_manager.py`
- Modify: `vj_server/tests/test_dj_manager.py`
- Modify: `protocol/schemas/messages/stream-route.schema.json`
- Modify: `protocol/README.md`
- Modify: `dj_client/src-tauri/src/protocol/messages.rs`
- Modify: `dj_client/src-tauri/src/protocol/client.rs`
- Modify: `dj_client/src-tauri/src/lib.rs`
- Modify: `dj_client/src-tauri/Cargo.toml`
- Modify: `dj_client/src-tauri/Cargo.lock`
- Preserve uncompiled: `dj_client/src-tauri/src/patterns.rs`

- [ ] **Step 1: Add a VJ regression test that requires relay-only routes and no source code**

Add a test that builds a real server state, including a DJ requesting direct mode:

```python
def test_stream_route_is_relay_only_and_omits_pattern_scripts() -> None:
    from vj_server.vj_server import VJServer

    server = VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)
    dj = DJConnection(dj_id="dj-1", dj_name="Containment DJ", websocket=None, direct_mode=True)
    server._djs[dj.dj_id] = dj
    server._active_dj_id = dj.dj_id

    route = server._build_stream_route_message(dj.dj_id, dj)

    assert route["route_mode"] == "relay"
    assert route["reason"] == "phase0_remote_execution_disabled"
    assert "pattern_scripts" not in route
    assert "minecraft_host" not in route
    assert "minecraft_port" not in route
```

- [ ] **Step 2: Add Rust regression coverage for hostile legacy fields**

Extend the stream-route deserialization test with a legacy payload containing executable-looking Lua. Add a client-handler test with this shape:

```rust
#[tokio::test]
async fn legacy_stream_route_scripts_are_ignored() {
    let state = Arc::new(Mutex::new(ConnectionState::default()));
    let (tx, _rx) = mpsc::channel(1);
    let input = r#"{
      "type": "stream_route",
      "route_mode": "relay",
      "preset": "edm",
      "pattern_scripts": {
        "hostile": "os.execute('calc.exe')"
      }
    }"#;
    let message: ServerMessage = serde_json::from_str(input).expect("legacy route must remain parseable");

    handle_server_message(&state, &tx, message).await;

    let current = state.lock();
    assert_eq!(current.route_mode, "relay");
    assert_eq!(current.pending_preset.as_deref(), Some("edm"));
}
```

- [ ] **Step 3: Run the new tests and observe the containment failure**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server && .venv/bin/python -m pytest tests/test_dj_manager.py -o addopts="" -q'
cargo test --manifest-path dj_client/src-tauri/Cargo.toml legacy_stream_route_scripts_are_ignored --locked
```

Expected before implementation: the VJ test finds `route_mode == "dual"` and `pattern_scripts`; the Rust state still stores remote scripts or the new test cannot compile against the intended reduced state.

- [ ] **Step 4: Make every VJ route relay-only and remove source delivery**

Replace `_build_stream_route_message` with a route that contains metadata only:

```python
def _build_stream_route_message(self, dj_id: str, dj: DJConnection) -> dict:
    """Build the Phase 0 relay-only routing policy for a DJ client."""
    is_active = self._active_dj_id == dj_id
    return {
        "type": "stream_route",
        "route_mode": "relay",
        "is_active": is_active,
        "zone": self.zone,
        "entity_count": self.entity_count,
        "current_pattern": self._pattern_name,
        "pattern_config": {
            "entity_count": self.entity_count,
            "zone_size": self._pattern_config.zone_size,
            "beat_boost": self._pattern_config.beat_boost,
            "base_scale": self._pattern_config.base_scale,
            "max_scale": self._pattern_config.max_scale,
        },
        "band_sensitivity": list(self._band_sensitivity),
        "preset": self._current_preset_name,
        "relay_fallback": True,
        "reason": "phase0_remote_execution_disabled",
    }
```

Also force the initial auth response route hint to `relay`, stop attaching direct Minecraft connection fields to either the auth response or stream route, remove the now-unused `StageManagerMixin._get_pattern_scripts`, and document in `protocol/README.md` that legacy clients may still send/receive the optional host fields but Phase 0 servers do not use them for direct rendering.

- [ ] **Step 5: Remove the source-bearing protocol field and every compiled Rust execution path**

Remove `pattern_scripts` from the JSON Schema and `StreamRouteMessage`. Remove `ConnectionState.pending_pattern_scripts`, its default, `take_pending_pattern_scripts`, and the stream-route handler assignment. In `lib.rs`, remove `pub mod patterns`, the `pattern_engine` local, and all `PatternEngine` loading/switch/config branches. Keep ordinary preset, route, roster, and UI state handling intact.

Remove `mlua` from `Cargo.toml`, then regenerate the lockfile using Cargo rather than hand-editing it:

```powershell
cargo check --manifest-path dj_client/src-tauri/Cargo.toml
```

Expected: `patterns.rs` remains in the repository but is not reachable from the crate; `Cargo.lock` has no `mlua`, `lua-src`, or `luajit-src` packages.

- [ ] **Step 6: Verify both positive compatibility and negative execution assertions**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server && .venv/bin/python -m pytest tests/test_dj_manager.py tests/test_stage_manager.py -o addopts="" -q'
cargo test --manifest-path dj_client/src-tauri/Cargo.toml --locked
cargo clippy --manifest-path dj_client/src-tauri/Cargo.toml --locked -- -D warnings
rg -n "pending_pattern_scripts|route\.pattern_scripts|pub mod patterns|mlua" vj_server/dj_manager.py protocol/schemas/messages/stream-route.schema.json dj_client/src-tauri/src dj_client/src-tauri/Cargo.toml dj_client/src-tauri/Cargo.lock --glob '!patterns.rs'
```

Expected: tests and Clippy exit 0; the final `rg` exits 1 with no matches in the listed active runtime files.

- [ ] **Step 7: Commit only the remote-execution containment paths**

```powershell
git add -- vj_server/dj_manager.py vj_server/stage_manager.py vj_server/tests/test_dj_manager.py protocol/schemas/messages/stream-route.schema.json protocol/README.md dj_client/src-tauri/src/protocol/messages.rs dj_client/src-tauri/src/protocol/client.rs dj_client/src-tauri/src/lib.rs dj_client/src-tauri/Cargo.toml dj_client/src-tauri/Cargo.lock
git diff --cached --check
git commit -m "fix(security): remove remote Lua execution path"
```

---

### Task 2: Make static-file resolution Windows-safe and loopback-only by default

**Files:**

- Modify: `vj_server/models.py`
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/cli.py`
- Modify: `vj_server/config.py`
- Create: `vj_server/tests/test_static_http.py`
- Modify: `vj_server/tests/test_config.py`
- Modify: `.env.example`

- [ ] **Step 1: Write traversal and bind-default tests first**

Create `test_static_http.py` with ordinary paths and the exact exploit classes:

```python
from inspect import signature
from pathlib import Path

import pytest

from vj_server.models import _resolve_static_path, run_http_server


@pytest.mark.parametrize(
    "raw_path",
    [
        "..%5csecret.txt",
        "safe/..\\..\\secret.txt",
        "%2e%2e/%2e%2e/secret.txt",
        "C:%5cWindows%5cwin.ini",
        "//server/share/secret.txt",
        "CON",
        "AUX.txt",
        "file.txt::$DATA",
        "%00secret.txt",
    ],
)
def test_resolver_rejects_cross_platform_escape(tmp_path: Path, raw_path: str) -> None:
    assert _resolve_static_path(tmp_path, raw_path) is None


def test_resolver_allows_file_inside_root(tmp_path: Path) -> None:
    asset = tmp_path / "assets" / "app.js"
    asset.parent.mkdir()
    asset.write_text("safe", encoding="utf-8")
    assert _resolve_static_path(tmp_path, "assets/app.js") == asset.resolve()


def test_resolver_rejects_symlink_escape(tmp_path: Path) -> None:
    root = tmp_path / "root"
    outside = tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    (outside / "secret.txt").write_text("secret", encoding="utf-8")
    (root / "escape").symlink_to(outside, target_is_directory=True)
    assert _resolve_static_path(root, "escape/secret.txt") is None


def test_http_server_defaults_to_loopback() -> None:
    assert signature(run_http_server).parameters["host"].default == "127.0.0.1"
```

Extend `test_config.py` to assert `ServerConfig().http_host == "127.0.0.1"` and that `HTTP_HOST=0.0.0.0` is an explicit opt-in.

- [ ] **Step 2: Run the tests and confirm they fail on the missing resolver and host**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server && .venv/bin/python -m pytest tests/test_static_http.py tests/test_config.py -o addopts="" -q'
```

Expected before implementation: import or assertion failures for `_resolve_static_path`, `http_host`, and the loopback default.

- [ ] **Step 3: Implement one canonical resolver used by both handlers**

Use `Path.resolve()` after normalizing both separator styles and rejecting Windows drive/UNC/NUL inputs:

```python
def _resolve_static_path(base: str | Path, raw_path: str) -> Path | None:
    parsed = urllib.parse.urlsplit(raw_path)
    if parsed.scheme or parsed.netloc:
        return None

    decoded = urllib.parse.unquote(parsed.path)
    if "\x00" in decoded:
        return None

    normalized = decoded.replace("\\", "/")
    if normalized.startswith("//"):
        return None

    relative_text = normalized.lstrip("/")
    windows_path = PureWindowsPath(relative_text)
    if windows_path.drive or windows_path.root:
        return None

    parts = [part for part in PurePosixPath(relative_text).parts if part not in ("", ".")]
    if ".." in parts:
        return None

    reserved_names = {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    }
    for part in parts:
        windows_name = part.rstrip(" .")
        device_name = windows_name.split(".", 1)[0].upper()
        if ":" in windows_name or device_name in reserved_names:
            return None

    root = Path(base).resolve()
    candidate = root.joinpath(*parts).resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        return None
    return candidate
```

Both `_make_directory_handler` and `MultiDirectoryHandler` must call this helper. A rejected path translates to a fixed non-existent child inside the selected root, never to a parent or OS special file.

- [ ] **Step 4: Thread the explicit HTTP bind host through all entry points**

Change the server signature to:

```python
def run_http_server(port: int, directory: str, host: str = "127.0.0.1") -> None:
```

Bind with `(host, port)`. Add `http_host: str = "127.0.0.1"` to `ServerConfig` and `VJServer`; load `HTTP_HOST`; add `--http-host` to both `vj_server/cli.py` and the legacy `vj_server.py` CLI; pass it into the HTTP thread. Update `.env.example` with the safe default and an explicit warning for `0.0.0.0`.

- [ ] **Step 5: Verify focused and existing handler tests**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server && .venv/bin/python -m pytest tests/test_static_http.py tests/test_models.py tests/test_config.py tests/test_vj_server_helpers.py -o addopts="" -q'
```

Expected: all selected tests pass; normal admin/preview assets resolve; every traversal and symlink escape returns `None`.

- [ ] **Step 6: Commit the static-server boundary**

```powershell
git add -- vj_server/models.py vj_server/vj_server.py vj_server/cli.py vj_server/config.py vj_server/tests/test_static_http.py vj_server/tests/test_config.py .env.example
git diff --cached --check
git commit -m "fix(security): contain VJ static file server"
```

---

### Task 3: Make browser OAuth state mandatory and single-use

**Files:**

- Modify: `site/src/lib/auth.ts`
- Modify: `site/src/lib/__tests__/auth.test.ts`
- Modify: `site/src/app/login/page.tsx`
- Modify: `site/src/app/auth/callback/page.tsx`

- [ ] **Step 1: Add fail-closed state-consumption tests**

Add the import and these cases to the existing OAuth-state test group:

```typescript
it("rejects a callback when no state was stored", () => {
  expect(consumeAndValidateOAuthState("received")).toBe(false);
});

it("rejects and consumes a mismatched state", () => {
  storeOAuthState("expected");
  expect(consumeAndValidateOAuthState("received")).toBe(false);
  expect(getStoredOAuthState()).toBeNull();
});

it("rejects empty states", () => {
  storeOAuthState("");
  expect(consumeAndValidateOAuthState("")).toBe(false);
});

it("accepts an exact state once", () => {
  storeOAuthState("state_abc");
  expect(consumeAndValidateOAuthState("state_abc")).toBe(true);
  expect(consumeAndValidateOAuthState("state_abc")).toBe(false);
});

it("does not exchange a code when callback state is missing", async () => {
  const exchange = vi.fn().mockResolvedValue({ access_token: "unused" });
  await expect(
    exchangeOAuthCodeWithValidatedState("code", "received", exchange)
  ).rejects.toThrow("Security validation failed");
  expect(exchange).not.toHaveBeenCalled();
});

it("does not exchange a code when callback state mismatches", async () => {
  storeOAuthState("expected");
  const exchange = vi.fn().mockResolvedValue({ access_token: "unused" });
  await expect(
    exchangeOAuthCodeWithValidatedState("code", "received", exchange)
  ).rejects.toThrow("Security validation failed");
  expect(exchange).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Run the focused test and observe the missing helper**

```powershell
npm --prefix site test -- src/lib/__tests__/auth.test.ts
```

Expected before implementation: the new helpers are missing; the current component branches would exchange a code when stored state is absent.

- [ ] **Step 3: Implement one consume-and-validate helper**

Add this beside the existing storage helpers:

```typescript
export function consumeAndValidateOAuthState(receivedState: string): boolean {
  const storedState = getStoredOAuthState();
  clearStoredOAuthState();
  return (
    storedState !== null &&
    storedState.length > 0 &&
    receivedState.length > 0 &&
    storedState === receivedState
  );
}

export async function exchangeOAuthCodeWithValidatedState<T>(
  code: string,
  state: string,
  exchange: (code: string, state: string) => Promise<T>
): Promise<T> {
  if (!consumeAndValidateOAuthState(state)) {
    throw new Error("Security validation failed. Please try signing in again.");
  }
  return exchange(code, state);
}
```

Replace both browser callback flows with `exchangeOAuthCodeWithValidatedState`. A missing stored state must show the same security-validation error as a mismatch and can no longer reach an exchange function by construction. Preserve the explicit desktop branch in `auth/callback/page.tsx`; the coordinator validates that signed desktop state.

- [ ] **Step 4: Verify unit, lint, and production build behavior**

```powershell
npm --prefix site test -- src/lib/__tests__/auth.test.ts
npm --prefix site run lint
npm --prefix site run build
```

Expected: all commands exit 0; both browser flows use `exchangeOAuthCodeWithValidatedState`; missing/mismatched state leaves both exchange mocks untouched; the desktop branch still precedes browser state consumption.

- [ ] **Step 5: Commit the browser OAuth boundary**

```powershell
git add -- site/src/lib/auth.ts site/src/lib/__tests__/auth.test.ts site/src/app/login/page.tsx site/src/app/auth/callback/page.tsx
git diff --cached --check
git commit -m "fix(security): require single-use OAuth state"
```

---

### Task 4: Fail closed on the community-bot webhook secret

**Files:**

- Modify: `community_bot/config.py`
- Modify: `community_bot/webhook_server.py`
- Create: `community_bot/tests/test_config.py`
- Create: `community_bot/tests/test_webhook_server.py`
- Modify: `.env.example`

- [ ] **Step 1: Add startup and request-boundary tests**

Cover missing and whitespace-only `MCAV_WEBHOOK_SECRET` in `test_config.py`. In `test_webhook_server.py`, create a minimal fake bot/config and cover empty configured secret, missing header, wrong header, and the correct header. The critical empty/empty case is:

```python
async def test_empty_configured_secret_never_authenticates(aiohttp_client) -> None:
    bot = FakeBot(webhook_secret="")
    client = await aiohttp_client(create_webhook_app(bot))
    response = await client.post(
        "/notify/role-change",
        json={"discord_id": "1", "roles": []},
    )
    assert response.status == 503
```

- [ ] **Step 2: Run the tests and reproduce the empty-secret bypass**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/community_bot && .venv/bin/python -m pytest tests/test_config.py tests/test_webhook_server.py -o addopts="" -q'
```

Expected before implementation: `Config.from_env()` accepts an empty secret and the missing header authenticates against it.

- [ ] **Step 3: Validate configuration and compare non-empty secrets in constant time**

In `Config.from_env`, strip and require the secret before constructing the dataclass:

```python
webhook_secret = os.environ.get("MCAV_WEBHOOK_SECRET", "").strip()
if not webhook_secret:
    raise RuntimeError("MCAV_WEBHOOK_SECRET is required")
```

At the request boundary, use defense in depth:

```python
configured_secret = bot.config.webhook_secret.strip()
provided_secret = request.headers.get("X-Webhook-Secret", "")
if not configured_secret:
    _logger.error("Webhook endpoint disabled because MCAV_WEBHOOK_SECRET is empty")
    return web.json_response({"error": "webhook unavailable"}, status=503)
if not provided_secret or not hmac.compare_digest(provided_secret, configured_secret):
    return web.json_response({"error": "unauthorized"}, status=401)
```

Update `.env.example` to mark `MCAV_WEBHOOK_SECRET` required and show a generation command without including a real secret.

- [ ] **Step 4: Verify the full community-bot suite**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/community_bot && .venv/bin/python -m pytest -q && .venv/bin/python -m ruff check .'
```

Expected: all tests and Ruff checks pass.

- [ ] **Step 5: Commit the webhook boundary**

```powershell
git add -- community_bot/config.py community_bot/webhook_server.py community_bot/tests/test_config.py community_bot/tests/test_webhook_server.py .env.example
git diff --cached --check
git commit -m "fix(security): require community webhook secret"
```

---

### Task 5: Protect coordinator metrics in every configured environment

**Files:**

- Modify: `coordinator/app/config.py`
- Modify: `coordinator/app/routers/metrics.py`
- Modify: `coordinator/tests/conftest.py`
- Create: `coordinator/tests/test_metrics_security.py`
- Modify: `.env.example`

- [ ] **Step 1: Add startup and endpoint matrix tests**

Create tests proving that missing/blank tokens fail settings construction in production, staging, and development. The endpoint matrix must assert missing/wrong/correct bearer values produce 401/401/200 with a configured token.

```python
@pytest.mark.parametrize("environment", ["production", "staging", "development"])
@pytest.mark.parametrize("metrics_token", [None, "", "   "])
def test_every_environment_requires_metrics_token(
    environment: str, metrics_token: str | None
) -> None:
    with pytest.raises(ValueError, match="MCAV_METRICS_TOKEN"):
        Settings(
            mcav_env=environment,
            metrics_token=metrics_token,
            user_jwt_secret="production-test-secret-that-is-at-least-32-chars",
        )
```

- [ ] **Step 2: Run the focused test and observe the public metrics behavior**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/coordinator && .venv/bin/python -m pytest tests/test_metrics_security.py -o addopts="" -q'
```

Expected before implementation: settings accept missing tokens and configured development bypasses authentication.

- [ ] **Step 3: Validate production startup and enforce configured tokens universally**

Call `_validate_metrics_token()` from `model_post_init` after JWT validation:

```python
def _validate_metrics_token(self) -> None:
    token = (self.metrics_token or "").strip()
    if not token:
        raise ValueError("MCAV_METRICS_TOKEN is required")
    self.metrics_token = token
```

Change the `mcav_env` field default from `development` to `production`. Set `MCAV_METRICS_TOKEN` to a non-secret test-only value in `tests/conftest.py` before importing the application, and include that value in the shared `Settings` fixture. Local development must configure its own token; there is no unauthenticated metrics mode.

Use constant-time bearer validation in the router:

```python
token = (settings.metrics_token or "").strip()
if not token:
    raise HTTPException(status_code=503, detail="Metrics authentication is not configured")

authorization = request.headers.get("authorization", "")
provided = authorization[7:] if authorization.startswith("Bearer ") else ""
if not provided or not hmac.compare_digest(provided, token):
    raise HTTPException(status_code=401, detail="Invalid or missing metrics token")
```

- [ ] **Step 4: Verify focused and full coordinator suites**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/coordinator && .venv/bin/python -m pytest tests/test_metrics_security.py -o addopts="" -q && .venv/bin/python -m pytest -q && .venv/bin/python -m ruff check app tests'
```

Expected: all commands exit 0; every environment requires and enforces a configured token.

- [ ] **Step 5: Commit the metrics boundary**

```powershell
git add -- coordinator/app/config.py coordinator/app/routers/metrics.py coordinator/tests/conftest.py coordinator/tests/test_metrics_security.py .env.example
git diff --cached --check
git commit -m "fix(security): fail closed on metrics authentication"
```

---

### Task 6: Define and implement the VJ-to-Minecraft authentication handshake

**Files:**

- Create: `protocol/schemas/messages/connected.schema.json`
- Create: `protocol/schemas/messages/ws-auth.schema.json`
- Create: `protocol/schemas/messages/ws-auth-ok.schema.json`
- Modify: `protocol/schemas/index.json`
- Modify: `protocol/README.md`
- Create: `protocol/tests/phase0-schemas.test.mjs`
- Modify: `vj_server/viz_client.py`
- Modify: `vj_server/config.py`
- Modify: `vj_server/cli.py`
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/relay.py`
- Create: `vj_server/tests/test_viz_client_auth.py`
- Create: `vj_server/tests/test_minecraft_connection.py`
- Modify: `vj_server/tests/test_config.py`
- Modify: `.env.example`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add the protocol schemas**

Use these exact message shapes:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "connected.schema.json",
  "title": "MinecraftConnectedMessage",
  "type": "object",
  "additionalProperties": true,
  "properties": {
    "type": { "const": "connected" },
    "v": { "type": "string" },
    "auth_required": { "type": "boolean" },
    "message": { "type": "string" },
    "version": { "type": "string" },
    "server_type": { "enum": ["paper", "fabric"] }
  },
  "required": ["type", "auth_required", "server_type"]
}
```

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "ws-auth.schema.json",
  "title": "MinecraftWebSocketAuthMessage",
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "type": { "const": "auth" },
    "v": { "type": "string" },
    "token": { "type": "string", "minLength": 1, "maxLength": 1024 }
  },
  "required": ["type", "token"]
}
```

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "ws-auth-ok.schema.json",
  "title": "MinecraftWebSocketAuthOkMessage",
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "type": { "const": "auth_ok" },
    "v": { "type": "string" }
  },
  "required": ["type"]
}
```

Document the sequence: `connected(auth_required)` → optional `auth(token)` → `auth_ok`. Bad/missing credentials close with 4001; the five-second deadline closes with 4002; a browser-style handshake carrying `Origin` closes with 4003 before `connected`.

Add `connected`, `ws_auth`, and `ws_auth_ok` to `protocol/schemas/index.json`. The optional `v` field is declared on all three schemas to preserve the repository-wide envelope rule.

- [ ] **Step 2: Write fake-WebSocket client tests before changing `VizClient`**

Cover unauthenticated loopback, successful authentication, missing local token, wrong token/close, auth timeout, heartbeat receive-loop ordering, and reconnect reusing the token. Assert that the token never appears in captured logs.

Create `protocol/tests/phase0-schemas.test.mjs` using `node:test` and standard-library JSON parsing. It must assert that all three inventory paths exist, each schema declares optional `v`, and representative versioned/unversioned messages satisfy required fields, const values, enums, string bounds, and `additionalProperties`. This focused dependency-free validator does not replace a future full protocol conformance harness.

Add connection wiring coverage in the new `test_minecraft_connection.py`; do not touch the current untracked `test_relay.py`.

- [ ] **Step 3: Run the tests and confirm the client cannot authenticate**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server && .venv/bin/python -m pytest tests/test_viz_client_auth.py tests/test_minecraft_connection.py tests/test_config.py -o addopts="" -q'
node --test protocol/tests/phase0-schemas.test.mjs
```

Expected before implementation: `VizClient` has no token, ignores `auth_required`, and `RelayMixin` constructs it without credentials.

- [ ] **Step 4: Add an auth token and one handshake reader to `VizClient`**

Add `auth_token: str | None = None` to the constructor and store the stripped token. Use a helper that works with or without the receive loop:

```python
async def _next_handshake_message(self) -> dict[str, Any]:
    if self._use_receive_loop:
        return await asyncio.wait_for(
            self._unmatched_responses.get(), timeout=self.connect_timeout
        )
    if self.ws is None:
        raise RuntimeError("WebSocket transport is not connected")
    raw = await asyncio.wait_for(self.ws.recv(), timeout=self.connect_timeout)
    return mjson.decode(raw)
```

During `connect()`, require the first message to be `connected`. If `auth_required` is true, require a non-empty local token, send `{"type":"auth","token": self.auth_token}`, and require `auth_ok` before returning true. On every failure, close the socket, cancel tasks/futures, set `_connected` false, and return false. Start heartbeat only after authentication completes; the receive loop may start earlier as the sole `recv()` consumer.

- [ ] **Step 5: Thread the token from environment/CLI to every reconnect**

Add `minecraft_ws_secret: str | None` to `ServerConfig` and `VJServer`. Load `MINECRAFT_WS_SECRET`; add `--minecraft-ws-secret`; pass the value through `RelayMixin.connect_minecraft()` into `VizClient(auth_token=self.minecraft_ws_secret)`. Never include it in status responses or logs. Update `.env.example` and the VJ service in `docker-compose.yml`.

- [ ] **Step 6: Verify client handshake and configuration propagation**

```powershell
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server && .venv/bin/python -m pytest tests/test_viz_client_auth.py tests/test_minecraft_connection.py tests/test_config.py tests/test_viz_client_bitmap.py tests/test_reconnect_stage_rehydrate.py -o addopts="" -q'
node --test protocol/tests/phase0-schemas.test.mjs
```

Expected: all selected tests pass for heartbeat and non-heartbeat modes; missing required credentials return false; reconnect uses the same credential.

- [ ] **Step 7: Commit the handshake contract and VJ client**

```powershell
git add -- protocol/schemas/messages/connected.schema.json protocol/schemas/messages/ws-auth.schema.json protocol/schemas/messages/ws-auth-ok.schema.json protocol/schemas/index.json protocol/README.md protocol/tests/phase0-schemas.test.mjs vj_server/viz_client.py vj_server/config.py vj_server/cli.py vj_server/vj_server.py vj_server/relay.py vj_server/tests/test_viz_client_auth.py vj_server/tests/test_minecraft_connection.py vj_server/tests/test_config.py .env.example docker-compose.yml
git diff --cached --check
git commit -m "fix(security): authenticate Minecraft relay connections"
```

---

### Task 7: Enforce the handshake and safe bind policy in Paper

**Files:**

- Create: `minecraft_plugin/src/main/java/com/audioviz/websocket/WebSocketSecurityPolicy.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/websocket/WebSocketSecurityPolicyTest.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerAuthTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/main/resources/config.yml`

- [ ] **Step 1: Write policy and server-integration tests**

Cover `127.0.0.1`, `localhost`, and `::1` with no secret; reject `0.0.0.0` and ordinary LAN addresses with no secret; accept non-loopback only with a non-empty secret; accept only the exact token; reject missing, whitespace, malformed JSON, and wrong tokens. The server-integration test must also reject a handshake carrying any non-empty `Origin` header before emitting `connected`.

In `VizWebSocketServerAuthTest`, use Mockito WebSocket, handler, queue, connection-listener, and timeout dependencies to prove: a secretless loopback-native client is admitted with `auth_required=false`; an authenticated configuration emits `auth_required=true`; a pre-auth batch update never reaches the queue; a correct token emits `auth_ok` and permits the next update; a wrong token closes with 4001; the captured timeout closes with 4002; an Origin handshake closes with 4003; pending/rejected clients are absent from active connection counts, heartbeat state, broadcasts, and connect/disconnect events. Add a package-private dependency-injection constructor for these tests while keeping the public production constructor unchanged.

- [ ] **Step 2: Run the tests and confirm the policy does not exist**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location minecraft_plugin
.\mvnw.cmd -Dtest=WebSocketSecurityPolicyTest,VizWebSocketServerAuthTest test
Pop-Location
```

Expected before implementation: test compilation fails because the policy class is absent.

- [ ] **Step 3: Implement the pure policy with constant-time comparison**

Create the policy around this core:

```java
public final class WebSocketSecurityPolicy {
    private final String secret;

    public WebSocketSecurityPolicy(String secret) {
        this.secret = secret == null ? "" : secret.strip();
    }

    public boolean requiresAuthentication() {
        return !secret.isEmpty();
    }

    public boolean tokenMatches(String candidate) {
        if (!requiresAuthentication() || candidate == null || candidate.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            secret.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    public static boolean isSafeConfiguration(String address, String secret) {
        String normalizedSecret = secret == null ? "" : secret.strip();
        if (address == null || address.isBlank()) {
            return false;
        }
        if (!normalizedSecret.isEmpty()) {
            return true;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Gate every server operation on authenticated state**

Default `websocket.address` to `127.0.0.1`. In `AudioVizPlugin`, validate address/secret before scheduling the listener; on unsafe configuration, log one severe remediation message and leave the listener offline.

On open, first close with 4003 any connection whose `ClientHandshake` contains a non-empty `Origin` header. Otherwise send `connected` with `auth_required`. When auth is required, schedule the five-second timeout. Accept only an `auth` message with an exact token, set the client authenticated, send `auth_ok`, and then notify the connection-state listener. Close all other pre-auth messages with 4001. Only authenticated clients may enqueue messages, receive broadcasts/heartbeats, count as active, or trigger connection-state events.

The package-private constructor accepts injected `MessageHandler`, `MessageQueue`, `WebSocketSecurityPolicy`, and a timeout scheduler callback. The public constructor creates production dependencies, starts the queue, and delegates. Tests capture rather than sleep for the timeout callback.

- [ ] **Step 5: Verify Paper tests and package**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location minecraft_plugin
.\mvnw.cmd test
.\mvnw.cmd package -DskipTests
Pop-Location
```

Expected: both commands exit 0; the shaded JAR is produced; no unauthenticated message reaches `MessageQueue` or `MessageHandler`.

- [ ] **Step 6: Commit the Paper boundary**

```powershell
git add -- minecraft_plugin/src/main/java/com/audioviz/websocket/WebSocketSecurityPolicy.java minecraft_plugin/src/test/java/com/audioviz/websocket/WebSocketSecurityPolicyTest.java minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerAuthTest.java minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java minecraft_plugin/src/main/resources/config.yml
git diff --cached --check
git commit -m "fix(security): secure Paper WebSocket listener"
```

---

### Task 8: Apply the same bind and authentication policy to Fabric

**Files:**

- Create: `minecraft_mod/src/main/java/com/audioviz/websocket/WebSocketSecurityPolicy.java`
- Create: `minecraft_mod/src/test/java/com/audioviz/websocket/WebSocketSecurityPolicyTest.java`
- Create: `minecraft_mod/src/test/java/com/audioviz/websocket/VizWebSocketServerAuthTest.java`
- Create: `minecraft_mod/src/test/java/com/audioviz/ModConfigTest.java`
- Modify: `minecraft_mod/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_mod/src/main/java/com/audioviz/ModConfig.java`
- Modify: `minecraft_mod/src/main/java/com/audioviz/AudioVizMod.java`

- [ ] **Step 1: Write Fabric policy, config, and server-integration tests first**

Mirror the Paper policy matrix, including rejection of every handshake with a non-empty `Origin` header. Add config persistence tests proving a new config writes `websocketAddress: "127.0.0.1"` and `websocketSecret: ""`, and proving loaded whitespace secrets are normalized.

In `VizWebSocketServerAuthTest`, use the existing injectable handler/queue/server dependencies to mirror Paper's secretless-loopback admission, authenticated handshake, close-code, queue, active-count, heartbeat, broadcast, and connection-event assertions. Advance `tick()` directly for the timeout case; do not use sleeps.

- [ ] **Step 2: Run the tests and observe the open listener**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location minecraft_mod
.\gradlew.bat test --tests com.audioviz.websocket.WebSocketSecurityPolicyTest --tests com.audioviz.websocket.VizWebSocketServerAuthTest --tests com.audioviz.ModConfigTest
Pop-Location
```

Expected before implementation: policy/config fields do not exist and startup still hard-codes `0.0.0.0`.

- [ ] **Step 3: Add config and the same pure policy**

Add these fields to `ModConfig` and normalize them in `validate()`:

```java
public String websocketAddress = "127.0.0.1";
public String websocketSecret = "";
```

Implement the same `WebSocketSecurityPolicy` behavior as Paper. Duplication is intentional in Phase 0 because the two artifacts have no shared Java module.

- [ ] **Step 4: Enforce handshake, timeout, event, heartbeat, and broadcast gates**

Pass address and secret from `AudioVizMod` to `VizWebSocketServer`. If configuration is unsafe, log one error and do not start the listener while leaving the Minecraft server running. Implement the same message sequence and close codes as Paper. Drive the five-second auth timeout from `tick()` so no new scheduler or thread is required. Only authenticated clients may enter the main-thread handler future or receive outbound data.

- [ ] **Step 5: Verify Fabric tests and build**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location minecraft_mod
.\gradlew.bat test
.\gradlew.bat build
Pop-Location
```

Expected: all tests and the remapped production JAR build pass; Paper and Fabric advertise the same `auth_required` contract.

- [ ] **Step 6: Commit the Fabric boundary**

```powershell
git add -- minecraft_mod/src/main/java/com/audioviz/websocket/WebSocketSecurityPolicy.java minecraft_mod/src/test/java/com/audioviz/websocket/WebSocketSecurityPolicyTest.java minecraft_mod/src/test/java/com/audioviz/websocket/VizWebSocketServerAuthTest.java minecraft_mod/src/test/java/com/audioviz/ModConfigTest.java minecraft_mod/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_mod/src/main/java/com/audioviz/ModConfig.java minecraft_mod/src/main/java/com/audioviz/AudioVizMod.java
git diff --cached --check
git commit -m "fix(security): secure Fabric WebSocket listener"
```

---

### Task 9: Disable the legacy DJ updater at runtime and build time

**Files:**

- Modify: `dj_client/src/App.tsx`
- Modify: `dj_client/src/hooks/useAutoUpdate.ts`
- Modify: `dj_client/package.json`
- Modify: `dj_client/package-lock.json`
- Modify: `dj_client/src-tauri/Cargo.toml`
- Modify: `dj_client/src-tauri/Cargo.lock`
- Modify: `dj_client/src-tauri/src/lib.rs`
- Modify: `dj_client/src-tauri/tauri.conf.json`
- Modify: `dj_client/src-tauri/capabilities/default.json`
- Regenerate: `dj_client/src-tauri/gen/schemas/acl-manifests.json`
- Regenerate: `dj_client/src-tauri/gen/schemas/capabilities.json`
- Regenerate: `dj_client/src-tauri/gen/schemas/desktop-schema.json`
- Regenerate: `dj_client/src-tauri/gen/schemas/windows-schema.json`
- Create: `dj_client/tests/phase0-containment.test.mjs`

- [ ] **Step 1: Add a dependency-free containment test**

Add `test:containment` to `dj_client/package.json` and create a Node test that reads active manifests/source:

```javascript
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(testDirectory, "..");
const read = (relativePath) => fs.readFileSync(path.join(clientRoot, relativePath), "utf8");

test("legacy updater is absent from the active runtime", () => {
  const packageJson = JSON.parse(read("package.json"));
  const cargoToml = read("src-tauri/Cargo.toml");
  const tauriConfig = JSON.parse(read("src-tauri/tauri.conf.json"));
  const capabilities = JSON.parse(read("src-tauri/capabilities/default.json"));
  const rustRuntime = read("src-tauri/src/lib.rs");
  const app = read("src/App.tsx");

  assert.equal(packageJson.dependencies["@tauri-apps/plugin-updater"], undefined);
  assert.doesNotMatch(cargoToml, /tauri-plugin-updater/);
  assert.equal(tauriConfig.bundle.createUpdaterArtifacts, false);
  assert.equal(tauriConfig.plugins?.updater, undefined);
  assert.equal(capabilities.permissions.some((value) => value.startsWith("updater:")), false);
  assert.doesNotMatch(rustRuntime, /tauri_plugin_updater/);
  assert.doesNotMatch(app, /useAutoUpdate|update-banner/);
});
```

- [ ] **Step 2: Run the test and prove the updater is active**

```powershell
npm --prefix dj_client run test:containment
```

Expected before implementation: multiple assertions fail for frontend, npm, Rust, Tauri config, and capabilities.

- [ ] **Step 3: Remove active updater wiring while retaining an inert tombstone**

Remove `useAutoUpdate` and the banner from `App.tsx`. Replace the hook implementation with a comment explaining the Phase 0 quarantine and a static function that performs no import, request, timer, download, or install. Remove the npm and Cargo updater dependencies, Tauri plugin registration, endpoint/pubkey, updater capabilities, signing script, and updater artifacts. Set `bundle.createUpdaterArtifacts` to `false`.

Regenerate lockfiles with package managers:

```powershell
npm --prefix dj_client uninstall @tauri-apps/plugin-updater
cargo check --manifest-path dj_client/src-tauri/Cargo.toml
```

Regenerate Tauri schemas through the CLI after removing the plugin/capabilities:

```powershell
npm --prefix dj_client run tauri -- build --no-bundle --no-sign
```

- [ ] **Step 4: Verify there is no updater runtime, ACL, endpoint, or artifact**

```powershell
npm --prefix dj_client run test:containment
npm --prefix dj_client run build
cargo test --manifest-path dj_client/src-tauri/Cargo.toml --locked
cargo clippy --manifest-path dj_client/src-tauri/Cargo.toml --locked -- -D warnings
rg -n "plugin-updater|tauri-plugin-updater|tauri_plugin_updater|createUpdaterArtifacts.: true|releases/latest/download/latest.json|updater:" dj_client/src dj_client/package.json dj_client/package-lock.json dj_client/src-tauri
```

Expected: tests/build/Clippy exit 0; `rg` exits 1 with no active updater matches, including generated schemas.

- [ ] **Step 5: Commit the updater quarantine**

```powershell
git add -- dj_client/src/App.tsx dj_client/src/hooks/useAutoUpdate.ts dj_client/package.json dj_client/package-lock.json dj_client/src-tauri/Cargo.toml dj_client/src-tauri/Cargo.lock dj_client/src-tauri/src/lib.rs dj_client/src-tauri/tauri.conf.json dj_client/src-tauri/capabilities/default.json dj_client/src-tauri/gen/schemas/acl-manifests.json dj_client/src-tauri/gen/schemas/capabilities.json dj_client/src-tauri/gen/schemas/desktop-schema.json dj_client/src-tauri/gen/schemas/windows-schema.json dj_client/tests/phase0-containment.test.mjs
git diff --cached --check
git commit -m "fix(security): disable legacy DJ client updater"
```

---

### Task 10: Quarantine unsupported v1 distribution and demo paths

**Files:**

- Replace with fail-closed sentinel: `.github/workflows/release-dj-client.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `.github/workflows/dj-client-ci.yml`
- Modify: `.github/workflows/docker.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/security.yml`
- Modify: `docker-compose.yml`
- Modify: `docker-compose.demo.yml`
- Modify: `dj_client/tests/phase0-containment.test.mjs`
- Modify: `README.md`
- Modify: `dj_client/README.md`
- Modify: `demo/README.md`

- [ ] **Step 1: Extend containment tests over workflows and Compose files**

Assert that the dedicated DJ release workflow contains `MCAV_PHASE0_RELEASE_DISABLED`, contains no `softprops/action-gh-release`, and has only manual dispatch; the combined release has no DJ-client build/download/glob; active release/security workflows contain no `continue-on-error` or `|| true`; both CI summary jobs fail when any required job is not successful; Docker `push` is always false; and both VJ Compose services require the `phase0-quarantined` profile.

- [ ] **Step 2: Run the test and show that tags/main currently publish broken artifacts**

```powershell
npm --prefix dj_client run test:containment
```

Expected before implementation: release and Docker publishing assertions fail; Compose services are enabled by default.

- [ ] **Step 3: Replace the dedicated DJ release with a manual fail-closed sentinel**

The workflow must contain only this non-publishing job:

```yaml
name: DJ Client Release (Phase 0 Quarantined)

on:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  release-disabled:
    name: MCAV_PHASE0_RELEASE_DISABLED
    runs-on: ubuntu-latest
    steps:
      - name: Explain containment freeze
        run: |
          echo "DJ client distribution is disabled during MCAV Phase 0 containment."
          echo "Re-enable only after the signed updater, rollback, and release gates are implemented."
          exit 1
```

- [ ] **Step 4: Remove DJ artifacts from combined release and keep CI unsigned**

Remove `build-dj-client`, DJ artifact downloads, and DJ file globs from `.github/workflows/release.yml`; retain verified Paper/Fabric release jobs. Remove every `continue-on-error` and `|| true` from the retained release path so missing plugin/mod artifacts fail the job. In `dj-client-ci.yml`, remove updater mutation and signing-secret environment variables and run the containment test before an unsigned validation build.

In `ci.yml` and `security.yml`, make Bandit, pip-audit, npm audit at `high`, cargo-audit, dependency verification, and license checks blocking. Generate JSON reports under `set +e`, capture the command status, upload with `if: always()`, and exit with the captured non-zero status; never mask it with `|| true`. Replace `npm install --package-lock-only` with `npm ci --ignore-scripts`. Make `CI Passed` and `Security Summary` explicitly require every `needs` result to equal `success`, including security, license, SBOM, and checksum jobs. Update their rendered Markdown tables to the repository's minimum separator form.

Update the combined release's CI gate to require both the `CI Passed` and `Security Summary` check runs on the tagged commit before building Paper/Fabric artifacts.

- [ ] **Step 5: Stop Docker publishing and hide unsupported VJ Compose services behind a profile**

Keep Dockerfile PR/manual validation, but remove registry login/package write permission, set `push: false`, and remove main/tag publishing triggers from `docker.yml`. Add:

```yaml
profiles:
  - phase0-quarantined
```

to the VJ service in `docker-compose.yml` and to both services in `docker-compose.demo.yml`, so ordinary `docker compose up` cannot present the broken demo as supported.

- [ ] **Step 6: Correct public documentation without claiming a replacement release**

At the top of each affected README, state:

```markdown
> **Phase 0 containment:** Prebuilt DJ-client releases, automatic updates, the VJ Docker image, and the zero-install Docker demo are temporarily unavailable. Source builds are for development verification only until signed release, rollback, clean-install, and end-to-end demo gates pass.
```

Remove “Download Latest Release,” “Docker Deployment,” and zero-install instructions as current recommendations. Keep historical development commands under an explicitly unsupported/quarantined heading. Do not advertise a release date.

- [ ] **Step 7: Verify the quarantine and workflow syntax**

```powershell
npm --prefix dj_client run test:containment
docker compose -f docker-compose.yml config --no-interpolate --quiet
docker compose -f docker-compose.demo.yml config --no-interpolate --quiet
wsl.exe --cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment python3 -c "import pathlib,yaml; files=['.github/workflows/release-dj-client.yml','.github/workflows/release.yml','.github/workflows/dj-client-ci.yml','.github/workflows/docker.yml','.github/workflows/ci.yml','.github/workflows/security.yml']; [yaml.safe_load(pathlib.Path(name).read_text(encoding='utf-8')) for name in files]"
```

Expected: containment and syntax checks pass; neither Compose file starts the quarantined VJ/demo services without an explicit profile; no workflow publishes a DJ client, updater metadata, or VJ Docker image; no security or release gate can report green after a required check fails.

- [ ] **Step 8: Commit the distribution quarantine**

```powershell
git add -- .github/workflows/release-dj-client.yml .github/workflows/release.yml .github/workflows/dj-client-ci.yml .github/workflows/docker.yml .github/workflows/ci.yml .github/workflows/security.yml docker-compose.yml docker-compose.demo.yml dj_client/tests/phase0-containment.test.mjs README.md dj_client/README.md demo/README.md
git diff --cached --check
git commit -m "chore(release): quarantine unsupported v1 distribution"
```

---

### Task 11: Run the Phase 0 release gate and record evidence

**Files:**

- Create: `docs/superpowers/reports/2026-07-10-phase-0-containment-verification.md`

- [ ] **Step 1: Run every Python suite under WSL**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server; .venv/bin/python -m pytest -q; .venv/bin/python -m ruff check .'
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/community_bot; .venv/bin/python -m pytest -q; .venv/bin/python -m ruff check .'
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/coordinator; .venv/bin/python -m pytest -q; .venv/bin/python -m ruff check app tests'
```

Expected: all suites and linters exit 0.

- [ ] **Step 2: Run blocking SAST and dependency-audit gates**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/vj_server; .venv/bin/python -m bandit -r . -c ../pyproject.toml; .venv/bin/python -m pip_audit --local'
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/community_bot; .venv/bin/python -m pip_audit --local'
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment/coordinator; .venv/bin/python -m pip_audit --local'
npm audit --audit-level=high
npm --prefix dj_client audit --audit-level=high
npm --prefix site audit --audit-level=high
npm --prefix worker audit --audit-level=high
cargo audit --file dj_client/src-tauri/Cargo.lock
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location minecraft_plugin; .\mvnw.cmd dependency:tree -B; Pop-Location
Push-Location minecraft_mod; .\gradlew.bat dependencies --configuration runtimeClasspath; Pop-Location
```

Expected: every command exits 0. A real advisory blocks Phase 0; record its identifier and amend this plan with an exact dependency remediation rather than suppressing the gate.

- [ ] **Step 3: Run web and native-client gates**

```powershell
npm --prefix site test
npm --prefix site run lint
npm --prefix site run build
node --test protocol/tests/phase0-schemas.test.mjs
npm --prefix dj_client run test:containment
npm --prefix dj_client run build
cargo fmt --manifest-path dj_client/src-tauri/Cargo.toml -- --check
cargo test --manifest-path dj_client/src-tauri/Cargo.toml --locked
cargo clippy --manifest-path dj_client/src-tauri/Cargo.toml --locked -- -D warnings
npm --prefix dj_client run tauri -- build --no-bundle --no-sign
```

Expected: all commands exit 0 and no updater endpoint is contacted.

- [ ] **Step 4: Run both Minecraft artifact gates**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location minecraft_plugin
.\mvnw.cmd clean test package
Pop-Location
Push-Location minecraft_mod
.\gradlew.bat clean test build
Pop-Location
```

Expected: all tests pass and both production JARs build under Java 21.

- [ ] **Step 5: Run negative security assertions**

```powershell
rg -n "pending_pattern_scripts|route\.pattern_scripts|pub mod patterns|mlua|tauri_plugin_updater|plugin-updater|tauri-plugin-updater|releases/latest/download/latest.json" vj_server/dj_manager.py protocol/schemas/messages/stream-route.schema.json dj_client/src dj_client/package.json dj_client/package-lock.json dj_client/src-tauri --glob '!patterns.rs'
rg -n "if \(storedState && storedState !== state\)" site/src
rg -n "secret != bot.config.webhook_secret|settings.metrics_token is None.*return" community_bot coordinator
rg -n "continue-on-error|\|\| true" .github/workflows/ci.yml .github/workflows/security.yml .github/workflows/release.yml .github/workflows/release-dj-client.yml .github/workflows/docker.yml
git diff --check
git status --short
git -C ../.. status --short
```

Expected: all four `rg` commands exit 1 with no matches; `git diff --check` exits 0; the clean linked worktree shows only the new verification report before commit; the original workspace still shows its known pre-existing untracked user work unchanged.

- [ ] **Step 6: Write the evidence report**

Record, without secrets:

- commit hashes for Tasks 1–10;
- exact toolchain versions;
- exact command and exit code for every gate above;
- test counts;
- built artifact names and SHA-256 hashes;
- confirmation that hostile legacy stream routes deserialize but cannot execute source;
- confirmation that missing/mismatched OAuth state never exchanges a code;
- confirmation that non-loopback Minecraft WebSocket startup without a secret leaves the listener offline;
- confirmation that tag/main workflows cannot publish DJ/updater/Docker artifacts;
- any non-Phase-0 limitation that remains, clearly marked as not a green release path.

- [ ] **Step 7: Commit the verified evidence**

```powershell
git add -- docs/superpowers/reports/2026-07-10-phase-0-containment-verification.md
git diff --cached --check
git commit -m "docs: record Phase 0 containment verification"
```

- [ ] **Step 8: Perform final branch review**

```powershell
git status --short
git -C ../.. status --short
git log --oneline --decorate -12
git diff main...HEAD --stat
git diff main...HEAD --check
```

Expected: all planned commits are present and atomic; the linked worktree is clean; no unrelated user file is staged or committed; the original workspace still preserves its pre-existing untracked work.

## Completion Criteria

Phase 0 is complete only when every Task 11 gate is green and its evidence report is committed. Completion does not authorize public DJ-client or Docker distribution. The next action is a focused Phase 1 foundation design covering workspace layout, canonical protocol envelope, benchmark harness, and the first Rust Show Engine skeleton; it must follow a new plan-first approval cycle.

## Final-review amendment — 2026-07-11

Rust dependency auditing uses cargo-audit's default vulnerability exit policy without advisory IDs in an ignore list and without promoting informational warnings to blocking failures. CI and the security workflow emit the complete JSON report, so a vulnerability remains blocking while unmaintained and unsoundness warnings remain visible and report-only. At this review boundary the lockfile reports 20 informational warnings (18 unmaintained and two unsoundness advisories); this amendment does not assert a warning-free dependency graph.

The warnings are upstream or optional/target-specific edges in the quarantined DJ-client graph. The Linux Tauri path reaches the GTK3 family through `tauri` / `tauri-runtime-wry` / `wry` / `webkit2gtk`, including `glib` and `proc-macro-error`. Other Tauri utility paths reach `fxhash` through `kuchikiki` / `selectors` and the `unic-*` crates through `urlpattern`. The optional `voice-opus` feature reaches `audiopus_sys` through `opus`, and `tokio-tungstenite` reaches the warned `rand` version independently of the client's direct `rand` dependency. Cargo-audit correctly reports these lockfile edges even when a feature or target is inactive on the runner.

Report-only warning treatment is bounded by the existing distribution quarantine: DJ release publication remains fail-closed, retained upload steps remain statically false, validation builds remain unsigned with `--no-bundle`, and updater/Docker distribution remains disabled. Reopening DJ distribution requires an approved plan that resolves or explicitly re-evaluates the upstream advisory topology together with signed artifacts, rollback, clean-install, and release-gate evidence; this amendment grants no distribution exception.
