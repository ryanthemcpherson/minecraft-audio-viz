# Two-Port Pterodactyl Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the complete secured MCAV deployment through only public ports `8080` and `25808`, with same-origin browser WSS and fingerprint-pinned DJ WSS over a public IP.

**Architecture:** In Pterodactyl unified mode, an `aiohttp` gateway owns HTTPS and browser WSS on `8080` and delegates upgraded connections to the existing browser handler through a narrow adapter. The DJ listener uses TLS on `25808`; a public-IP SAN is generated during bootstrap and the Rust client verifies its configured SHA-256 fingerprint before sending any application data. Legacy non-Pterodactyl split-port behavior remains available.

**Tech Stack:** Python 3.12 in WSL, asyncio, aiohttp, websockets, msgspec, pytest/pytest-asyncio, Rust 2024, Tokio, tokio-tungstenite/native-tls, React 19/TypeScript, Bash/PowerShell Pterodactyl packaging, Vite.

**Spec:** `docs/superpowers/specs/2026-08-21-two-port-pterodactyl-gateway-design.md`

## Global Constraints

- Public Pterodactyl listeners are exactly `8080/tcp` and `25808/tcp`.
- Unified mode serves admin HTTPS, preview HTTPS, and browser WSS `/ws` on `8080`; it does not start `8766`.
- DJ WSS binds `25808`; global non-Pterodactyl DJ defaults remain `9000`.
- Minecraft `8765` and metrics `9001` bind loopback only.
- Public plaintext WS and trust-on-first-use are prohibited.
- The generated certificate must include the configured public IP SAN.
- DJ certificate verification must finish before `dj_auth`, `code_auth`, credentials, palette data, or audio frames are sent.
- Static responses preserve canonical containment, exact routing, MIME types, GET/HEAD, redirects, 404, and exactly one `Cache-Control: no-store` header.
- Existing VJ/browser/DJ authentication, rate limits, session generation, emergency authority, bitmap, voice, and router semantics remain owned by their current handlers.
- Use WSL-native Python and the project-local `vj_server/.venv`; never install Python packages with Windows Python.
- Preserve the redesigned admin/preview HTML and the untracked root `AGENTS.md`.
- Each production change begins with a focused failing regression, ends with focused and full verification, and lands in one conventional atomic commit.

## File and Interface Map

- `vj_server/web_gateway.py`: unified static/runtime-config/browser-WebSocket gateway and `AiohttpBrowserSocket` adapter.
- `vj_server/vj_server.py`: selects unified versus legacy listeners and applies TLS to the DJ listener.
- `vj_server/cli.py`: exposes validated unified-web/public-origin settings.
- `vj_server/models.py`: retains the legacy threaded HTTP handler; shared containment helpers may move only when both servers consume them.
- `vj_server/pterodactyl.py`: validates public IP identity, generates/rotates the certificate, and writes deployment endpoints/fingerprint.
- `admin_panel/runtime-config.js`: checked-in legacy browser-transport fallback.
- `admin_panel/js/utils/browser-endpoint.js`: resolves runtime-config, query override, and legacy browser endpoints.
- `admin_panel/js/services/WebSocketService.js`: connects to an explicit WebSocket URL while preserving session-generation behavior.
- `preview_tool/frontend/js/browser-endpoint.js`: equivalent preview endpoint resolver at that independently served root.
- `dj_client/src-tauri/src/protocol/tls.rs`: fingerprint parsing, peer-certificate hashing, pinned TLS connector, and pre-auth verification.
- `dj_client/src-tauri/src/protocol/client.rs`: consumes the verified connector before splitting/sending on the socket.
- `dj_client/src-tauri/src/{lib.rs,state.rs}` and React connection components/hooks: carry and persist the non-secret fingerprint.
- `deploy/pterodactyl/*`: exact two-port defaults, runtime dependency lock, startup validation, release verification, and operator handoff.

---

### Task 1: Reconcile the Pterodactyl Baseline

**Files:**
- Integrate from `main`: `deploy/pterodactyl/**`
- Integrate from `main`: `vj_server/pterodactyl.py`
- Integrate from `main`: `vj_server/tests/test_pterodactyl.py`
- Resolve: `admin_panel/index.html`
- Resolve: `preview_tool/frontend/index.html`
- Resolve: `vj_server/cli.py`

**Interfaces:**
- Consumes: reviewed redesign HEAD and `main` commits `c2541eb..4f5ae34`.
- Produces: one branch containing the redesigned panel and the tested portable Pterodactyl bundle, before two-port behavior changes.

- [ ] **Step 1: Prove both branches and the worktree state**

Run:

```powershell
git status --short
git rev-parse HEAD
git rev-parse main
git merge-base HEAD main
```

Expected: only `?? AGENTS.md`; named branch `feature/vj-control-panel`; both tips and their merge base are recorded in the task report.

- [ ] **Step 2: Merge `main` without committing**

Run:

```powershell
git merge --no-commit --no-ff main
```

Expected: the Pterodactyl files appear. If HTML or CLI conflicts occur, keep the redesigned five-workspace/auth/runtime behavior and incorporate `main`'s Pterodactyl bootstrap flags and release-relative asset paths. Do not restore removed AdminApp monolith code or legacy panel layout.

- [ ] **Step 3: Run the reconciled baseline gates**

Run:

```powershell
npm run test:admin
node --test admin_panel/js/services/WebSocketService.test.mjs preview_tool/frontend/js/auth.test.mjs preview_tool/frontend/js/app-connection.test.mjs protocol/tests/phase0-schemas.test.mjs
npm run build
```

Run from WSL:

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/feature-vj-control-panel/vj_server
.venv/bin/python -m pytest -q
.venv/bin/ruff check .
.venv/bin/ruff format --check .
```

Run the deployment suites:

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/feature-vj-control-panel
bash deploy/pterodactyl/test-start-mcav.sh
bash deploy/pterodactyl/test-build-release.sh
```

Expected: every pre-change suite passes. Any regression is resolved within the semantic merge before continuing.

- [ ] **Step 4: Commit the reconciled baseline**

```powershell
git add -- .github/workflows/release.yml admin_panel/index.html preview_tool/frontend/index.html vj_server/cli.py vj_server/pterodactyl.py vj_server/tests/test_pterodactyl.py deploy/pterodactyl docs/deployment/PTERODACTYL.md
git commit -m "chore: reconcile VJ deployment baseline"
```

Expected: hooks pass; `git status --short` returns only `?? AGENTS.md`.

### Task 2: Build the Unified Static Gateway

**Files:**
- Create: `vj_server/web_gateway.py`
- Create: `vj_server/tests/test_web_gateway.py`
- Modify: `vj_server/pyproject.toml`
- Modify: `vj_server/models.py`

**Interfaces:**
- Consumes: `VJServer._handle_browser_client(websocket) -> Awaitable[None]`, existing project roots, TLS context.
- Produces: `UnifiedWebConfig`, `AiohttpBrowserSocket`, `create_unified_web_app()`, and `start_unified_web_gateway()`.
- Internal route handlers: `serve_runtime_config(request) -> web.Response`, `serve_browser_websocket(request) -> web.WebSocketResponse`, and `serve_static_request(request) -> web.StreamResponse`.
- Test fixture: `gateway_client` wraps `create_unified_web_app()` in `aiohttp.test_utils.TestServer` and `TestClient`, with temporary admin/preview roots and a recording browser handler.

```python
@dataclass(frozen=True)
class UnifiedWebConfig:
    project_root: Path
    public_origin: str
    ws_path: str = "/ws"
    runtime_config_path: str = "/runtime-config.js"
    max_message_size: int = 65_536


BrowserHandler = Callable[["AiohttpBrowserSocket"], Awaitable[None]]
UNIFIED_CONFIG_KEY = web.AppKey("unified_config", UnifiedWebConfig)
BROWSER_HANDLER_KEY = web.AppKey("browser_handler", BrowserHandler)


def create_unified_web_app(
    browser_handler: Callable[[AiohttpBrowserSocket], Awaitable[None]],
    config: UnifiedWebConfig,
) -> web.Application:
    app = web.Application(client_max_size=config.max_message_size)
    app[UNIFIED_CONFIG_KEY] = config
    app[BROWSER_HANDLER_KEY] = browser_handler
    app.router.add_route("GET", config.runtime_config_path, serve_runtime_config)
    app.router.add_route("GET", config.ws_path, serve_browser_websocket)
    app.router.add_route("*", "/{request_path:.*}", serve_static_request)
    return app


async def start_unified_web_gateway(
    browser_handler: Callable[[AiohttpBrowserSocket], Awaitable[None]],
    host: str,
    port: int,
    ssl_context: ssl.SSLContext,
    config: UnifiedWebConfig,
) -> web.AppRunner:
    runner = web.AppRunner(create_unified_web_app(browser_handler, config))
    await runner.setup()
    await web.TCPSite(runner, host, port, ssl_context=ssl_context).start()
    return runner
```

- [ ] **Step 1: Write failing static/security tests**

Add table-driven tests that start the real aiohttp application and assert:

```python
@pytest.mark.parametrize("method", ["GET", "HEAD"])
async def test_unified_gateway_serves_admin_with_no_store(method, gateway_client):
    response = await gateway_client.request(method, "/")
    assert response.status == 200
    assert response.headers.getall("Cache-Control") == ["no-store"]
    if method == "HEAD":
        assert await response.read() == b""


@pytest.mark.parametrize(
    "path",
    [
        "/../outside-secret.txt",
        "/preview/../../outside-secret.txt",
        "/%2e%2e/outside-secret.txt",
        "/preview/%2e%2e%5coutside-secret.txt",
        "/administrator/index.html",
    ],
)
async def test_unified_gateway_contains_static_paths(path, gateway_client):
    response = await gateway_client.get(path)
    assert response.status == 404
    assert response.headers.getall("Cache-Control") == ["no-store"]
```

Also assert exact `/preview` redirect, `/preview/` success, MIME types for CSS/JS/PNG, bounded 404s, null-byte rejection, and that filesystem paths never appear in responses.

- [ ] **Step 2: Run RED**

```bash
cd vj_server
.venv/bin/python -m pytest tests/test_web_gateway.py -q
```

Expected: collection fails with `ModuleNotFoundError: No module named 'vj_server.web_gateway'`.

- [ ] **Step 3: Add aiohttp and implement contained static routing**

Add `aiohttp>=3.11.0,<4.0` to project dependencies, then install from WSL:

```bash
cd vj_server
.venv/bin/python -m pip install -e .
```

Implement canonical selection before opening a file:

```python
def resolve_contained_path(root: Path, raw_relative_path: str) -> Path | None:
    if "\x00" in raw_relative_path:
        return None
    decoded = unquote(raw_relative_path).replace("\\", "/")
    if decoded.startswith("/"):
        decoded = decoded[1:]
    candidate = (root.resolve() / decoded).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        return None
    return candidate
```

Use `asyncio.to_thread(path.read_bytes)` for static reads. Construct every success, redirect, and error response through one helper that replaces any existing cache header with exactly `Cache-Control: no-store`. Handle HEAD by calculating the GET headers and returning an empty body.

- [ ] **Step 4: Implement runtime configuration and adapter primitives**

The runtime script contains fixed values only:

```python
RUNTIME_CONFIG_BODY = (
    'window.MCAV_RUNTIME_CONFIG = Object.freeze({'
    'browserWebSocketMode: "same-origin",'
    'browserWebSocketPath: "/ws"'
    '});\n'
).encode("utf-8")
```

Implement `AiohttpBrowserSocket.send`, `recv`, `close`, `remote_address`, `__aiter__`, and `__anext__`. Text frames return `str`, binary frames return `bytes`, normal closure ends iteration, and aiohttp error frames raise `ConnectionError`.

`serve_browser_websocket` compares `request.headers["Origin"]` with `config.public_origin` before preparing the socket, returns 403 on absence/mismatch, and creates `web.WebSocketResponse(max_msg_size=config.max_message_size)`. It sets one no-store header on the handshake, awaits the existing browser handler through the adapter, treats adapter `ConnectionError` as a closed client, and always closes/returns the response so the handler's `finally` cleanup remains authoritative.

- [ ] **Step 5: Run GREEN and commit**

```bash
cd vj_server
.venv/bin/python -m pytest tests/test_web_gateway.py tests/test_static_http.py -q
.venv/bin/ruff check web_gateway.py tests/test_web_gateway.py
.venv/bin/ruff format --check web_gateway.py tests/test_web_gateway.py
```

```powershell
git add -- vj_server/web_gateway.py vj_server/tests/test_web_gateway.py vj_server/pyproject.toml vj_server/models.py
git commit -m "feat(vj): add unified browser gateway"
```

### Task 3: Wire Unified Browser WSS into VJ Lifecycle

**Files:**
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/cli.py`
- Modify: `vj_server/tests/test_vj_server_helpers.py`
- Modify: `vj_server/tests/test_browser_auth.py`
- Modify: `vj_server/tests/test_web_gateway.py`

**Interfaces:**
- Consumes: `start_unified_web_gateway()` and `UnifiedWebConfig` from Task 2.
- Produces: `VJServer(unified_web: bool, public_origin: str | None)` and CLI flags `--unified-web`, `--public-origin`.

- [ ] **Step 1: Write failing lifecycle and real-upgrade tests**

Add tests proving unified mode starts one gateway and no threaded HTTP or `8766` server:

```python
async def test_unified_mode_starts_gateway_without_legacy_browser_listener(monkeypatch):
    starts = []

    class FakeListener:
        def close(self):
            return None

        async def wait_closed(self):
            return None

    class FakeGatewayRunner:
        async def cleanup(self):
            return None

    async def fake_gateway(*args, **kwargs):
        starts.append(("gateway", args[2]))
        return FakeGatewayRunner()

    async def fake_ws_serve(handler, host, port, **kwargs):
        starts.append(("websocket", port))
        return FakeListener()

    monkeypatch.setattr(vj_server_module, "start_unified_web_gateway", fake_gateway)
    monkeypatch.setattr(vj_server_module, "ws_serve", fake_ws_serve)
    server = VJServer(
        http_port=18080,
        broadcast_port=18766,
        unified_web=True,
        public_origin="https://203.0.113.9:18080",
        metrics_port=None,
        show_spectrograph=False,
    )
    task = asyncio.create_task(server.run())
    for _attempt in range(100):
        if len(starts) >= 2:
            break
        await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert starts.count(("gateway", 18080)) == 1
    assert all(port != 18766 for _kind, port in starts)
```

Add a real TLS WebSocket test with allowed origin that receives `auth_required`, authenticates as a VJ operator, and receives initial state. Add wrong-origin, missing-origin production, wrong-path, oversized-message, and disconnect-cleanup cases.

- [ ] **Step 2: Run RED**

```bash
cd vj_server
.venv/bin/python -m pytest tests/test_web_gateway.py tests/test_vj_server_helpers.py tests/test_browser_auth.py -q
```

Expected: constructor/CLI reject `unified_web` and `/ws` has no upgrade handler.

- [ ] **Step 3: Implement CLI and lifecycle selection**

Add parser arguments:

```python
parser.add_argument("--unified-web", action="store_true")
parser.add_argument("--public-origin")
```

Reject unified mode without HTTPS, without `public_origin`, when `http_port <= 0`, or when `http_port == dj_port`. Normalize the origin to exact `https://host:port` with no path/query/fragment.

In `VJServer.run()`:

```python
gateway_runner = None
if self.unified_web:
    gateway_runner = await start_unified_web_gateway(
        self._handle_browser_client,
        self.http_host,
        self.http_port,
        self.server_ssl_context,
        UnifiedWebConfig(self.project_root, self.public_origin),
    )
else:
    self._start_legacy_http_thread()
    broadcast_server = await ws_serve(
        self._handle_browser_client,
        "0.0.0.0",
        self.broadcast_port,
        max_size=65_536,
        ssl=self.server_ssl_context,
    )
```

Initialize `broadcast_server = None`; in shutdown, close it only when present and always `await gateway_runner.cleanup()` when the gateway started.

- [ ] **Step 4: Run GREEN and full Python regression**

```bash
cd vj_server
.venv/bin/python -m pytest tests/test_web_gateway.py tests/test_vj_server_helpers.py tests/test_browser_auth.py -q
.venv/bin/python -m pytest -q
.venv/bin/ruff check .
.venv/bin/ruff format --check .
```

Expected: focused and full suites pass; wrong-origin clients receive 403 before authentication; cancellation cleans the aiohttp runner.

- [ ] **Step 5: Commit**

```powershell
git add -- vj_server/vj_server.py vj_server/cli.py vj_server/tests/test_vj_server_helpers.py vj_server/tests/test_browser_auth.py vj_server/tests/test_web_gateway.py
git commit -m "feat(vj): unify browser transport on web port"
```

### Task 4: Select Same-Origin Browser Endpoints at Runtime

**Files:**
- Create: `admin_panel/runtime-config.js`
- Create: `admin_panel/js/utils/browser-endpoint.js`
- Create: `admin_panel/tests/browser-endpoint.test.mjs`
- Create: `preview_tool/frontend/js/browser-endpoint.js`
- Create: `preview_tool/frontend/js/browser-endpoint.test.mjs`
- Modify: `admin_panel/index.html`
- Modify: `admin_panel/js/admin-app.js`
- Modify: `admin_panel/js/services/WebSocketService.js`
- Modify: `admin_panel/js/services/WebSocketService.test.mjs`
- Modify: `preview_tool/frontend/index.html`
- Modify: `preview_tool/frontend/js/app.js`
- Modify: `preview_tool/frontend/js/app-connection.test.mjs`

**Interfaces:**
- Consumes: `/runtime-config.js` contract from Task 2.
- Produces: `resolveBrowserWebSocketUrl(locationLike, searchParams, runtimeConfig) -> string` in each independently served frontend and `WebSocketService({ url })`.

- [ ] **Step 1: Write failing endpoint tests**

Use table-driven tests in both frontend roots:

```javascript
test('unified runtime config selects same-origin WSS path', () => {
    const url = resolveBrowserWebSocketUrl(
        { protocol: 'https:', hostname: '203.0.113.9', host: '203.0.113.9:8080' },
        new URLSearchParams(),
        { browserWebSocketMode: 'same-origin', browserWebSocketPath: '/ws' },
    );
    assert.equal(url, 'wss://203.0.113.9:8080/ws');
});

test('legacy mode preserves the explicit port override', () => {
    const url = resolveBrowserWebSocketUrl(
        { protocol: 'http:', hostname: 'localhost', host: 'localhost:8080' },
        new URLSearchParams('port=18766'),
        { browserWebSocketMode: 'legacy', browserWebSocketPort: 8766 },
    );
    assert.equal(url, 'ws://localhost:18766/');
});
```

Add malformed path tests: only a leading-slash path without query, fragment, backslash, or scheme is accepted. Invalid runtime config falls back to the checked-in legacy port.

- [ ] **Step 2: Run RED**

```powershell
node --test admin_panel/tests/browser-endpoint.test.mjs preview_tool/frontend/js/browser-endpoint.test.mjs admin_panel/js/services/WebSocketService.test.mjs preview_tool/frontend/js/app-connection.test.mjs
```

Expected: endpoint modules are missing and `WebSocketService` doesn't accept `url`.

- [ ] **Step 3: Implement deterministic endpoint resolution**

The checked-in fallback is non-secret:

```javascript
window.MCAV_RUNTIME_CONFIG = Object.freeze({
    browserWebSocketMode: 'legacy',
    browserWebSocketPort: 8766,
});
```

Both HTML roots load `<script src="/runtime-config.js"></script>` before the application module. Endpoint resolvers use:

```javascript
export function resolveBrowserWebSocketUrl(locationLike, searchParams, runtimeConfig = {}) {
    const scheme = locationLike.protocol === 'https:' ? 'wss' : 'ws';
    if (runtimeConfig.browserWebSocketMode === 'same-origin') {
        const path = runtimeConfig.browserWebSocketPath;
        if (typeof path === 'string' && /^\/[A-Za-z0-9/_-]*$/.test(path)) {
            return `${scheme}://${locationLike.host}${path}`;
        }
    }
    const override = Number.parseInt(searchParams.get('port'), 10);
    const configured = Number.parseInt(runtimeConfig.browserWebSocketPort, 10);
    const port = Number.isInteger(override) && override > 0 && override <= 65535
        ? override
        : (Number.isInteger(configured) ? configured : 8766);
    return `${scheme}://${locationLike.hostname}:${port}/`;
}
```

`WebSocketService` stores the explicit URL and creates sockets with `new WebSocket(this.url)`. Preserve the existing per-generation callback guards, authentication queue boundaries, reconnect semantics, and immediate emergency-send behavior.

- [ ] **Step 4: Run GREEN, full frontend tests, and build**

```powershell
node --test admin_panel/tests/browser-endpoint.test.mjs preview_tool/frontend/js/browser-endpoint.test.mjs admin_panel/js/services/WebSocketService.test.mjs preview_tool/frontend/js/app-connection.test.mjs preview_tool/frontend/js/auth.test.mjs
npm run test:admin
npm run build
```

Run `node --check` on every first-party JavaScript file under `admin_panel/js` and `preview_tool/frontend/js`, excluding vendored files. Expected: all checks pass and the build reports only established classic-script notices.

- [ ] **Step 5: Commit**

```powershell
git add -- admin_panel/runtime-config.js admin_panel/index.html admin_panel/js/admin-app.js admin_panel/js/utils/browser-endpoint.js admin_panel/tests/browser-endpoint.test.mjs admin_panel/js/services/WebSocketService.js admin_panel/js/services/WebSocketService.test.mjs preview_tool/frontend/index.html preview_tool/frontend/js/app.js preview_tool/frontend/js/browser-endpoint.js preview_tool/frontend/js/browser-endpoint.test.mjs preview_tool/frontend/js/app-connection.test.mjs
git commit -m "feat(admin): use same-origin browser transport"
```

### Task 5: Generate and Rotate Public-IP TLS Identity

**Files:**
- Modify: `vj_server/pterodactyl.py`
- Modify: `vj_server/cli.py`
- Modify: `vj_server/tests/test_pterodactyl.py`
- Modify: `deploy/pterodactyl/mcav.env.example`
- Modify: `deploy/pterodactyl/start-mcav.sh`
- Modify: `deploy/pterodactyl/test-start-mcav.sh`

**Interfaces:**
- Consumes: Pterodactyl bootstrap from Task 1.
- Produces: `parse_public_ip(value: str) -> IPv4Address | IPv6Address`, `certificate_covers_ip()`, explicit certificate rotation, normalized fingerprint/endpoints, and exact deployment environment.

- [ ] **Step 1: Write failing identity and wrapper tests**

Add Python tests for IPv4 and IPv6 SAN arguments, invalid host rejection, existing localhost-only certificate refusal, explicit rotation, identity preservation, and FIRST_LOGIN output:

```python
def test_first_run_scopes_tls_identity_to_public_ip(bootstrap_paths):
    result = bootstrap_pterodactyl(
        bootstrap_paths,
        release_version="26.1-test",
        public_host="8.8.8.8",
    )
    login = result.first_login.read_text(encoding="utf-8")
    assert "ADMIN_URL=https://8.8.8.8:8080/" in login
    assert "DJ_ENDPOINT=wss://8.8.8.8:25808" in login
    fingerprint = re.search(r"TLS_SHA256_FINGERPRINT=([0-9a-f]{64})", login)
    assert fingerprint is not None


def test_existing_wrong_san_requires_explicit_rotation(bootstrap_paths):
    bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host="8.8.8.8")
    with pytest.raises(BootstrapError, match="rotate-tls-identity"):
        bootstrap_pterodactyl(bootstrap_paths, "26.1-test", public_host="1.1.1.1")
```

Extend the shell test to assert the fake runtime receives:

```text
--http-port
8080
--port
25808
--unified-web
--public-origin
https://8.8.8.8:8080
```

and does not receive `--broadcast-port` in unified mode.

- [ ] **Step 2: Run RED**

```bash
cd vj_server
.venv/bin/python -m pytest tests/test_pterodactyl.py -q
cd ..
bash deploy/pterodactyl/test-start-mcav.sh
```

Expected: bootstrap rejects `public_host`, FIRST_LOGIN lacks endpoints, and the wrapper still sends `9000`/`8766`.

- [ ] **Step 3: Implement validated public-IP identity**

Use `ipaddress.ip_address()` and construct SAN input without shell interpolation:

```python
def parse_public_ip(value: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address:
    try:
        return ipaddress.ip_address(value.strip())
    except ValueError as exc:
        raise BootstrapError("MCAV_PUBLIC_HOST must be a public IPv4 or IPv6 address") from exc
```

Reject unspecified, loopback, multicast, link-local, and private addresses for Pterodactyl public mode. Pass `subjectAltName=IP:<address>,DNS:localhost,IP:127.0.0.1` as one fixed OpenSSL argument. Validate existing certificates with `openssl x509 -checkip <address> -noout -in <cert>` through `CommandRunner`.

Normalize OpenSSL's SHA-256 fingerprint by removing separators and lowercasing, then require exactly 64 hex characters. Format IPv6 endpoints with brackets.

- [ ] **Step 4: Implement explicit certificate-only rotation**

Add CLI `--rotate-tls-identity` available only with `--bootstrap-pterodactyl` and `--public-host`. Rotation stages and validates a new key/certificate, atomically replaces only `tls.key` and `tls.crt`, updates endpoint/fingerprint lines in `FIRST_LOGIN.txt`, and preserves `runtime.env`, `dj_auth.json`, usernames, passwords, and plugin configuration byte-for-byte.

The default bootstrap path refuses SAN mismatch and prints the exact rotation command. It never deletes or silently overwrites an identity.

- [ ] **Step 5: Configure exact Pterodactyl defaults and run GREEN**

Set:

```bash
http_port="${HTTP_PORT:-8080}"
dj_port="${VJ_SERVER_PORT:-25808}"
public_host="${MCAV_PUBLIC_HOST:?MCAV_PUBLIC_HOST must be the public server IP}"
```

Pass unified flags and no broadcast-port flag. Update `mcav.env.example` to show `MCAV_PUBLIC_HOST`, `HTTP_PORT=8080`, `VJ_SERVER_PORT=25808`, `UNIFIED_WEB=true`, and loopback metrics configuration.

Run:

```bash
cd vj_server
.venv/bin/python -m pytest tests/test_pterodactyl.py -q
.venv/bin/ruff check pterodactyl.py tests/test_pterodactyl.py
.venv/bin/ruff format --check pterodactyl.py tests/test_pterodactyl.py
cd ..
bash deploy/pterodactyl/test-start-mcav.sh
```

- [ ] **Step 6: Commit**

```powershell
git add -- vj_server/pterodactyl.py vj_server/cli.py vj_server/tests/test_pterodactyl.py deploy/pterodactyl/mcav.env.example deploy/pterodactyl/start-mcav.sh deploy/pterodactyl/test-start-mcav.sh
git commit -m "feat(deploy): scope TLS identity to public endpoint"
```

### Task 6: Secure the DJ Listener on Port 25808

**Files:**
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/tests/test_vj_server_helpers.py`
- Modify: `vj_server/tests/test_browser_auth.py`
- Modify: `deploy/pterodactyl/test-start-mcav.sh`

**Interfaces:**
- Consumes: existing `server_ssl_context`, DJ handler, and Pterodactyl port values.
- Produces: TLS-protected DJ listener whenever server TLS is configured; plaintext remains possible only in explicit non-public local development.
- Test fixture: `tls_context` is an `ssl.SSLContext` loaded from the focused test certificate/key pair and assigned to `server.server_ssl_context` before startup.

- [ ] **Step 1: Write failing listener tests**

Capture `ws_serve` keyword arguments and assert:

```python
async def test_dj_listener_uses_server_tls_context(monkeypatch, tls_context):
    calls = []

    class FakeClosableServer:
        def close(self):
            return None

        async def wait_closed(self):
            return None

    async def fake_serve(handler, host, port, **kwargs):
        calls.append((handler, host, port, kwargs))
        return FakeClosableServer()

    monkeypatch.setattr(vj_server_module, "ws_serve", fake_serve)
    server = VJServer(
        dj_port=25808,
        http_port=0,
        metrics_port=None,
        show_spectrograph=False,
    )
    server.server_ssl_context = tls_context
    task = asyncio.create_task(server.run())
    for _attempt in range(100):
        if calls:
            break
        await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    dj_call = next(call for call in calls if call[2] == 25808)
    assert dj_call[3]["ssl"] is tls_context
```

Add a real local WSS test that completes DJ auth over the TLS listener. Add a public-mode guard test proving startup refuses a missing TLS context rather than opening plaintext `25808`.

- [ ] **Step 2: Run RED**

```bash
cd vj_server
.venv/bin/python -m pytest tests/test_vj_server_helpers.py tests/test_browser_auth.py -q
```

Expected: DJ `ws_serve` kwargs omit `ssl` and public-mode guard is absent.

- [ ] **Step 3: Apply TLS and fail-closed validation**

Start the DJ listener with:

```python
dj_server = await ws_serve(
    self._handle_dj_connection,
    "0.0.0.0",
    self.dj_port,
    max_size=65_536,
    ssl=self.server_ssl_context,
)
```

When `unified_web` is enabled, require a non-null TLS context for both public listeners and log `DJ WebSocket server: wss://<host>:<port>`. Legacy mode without TLS retains its existing `ws://` log and behavior.

- [ ] **Step 4: Run GREEN and full Python verification**

```bash
cd vj_server
.venv/bin/python -m pytest tests/test_vj_server_helpers.py tests/test_browser_auth.py tests/test_pterodactyl.py -q
.venv/bin/python -m pytest -q
.venv/bin/ruff check .
.venv/bin/ruff format --check .
```

- [ ] **Step 5: Commit**

```powershell
git add -- vj_server/vj_server.py vj_server/tests/test_vj_server_helpers.py vj_server/tests/test_browser_auth.py deploy/pterodactyl/test-start-mcav.sh
git commit -m "fix(vj): require TLS for public DJ transport"
```

### Task 7: Pin the DJ Server Certificate Before Authentication

**Files:**
- Create: `dj_client/src-tauri/src/protocol/tls.rs`
- Modify: `dj_client/src-tauri/src/protocol/mod.rs`
- Modify: `dj_client/src-tauri/src/protocol/client.rs`
- Modify: `dj_client/src-tauri/src/lib.rs`
- Modify: `dj_client/src-tauri/src/state.rs`
- Modify: `dj_client/src-tauri/Cargo.toml`
- Modify: `dj_client/src-tauri/Cargo.lock`

**Interfaces:**
- Consumes: WSS DJ endpoint and normalized 64-hex fingerprint from Tasks 5-6.
- Produces: `normalize_sha256_fingerprint()`, `connect_verified()`, `DjClientConfig.tls_fingerprint`, and certificate-specific `ClientError` variants.
- Test fixture: `TlsWebSocketFixture::start() -> TlsWebSocketFixture` exposes `url: String`, `different_fingerprint() -> String`, and async `received_messages() -> Vec<Message>` after orderly shutdown.

```rust
pub fn normalize_sha256_fingerprint(value: &str) -> Result<[u8; 32], ClientError>;

pub async fn connect_verified(
    url: &str,
    websocket_config: WebSocketConfig,
    expected_fingerprint: Option<&str>,
) -> Result<WebSocketStream<MaybeTlsStream<TcpStream>>, ClientError>;
```

- [ ] **Step 1: Write failing parsing and pre-auth transport tests**

Add unit tests in `tls.rs` for lowercase, uppercase, colon-separated input, wrong length, and non-hex data. Add a local Tokio WSS fixture with a generated self-signed certificate and tests for matching pin, mismatching pin, rotated certificate, missing peer certificate, and normal platform-validation behavior without a pin.

The mismatch fixture records every application frame and asserts it receives none:

```rust
#[tokio::test]
async fn fingerprint_mismatch_sends_no_application_data() {
    let fixture = TlsWebSocketFixture::start().await;
    let result = connect_verified(
        &fixture.url,
        WebSocketConfig::default(),
        Some(&fixture.different_fingerprint()),
    )
    .await;
    assert!(matches!(result, Err(ClientError::TlsFingerprintMismatch { .. })));
    assert!(fixture.received_messages().await.is_empty());
}
```

Use `rcgen` only as a dev-dependency for the local certificate fixture; use the production `native-tls` and `tokio-tungstenite` path for the actual connection.

- [ ] **Step 2: Run RED**

```powershell
cargo test --manifest-path dj_client/src-tauri/Cargo.toml protocol::tls -- --nocapture
```

Expected: `protocol::tls` and the fingerprint error variants don't exist.

- [ ] **Step 3: Implement strict fingerprint parsing**

Add direct dependencies `native-tls = "0.2"` and `sha2 = "0.10"`, plus `rcgen` under dev-dependencies. Parse only ASCII hex after removing `:` separators and ASCII whitespace. Require exactly 64 hex characters and return `ClientError::InvalidTlsFingerprint` otherwise.

```rust
pub fn normalize_sha256_fingerprint(value: &str) -> Result<[u8; 32], ClientError> {
    let normalized: String = value
        .chars()
        .filter(|character| !character.is_ascii_whitespace() && *character != ':')
        .collect();
    if normalized.len() != 64 || !normalized.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(ClientError::InvalidTlsFingerprint);
    }
    let mut output = [0_u8; 32];
    for (index, slot) in output.iter_mut().enumerate() {
        *slot = u8::from_str_radix(&normalized[index * 2..index * 2 + 2], 16)
            .map_err(|_| ClientError::InvalidTlsFingerprint)?;
    }
    Ok(output)
}
```

- [ ] **Step 4: Implement pinned TLS connection ordering**

When a pin is present, build a native TLS connector with self-signed chain acceptance enabled and invalid-hostname acceptance disabled. Connect with `connect_async_tls_with_config`, extract the peer leaf certificate DER, calculate SHA-256, compare against the parsed pin, and return the connected WebSocket only on equality.

When no pin is present, use the normal platform-verifying connector. Never retry with plaintext and never call `split()`, `send()`, or authentication code before `connect_verified()` returns success.

Add errors:

```rust
InvalidTlsFingerprint,
MissingPeerCertificate,
TlsFingerprintMismatch { expected: String, observed: String },
TlsHandshake(String),
```

Fingerprints are non-secret and may appear in the local error; passwords, tokens, and certificate private data may not.

- [ ] **Step 5: Thread the pin through the Rust connection configuration**

Add `tls_fingerprint: Option<String>` to `DjClientConfig`, `AppState`, `connect_with_code`, `connect_direct`, and reconnect configuration. Defaults use `None`. Store it only after successful configuration validation. Ensure every reconnect repeats certificate verification.

- [ ] **Step 6: Run GREEN and full Rust tests**

```powershell
cargo fmt --manifest-path dj_client/src-tauri/Cargo.toml -- --check
cargo test --manifest-path dj_client/src-tauri/Cargo.toml protocol::tls -- --nocapture
cargo test --manifest-path dj_client/src-tauri/Cargo.toml
```

Expected: local fixture tests prove matching success and pre-auth failure for every invalid certificate case; the full Rust suite passes.

- [ ] **Step 7: Commit**

```powershell
git add -- dj_client/src-tauri/src/protocol/tls.rs dj_client/src-tauri/src/protocol/mod.rs dj_client/src-tauri/src/protocol/client.rs dj_client/src-tauri/src/lib.rs dj_client/src-tauri/src/state.rs dj_client/src-tauri/Cargo.toml dj_client/src-tauri/Cargo.lock
git commit -m "feat(dj): pin public server certificates"
```

### Task 8: Add Certificate Trust to the DJ Connection Profile

**Files:**
- Modify: `dj_client/src/components/ConnectForm.tsx`
- Modify: `dj_client/src/components/DisconnectedView.tsx`
- Modify: `dj_client/src/hooks/useConnection.ts`
- Create: `dj_client/tests/tls-profile-contract.test.mjs`

**Interfaces:**
- Consumes: `tls_fingerprint` Tauri command parameter from Task 7.
- Produces: editable/persisted non-secret fingerprint with explicit validation and certificate error presentation.

- [ ] **Step 1: Write failing source-contract tests**

The Node test reads the three TypeScript files and proves the complete UI-to-command chain:

```javascript
test('DJ connection profile carries the certificate fingerprint to Tauri', () => {
    assert.match(connectFormSource, /tlsFingerprint: string/);
    assert.match(disconnectedViewSource, /tlsFingerprint=/);
    assert.match(connectionHookSource, /mcav\.tlsFingerprint/);
    assert.match(connectionHookSource, /tlsFingerprint:\s*normalizedTlsFingerprint/);
});

test('connection profile labels the fingerprint as SHA-256 and non-secret', () => {
    assert.match(connectFormSource, /Server certificate SHA-256/i);
    assert.match(connectFormSource, /safe to save/i);
    assert.match(connectFormSource, /64 hexadecimal/i);
});
```

Also assert no password/key value is written to localStorage.

- [ ] **Step 2: Run RED**

```powershell
node --test dj_client/tests/tls-profile-contract.test.mjs
```

Expected: fingerprint props, persistence, and Tauri command field are absent.

- [ ] **Step 3: Implement the profile control and validation**

Add an advanced connection field labeled `Server certificate SHA-256 fingerprint`, explanatory copy `Safe to save; never share the server password`, and a monospace input. Normalize uppercase and colon-separated values for display/persistence. Empty input means normal platform certificate validation; non-empty input must contain exactly 64 hex characters after normalization before Connect is enabled.

Persist only:

```typescript
localStorage.setItem('mcav.tlsFingerprint', normalizedTlsFingerprint);
```

Pass `tlsFingerprint: normalizedTlsFingerprint || null` to both connect-code and direct Tauri invocations. Surface `InvalidTlsFingerprint`, `MissingPeerCertificate`, and `TlsFingerprintMismatch` as distinct connection errors. Do not offer an acceptance bypass.

- [ ] **Step 4: Run GREEN and DJ build/tests**

```powershell
node --test dj_client/tests/tls-profile-contract.test.mjs
npm --prefix dj_client run build
npm --prefix dj_client run test:rust
```

Expected: source contract, TypeScript build, Vite build, and all Rust tests pass.

- [ ] **Step 5: Commit**

```powershell
git add -- dj_client/src/components/ConnectForm.tsx dj_client/src/components/DisconnectedView.tsx dj_client/src/hooks/useConnection.ts dj_client/tests/tls-profile-contract.test.mjs
git commit -m "feat(dj): configure pinned server trust"
```

### Task 9: Package, Document, and Prove the Two-Port Release

**Files:**
- Modify: `deploy/pterodactyl/runtime-lock.json`
- Modify: `deploy/pterodactyl/build-runtime.sh`
- Modify: `deploy/pterodactyl/build-release.sh`
- Modify: `deploy/pterodactyl/test-build-release.sh`
- Modify: `deploy/pterodactyl/verify-release.ps1`
- Modify: `docs/deployment/PTERODACTYL.md`
- Modify: `README.md`
- Modify: `docs/CONNECTIVITY.md`
- Modify: `.github/workflows/release.yml`
- Test: `vj_server/tests/test_web_gateway.py`
- Test: `vj_server/tests/test_pterodactyl.py`

**Interfaces:**
- Consumes: all prior gateway, TLS, client, and Pterodactyl interfaces.
- Produces: portable release containing aiohttp, exact two-port operator instructions, release gates, and final real transport evidence.

- [ ] **Step 1: Write failing release-contract tests**

Extend release tests to inspect the archive and runtime:

```python
import json
import sys
from pathlib import Path
from zipfile import ZipFile

archive_path = Path(sys.argv[1])
lock = json.loads(Path("deploy/pterodactyl/runtime-lock.json").read_text())
dependencies = set(lock["dependencies"])
assert any(item.startswith("aiohttp==") for item in dependencies)

with ZipFile(archive_path) as release_zip:
    env_text = release_zip.read("mcav-vj/mcav.env.example").decode("utf-8")
    assert "HTTP_PORT=8080" in env_text
    assert "VJ_SERVER_PORT=25808" in env_text
    assert "UNIFIED_WEB=true" in env_text

deployment_text = Path("docs/deployment/PTERODACTYL.md").read_text(encoding="utf-8")
allocation_section = deployment_text.split("## Allocations", 1)[1].split("##", 1)[0]
assert "8080" in allocation_section
assert "25808" in allocation_section
assert "8766" not in allocation_section
```

Add documentation contract assertions that the allocation section lists only `8080` and `25808`, identifies `/ws`, explains certificate import/pinning and rotation, and never recommends public `8766` or plaintext DJ WS.

- [ ] **Step 2: Run RED**

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/feature-vj-control-panel
bash deploy/pterodactyl/test-build-release.sh
```

```powershell
node --test dj_client/tests/phase0-containment.test.mjs dj_client/tests/tls-profile-contract.test.mjs
```

Expected: aiohttp is absent from the runtime lock/import smoke and deployment docs still list `8766`/`9000`.

- [ ] **Step 3: Lock and verify the runtime dependency**

Add exact `aiohttp` and resolved transitive versions to `runtime-lock.json` using hashes/version resolution from the WSL build environment. Update native runtime smoke to import `aiohttp` on both architectures. Keep `--only-binary=:all:` and the existing allowed platform tags; do not introduce source compilation in the portable build.

Update release verification to require the runtime config asset and reject secrets, private keys, test files, caches, and bytecode exactly as before.

- [ ] **Step 4: Update deployment and connectivity documentation**

Document the exact operator flow:

```text
Public TCP allocations:
  8080  Admin HTTPS, preview HTTPS, browser WSS /ws
  25808 DJ WSS

Private container services:
  8765  Minecraft renderer
  9001  Metrics
```

Require `MCAV_PUBLIC_HOST=<public-ip>`, importing `tls.crt` on admin machines, copying the SHA-256 fingerprint from `FIRST_LOGIN.txt` into the DJ profile, and using the explicit rotation command when the IP or certificate changes.

- [ ] **Step 5: Run the complete automated matrix**

PowerShell:

```powershell
npm run test:admin
node --test admin_panel/js/services/WebSocketService.test.mjs preview_tool/frontend/js/auth.test.mjs preview_tool/frontend/js/app-connection.test.mjs preview_tool/frontend/js/browser-endpoint.test.mjs protocol/tests/phase0-schemas.test.mjs
npm run build
npm --prefix dj_client run build
npm --prefix dj_client run test:containment
npm --prefix dj_client run test:rust
```

WSL:

```bash
cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/feature-vj-control-panel/vj_server
.venv/bin/python -m pytest -q
.venv/bin/ruff check .
.venv/bin/ruff format --check .
cd ..
vj_server/.venv/bin/bandit -q -c pyproject.toml preview_tool/backend/server.py vj_server/*.py
bash deploy/pterodactyl/test-start-mcav.sh
bash deploy/pterodactyl/test-build-release.sh
```

Expected: all tests pass; only established WebSocket deprecation/build notices remain.

- [ ] **Step 6: Run real two-port transport smoke**

Start the current bundle with a temporary test identity and non-conflicting local equivalents of public ports. Verify with real clients:

- HTTPS GET/HEAD for `/`, `/preview/`, and `/runtime-config.js`
- browser WSS `/ws` auth, initial state, one control round trip, bitmap frame, and clean disconnect
- DJ WSS matching-pin auth and one audio frame
- mismatching-pin DJ sends zero application frames
- wrong-origin browser receives 403
- traversal requests receive bounded 404
- shutdown closes listeners cleanly

Use `ss -ltnp` in WSL to prove the VJ process has exactly two non-loopback listeners. Confirm renderer and metrics are loopback-only and `8766` is absent. Capture the command output and fresh browser console result in the ignored SDD report; no new visual screenshot is required unless layout changes.

- [ ] **Step 7: Review the full range and commit packaging/docs**

Run:

```powershell
git diff --check
git status --short
```

Request an independent code review covering gateway containment/origin, adapter cleanup, listener exposure, TLS SAN/rotation, pin-before-auth ordering, secrets, packaging, and legacy compatibility. Resolve every Critical or Important finding with regression-first follow-up commits and scoped re-review.

Commit the packaging/documentation unit:

```powershell
git add -- deploy/pterodactyl/runtime-lock.json deploy/pterodactyl/build-runtime.sh deploy/pterodactyl/build-release.sh deploy/pterodactyl/test-build-release.sh deploy/pterodactyl/verify-release.ps1 docs/deployment/PTERODACTYL.md README.md docs/CONNECTIVITY.md .github/workflows/release.yml
git commit -m "feat(release): publish two-port VJ bundle"
```

Expected final status: only the preserved untracked `AGENTS.md`; no tracked diff; all review findings resolved.
