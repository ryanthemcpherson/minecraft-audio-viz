# VJ Unified Ingress and Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the packaged VJ runtime expose one secure TLS port, portable first-run onboarding, managed-runtime readiness/health/shutdown, parent monitoring, and certificate-pinned DJ connectivity while preserving existing VJ behavior.

**Architecture:** Introduce an `aiohttp` ingress package and a small WebSocket adapter so the existing DJ and relay handlers can migrate without duplicating business logic. Setup identity and TLS generation are focused services with atomic state files. Managed-mode lifecycle reports over the already authenticated renderer channel; the DJ client uses Rustls native trust or an exact administrator-provided self-signed certificate pin.

**Tech Stack:** Python 3.12, `aiohttp`, `cryptography`, websockets 16 compatibility, msgspec, bcrypt, pytest/pytest-asyncio, React 19/Vitest, Rust 2024, Tokio, tokio-tungstenite with Rustls, rustls 0.23, SHA-256.

**Spec:** `docs/superpowers/specs/2026-08-31-self-installing-paper-release-design.md`

## Global Constraints

- Complete the trust/store and Paper-supervision plans first; consume their runtime-control schemas unchanged.
- Public admin credentials and DJ traffic require TLS. Plain HTTP is allowed only with an explicit development flag and loopback bind.
- One public listener serves `/`, `/preview/`, `/assets/`, `/setup/*`, `/ws/admin`, `/ws/preview`, `/ws/dj`, and `/healthz`.
- Metrics and the Minecraft renderer remain on loopback-only listeners.
- Browser routes validate Origin; native DJ authentication does not rely on Origin.
- Authentication succeeds before state, roster, scene, metrics, or frame subscriptions are returned.
- Static paths, headers, payloads, connection counts, rates, auth attempts, setup attempts, queue depths, and timeouts are bounded.
- Setup tokens are 256 random bits, HMAC-SHA-256 hashed with the renderer secret, expire after 30 minutes, allow five failures, and are single use.
- Passwords contain 12-72 UTF-8 bytes and use bcrypt cost 12 for release 1.2.0.
- Release builds have no accept-any-certificate option. A self-signed DJ connection requires the exact SHA-256 leaf fingerprint before WebSocket authentication bytes are sent.
- The VJ process exits when the exact parent PID/start identity disappears.
- Preserve existing DJ queue, connect-code, pattern, admin, preview, renderer, metrics, and Pterodactyl behavior through adapters and compatibility flags.
- Use WSL-native Python in `vj_server/.venv`; do not install Python packages with Windows Python.

## File Structure

### Transport and ingress

- `vj_server/transport.py` — `WebSocketPeer` protocol plus websockets and aiohttp adapters.
- `vj_server/ingress/__init__.py` — package exports.
- `vj_server/ingress/limits.py` — immutable route/body/rate/connection limits.
- `vj_server/ingress/static.py` — contained route-to-root static resolver and security headers.
- `vj_server/ingress/app.py` — aiohttp application, route setup, connection accounting, lifecycle.
- `vj_server/ingress/security.py` — Origin, remote-address, rate, payload, and TLS policy helpers.
- `vj_server/tests/test_transport.py` — adapter semantics and close/error compatibility.
- `vj_server/tests/test_ingress_static.py` — traversal, headers, content type, and caching tests.
- `vj_server/tests/test_ingress_routes.py` — one-port routes, TLS policy, auth boundary, and limit tests.
- `vj_server/models.py` — keeps deprecated static helpers for Pterodactyl compatibility but no longer owns the managed ingress.
- `vj_server/vj_server.py` — starts one ingress runner instead of three public servers in managed mode.

### Identity and setup

- `vj_server/identity.py` — atomic auth/TLS/setup paths and partial-identity validation.
- `vj_server/setup.py` — setup-token HMAC lifecycle and administrator creation.
- `vj_server/tls.py` — portable P-256 self-signed certificate generation and supplied-pair validation.
- `vj_server/tests/test_identity.py` — first-run, idempotence, partial state, permissions, and atomicity.
- `vj_server/tests/test_setup.py` — token expiry/attempt/rotation/single-use/password tests.
- `vj_server/tests/test_tls.py` — SAN, fingerprint, key, validity, and cross-platform behavior.
- `admin_panel/setup.html` — minimal first-run shell loaded only while setup is available.
- `admin_panel/js/setup.js` — fragment extraction, `history.replaceState`, account creation, and error UI.
- `admin_panel/css/setup.css` — accessible dark setup surface using canonical design tokens.
- `admin_panel/tests/setup.test.js` — token-removal, form, error, keyboard, and secret-storage tests.

### Managed runtime

- `vj_server/managed.py` — managed environment parsing, parent monitor, readiness/health/control messages, and performance level.
- `vj_server/tests/test_managed.py` — parent reuse, readiness order, health, shutdown, and performance instructions.
- `vj_server/cli.py` — `--managed-by-paper`, one public bind/port, state paths, and development-only legacy listener flags.
- `vj_server/config.py` — validated managed/public settings.
- `vj_server/viz_client.py` — sends readiness/health and consumes shutdown/performance after renderer auth.
- `vj_server/metrics.py` — publishes managed lifecycle/performance counters without public binding.

### DJ certificate trust and invite

- `protocol/schemas/types/dj-invite.schema.json` — server URL, connect code, certificate fingerprint, and expiration.
- `protocol/schemas/index.json` — registers `dj_invite` type.
- `admin_panel/js/managers/connect-codes.js` — emits copyable invite JSON/URL from a generated code and runtime fingerprint.
- `admin_panel/tests/connect-codes.test.js` — invite content and no-secret tests.
- `dj_client/src-tauri/src/protocol/tls.rs` — native-root or exact-leaf-pin Rustls verifier.
- `dj_client/src-tauri/src/protocol/client.rs` — connects to `/ws/dj` using pinned TLS config.
- `dj_client/src-tauri/src/protocol/mod.rs` — exports TLS types.
- `dj_client/src-tauri/src/state.rs` — bounded saved server profile with URL and optional fingerprint.
- `dj_client/src-tauri/src/lib.rs` — Tauri commands consume invite/profile.
- `dj_client/src/hooks/useConnection.ts` — parses invite and submits URL/code/fingerprint.
- `dj_client/src/components/ConnectForm.tsx` — invite/manual fingerprint UI.
- `dj_client/src-tauri/Cargo.toml` and `Cargo.lock` — Rustls/native-roots/SHA dependencies.
- `dj_client/src-tauri/src/protocol/tls_tests.rs` — trusted, pinned, wrong-pin, host, expiry, and no-auth-before-pin tests.
- `dj_client/src/hooks/useConnection.test.tsx` — invite and error behavior.

---

### Task 1: Add a transport-neutral WebSocket boundary

**Files:**
- Create: `vj_server/transport.py`
- Create: `vj_server/tests/test_transport.py`
- Modify: `vj_server/models.py`
- Modify: `vj_server/dj_manager.py`
- Modify: `vj_server/relay.py`
- Modify: `vj_server/vj_server.py`

**Interfaces:**
- Consumes: current `websockets` connection objects and future `aiohttp.web.WebSocketResponse` objects.
- Produces: `WebSocketPeer.recv() -> str | bytes`, `send(str | bytes)`, `close(code: int, reason: str)`, async iteration, `remote_address`, and `request_headers` with identical behavior for existing handlers.

- [ ] **Step 1: Write failing adapter contract tests**

Define a runtime-checkable protocol and exercise fake websockets/aiohttp transports:

```python
async def assert_peer_contract(peer: WebSocketPeer) -> None:
    assert peer.remote_address == ("127.0.0.1", 43210)
    assert peer.request_headers.get("Origin") == "https://localhost:8080"
    await peer.send("hello")
    assert await peer.recv() == "reply"
    await peer.close(4003, "policy")
    assert peer.closed
```

Test text, bytes, peer close, abnormal close, oversized messages, iteration termination, send-after-close, and exception normalization into `PeerClosed`/`PeerProtocolError` without exposing frame content.

- [ ] **Step 2: Run the focused test and confirm the transport module is missing**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_transport.py -q'
```

Expected: FAIL during import.

- [ ] **Step 3: Implement adapters and type the handler boundaries**

```python
class WebSocketPeer(Protocol):
    remote_address: tuple[str, int] | None
    request_headers: Mapping[str, str]

    async def recv(self) -> str | bytes: ...
    async def send(self, message: str | bytes) -> None: ...
    async def close(self, code: int = 1000, reason: str = "") -> None: ...
    def __aiter__(self) -> AsyncIterator[str | bytes]: ...
```

`WebsocketsPeer` delegates without behavior changes. `AiohttpPeer` maps `WSMsgType.TEXT/BINARY/CLOSE/CLOSED/ERROR`, enforces the route maximum before returning, and never returns PING/PONG control frames to application handlers.

Change DJ/relay handler annotations and stored connection types to `WebSocketPeer`. Keep the existing `ws_serve` calls temporarily using `WebsocketsPeer`, proving no product behavior changes in this task.

- [ ] **Step 4: Run adapter plus existing DJ/browser/relay suites**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_transport.py tests/test_dj_manager.py tests/test_browser_auth.py tests/test_relay.py -q'
```

Expected: PASS.

- [ ] **Step 5: Commit the transport seam**

```powershell
git status --short
git diff -- vj_server/transport.py vj_server/tests/test_transport.py vj_server/models.py vj_server/dj_manager.py vj_server/relay.py vj_server/vj_server.py
git add -- vj_server/transport.py vj_server/tests/test_transport.py vj_server/models.py vj_server/dj_manager.py vj_server/relay.py vj_server/vj_server.py
git commit -m "refactor(vj): isolate WebSocket transport"
```

### Task 2: Serve contained static assets and health on aiohttp

**Files:**
- Create: `vj_server/ingress/__init__.py`
- Create: `vj_server/ingress/limits.py`
- Create: `vj_server/ingress/static.py`
- Create: `vj_server/ingress/security.py`
- Create: `vj_server/ingress/app.py`
- Create: `vj_server/tests/test_ingress_static.py`
- Create: `vj_server/tests/test_ingress_routes.py`
- Modify: `vj_server/pyproject.toml`
- Modify: `uv.lock`

**Interfaces:**
- Consumes: project root, TLS context, route callbacks, `IngressLimits`, allowed browser origins, and setup availability.
- Produces: `IngressServer.start()`, `stop()`, `healthy`, `bound_address`, `certificate_fingerprint`, and aiohttp request/WebSocket route dispatch.

- [ ] **Step 1: Add `aiohttp==3.12.15` and write failing route/static tests**

Use `aiohttp.test_utils.TestServer/TestClient`. Test root/preview/assets/health success, method rejection, missing files, directories, dotfiles, traversal variants from existing `test_static_http.py`, Windows separators/reserved names, encoded slashes, symlinks, case collisions, MIME allowlist, conditional requests, ranges disabled, and headers.

```python
async def test_html_has_noncache_security_headers(ingress_client: TestClient) -> None:
    response = await ingress_client.get("/")
    assert response.status == 200
    assert response.headers["Cache-Control"] == "no-store"
    assert response.headers["X-Content-Type-Options"] == "nosniff"
    assert response.headers["Content-Security-Policy"] == EXPECTED_CSP
    assert "text/html" in response.headers["Content-Type"]
```

- [ ] **Step 2: Run tests and confirm missing ingress package**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/pip install -e ".[release]"; .venv/bin/python -m pytest tests/test_ingress_static.py tests/test_ingress_routes.py -q'
```

Expected: FAIL during import.

- [ ] **Step 3: Implement fixed route roots and security headers**

Map only these roots: `/` to `admin_panel`, `/preview/` to `preview_tool/frontend`, and `/assets/` to declared asset roots. Decode once, split on `/`, reject empty internal segments, dot segments, backslashes, colon, NUL, control characters, Windows devices, non-NFC names, and any resolved path outside the fixed root. Reject symlinks at every path component.

Allow MIME types only for `.html`, `.js`, `.mjs`, `.css`, `.json`, `.png`, `.jpg`, `.jpeg`, `.svg`, `.ico`, `.woff2`, `.wasm`, and `.map`. Set exact CSP from the bundled asset requirements; do not use `unsafe-eval`. HTML/setup use `no-store`; hashed assets use `public,max-age=31536000,immutable`; other assets use `no-cache`.

- [ ] **Step 4: Implement ingress lifecycle and minimal health**

`IngressServer.start()` creates one `AppRunner`, one `TCPSite`, and returns only after bind succeeds. The connection limiter reserves/release slots in `try/finally`. `/healthz` returns `{"status":"ok"}` or HTTP 503 `{"status":"starting"}` with no versions, paths, queues, or exception text.

In production mode, refuse missing TLS or public plaintext. Development plaintext requires `allow_insecure_loopback=True` and a loopback host.

- [ ] **Step 5: Run ingress, existing static, and full VJ tests**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_ingress_static.py tests/test_ingress_routes.py tests/test_static_http.py -q; .venv/bin/python -m pytest tests -q'
```

Expected: PASS. Existing static helpers remain for legacy/Pterodactyl compatibility but are not used by `IngressServer`.

- [ ] **Step 6: Commit contained ingress foundations**

```powershell
git status --short
git diff -- vj_server/ingress vj_server/tests/test_ingress_static.py vj_server/tests/test_ingress_routes.py vj_server/pyproject.toml uv.lock
git add -- vj_server/ingress vj_server/tests/test_ingress_static.py vj_server/tests/test_ingress_routes.py vj_server/pyproject.toml uv.lock
git commit -m "feat(vj): serve control assets on secure ingress"
```

### Task 3: Move DJ, admin, and preview WebSockets onto one listener

**Files:**
- Modify: `vj_server/ingress/app.py`
- Modify: `vj_server/ingress/security.py`
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/cli.py`
- Modify: `vj_server/config.py`
- Create: `vj_server/tests/test_ingress_websockets.py`
- Modify: `vj_server/tests/test_vj_server_helpers.py`
- Modify: `vj_server/tests/test_static_http.py`

**Interfaces:**
- Consumes: `VJServer._handle_dj_connection(WebSocketPeer)`, `_handle_browser_client(WebSocketPeer)`, preview read-only policy, TLS/Origin/connection limits.
- Produces: `/ws/dj`, `/ws/admin`, and `/ws/preview` upgrades on the same `IngressServer`; managed mode no longer starts public ports 9000 or 8766.

- [ ] **Step 1: Write failing one-port and route-isolation tests**

Start real aiohttp TLS test ingress and connect real clients. Assert each route reaches only its handler and applies distinct policy:

```python
async def test_unauthenticated_admin_receives_no_state(server: RunningIngress) -> None:
    async with server.ws_connect("/ws/admin", origin=server.origin) as websocket:
        await websocket.send_json({"type": "get_zones"})
        message = await websocket.receive_json()
        assert message == {"type": "auth_error", "error": "authentication required"}
        assert server.vj.browser_clients == set()
```

Cover valid/wrong/missing Origin on browser routes, arbitrary Origin on DJ route, TLS requirement, 64 KiB message limit, binary rejection where unsupported, idle timeout, route connection caps, auth-attempt limits, cleanup on handler exception, and DJ frames not blocked by a slow static response.

- [ ] **Step 2: Run the focused tests and confirm WebSocket routes are absent**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_ingress_websockets.py -q'
```

Expected: FAIL with 404/route missing.

- [ ] **Step 3: Add route-specific upgrade handlers**

Create `WebSocketResponse(max_msg_size=65_536, heartbeat=None, autoclose=True, autoping=True)` only after route admission succeeds. Wrap it in `AiohttpPeer` and call the existing handler. Admin and preview pass the normalized remote IP from trusted socket peer data only; do not trust forwarding headers unless a separately configured trusted proxy CIDR matches.

Admin requires existing VJ authentication. Preview defaults to the same authenticated handler for release 1.2.0; a future public read-only mode is out of scope. DJ applies existing code/static authentication and queue policy.

- [ ] **Step 4: Switch managed `VJServer.run()` to one ingress**

Managed mode constructs and starts `IngressServer`, then starts loopback metrics, coordinator, renderer reconnect, and main loop. Remove the managed-mode `ws_serve` and HTTP thread starts; retain a `--legacy-separate-listeners` development/Pterodactyl compatibility flag that uses old ports and prints a deprecation warning.

Shutdown first rejects new ingress connections, sends bounded shutdown messages, closes active peers, cancels background tasks, stops metrics, and cleans up the `AppRunner`. Update CLI help to document one `--public-host/--public-port`; reject simultaneous managed and legacy flags.

- [ ] **Step 5: Run one-port, DJ, relay, CLI, and full VJ tests**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_ingress_websockets.py tests/test_dj_manager.py tests/test_browser_auth.py tests/test_relay.py tests/test_static_http.py tests/test_vj_server_helpers.py -q; .venv/bin/python -m pytest tests -q'
```

Expected: PASS and managed tests bind one public socket only.

- [ ] **Step 6: Commit unified WebSockets**

```powershell
git status --short
git diff -- vj_server/ingress vj_server/vj_server.py vj_server/cli.py vj_server/config.py vj_server/tests
git add -- vj_server/ingress/app.py vj_server/ingress/security.py vj_server/vj_server.py vj_server/cli.py vj_server/config.py vj_server/tests/test_ingress_websockets.py vj_server/tests/test_vj_server_helpers.py vj_server/tests/test_static_http.py
git commit -m "feat(vj): unify public traffic on one TLS port"
```

### Task 4: Generate portable TLS identity and atomic administrator setup

**Files:**
- Create: `vj_server/identity.py`
- Create: `vj_server/setup.py`
- Create: `vj_server/tls.py`
- Create: `vj_server/tests/test_identity.py`
- Create: `vj_server/tests/test_setup.py`
- Create: `vj_server/tests/test_tls.py`
- Modify: `vj_server/ingress/app.py`
- Modify: `vj_server/cli.py`
- Modify: `vj_server/pterodactyl.py`
- Modify: `vj_server/pyproject.toml`
- Modify: `uv.lock`

**Interfaces:**
- Consumes: managed state directory, renderer secret, clock, random bytes, configured public names, optional supplied cert/key.
- Produces: `IdentityStore.ensure() -> IdentityState`, `SetupManager.rotate() -> SetupOffer`, `verify(token)`, `create_admin(token, username, password)`, and `TlsManager.ensure() -> TlsIdentity`.

- [ ] **Step 1: Write failing identity/TLS/setup transaction tests**

Cover empty first run, idempotent second run, every partial identity subset, file permission policy, termination at each atomic boundary, supplied valid/invalid/mismatched key pair, generated P-256 key, SANs, 397-day validity, SHA-256 fingerprint format, token hash, expiry, five attempts, rotation, single use, username normalization, password byte limits, bcrypt cost, concurrent create, and no plaintext credential file.

```python
async def test_successful_setup_consumes_token_and_stores_only_bcrypt(tmp_path: Path) -> None:
    identity = identity_store(tmp_path, renderer_secret=b"r" * 32)
    offer = identity.setup.rotate()
    await identity.setup.create_admin(offer.token, "VJ_Admin", "correct horse battery")
    stored = json.loads((tmp_path / "auth.json").read_text())
    assert stored["vj_operators"]["vj_admin"]["key_hash"].startswith("bcrypt:$2")
    assert "correct horse battery" not in (tmp_path / "auth.json").read_text()
    assert identity.setup.verify(offer.token) is False
```

- [ ] **Step 2: Run focused tests and confirm modules are missing**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_identity.py tests/test_setup.py tests/test_tls.py -q'
```

Expected: FAIL during import.

- [ ] **Step 3: Implement portable TLS generation and validation**

Move the locked `cryptography` dependency from the release-tool extra into the VJ package's required runtime dependencies. Use `cryptography.x509` with ECDSA P-256, random 128-bit serial, UTC `not_valid_before = now - 5 minutes`, `not_valid_after = now + 397 days`, `BasicConstraints(ca=False)`, server-auth EKU, and SAN entries for localhost, loopbacks, plus validated configured DNS/IP values. Write PKCS#8 PEM key mode `0600` and certificate mode `0644` through unique temp files and atomic replace.

Validate a supplied pair by loading both, comparing public keys, checking current validity, server-auth use, and configured public name when present. Return an uppercase 64-hex SHA-256 DER fingerprint; user interfaces may group it visually without changing the invite value.

- [ ] **Step 4: Implement atomic identity and HMAC setup state**

`IdentityStore` refuses a partial state set; it never overwrites it. `SetupManager` stores only HMAC-SHA-256 with the renderer secret as key and token bytes as message, UTC timestamps, attempts, and consumed. Compare HMAC with `hmac.compare_digest`. Token is `secrets.token_urlsafe(32)` and never logged by the service.

Normalize usernames with Unicode NFKC then ASCII-lowercase and require regex `[a-z0-9][a-z0-9_-]{2,31}`. Count password UTF-8 bytes, reject NUL/control, and hash at bcrypt rounds 12. Commit auth and consumed setup state under one journal so termination cannot create an administrator while leaving token reusable.

- [ ] **Step 5: Route setup endpoints and retire plaintext first-login generation**

`GET /setup/status` reveals only available/expired/complete. `POST /setup/verify` and `/setup/admin` accept JSON bodies at most 4 KiB and rate limit by socket IP plus setup state. Apply `no-store` and identical generic invalid/expired responses.

Keep `pterodactyl.py` behavior compatible for the legacy bundle, but refactor its TLS/auth primitives to call the new services. Its historical `FIRST_LOGIN.txt` remains only for old bundle builds; managed plugin mode never creates it.

- [ ] **Step 6: Run focused, Pterodactyl, ingress, and full tests**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_identity.py tests/test_setup.py tests/test_tls.py tests/test_pterodactyl.py tests/test_ingress_routes.py -q; .venv/bin/python -m pytest tests -q'
```

Expected: PASS.

- [ ] **Step 7: Commit identity and setup services**

```powershell
git status --short
git diff -- vj_server/identity.py vj_server/setup.py vj_server/tls.py vj_server/tests/test_identity.py vj_server/tests/test_setup.py vj_server/tests/test_tls.py vj_server/ingress/app.py vj_server/cli.py vj_server/pterodactyl.py vj_server/pyproject.toml uv.lock
git add -- vj_server/identity.py vj_server/setup.py vj_server/tls.py vj_server/tests/test_identity.py vj_server/tests/test_setup.py vj_server/tests/test_tls.py vj_server/ingress/app.py vj_server/cli.py vj_server/pterodactyl.py vj_server/pyproject.toml uv.lock
git commit -m "feat(vj): onboard administrators securely"
```

### Task 5: Build the accessible first-run setup surface

**Files:**
- Create: `admin_panel/setup.html`
- Create: `admin_panel/js/setup.js`
- Create: `admin_panel/css/setup.css`
- Create: `admin_panel/tests/setup.test.js`
- Modify: `vj_server/ingress/app.py`
- Modify: `package.json`
- Modify: `package-lock.json`

**Interfaces:**
- Consumes: raw setup token from URL fragment, `/setup/status`, `/setup/verify`, and `/setup/admin` JSON APIs.
- Produces: an accessible setup flow that removes the token from browser history immediately and never stores credentials or token in browser persistence.

- [ ] **Step 1: Write failing browser-DOM setup tests**

Add a root `test` script using Vitest in run mode plus locked Vitest/jsdom development dependencies. Test fragment extraction/removal before network call, expired/invalid/generic errors, username/password client bounds, submit disabling, keyboard order, focus management, reduced motion, fetch body, no `localStorage`/`sessionStorage`, and success redirect.

```javascript
it('removes the setup fragment before verifying it', async () => {
  history.replaceState({}, '', '/setup/#token=secret-value');
  await startSetup({ fetch: fakeFetch });
  expect(location.hash).toBe('');
  expect(fakeFetch.calls[0].body).toContain('secret-value');
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});
```

- [ ] **Step 2: Run the focused frontend test and confirm files are missing**

Run:

```powershell
npm test -- admin_panel/tests/setup.test.js
```

Expected: FAIL because the setup module does not exist.

- [ ] **Step 3: Implement the setup document and controller**

Use semantic `<main>`, labelled fields, live status region, visible password requirements, and one primary action. On module start, parse `#token=<base64url>`, immediately call `history.replaceState({}, document.title, '/setup/')`, keep token only in a closure, and POST it in JSON. Clear the closure after success or terminal failure.

Render all server strings with `textContent`. Do not use `innerHTML`. Password input uses `autocomplete="new-password"`; username uses `autocomplete="username"`. On success, clear both inputs and navigate to `/` after a visible confirmation.

- [ ] **Step 4: Implement canonical dark styling and reduced-motion behavior**

Use existing canonical color/font variables where available; define local fallbacks from the spec. Maintain WCAG AA contrast, visible focus, 44 px interactive targets, responsive width, and no animation under `prefers-reduced-motion: reduce`. Do not load external fonts or scripts during setup.

- [ ] **Step 5: Serve setup assets only while setup is available**

Ingress returns 404 for `/setup/` after setup completion except authenticated recovery mode. Static setup assets receive `no-store`. CSP permits only self scripts/styles and same-origin HTTPS/WSS connections.

- [ ] **Step 6: Run frontend, ingress, and containment tests**

Run:

```powershell
npm test -- admin_panel/tests/setup.test.js
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_ingress_routes.py tests/test_setup.py -q'
```

Expected: PASS.

- [ ] **Step 7: Commit the setup UI**

```powershell
git status --short
git diff -- admin_panel/setup.html admin_panel/js/setup.js admin_panel/css/setup.css admin_panel/tests/setup.test.js vj_server/ingress/app.py package.json
git add -- admin_panel/setup.html admin_panel/js/setup.js admin_panel/css/setup.css admin_panel/tests/setup.test.js vj_server/ingress/app.py package.json package-lock.json
git commit -m "feat(web): add first-run MCAV setup"
```

### Task 6: Report managed readiness, health, parent death, shutdown, and load level

**Files:**
- Create: `vj_server/managed.py`
- Create: `vj_server/tests/test_managed.py`
- Modify: `vj_server/cli.py`
- Modify: `vj_server/config.py`
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/viz_client.py`
- Modify: `vj_server/metrics.py`

**Interfaces:**
- Consumes: managed environment (`MCAV_RENDERER_SECRET`, generation, nonce, parent PID/start identity, state dir), ingress lifecycle, VJ loop health, renderer connection, and `runtime_shutdown`/`runtime_performance` messages.
- Produces: `ManagedRuntime.start()`, `ready_payload()`, 5-second health payloads, parent monitor, graceful stop event, and applied performance level.

- [ ] **Step 1: Write failing managed-mode order and control tests**

Use fake ingress/renderer/process inspectors. Assert readiness cannot send before TLS ingress bind, identity validation, metrics bind, and renderer authentication. Cover wrong/missing environment, PID reuse, parent death, parent inspector failure, shutdown generation mismatch, performance bounds, health sequence, queue limits, event-loop stall, render-loop stall, secret redaction, and Windows/POSIX parent identity adapters.

```python
async def test_readiness_follows_all_listener_binds_and_renderer_auth() -> None:
    managed = fixture_managed()
    await managed.start()
    assert events == ["identity", "ingress-bound", "metrics-bound", "renderer-auth", "runtime-ready"]
```

- [ ] **Step 2: Run focused tests and confirm managed module is missing**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_managed.py -q'
```

Expected: FAIL during import.

- [ ] **Step 3: Parse managed environment and monitor exact parent identity**

Require generation unsigned 64-bit, nonce 32-64 Base64URL characters, parent PID positive, parent start identity platform-normalized, renderer secret at least 32 bytes, and owned absolute state dir. Do not accept these through command arguments.

On Linux, read `/proc/<pid>/stat` starttime safely and compare to the passed boot-relative tick value. On Windows, query process creation time through the existing `windows` capability in a tiny packaged helper only if Python cannot access it; the build matrix tests both. Poll once per second. After two consecutive missing/mismatched observations, set stop and exit cleanly. Never signal or kill the parent.

- [ ] **Step 4: Send readiness/health and consume control after renderer auth**

Extend `VizClient` with an authenticated control callback. After `auth_ok`, managed mode sends runtime-ready only after local startup barrier completion. Health sends every five seconds with increasing sequence and bounded integers. Shutdown validates generation and sets the existing VJ stop event. Performance validates level plus server hard caps and updates preview interval, target FPS, entity budget, and particle policy without persisting config.

- [ ] **Step 5: Publish loopback-only metrics and structured logs**

Add counters/gauges for managed state, parent monitor, readiness, health sends, control rejects, ingress state, performance level, and reason categories. Do not use URL, username, IP, exception text, path, nonce, or version from untrusted input as a metric label.

- [ ] **Step 6: Run managed, renderer, metrics, ingress, and full tests**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_managed.py tests/test_viz_client_auth.py tests/test_metrics.py tests/test_ingress_websockets.py -q; .venv/bin/python -m pytest tests -q'
```

Expected: PASS.

- [ ] **Step 7: Commit managed lifecycle**

```powershell
git status --short
git diff -- vj_server/managed.py vj_server/tests/test_managed.py vj_server/cli.py vj_server/config.py vj_server/vj_server.py vj_server/viz_client.py vj_server/metrics.py
git add -- vj_server/managed.py vj_server/tests/test_managed.py vj_server/cli.py vj_server/config.py vj_server/vj_server.py vj_server/viz_client.py vj_server/metrics.py
git commit -m "feat(vj): integrate Paper-managed lifecycle"
```

### Task 7: Produce certificate-bound DJ invites

**Files:**
- Create: `protocol/schemas/types/dj-invite.schema.json`
- Modify: `protocol/schemas/index.json`
- Create: `admin_panel/js/managers/connect-codes.js`
- Create: `admin_panel/tests/connect-codes.test.js`
- Modify: `admin_panel/index.html`
- Modify: `admin_panel/js/app.js`
- Modify: `vj_server/relay.py`
- Modify: `vj_server/ingress/app.py`

**Interfaces:**
- Consumes: generated connect code, configured canonical public URL, TLS leaf SHA-256 fingerprint, and code expiration.
- Produces: exact invite JSON `{schema_version, server_url, connect_code, certificate_sha256, expires_at}` and `mcav://connect?invite=<base64url-json>` copy action.

- [ ] **Step 1: Write failing invite schema and browser tests**

Test trusted-certificate and self-signed cases, missing public URL, URL normalization to `wss://host:port/ws/dj`, exact 64-hex uppercase fingerprint, expiration, base64url round trip, clipboard failure, no administrator credential, no renderer secret, and safe `textContent` rendering.

```javascript
it('builds an exact pinned invite for generated TLS', () => {
  expect(buildInvite(runtime, code)).toEqual({
    schema_version: 1,
    server_url: 'wss://mc.example:8080/ws/dj',
    connect_code: 'BEAT-7K3M',
    certificate_sha256: 'AA'.repeat(32),
    expires_at: 1788200000,
  });
});
```

- [ ] **Step 2: Run focused tests and confirm invite module is absent**

Run:

```powershell
npm test -- admin_panel/tests/connect-codes.test.js
```

Expected: FAIL during module resolution.

- [ ] **Step 3: Define invite schema and authenticated runtime-info response**

The schema requires `schema_version: 1`, HTTPS-derived WSS URL no credentials/query/fragment, existing connect-code format, optional `certificate_sha256` as 64 uppercase hex only when generated/self-signed TLS is active, and Unix expiration within the connect-code maximum TTL.

Expose public URL and leaf fingerprint only to an authenticated admin WebSocket in the existing connect-code response. Never expose key paths or certificate PEM.

- [ ] **Step 4: Implement copyable JSON and deep-link invites**

Keep one module responsible for normalization/encoding. The UI shows URL, expiry, and a grouped fingerprint; Copy Invite writes the `mcav://` link. Manual copy fallback selects a readonly field only after explicit click.

- [ ] **Step 5: Run invite, relay auth, and frontend tests**

Run:

```powershell
npm test -- admin_panel/tests/connect-codes.test.js
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests/test_browser_auth.py tests/test_relay.py tests/test_ingress_websockets.py -q'
```

Expected: PASS.

- [ ] **Step 6: Commit DJ invites**

```powershell
git status --short
git diff -- protocol/schemas/types/dj-invite.schema.json protocol/schemas/index.json admin_panel/js/managers/connect-codes.js admin_panel/tests/connect-codes.test.js admin_panel/index.html admin_panel/js/app.js vj_server/relay.py vj_server/ingress/app.py
git add -- protocol/schemas/types/dj-invite.schema.json protocol/schemas/index.json admin_panel/js/managers/connect-codes.js admin_panel/tests/connect-codes.test.js admin_panel/index.html admin_panel/js/app.js vj_server/relay.py vj_server/ingress/app.py
git commit -m "feat(web): issue certificate-bound DJ invites"
```

### Task 8: Pin self-signed certificates in the DJ client

**Files:**
- Create: `dj_client/src-tauri/src/protocol/tls.rs`
- Create: `dj_client/src-tauri/src/protocol/tls_tests.rs`
- Modify: `dj_client/src-tauri/src/protocol/client.rs`
- Modify: `dj_client/src-tauri/src/protocol/mod.rs`
- Modify: `dj_client/src-tauri/src/state.rs`
- Modify: `dj_client/src-tauri/src/lib.rs`
- Modify: `dj_client/src-tauri/Cargo.toml`
- Modify: `dj_client/src-tauri/Cargo.lock`
- Modify: `dj_client/src/hooks/useConnection.ts`
- Create: `dj_client/src/hooks/useConnection.test.tsx`
- Modify: `dj_client/src/components/ConnectForm.tsx`
- Modify: `dj_client/package.json`
- Modify: `dj_client/package-lock.json`

**Interfaces:**
- Consumes: invite/deep link or manual server URL, code, DJ name, and optional exact leaf fingerprint.
- Produces: normalized `ServerProfile`, Rustls client config using native roots or pinned self-signed leaf, and WebSocket connection to `/ws/dj` only after TLS verification.

- [ ] **Step 1: Write failing Rust TLS and React invite tests**

Generate ephemeral trusted/self-signed certificates in Rust tests. Cover native trusted root, correct pin, wrong pin, malformed pin, expired cert, hostname mismatch, changed cert, pin scoped to profile, no pin with self-signed, and proof the mock server receives no WebSocket/auth bytes after pin failure.

React tests cover `mcav://` parsing, malformed/oversized invite, URL/path normalization, code expiry warning, manual fingerprint formatting, saved-profile pin, changed-pin confirmation, and actionable TLS errors.

Add a DJ-client `test:ui` script using Vitest in run mode plus locked Vitest, jsdom, and React Testing Library development dependencies. Keep the existing `test` script as the full Rust suite so established CI behavior does not change.

```rust
#[tokio::test]
async fn wrong_pin_sends_no_websocket_handshake() {
    let server = TestTlsServer::self_signed().await;
    let result = connect_with_profile(server.url(), fingerprint([0x11; 32])).await;
    assert!(matches!(result, Err(ConnectionError::CertificatePinMismatch { .. })));
    assert_eq!(server.application_bytes_received(), 0);
}
```

- [ ] **Step 2: Run focused tests and confirm missing TLS module/UI support**

Run:

```powershell
Set-Location dj_client\src-tauri
cargo test protocol::tls_tests
Set-Location ..\..
npm --prefix dj_client run test:ui -- src/hooks/useConnection.test.tsx
```

Expected: FAIL because pinning/invite support is absent.

- [ ] **Step 3: Switch tokio-tungstenite to Rustls and implement verifier**

Use `tokio-tungstenite = { version = "0.29", features = ["rustls-tls-native-roots"] }`, `rustls = "0.23"`, `rustls-native-certs`, `sha2`, and `x509-parser` with audited locked versions. Remove the `native-tls` feature.

For no pin, build `WebPkiServerVerifier` from platform roots. For a pin, parse the leaf, compute SHA-256 DER, compare with `subtle` constant-time equality, add that exact self-signed leaf to a temporary root store, then delegate to WebPKI so hostname, validity, key usage, and signature still validate. Never implement a verifier that returns success solely because a pin string exists.

- [ ] **Step 4: Connect to exact invite URL before sending authentication**

Replace `connect_async_with_config` with `connect_async_tls_with_config` and an explicit Rustls connector. Normalize allowed schemes to `wss` in release builds, require path `/ws/dj`, reject userinfo/query/fragment, and cap URL at 2048 bytes. Construct/send `code_auth` only after the TLS/WebSocket future returns success.

Persist server URL and optional uppercase fingerprint in `tauri-plugin-store`; never persist connect code. A certificate mismatch never overwrites the saved pin automatically.

- [ ] **Step 5: Parse invites and present trust errors clearly**

Limit decoded invite JSON to 4 KiB, reject unknown fields and expired code. Manual self-signed entry requires all 64 fingerprint hex characters. UI errors distinguish untrusted certificate, pin mismatch, expired certificate, hostname mismatch, connection timeout, invalid invite, and authentication failure without exposing certificate bytes or internal errors.

- [ ] **Step 6: Run Rust, frontend, containment, audit, and build checks**

Run:

```powershell
Set-Location dj_client\src-tauri
cargo fmt --check
cargo test protocol::tls_tests
cargo test
cargo audit
Set-Location ..
npm run test:ui -- src/hooks/useConnection.test.tsx
npm run test:ui
npm test
npm run test:containment
npm run build
```

Expected: PASS. `rg -n "danger_accept_invalid|NoCertificateVerification|accept.*invalid" src-tauri/src` returns no match.

- [ ] **Step 7: Commit pinned DJ connectivity**

```powershell
git status --short
git diff -- dj_client/src-tauri/src/protocol dj_client/src-tauri/src/state.rs dj_client/src-tauri/src/lib.rs dj_client/src-tauri/Cargo.toml dj_client/src-tauri/Cargo.lock dj_client/src/hooks/useConnection.ts dj_client/src/hooks/useConnection.test.tsx dj_client/src/components/ConnectForm.tsx dj_client/package.json dj_client/package-lock.json
git add -- dj_client/src-tauri/src/protocol dj_client/src-tauri/src/state.rs dj_client/src-tauri/src/lib.rs dj_client/src-tauri/Cargo.toml dj_client/src-tauri/Cargo.lock dj_client/src/hooks/useConnection.ts dj_client/src/hooks/useConnection.test.tsx dj_client/src/components/ConnectForm.tsx dj_client/package.json dj_client/package-lock.json
git commit -m "feat(dj): pin self-signed MCAV servers"
```

### Task 9: Run the packaged managed-runtime integration checkpoint

**Files:**
- Create: `deploy/runtime/build-managed-runtime.sh`
- Create: `deploy/runtime/build-managed-runtime.ps1`
- Create: `deploy/runtime/runtime-lock.json`
- Create: `deploy/runtime/test-managed-runtime.py`
- Modify: `deploy/pterodactyl/build-runtime.sh`
- Modify: `deploy/pterodactyl/runtime-lock.json`
- Modify: `deploy/pterodactyl/test-build-release.sh`

**Interfaces:**
- Consumes: pinned standalone Python base, locked wheels, VJ/admin/preview/config/pattern source, Task 1 signing tool, and a test runtime manifest key.
- Produces: one development managed-runtime ZIP for the host platform and an integration result proving ingress/setup/readiness/parent/shutdown behavior.

- [ ] **Step 1: Write a failing packaged-runtime smoke harness**

The harness extracts only through the Java foundation's test CLI, starts a fake authenticated renderer, launches the actual packaged runtime with managed environment, completes TLS setup, connects admin/preview/DJ on one port, observes readiness/health, sends performance and shutdown, and asserts child exit.

```python
def test_managed_runtime_end_to_end(package: Path, java_verifier: Path) -> None:
    installed = java_install_fixture(java_verifier, package)
    with FakeRenderer(secret=SECRET) as renderer:
        runtime = launch_managed(installed, renderer)
        assert renderer.wait_for("runtime_ready", timeout=30)["runtime_api"] == 1
        assert_https_health(runtime.public_url) == {"status": "ok"}
        renderer.send({"type": "runtime_shutdown", "generation": GENERATION, "reason": "test"})
        assert runtime.wait(timeout=10) == 0
```

- [ ] **Step 2: Run the harness and confirm the builder is absent**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest deploy/runtime/test-managed-runtime.py -q'
```

Expected: FAIL because no managed runtime package exists.

- [ ] **Step 3: Refactor one pinned runtime lock for managed and legacy builders**

Move the standalone Python versions, URLs, SHA-256 digests, platform tags, and all wheel versions into `deploy/runtime/runtime-lock.json`. Include `aiohttp`, `cryptography`, and their transitive wheels. Both managed and Pterodactyl builders consume this lock; the Pterodactyl archive layout remains compatible.

The builder uses WSL for Linux, an explicit PowerShell path for Windows in the release plan, installs with `pip --require-hashes --no-deps` from a downloaded wheelhouse, copies only declared product assets, compiles bytecode reproducibly, removes caches/tests/build metadata, writes `files.json`, normalizes timestamps, and produces ZIP.

- [ ] **Step 4: Build and run the real managed smoke**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; bash deploy/runtime/build-managed-runtime.sh --platform linux-x86_64 --output dist/runtime-dev; .release-venv/bin/python -m pytest deploy/runtime/test-managed-runtime.py -q'
```

Expected: PASS with one public bound port and no surviving child.

- [ ] **Step 5: Reverify Pterodactyl compatibility and full component suites**

Run:

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; bash deploy/pterodactyl/test-build-release.sh; cd vj_server; .venv/bin/python -m pytest tests -q'
Set-Location minecraft_plugin
.\mvnw.cmd -q verify
Set-Location ..\dj_client\src-tauri
cargo test
```

Expected: PASS.

- [ ] **Step 6: Commit packaged-runtime integration**

```powershell
git status --short
git diff -- deploy/runtime deploy/pterodactyl
git add -- deploy/runtime/build-managed-runtime.sh deploy/runtime/build-managed-runtime.ps1 deploy/runtime/runtime-lock.json deploy/runtime/test-managed-runtime.py deploy/pterodactyl/build-runtime.sh deploy/pterodactyl/runtime-lock.json deploy/pterodactyl/test-build-release.sh
git commit -m "build(runtime): package managed VJ sidecar"
```

## Plan Completion Evidence

Before release completion, record:

- each task commit hash and full affected-file scope;
- full Python, admin frontend, Rust, Java integration, Pterodactyl compatibility, audit, and containment results;
- one-port socket assertions and route connection/payload/rate bounds;
- static-path hostile-case count and security-header output;
- setup/TLS atomicity and no-plaintext evidence;
- correct-pin/wrong-pin/no-auth-before-pin Rust evidence;
- readiness order, parent death, shutdown, and performance-control evidence; and
- the digest and file manifest of the packaged managed-runtime smoke artifact.

This plan is complete when one real packaged runtime can be launched in managed mode, generate/validate identity, onboard an administrator, serve all public product traffic over one TLS port, accept a certificate-pinned DJ, report authenticated readiness/health, respond to load/shutdown control, exit with its parent, and leave the legacy Pterodactyl tests passing.
