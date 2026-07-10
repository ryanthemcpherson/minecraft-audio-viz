# MCAV v1 Phase 0 Security Audit

## Executive summary

The current v1 tree must not be treated as production-ready or publicly distributable. The audit confirmed one critical remote-code-execution path, four high-severity authentication or filesystem boundary failures, one medium-severity observability exposure, and unsafe distribution paths that can publish or advertise artifacts which have not passed a working release gate.

The immediate remediation is the reviewed Phase 0 containment program in `docs/superpowers/plans/2026-07-10-phase-0-containment.md`. That program intentionally does not claim that v1 becomes the final product; it establishes a safe baseline before the approved v2 Show Engine work begins.

## Critical findings

### MCAV-SEC-001: Remote Lua source reaches an unrestricted native runtime

- Rule IDs: NEXT-INJECT-003; general untrusted-code execution boundary
- Severity: Critical
- Locations:
  - `vj_server/dj_manager.py:1083`
  - `protocol/schemas/messages/stream-route.schema.json:58`
  - `dj_client/src-tauri/src/protocol/client.rs:696`
  - `dj_client/src-tauri/src/lib.rs:393`
  - `dj_client/src-tauri/src/patterns.rs:50`
  - `dj_client/src-tauri/Cargo.toml:30`
- Evidence: The VJ server attaches the complete pattern-source map to `stream_route`. The Rust client stores that map, passes every source string into `PatternEngine`, and constructs a full `mlua::Lua::new()` runtime before executing the source.
- Impact: A compromised or malicious VJ server can execute arbitrary Lua capabilities in the DJ client process. Because the runtime is created with the full standard library rather than an allowlisted sandbox, this is a native-client remote-code-execution boundary.
- Fix: Stop emitting source-bearing fields, force the containment route to relay mode, ignore legacy source fields, remove the compiled Rust Lua module and dependency, and retain Lua only inside the local authoritative VJ process until the v2 typed ShowIR runtime replaces it.
- Mitigation: Do not connect the current DJ client to an untrusted VJ server. This is not an adequate release mitigation; the execution path must be removed.
- False positive notes: None. The delivery, storage, and execution chain is directly present in the current tree.

## High findings

### MCAV-SEC-002: Static-file containment is bypassable with Windows separators

- Rule IDs: FASTAPI-FILES-001; NEXT-PATH-001
- Severity: High
- Locations:
  - `vj_server/models.py:595`
  - `vj_server/models.py:598`
  - `vj_server/models.py:628`
  - `vj_server/models.py:631`
  - `vj_server/models.py:670`
- Evidence: Both handlers normalize with `posixpath` and split only on `/`. A percent-decoded backslash remains inside a path segment and is later passed to `os.path.join`, where Windows interprets it as a separator. The server also binds to `("", port)`, exposing the handler on every interface.
- Impact: A remote caller on a Windows VJ host can request files outside the intended admin/preview roots, subject to process permissions.
- Fix: Normalize both separator styles, reject drive/UNC/NUL/traversal input, resolve the candidate and enforce `relative_to(root)`, use the same helper in both handlers, and bind HTTP to `127.0.0.1` by default.
- Mitigation: Firewall port 8080 and run only on loopback until patched.
- False positive notes: The exploit is Windows-specific, but Windows is a primary project environment and the code explicitly uses Windows-compatible filesystem APIs.

### MCAV-SEC-003: Minecraft command WebSockets are open by default and Fabric has no authentication

- Rule IDs: FASTAPI-WS-001; general service authentication boundary
- Severity: High
- Locations:
  - `minecraft_plugin/src/main/resources/config.yml:6`
  - `minecraft_plugin/src/main/resources/config.yml:11`
  - `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java:60`
  - `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java:91`
  - `minecraft_mod/src/main/java/com/audioviz/AudioVizMod.java:256`
  - `minecraft_mod/src/main/java/com/audioviz/websocket/VizWebSocketServer.java:128`
- Evidence: Paper defaults to `0.0.0.0` with an empty `ws-secret` and marks such clients authenticated. Fabric hard-codes `0.0.0.0`, immediately sends `connected`, and has no authentication state.
- Impact: Any reachable network client can issue visualization, entity, stage, and related control messages to the Minecraft process. On loopback, an unrestricted browser Origin would also permit a malicious page to target the local listener.
- Fix: Define one `connected(auth_required)` / `auth(token)` / `auth_ok` contract, pass the secret end-to-end from VJ configuration, default both listeners to loopback, reject unsafe non-loopback/empty-secret configuration, reject browser `Origin` handshakes, compare tokens in constant time, and gate every queue/event/broadcast path on authenticated state.
- Mitigation: Bind both listeners to loopback and firewall port 8765 until patched.
- False positive notes: Paper has an optional secret, but its default disables the check. Fabric has no equivalent check.

### MCAV-SEC-004: Missing browser OAuth state is accepted

- Rule IDs: REACT-REDIRECT-001; OAuth CSRF/state binding requirement
- Severity: High
- Locations:
  - `site/src/app/login/page.tsx:76`
  - `site/src/app/login/page.tsx:78`
  - `site/src/app/auth/callback/page.tsx:81`
  - `site/src/app/auth/callback/page.tsx:84`
- Evidence: Both browser flows reject only when `storedState` is truthy and different. If storage is absent, cleared, blocked, or attacker-manipulated to an empty value, the authorization code is still exchanged.
- Impact: The browser loses the correlation between the login it initiated and the callback it accepts, enabling login-CSRF/account-confusion scenarios.
- Fix: Consume state exactly once and require non-empty exact equality before any browser code exchange. Keep the signed desktop flow separate and coordinator-validated.
- Mitigation: None reliable in the browser flow; missing state must fail closed.
- False positive notes: The desktop callback is intentionally different because the coordinator validates its signed state. The finding applies to the two browser branches.

### MCAV-SEC-005: An empty community webhook secret authenticates a missing header

- Rule IDs: general webhook authentication; FASTAPI-AUTH-001 analogous boundary
- Severity: High
- Locations:
  - `community_bot/config.py:33`
  - `community_bot/webhook_server.py:38`
  - `community_bot/webhook_server.py:39`
- Evidence: Configuration defaults the shared secret to `""`. The request handler defaults a missing `X-Webhook-Secret` to `""` and compares the two with normal equality.
- Impact: When the deployment omits one environment variable, any caller that can reach the webhook can trigger Discord role synchronization actions.
- Fix: Reject missing/blank configuration at startup, deny an empty configured secret at the request boundary, require a non-empty header, and use `hmac.compare_digest`.
- Mitigation: Keep port 8100 private and set a strong secret immediately, but retain the code fix as defense in depth.
- False positive notes: None when `MCAV_WEBHOOK_SECRET` is missing or blank. A correctly configured current deployment is not vulnerable to the empty/empty variant.

## Medium findings

### MCAV-SEC-006: Coordinator metrics authentication fails open

- Rule IDs: FASTAPI-AUTH-001; FASTAPI-AUTH-002
- Severity: Medium
- Locations:
  - `coordinator/app/config.py:104`
  - `coordinator/app/config.py:107`
  - `coordinator/app/routers/metrics.py:33`
  - `coordinator/app/routers/metrics.py:35`
  - `coordinator/app/routers/metrics.py:39`
- Evidence: A missing token returns successfully, and every development environment bypasses authentication even when a token is configured. The environment field itself defaults to development.
- Impact: Deployment topology, request paths, error/latency behavior, and operational counters can be exposed to unauthenticated callers. The permissive environment default can silently carry this behavior into a misconfigured deployment.
- Fix: Default the environment to production, require a non-empty token in every environment at startup, and enforce the bearer token with constant-time comparison. Development uses an explicit test/local token rather than an unauthenticated exception.
- Mitigation: Restrict `/metrics` at the reverse proxy until patched.
- False positive notes: An external proxy may already protect the route; that control is not visible in application code and must be verified at runtime.

### MCAV-SEC-007: Updater and release paths can distribute unverified artifacts

- Rule IDs: REACT-SUPPLY-001; NEXT-SUPPLY-001
- Severity: Medium
- Locations:
  - `dj_client/package.json:20`
  - `dj_client/src-tauri/Cargo.toml:35`
  - `dj_client/src-tauri/tauri.conf.json:31`
  - `dj_client/src-tauri/tauri.conf.json:64`
  - `.github/workflows/release-dj-client.yml:4`
  - `.github/workflows/release-dj-client.yml:272`
  - `.github/workflows/docker.yml:4`
  - `.github/workflows/docker.yml:58`
  - `.github/workflows/ci.yml:57`
  - `.github/workflows/ci.yml:91`
  - `.github/workflows/security.yml:46`
- Evidence: The shipped client automatically checks a repository-wide latest-release endpoint and has download/install permissions. Tag workflows create releases and updater metadata despite optional/missing artifacts, while the Docker workflow pushes on main and tags. CI and security workflows also suppress Bandit and dependency-audit failures, allowing their summary checks to remain green.
- Impact: Users can receive incomplete, mismatched, or insufficiently verified updates and images. This is an integrity and availability risk even without a demonstrated signing-key compromise.
- Fix: Remove the updater runtime and ACL, stop updater artifacts, make the DJ release workflow a fail-closed sentinel, remove DJ artifacts from the combined release, stop Docker publishing until signed install/rollback/release gates exist, and make every retained security/release check blocking.
- Mitigation: Do not create release tags and do not advertise current binaries. Manual discipline is insufficient as the permanent control.
- False positive notes: Some individual artifacts may be valid; the finding is that the automated path does not prove the complete supported release contract.

### MCAV-SEC-008: Broken Docker/demo paths are advertised as supported

- Rule IDs: FASTAPI-DEPLOY-001; production integrity/availability boundary
- Severity: Medium
- Locations:
  - `Dockerfile:46`
  - `docker-compose.demo.yml:34`
  - `docker-compose.demo.yml:38`
  - `README.md:62`
  - `README.md:68`
  - `README.md:88`
  - `README.md:490`
- Evidence: The image invokes `audioviz-vj` with unsupported `--dj-port`/`--http-port` flags, and the demo adds an unsupported `--pattern` flag. Public documentation describes Docker as a production deployment and the demo as zero-install.
- Impact: A release can be green while its promoted first-run path fails, undermining operational reliability and encouraging insecure `--no-auth` workarounds.
- Fix: Quarantine the VJ/demo services behind an explicit Compose profile, stop image publishing, and label the path unsupported until clean-machine and end-to-end gates pass.
- Mitigation: Use source development commands only and do not direct users to Docker/demo paths.
- False positive notes: This is primarily a release-integrity finding, not a direct confidentiality exploit.

## Deferred risks outside Phase 0

Phase 0 does not redesign browser refresh-token storage, browser/VJ control-channel authorization, the hosted control-plane session model, resource-pack trust, or the v2 ShowIR/plugin capability model. Those items remain release-blocking design work under the approved v2 specification. Their deferral must not be interpreted as acceptance for public production use.

## Remediation tracking

- Implementation plan: `docs/superpowers/plans/2026-07-10-phase-0-containment.md`
- Architecture specification: `docs/superpowers/specs/2026-07-10-mcav-v2-platform-design.md`
- Phase 0 closes only after the plan’s full verification gate passes and a command/artifact evidence report is committed.
