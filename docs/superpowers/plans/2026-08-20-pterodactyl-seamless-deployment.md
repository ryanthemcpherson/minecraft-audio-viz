# Seamless Pterodactyl Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify one SFTP-uploadable archive that securely bootstraps the MCAV Paper plugin and authenticated HTTPS/WSS VJ service inside the official Pterodactyl Java 25 container without system Python or node access.

**Architecture:** Extend the VJ server with exact operator identity authentication, TLS-capable HTTP/browser listeners, and an explicit asset root. Add a testable Python bootstrap module for credential, certificate, plugin, and config installation; a small Bash wrapper keeps Paper as the foreground process. A reproducible release builder assembles portable AMD64 and ARM64 Python runtimes plus application assets into one archive.

**Tech Stack:** Python 3.11+, asyncio/websockets, bcrypt, OpenSSL CLI, Java 25/Paper, Bash, vanilla browser JavaScript, node:test, pytest, Maven, python-build-standalone.

**Spec:** `docs/superpowers/specs/2026-08-20-pterodactyl-seamless-deployment-design.md`

## Global Constraints

- Target `ghcr.io/pterodactyl/yolks:java_25` on Linux AMD64 and ARM64.
- Require only SFTP, extraction, one startup-command prefix, and one restart.
- Preserve the exact existing Paper command after `--`; Paper remains the foreground process.
- Paper must start when bootstrap, TLS, or VJ startup fails.
- Never ship or log live credentials, the Minecraft shared secret, or the TLS private key.
- Require exact administrator username/password authentication before sending browser state or accepting control commands.
- Use HTTPS on `8080`, WSS on `8766`, DJ WebSocket on `9000`, loopback renderer WebSocket on `8765`, and loopback metrics on `9001`.
- Never enable `--no-auth` in the deployment wrapper.
- Preserve unrelated dirty-worktree files and stage only task-owned paths.
- Use WSL-native Python virtual environments for Python development and verification.

---

### Task 1: Exact browser identity authentication

**Files:**
- Modify: `vj_server/relay.py`
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/models.py`
- Test: `vj_server/tests/test_relay.py`

**Interfaces:**
- Consumes: `DJAuthConfig.verify_vj(vj_id: str, password: str) -> bool` from `vj_server/models.py`.
- Produces: `VJServer.check_browser_auth_rate_limit(remote_ip: str, now: float | None = None) -> bool` and browser auth message `{"type":"vj_auth","username":str,"password":str}`.

- [ ] **Step 1: Write failing relay tests for exact identity and pre-auth isolation**

Add focused async tests that create two VJ operators with different bcrypt hashes, then prove that a matching username/password succeeds, a password paired with the wrong username fails generically, a non-auth first message closes with `4003`, and no state message is sent before success.

```python
@pytest.mark.asyncio
async def test_browser_auth_requires_matching_username_and_password(browser_server):
    websocket = FakeWebSocket(
        {"type": "vj_auth", "username": "lighting", "password": "video-secret"}
    )
    await browser_server._handle_browser_client(websocket)
    assert websocket.close_code == 4004
    assert websocket.decoded_messages() == [
        {"type": "auth_error", "error": "Invalid username or password"}
    ]
```

- [ ] **Step 2: Run the focused tests and confirm RED**

Run in WSL from `vj_server`:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -e '.[dev]'
.venv/bin/pytest tests/test_relay.py -k 'browser_auth' -q
```

Expected: failures because the relay ignores `username`, accepts any operator's password, and lacks browser-auth throttling.

- [ ] **Step 3: Implement exact named-operator verification and rate limiting**

Parse and type-check both auth fields, call `self.auth_config.verify_vj(username, password)`, return one generic error, and maintain a bounded per-IP deque with five failed attempts per rolling 60 seconds. Clear successful-attempt state for that IP. Authentication remains the first message and the existing five-second timeout remains mandatory.

```python
username = auth_data.get("username")
password = auth_data.get("password")
if not isinstance(username, str) or not isinstance(password, str):
    await self._reject_browser_auth(websocket)
    return
if self._browser_auth_is_rate_limited(remote_ip):
    await self._reject_browser_auth(websocket)
    return
if not self.auth_config.verify_vj(username, password):
    self._record_browser_auth_failure(remote_ip)
    await self._reject_browser_auth(websocket)
    return
```

- [ ] **Step 4: Run focused and full VJ tests**

```bash
.venv/bin/pytest tests/test_relay.py -k 'browser_auth' -q
.venv/bin/pytest -q
```

Expected: all tests pass with no credential values in captured logs.

- [ ] **Step 5: Commit the server authentication change**

```bash
git add vj_server/relay.py vj_server/vj_server.py vj_server/models.py vj_server/tests/test_relay.py
git commit -m "fix(vj): require exact browser operator identity"
```

---

### Task 2: HTTPS/WSS server and explicit deployment paths

**Files:**
- Modify: `vj_server/models.py`
- Modify: `vj_server/vj_server.py`
- Modify: `vj_server/cli.py`
- Test: `vj_server/tests/test_static_http.py`
- Test: `vj_server/tests/test_config.py`

**Interfaces:**
- Produces: `build_server_ssl_context(cert_path: Path, key_path: Path) -> ssl.SSLContext`.
- Produces: `run_http_server(port: int, directory: str, host: str, ssl_context: ssl.SSLContext | None = None) -> None`.
- Produces CLI options `--http-port`, `--project-root`, `--tls-cert`, and `--tls-key` with matching environment variables `HTTP_PORT`, `MCAV_PROJECT_ROOT`, `TLS_CERT`, and `TLS_KEY`.

- [ ] **Step 1: Write failing TLS and CLI propagation tests**

Assert that certificate and key must be provided together, invalid paths fail before listeners start, project root is explicit, HTTP port reaches `VJServer`, the static listener wraps its socket with the supplied context, and browser `websockets.serve` receives the same context.

```python
def test_tls_cert_and_key_are_atomic_cli_pair(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["audioviz-vj", "--tls-cert", "cert.pem"])
    assert cli.vj_server() == 2
```

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
.venv/bin/pytest tests/test_static_http.py tests/test_config.py -q
```

Expected: failures for missing CLI arguments, SSL context, and explicit asset-root propagation.

- [ ] **Step 3: Implement TLS listeners and explicit paths**

Construct one `ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)`, set minimum TLS 1.2, load the certificate chain, wrap `ThreadingHTTPServer.socket`, and pass the same context only to the browser WebSocket listener. Keep renderer and metrics listeners loopback/plaintext and retain the DJ listener's existing transport for native-client compatibility.

```python
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.minimum_version = ssl.TLSVersion.TLSv1_2
context.load_cert_chain(certfile=cert_path, keyfile=key_path)
```

- [ ] **Step 4: Run focused and full VJ tests**

```bash
.venv/bin/pytest tests/test_static_http.py tests/test_config.py -q
.venv/bin/pytest -q
```

Expected: all tests pass; legacy non-deployment callers still work without TLS when neither TLS option is supplied.

- [ ] **Step 5: Commit TLS/path support**

```bash
git add vj_server/models.py vj_server/vj_server.py vj_server/cli.py vj_server/tests/test_static_http.py vj_server/tests/test_config.py
git commit -m "feat(vj): support secured deployment listeners"
```

---

### Task 3: Admin and preview login gates

**Files:**
- Modify: `admin_panel/index.html`
- Modify: `admin_panel/js/admin-app.js`
- Modify: `admin_panel/js/services/WebSocketService.js`
- Create: `admin_panel/js/services/WebSocketService.test.mjs`
- Modify: `preview_tool/frontend/index.html`
- Modify: `preview_tool/frontend/js/app.js`
- Create: `preview_tool/frontend/js/auth.test.mjs`

**Interfaces:**
- Consumes: browser auth message from Task 1.
- Produces: in-memory credential object `{ username: string, password: string }`, login overlay state, and scheme selection `https:` to `wss:`.

- [ ] **Step 1: Write failing Node tests for credential handling and WSS selection**

Export small pure helpers and assert that HTTPS chooses WSS, HTTP development chooses WS, auth messages include both fields, URL parameters are ignored, browser storage is never read or written for credentials, and logout clears the in-memory values.

```javascript
test('buildAuthMessage binds username to password', () => {
  assert.deepEqual(buildAuthMessage('mcav-admin', 'secret'), {
    type: 'vj_auth', username: 'mcav-admin', password: 'secret'
  });
});
```

- [ ] **Step 2: Run Node tests and confirm RED**

```bash
node --test admin_panel/js/services/WebSocketService.test.mjs preview_tool/frontend/js/auth.test.mjs
```

Expected: missing helpers and current password-only/localStorage behavior fail.

- [ ] **Step 3: Implement accessible login gates**

Add labeled username/password inputs, submit button, generic error region with `aria-live="polite"`, and a certificate-warning note. Do not instantiate live application state until form submission. Pass credentials into the clients, retain them only on the active object, and return to the gate on `auth_error` or logout. Both admin and preview use `wss://` when loaded from HTTPS.

```javascript
export function websocketScheme(pageProtocol) {
  return pageProtocol === 'https:' ? 'wss' : 'ws';
}

export function buildAuthMessage(username, password) {
  return { type: 'vj_auth', username, password };
}
```

- [ ] **Step 4: Run Node tests and Vite build**

```bash
node --test admin_panel/js/services/WebSocketService.test.mjs preview_tool/frontend/js/auth.test.mjs
npm run build
```

Expected: Node tests and production build pass; generated output contains no `vj_password` query handling or `mcav_vj_password` storage key.

- [ ] **Step 5: Commit browser security**

```bash
git add admin_panel/index.html admin_panel/js/admin-app.js admin_panel/js/services/WebSocketService.js admin_panel/js/services/WebSocketService.test.mjs preview_tool/frontend/index.html preview_tool/frontend/js/app.js preview_tool/frontend/js/auth.test.mjs
git commit -m "feat(admin): add secure operator login gate"
```

---

### Task 4: Idempotent deployment bootstrap

**Files:**
- Create: `vj_server/pterodactyl.py`
- Modify: `vj_server/cli.py`
- Create: `vj_server/tests/test_pterodactyl.py`
- Create: `deploy/pterodactyl/plugin-config.default.yml`

**Interfaces:**
- Produces: `BootstrapPaths`, `BootstrapResult`, and `bootstrap_pterodactyl(paths: BootstrapPaths, release_version: str) -> BootstrapResult`.
- Produces CLI command `audioviz-vj --bootstrap-pterodactyl --project-root /home/container/mcav-vj --plugins-dir /home/container/plugins`.
- Consumes: release JAR at `mcav-vj/release/AudioViz.jar` and OpenSSL executable included by the Java 25 yolk.

- [ ] **Step 1: Write failing bootstrap tests**

Use temporary directories and synthetic plugin JAR ZIPs. Cover secure first-run state, bcrypt verification, ECDSA certificate generation through an injected runner, exact JAR plugin-name detection, same-digest no-op, differing-version backup, atomic replacement, YAML field preservation, malformed-state refusal, and byte-identical second-run state.

```python
def test_second_bootstrap_preserves_identity(tmp_path, bootstrap_paths):
    first = bootstrap_pterodactyl(bootstrap_paths, "26.1")
    before = snapshot_identity_files(bootstrap_paths)
    second = bootstrap_pterodactyl(bootstrap_paths, "26.1")
    assert snapshot_identity_files(bootstrap_paths) == before
    assert second.credentials_created is False
```

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
.venv/bin/pytest tests/test_pterodactyl.py -q
```

Expected: import failure because the bootstrap module does not exist.

- [ ] **Step 3: Implement transactional bootstrap**

Use `secrets.token_urlsafe`, bcrypt, `zipfile`, `hashlib`, `tempfile`, `os.replace`, and restrictive modes. Match `name: AudioViz` from JAR descriptors, back up recoverably, and patch only the root `ws-secret` plus `websocket.address` and `websocket.port` while preserving every other line.

Generate the certificate through an argument-array subprocess call to `openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -sha256 -days 397 -nodes`, validate it with `ssl.SSLContext.load_cert_chain`, and record `openssl x509 -fingerprint -sha256 -noout` output without recording the private key.

- [ ] **Step 4: Run bootstrap and full VJ tests**

```bash
.venv/bin/pytest tests/test_pterodactyl.py -q
.venv/bin/pytest -q
```

Expected: all tests pass, second-run identity files are byte-identical, and failure fixtures preserve prior files.

- [ ] **Step 5: Commit bootstrap**

```bash
git add vj_server/pterodactyl.py vj_server/cli.py vj_server/tests/test_pterodactyl.py deploy/pterodactyl/plugin-config.default.yml
git commit -m "feat(deploy): bootstrap pterodactyl securely"
```

---

### Task 5: Paper-safe startup wrapper

**Files:**
- Create: `deploy/pterodactyl/start-mcav.sh`
- Create: `deploy/pterodactyl/test-start-mcav.sh`
- Create: `deploy/pterodactyl/mcav.env.example`

**Interfaces:**
- Consumes: `audioviz-vj` portable executable, bootstrap CLI from Task 4, `state/runtime.env`, TLS files, and Paper command arguments following `--`.
- Produces: one wrapper command that always `exec`s the exact Paper argument vector unless that vector is absent.

- [ ] **Step 1: Write failing shell integration tests**

Use temporary fixtures with fake VJ and Paper executables. Assert exact argument preservation, architecture selection for `x86_64` and `aarch64`, early rejection without a Paper command, bootstrap-failure Paper fallback, VJ-bind-failure Paper fallback, no `--no-auth`, and HTTPS/WSS arguments.

```bash
run_wrapper -- java -Xms128M -jar server.jar nogui
assert_file_contains "$PAPER_CAPTURE" $'java\n-Xms128M\n-jar\nserver.jar\nnogui'
```

- [ ] **Step 2: Run the shell tests and confirm RED**

```bash
bash deploy/pterodactyl/test-start-mcav.sh
```

Expected: failure because `start-mcav.sh` does not exist.

- [ ] **Step 3: Implement the wrapper**

Resolve its own directory, require `--`, select `bin/linux-amd64/audioviz-vj` or `bin/linux-arm64/audioviz-vj`, run bootstrap, start VJ in the background with authenticated HTTPS/WSS arguments, wait through initial bind, print endpoints, and execute Paper with `exec "$@"`. VJ/bootstrap failures emit phase-specific errors but never discard or reconstruct Paper arguments.

- [ ] **Step 4: Run shell integration tests**

```bash
bash deploy/pterodactyl/test-start-mcav.sh
```

Expected: all wrapper scenarios pass.

- [ ] **Step 5: Commit wrapper**

```bash
git add deploy/pterodactyl/start-mcav.sh deploy/pterodactyl/test-start-mcav.sh deploy/pterodactyl/mcav.env.example
git commit -m "feat(deploy): launch VJ alongside Paper safely"
```

---

### Task 6: Portable runtimes and release assembly

**Files:**
- Create: `deploy/pterodactyl/runtime-lock.json`
- Create: `deploy/pterodactyl/build-runtime.sh`
- Create: `deploy/pterodactyl/build-release.ps1`
- Create: `deploy/pterodactyl/verify-release.ps1`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: pinned python-build-standalone AMD64/ARM64 URLs and SHA-256 values, `vj_server/pyproject.toml`, current runtime assets, and the Maven-built plugin JAR.
- Produces: `dist/mcav-pterodactyl-26.1.zip` and `dist/mcav-pterodactyl-26.1.sha256`.

- [ ] **Step 1: Create a failing release verifier**

The verifier rejects missing roots, development files, live `dj_auth.json`, missing executable modes, unexpected architectures, absent plugin/config assets, duplicate entries, and any manifest digest mismatch.

```powershell
& .\deploy\pterodactyl\verify-release.ps1 -Archive .\dist\mcav-pterodactyl-26.1.zip
if ($LASTEXITCODE -ne 0) { throw 'Release verification failed' }
```

- [ ] **Step 2: Run the verifier and confirm RED**

Expected: failure because the final archive and portable runtimes do not exist.

- [ ] **Step 3: Implement pinned multi-architecture runtime assembly**

Create `runtime-lock.json` with these exact immutable inputs:

```json
{
  "python": "3.12.14",
  "release": "20260814",
  "runtimes": {
    "linux-amd64": {
      "url": "https://github.com/astral-sh/python-build-standalone/releases/download/20260814/cpython-3.12.14%2B20260814-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz",
      "sha256": "5acfa3e9ba26b51ae161c83aff278da915b590d22373a424b2ba55b8afe91fcc"
    },
    "linux-arm64": {
      "url": "https://github.com/astral-sh/python-build-standalone/releases/download/20260814/cpython-3.12.14%2B20260814-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz",
      "sha256": "2d8e17dfd732102cfeb18e0e1fa6769b24caa034e159981129590fe409c7157a"
    }
  },
  "dependencies": [
    "websockets==17.0.1",
    "numpy==2.5.2",
    "python-dotenv==1.2.3",
    "lupa==2.8",
    "better-profanity==0.7.0",
    "bcrypt==5.0.0",
    "msgspec==0.21.1"
  ]
}
```

Download and verify the two standalone runtimes, install the locked dependencies into each relocatable tree with `pip --platform` and binary wheels only, add an `audioviz-vj` launcher, and smoke-test the native architecture. Cross-architecture archive validation must not claim execution when QEMU/native execution is unavailable.

- [ ] **Step 4: Assemble the one-root release archive**

Build the plugin with Maven, copy only runtime VJ Python files and web/pattern/config assets, include both runtimes and wrapper, normalize archive paths to `/`, generate component and archive SHA-256 values, and refuse to overwrite an existing release unless its version-specific staging directory is empty.

- [ ] **Step 5: Verify the archive and Java 25 container behavior**

```powershell
.\deploy\pterodactyl\build-release.ps1 -Version 26.1
.\deploy\pterodactyl\verify-release.ps1 -Archive .\dist\mcav-pterodactyl-26.1.zip
```

When Docker is available, additionally run the extracted archive in `ghcr.io/pterodactyl/yolks:java_25` with a Paper sentinel command and confirm no system Python is used. If Docker is unavailable, record that exact environment limitation without presenting container execution as passed.

- [ ] **Step 6: Commit reproducible release tooling**

```bash
git add deploy/pterodactyl/runtime-lock.json deploy/pterodactyl/build-runtime.sh deploy/pterodactyl/build-release.ps1 deploy/pterodactyl/verify-release.ps1 .gitignore
git commit -m "build(deploy): assemble portable pterodactyl release"
```

---

### Task 7: Full verification and handoff

**Files:**
- Create: `docs/deployment/PTERODACTYL.md`
- Update generated artifact: `dist/mcav-pterodactyl-26.1.zip`
- Update generated artifact: `dist/mcav-pterodactyl-26.1.sha256`

**Interfaces:**
- Produces the administrator handoff instructions, verified archive, checksum, generated first-login path, and exact startup prefix.

- [ ] **Step 1: Write administrator instructions**

Document the four operator actions, allocation list, exact SFTP layout, one-line startup prefix, first-login path, self-signed certificate warning/fingerprint check, trusted-certificate replacement, rollback locations, and failure messages that still allow Paper to start.

- [ ] **Step 2: Run all verification suites fresh**

```bash
cd vj_server && .venv/bin/pytest -q
cd .. && node --test admin_panel/js/services/WebSocketService.test.mjs preview_tool/frontend/js/auth.test.mjs
npm run build
cd minecraft_plugin && mvn test package
cd .. && bash deploy/pterodactyl/test-start-mcav.sh
```

Then run the PowerShell release verifier and available Java 25 container test from Task 6.

- [ ] **Step 3: Inspect scope and artifact manifest**

```bash
git status --short
git diff --check
```

Confirm no unrelated dirty file was staged or modified by deployment work and no secrets exist in the archive.

- [ ] **Step 4: Commit documentation only**

```bash
git add docs/deployment/PTERODACTYL.md
git commit -m "docs: add pterodactyl upload handoff"
```

- [ ] **Step 5: Deliver the artifact**

Provide clickable absolute paths for the ZIP, checksum file, administrator guide, and plugin JAR; report exact test counts, container coverage, archive size, and SHA-256. State any verification limitation explicitly.
