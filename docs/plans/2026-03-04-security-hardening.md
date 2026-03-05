# Security Hardening Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix all critical and high severity vulnerabilities, harden medium-severity issues, for pre-deployment readiness.

**Architecture:** Fixes are isolated per-component — coordinator (Python/FastAPI), VJ server (Python), Minecraft plugin (Java), Docker infra. No cross-component dependencies except Task 4 (plugin WS auth touches both VJ server and plugin).

**Tech Stack:** Python 3.12 (FastAPI, websockets, lupa), Java 21 (Paper API), Docker Compose, Rust/Tauri

---

## Phase 1: Critical + High (Must-Fix)

### Task 1: Rotate Leaked Resend API Key

**Files:**
- Check: `coordinator/.env` (should NOT be in git history)
- Verify: root `.gitignore` line 45

**Step 1: Verify .env was never committed**

Run: `git log --all --diff-filter=A -- coordinator/.env`
Expected: No output (file was never tracked)

If output shows it WAS committed, run `git log --all -- coordinator/.env` to check full history and consider `git filter-repo` to scrub.

**Step 2: Rotate the Resend API key**

1. Log into Resend dashboard
2. Revoke the compromised Resend API key (see coordinator/.env)
3. Generate new key
4. Update `coordinator/.env` locally with new key

**Step 3: Add coordinator-level .gitignore**

Create `coordinator/.gitignore`:
```
.env
test.db
__pycache__/
```

**Step 4: Commit**

```bash
git add coordinator/.gitignore
git commit -m "chore: add coordinator .gitignore for defense-in-depth"
```

---

### Task 2: Auth-Gate `/disconnect` Endpoint

**Files:**
- Modify: `coordinator/app/routers/connect.py:280-324`
- Test: `coordinator/tests/test_connect.py` (or create)

**Context:** The `/disconnect/{dj_session_id}` endpoint has no auth. Other server-scoped endpoints use server JWT auth. The endpoint should require a valid server key JWT so only the VJ server that owns the session can disconnect its DJs.

**Step 1: Write failing test**

In `coordinator/tests/test_connect.py`, add:
```python
@pytest.mark.asyncio
async def test_disconnect_requires_auth(client: AsyncClient):
    """Unauthenticated disconnect should return 401/403."""
    fake_id = "00000000-0000-0000-0000-000000000000"
    response = await client.post(f"/api/v1/disconnect/{fake_id}")
    assert response.status_code in (401, 403)
```

**Step 2: Run test to verify it fails**

Run: `cd coordinator && python -m pytest tests/test_connect.py::test_disconnect_requires_auth -v`
Expected: FAIL (currently returns 204 or 404, not 401/403)

**Step 3: Add auth dependency to disconnect endpoint**

In `coordinator/app/routers/connect.py`, find the disconnect endpoint (~line 280):

```python
# BEFORE:
@router.post("/disconnect/{dj_session_id}", status_code=204)
async def disconnect_dj(
    dj_session_id: uuid.UUID,
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> Response:
```

Add server auth dependency (check how other server-authenticated endpoints work in `routers/servers.py` or `routers/shows.py` — look for a `get_current_server` or `verify_server_key` dependency):

```python
# AFTER:
@router.post("/disconnect/{dj_session_id}", status_code=204)
async def disconnect_dj(
    dj_session_id: uuid.UUID,
    request: Request,
    session: AsyncSession = Depends(get_session),
    server: VJServer = Depends(get_current_server),  # ADD THIS — use the same dependency other server endpoints use
) -> Response:
```

Additionally, verify the session belongs to the server's show:
```python
    # After fetching dj_session, verify it belongs to this server's active show
    if dj_session.show_id != server.current_show_id:
        raise HTTPException(status_code=403, detail="Session does not belong to this server")
```

**Step 4: Run test to verify it passes**

Run: `cd coordinator && python -m pytest tests/test_connect.py::test_disconnect_requires_auth -v`
Expected: PASS

**Step 5: Run full coordinator test suite**

Run: `cd coordinator && python -m pytest -v`
Expected: All pass (no regressions)

**Step 6: Commit**

```bash
git add coordinator/app/routers/connect.py coordinator/tests/test_connect.py
git commit -m "fix(coordinator): require server auth for /disconnect endpoint"
```

---

### Task 3: Fix Plugin Path Traversal (bitmap_image + bitmap_dj_logo)

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java:2498-2549`

**Context:** Two WS message handlers (`bitmap_image` and `bitmap_dj_logo`) accept file paths from WS messages without sanitization. `bitmap_image` has zero validation. `bitmap_dj_logo` skips validation for absolute paths.

**Step 1: Fix `bitmap_image` `load_file` handler (~line 2498)**

```java
// BEFORE:
case "load_file" -> {
    String path = message.get("path").getAsString();
    BitmapFrameBuffer buf = patternMgr.getFrameBuffer(zone);
    if (buf != null) {
        imagePattern.loadFromFile(new java.io.File(path), buf.getWidth(), buf.getHeight());

// AFTER:
case "load_file" -> {
    String path = message.get("path").getAsString();
    java.io.File base = plugin.getDataFolder();
    java.io.File file = new java.io.File(base, path);
    try {
        if (!file.getCanonicalFile().toPath().startsWith(base.getCanonicalFile().toPath())) {
            plugin.getLogger().warning("Path traversal blocked: " + path);
            break;
        }
    } catch (java.io.IOException e) {
        plugin.getLogger().warning("Invalid path: " + path);
        break;
    }
    BitmapFrameBuffer buf = patternMgr.getFrameBuffer(zone);
    if (buf != null) {
        imagePattern.loadFromFile(file, buf.getWidth(), buf.getHeight());
```

**Step 2: Fix `bitmap_dj_logo` `load_file` handler (~line 2536)**

```java
// BEFORE:
case "load_file" -> {
    String path = message.get("path").getAsString();
    java.io.File file = new java.io.File(path);
    if (!file.isAbsolute()) {
        file = new java.io.File(plugin.getDataFolder(), path);
    }

// AFTER:
case "load_file" -> {
    String path = message.get("path").getAsString();
    java.io.File base = plugin.getDataFolder();
    java.io.File file = new java.io.File(base, path);  // always resolve against base
    try {
        if (!file.getCanonicalFile().toPath().startsWith(base.getCanonicalFile().toPath())) {
            plugin.getLogger().warning("Path traversal blocked (dj_logo): " + path);
            break;
        }
    } catch (java.io.IOException e) {
        plugin.getLogger().warning("Invalid path (dj_logo): " + path);
        break;
    }
```

**Step 3: Build to verify no compilation errors**

Run: `cd minecraft_plugin && mvn package -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java
git commit -m "fix(plugin): block path traversal in bitmap_image and bitmap_dj_logo handlers"
```

---

### Task 4: Add Plugin WebSocket Authentication

**Files:**
- Modify: `minecraft_plugin/src/main/resources/config.yml` (add `ws-secret` field)
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `vj_server/vj_server.py` (send auth token on connect to plugin)

**Context:** Port 8765 accepts any connection with full command access. Add a shared-secret handshake: the VJ server sends `{"type":"auth","token":"<secret>"}` as the first message, the plugin verifies it, and rejects connections that don't authenticate within 5 seconds.

**Step 1: Add `ws-secret` to plugin config.yml**

In `minecraft_plugin/src/main/resources/config.yml`, add:
```yaml
# Shared secret for WebSocket authentication. Set to empty string to disable (not recommended).
ws-secret: ""
```

**Step 2: Add auth check to VizWebSocketServer.java**

In `onOpen` (~line 83), store `authenticated = false` on the `ClientInfo`. Start a 5-second timer task.

In `onMessage` (~line 130), before processing any message, check:
```java
ClientInfo info = clients.get(conn);
if (!info.authenticated) {
    // First message must be auth
    try {
        JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
        if ("auth".equals(msg.get("type").getAsString())) {
            String token = msg.get("token").getAsString();
            String expected = plugin.getConfig().getString("ws-secret", "");
            if (expected.isEmpty() || expected.equals(token)) {
                info.authenticated = true;
                conn.send("{\"type\":\"auth_ok\"}");
                return;
            }
        }
    } catch (Exception ignored) {}
    conn.close(4001, "Authentication failed");
    return;
}
```

Add `boolean authenticated = false` field to `ClientInfo` inner class.

Add a scheduled task in `onOpen` to close unauthenticated connections after 5 seconds:
```java
plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
    ClientInfo ci = clients.get(conn);
    if (ci != null && !ci.authenticated) {
        conn.close(4002, "Authentication timeout");
    }
}, 100L); // 5 seconds = 100 ticks
```

**Step 3: Send auth token from VJ server**

In `vj_server/vj_server.py`, find the `VizClient` connection code (where it connects to the plugin on port 8765). After the WebSocket connection is established, send the auth message before any other message:
```python
await websocket.send(json.dumps({"type": "auth", "token": self.plugin_ws_secret}))
auth_response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
```

Add `--plugin-ws-secret` CLI argument to vj_server argparse, passed through to the VizClient.

**Step 4: Build plugin**

Run: `cd minecraft_plugin && mvn package -q`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add minecraft_plugin/ vj_server/vj_server.py
git commit -m "feat(plugin): add WebSocket shared-secret authentication"
```

---

### Task 5: Gate `/servers/register` Endpoint

**Files:**
- Modify: `coordinator/app/routers/servers.py:80-122`
- Test: `coordinator/tests/test_servers.py`

**Step 1: Write failing test**

```python
@pytest.mark.asyncio
async def test_register_server_requires_auth(client: AsyncClient):
    """Unauthenticated server registration should return 401."""
    response = await client.post("/api/v1/servers/register", json={
        "name": "test-server",
        "minecraft_host": "localhost",
    })
    assert response.status_code == 401
```

**Step 2: Run test, verify failure**

Run: `cd coordinator && python -m pytest tests/test_servers.py::test_register_server_requires_auth -v`
Expected: FAIL (currently returns 201)

**Step 3: Change `get_current_user_optional` to `get_current_user` (required)**

In `coordinator/app/routers/servers.py` line 88:
```python
# BEFORE:
user: User | None = Depends(get_current_user_optional),

# AFTER:
user: User = Depends(get_current_user),
```

Remove any `if user is None` fallback logic that creates anonymous registrations.

**Step 4: Run tests**

Run: `cd coordinator && python -m pytest -v`
Expected: All pass

**Step 5: Commit**

```bash
git add coordinator/app/routers/servers.py coordinator/tests/test_servers.py
git commit -m "fix(coordinator): require auth for server registration"
```

---

### Task 6: Fix OAuth Silent Account Takeover

**Files:**
- Modify: `coordinator/app/services/auth_service.py:214-231` (Discord) and `304-319` (Google)

**Context:** When OAuth email matches an existing email-only account, the code silently links the OAuth provider. This enables account takeover.

**Step 1: Write failing test**

```python
@pytest.mark.asyncio
async def test_oauth_does_not_silently_link_existing_email(session: AsyncSession):
    """OAuth login with an email matching an existing account should not auto-link."""
    # Create a user with email only (password-based)
    existing = User(email="victim@example.com", password_hash="$2b$12$...", email_verified=True)
    session.add(existing)
    await session.commit()

    # Attempt Discord OAuth with the same email
    result = await auth_service.discord_login(
        session, discord_id="attacker123", discord_email="victim@example.com", ...
    )
    # Should NOT have linked Discord to the existing account
    await session.refresh(existing)
    assert existing.discord_id is None  # or assert result indicates "link pending"
```

**Step 2: Run test, verify failure**

Expected: FAIL — currently the code links the Discord ID.

**Step 3: Change auto-link to create a separate account or require confirmation**

In `auth_service.py` around line 218, instead of silently linking:

```python
# BEFORE:
if existing_by_email is not None:
    existing_by_email.discord_id = discord_id
    existing_by_email.discord_username = discord_username
    existing_by_email.email_verified = True

# AFTER:
if existing_by_email is not None:
    if existing_by_email.discord_id is not None:
        # Already linked to a different Discord — reject
        raise ValueError("Email already associated with another account")
    # Don't auto-link. Create a new account with the Discord ID instead.
    # The user can manually link accounts from their settings.
    pass  # Fall through to create new account below
```

Apply the same pattern to the Google OAuth path (~line 304-319).

**Step 4: Run tests**

Run: `cd coordinator && python -m pytest -v`
Expected: All pass

**Step 5: Commit**

```bash
git add coordinator/app/services/auth_service.py coordinator/tests/
git commit -m "fix(coordinator): prevent silent OAuth account takeover via email linking"
```

---

### Task 7: Auth-Gate Coordinator `/metrics`

**Files:**
- Modify: `coordinator/app/routers/metrics.py:27-31`
- Modify: `coordinator/app/config.py` (add `metrics_token` setting)

**Step 1: Add `MCAV_METRICS_TOKEN` to config**

In `coordinator/app/config.py`, add to the Settings class:
```python
metrics_token: str | None = None  # Optional bearer token for /metrics
```

**Step 2: Add auth dependency to metrics endpoint**

```python
# In metrics.py:
async def _verify_metrics_token(
    authorization: str | None = Header(None),
    settings: Settings = Depends(get_settings),
) -> None:
    if settings.metrics_token and settings.mcav_env != "development":
        if not authorization or authorization != f"Bearer {settings.metrics_token}":
            raise HTTPException(status_code=401, detail="Invalid metrics token")

@router.get("/metrics", dependencies=[Depends(_verify_metrics_token)], ...)
```

**Step 3: Run tests**

Run: `cd coordinator && python -m pytest -v`
Expected: All pass (dev mode skips token check)

**Step 4: Commit**

```bash
git add coordinator/app/routers/metrics.py coordinator/app/config.py
git commit -m "fix(coordinator): add optional bearer token auth for /metrics endpoint"
```

---

### Task 8: Proxy-Aware Rate Limiting

**Files:**
- Modify: `coordinator/app/middleware/rate_limit.py:43`
- Modify: `coordinator/app/config.py` (add `trusted_proxies` setting)

**Step 1: Add trusted proxy config**

In `coordinator/app/config.py`:
```python
trusted_proxies: list[str] = ["127.0.0.1", "::1"]  # IPs of reverse proxies
```

**Step 2: Extract real client IP**

In `rate_limit.py`, replace line 43:
```python
# BEFORE:
client_ip = request.client.host if request.client else "unknown"

# AFTER:
def _get_client_ip(request: Request, trusted_proxies: list[str]) -> str:
    client_host = request.client.host if request.client else "unknown"
    if client_host in trusted_proxies:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            # Take the rightmost IP not in trusted_proxies
            ips = [ip.strip() for ip in forwarded.split(",")]
            for ip in reversed(ips):
                if ip not in trusted_proxies:
                    return ip
        real_ip = request.headers.get("x-real-ip")
        if real_ip:
            return real_ip
    return client_host
```

**Step 3: Run tests**

Run: `cd coordinator && python -m pytest -v`
Expected: All pass

**Step 4: Commit**

```bash
git add coordinator/app/middleware/rate_limit.py coordinator/app/config.py
git commit -m "fix(coordinator): extract real client IP from X-Forwarded-For behind proxies"
```

---

### Task 9: Docker Postgres Hardening

**Files:**
- Modify: `docker-compose.yml:64-73`

**Step 1: Remove host port mapping and default password**

```yaml
# BEFORE:
postgres:
  image: postgres:16-alpine
  environment:
    - POSTGRES_USER=${POSTGRES_USER:-postgres}
    - POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-postgres}
  ports:
    - "5432:5432"

# AFTER:
postgres:
  image: postgres:16-alpine
  environment:
    - POSTGRES_USER=${POSTGRES_USER:?POSTGRES_USER must be set}
    - POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}
  # No host port mapping — access via internal Docker network only
  # Uncomment for local development: ports: ["127.0.0.1:5432:5432"]
```

**Step 2: Verify compose file parses**

Run: `docker compose -f docker-compose.yml config > /dev/null 2>&1 || echo "needs env vars (expected)"`

**Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "fix(docker): remove default postgres password and host port exposure"
```

---

## Phase 2: Medium Severity Hardening

### Task 10: Reduce Browser WS Max Size + Add Connection Limits

**Files:**
- Modify: `vj_server/vj_server.py` (~lines 6213, 6222, 1682, 2792)

**Step 1: Reduce browser WS max_size**

```python
# BEFORE (line 6222):
max_size=10 * 1024 * 1024,

# AFTER:
max_size=65_536,  # 64KB, same as DJ port
```

**Step 2: Add connection limits**

At `_broadcast_clients.add(websocket)` (~line 2792), add:
```python
MAX_BROWSER_CLIENTS = 50
if len(self._broadcast_clients) >= MAX_BROWSER_CLIENTS:
    await websocket.close(4003, "Connection limit reached")
    return
self._broadcast_clients.add(websocket)
```

At DJ registration (~line 1682), add:
```python
MAX_DJ_CONNECTIONS = 10
if len(self._djs) >= MAX_DJ_CONNECTIONS:
    await websocket.close(4003, "DJ connection limit reached")
    return
```

**Step 3: Commit**

```bash
git add vj_server/vj_server.py
git commit -m "fix(vj): reduce browser WS max_size to 64KB, add connection limits"
```

---

### Task 11: Lua Sandbox Timeout + Hardening

**Files:**
- Modify: `vj_server/patterns.py` (~lines 166-171, 462-497)

**Step 1: Harden sandbox globals**

```python
# BEFORE (line 168):
self._lua.execute("""
    os = nil; io = nil; debug = nil; package = nil
    require = nil; load = nil; loadfile = nil; dofile = nil
    collectgarbage = nil; rawget = nil; rawset = nil
""")

# AFTER:
self._lua.execute("""
    os = nil; io = nil; debug = nil; package = nil
    require = nil; load = nil; loadfile = nil; dofile = nil
    collectgarbage = nil; rawget = nil; rawset = nil
    pcall = nil; xpcall = nil; rawequal = nil; rawlen = nil
    string.dump = nil; string.rep = nil
""")
```

**Step 2: Add timeout to calculate() call**

Wrap the `calculate()` call (~line 497) in a thread with timeout:

```python
import concurrent.futures

_lua_executor = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="lua")

def calculate_entities(self, audio: AudioState) -> list:
    if self._calculate is None:
        return []
    # ... build audio_table, config_table, dt ...
    future = _lua_executor.submit(self._calculate, audio_table, config_table, dt)
    try:
        result = future.result(timeout=0.1)  # 100ms max
    except concurrent.futures.TimeoutError:
        logger.warning(f"Pattern '{self.name}' timed out, skipping frame")
        return []
```

Note: lupa `LuaRuntime` is not thread-safe. The executor should use `max_workers=1` and the pattern should only be called from this one executor. Review existing threading model before implementing.

**Step 3: Commit**

```bash
git add vj_server/patterns.py
git commit -m "fix(vj): harden Lua sandbox and add 100ms timeout for pattern execution"
```

---

### Task 12: VJ Metrics Bind Localhost

**Files:**
- Modify: `vj_server/metrics.py` (~line 157)

**Step 1: Change default bind**

```python
# BEFORE:
host: str = "0.0.0.0",

# AFTER:
host: str = "127.0.0.1",
```

**Step 2: Commit**

```bash
git add vj_server/metrics.py
git commit -m "fix(vj): bind metrics server to localhost by default"
```

---

### Task 13: Banner Pixel Path Sanitization

**Files:**
- Modify: `vj_server/vj_server.py` (~line 2452)

**Step 1: Sanitize dj_id before path construction**

```python
# BEFORE:
pixel_path = Path(f"configs/banners/{dj_id}_pixels.bin")

# AFTER:
import re
safe_id = re.sub(r"[^a-zA-Z0-9_\-]", "", dj_id)
pixel_path = Path(f"configs/banners/{safe_id}_pixels.bin")
banners_dir = Path("configs/banners").resolve()
if not pixel_path.resolve().is_relative_to(banners_dir):
    logger.warning(f"Blocked path traversal in banner for dj_id: {dj_id}")
    continue
```

**Step 2: Commit**

```bash
git add vj_server/vj_server.py
git commit -m "fix(vj): sanitize dj_id in banner pixel path construction"
```

---

### Task 14: OAuth State Hardening

**Files:**
- Modify: `coordinator/app/routers/auth.py` (~lines 67-80, 290-300)

**Step 1: Add expiry and redirect_uri to state JWT**

```python
# BEFORE (line 67-72):
def _create_oauth_state(jwt_secret, *, desktop=False, provider="discord"):
    payload = {"nonce": secrets.token_urlsafe(16), ...}
    return pyjwt.encode(payload, jwt_secret, algorithm="HS256")

# AFTER:
def _create_oauth_state(jwt_secret, *, desktop=False, provider="discord", redirect_uri: str = ""):
    payload = {
        "nonce": secrets.token_urlsafe(16),
        "exp": datetime.utcnow() + timedelta(minutes=10),
        "redirect_uri": redirect_uri,
        ...
    }
    return pyjwt.encode(payload, jwt_secret, algorithm="HS256")
```

In the callback, verify `redirect_uri` matches:
```python
state_payload = _validate_oauth_state(state, settings.user_jwt_secret)
if state_payload.get("redirect_uri") != str(request.url_for("discord_callback")):
    raise HTTPException(status_code=400, detail="Invalid OAuth state redirect")
```

**Step 2: Run tests**

Run: `cd coordinator && python -m pytest -v`
Expected: All pass

**Step 3: Commit**

```bash
git add coordinator/app/routers/auth.py
git commit -m "fix(coordinator): add expiry and redirect_uri binding to OAuth state JWT"
```

---

### Task 15: Constant-Time Webhook Secret + OAuth Delete Confirmation

**Files:**
- Modify: `coordinator/app/routers/discord_webhooks.py:37`
- Modify: `coordinator/app/routers/auth.py:883-888`

**Step 1: Fix webhook secret comparison**

```python
# BEFORE (line 37):
if not x_webhook_secret or x_webhook_secret != settings.discord_webhook_secret:

# AFTER:
import hmac
if not x_webhook_secret or not hmac.compare_digest(x_webhook_secret, settings.discord_webhook_secret):
```

**Step 2: Fix OAuth account deletion**

```python
# BEFORE (line 886-888):
else:
    # OAuth-only users: password field is ignored
    pass

# AFTER:
else:
    # OAuth-only users must re-authenticate via OAuth
    # For now, require a confirmation field in the request body
    if not body.confirm_delete:
        raise HTTPException(status_code=400, detail="OAuth users must confirm deletion with confirm_delete=true")
```

Add `confirm_delete: bool = False` to the `DeleteAccountRequest` schema.

**Step 3: Run tests**

Run: `cd coordinator && python -m pytest -v`
Expected: All pass

**Step 4: Commit**

```bash
git add coordinator/app/routers/discord_webhooks.py coordinator/app/routers/auth.py
git commit -m "fix(coordinator): constant-time webhook secret, require confirmation for OAuth account deletion"
```

---

### Task 16: Coordinator Dockerfile + Docker Compose Hardening

**Files:**
- Modify: `coordinator/Dockerfile.local`
- Modify: `docker-compose.demo.yml:37`
- Modify: `docker-compose.yml` (JWT secret)

**Step 1: Add USER to Dockerfile**

Add before CMD in `coordinator/Dockerfile.local`:
```dockerfile
RUN useradd -m -u 1000 mcav
USER mcav
```

Also add `.dockerignore` if not present:
```
.env
test.db
__pycache__
.venv
```

**Step 2: Add JWT secret requirement to docker-compose.yml**

```yaml
# BEFORE:
# MCAV_USER_JWT_SECRET not set, defaults to insecure value

# AFTER:
- MCAV_USER_JWT_SECRET=${MCAV_USER_JWT_SECRET:?JWT secret must be set}
```

**Step 3: Guard demo --no-auth mode**

Note: Investigation revealed `--no-auth` is not an actual flag — auth is OFF by default (`require_auth=False`). The real fix is to make auth ON by default:

In `vj_server/vj_server.py` argparse (~line 6420):
```python
# BEFORE:
parser.add_argument("--require-auth", action="store_true")

# AFTER:
parser.add_argument("--no-auth", action="store_true", help="Disable DJ authentication (demo/dev only)")
```

And flip the default (~line 6455):
```python
# BEFORE:
require_auth=args.require_auth,  # default False

# AFTER:
require_auth=not args.no_auth,  # default True (auth required)
```

Update `docker-compose.demo.yml` to explicitly use `--no-auth` (it may already have this).

**Step 4: Build and verify**

Run: `docker compose -f docker-compose.yml config > /dev/null 2>&1 || echo "needs env vars (expected)"`
Run: `cd minecraft_plugin && mvn package -q` (if plugin touched)

**Step 5: Commit**

```bash
git add coordinator/Dockerfile.local docker-compose.yml docker-compose.demo.yml vj_server/vj_server.py
git commit -m "fix(docker): non-root coordinator, required JWT secret, auth-on-by-default for VJ server"
```

---

## Phase 3: Low Severity (Brief Notes)

These are lower priority. Each is a small standalone fix:

- **L1**: Add `InputSanitizer.isValidZoneName(args[1])` check in `AudioVizCommand.java:104` before `zoneExists()`
- **L2**: Clamp `count` in `MessageHandler.java:214` before passing to pool: `Math.max(0, Math.min(count, maxEntities))`
- **L3**: Add `MAX_PARTICLES_PER_TICK = 2000` cap in `MessageHandler.java` particle processing
- **L4**: In `VizWebSocketServer.java:141`, check pong only in first 64 chars: `message.substring(0, Math.min(64, message.length())).contains("pong")`
- **L5+L6**: Already addressed in Task 11 (Lua sandbox hardening)
- **L7**: Upgrade refresh token hashing to HMAC-SHA256 with JWT secret as key in `auth_service.py:32`
- **L8**: Add CSP header to `coordinator/app/middleware/security.py`: `"Content-Security-Policy": "default-src 'self'; script-src 'none'"`
- **L9**: Change `MCAV_ENV` default from `"development"` to `"production"` in `coordinator/app/config.py:61`
- **L10**: Add security headers to `demo/nginx-preview.conf`: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`

---

## Execution Order

Tasks 1-9 (Phase 1) are independent and can be parallelized. Recommended grouping:

**Parallel batch A (Coordinator):** Tasks 2, 5, 6, 7, 8
**Parallel batch B (Plugin):** Tasks 3, 4
**Parallel batch C (Infra):** Tasks 1, 9

Phase 2 tasks depend on Phase 1 being complete (especially Task 16 depends on understanding Task 4's VJ server changes).
