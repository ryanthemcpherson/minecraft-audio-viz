# Self-Installing Paper Release Design

**Date:** 2026-08-31

**Status:** Approved for implementation

## Objective

Ship MCAV as a production-ready Paper experience whose server-side installation begins with one action: place one `AudioViz.jar` in the server's `plugins/` directory and start Paper. The plugin installs, verifies, configures, launches, monitors, upgrades, and rolls back the native VJ runtime without requiring Python, shell scripts, Docker, node access, or a modified Paper startup command.

The release preserves the current split architecture. Paper owns Minecraft rendering; the Python/Lua/NumPy VJ engine remains a separate process connected over an authenticated loopback WebSocket. Installation convenience must not move audio analysis or pattern calculation onto the Paper tick thread or into Paper's JVM.

The release is complete only when clean installation, offline restart, upgrade, rollback, hostile-artifact handling, process supervision, real-Paper performance, end-to-end DJ rendering, signed artifacts, and operator documentation have all passed release-blocking gates.

## User Experience

The canonical Paper installation is:

1. Download the release `AudioViz.jar` and verify its published signature or checksum.
2. Place the JAR in `plugins/`.
3. Assign one additional TCP port to the Minecraft server.
4. Start or restart Paper.
5. Follow the one-time setup URL or code printed to the server console.

The first start generates `plugins/AudioViz/config.yml`, selects the correct native runtime, downloads it from the official release origin, verifies and activates it, creates the local renderer secret and TLS identity, starts the VJ service, and exposes the control center. Paper remains usable throughout this work.

Subsequent starts work without internet access when a compatible verified runtime is cached. Replacing `AudioViz.jar` is the explicit core-upgrade action. MCAV does not install new executable code automatically during a show.

## Scope

### Included

- A universal Paper plugin JAR containing the renderer, installer, supervisor, commands, configuration, and release-verification public keys.
- Downloadable VJ runtime archives for Linux AMD64, Linux ARM64, and Windows AMD64.
- A single public TLS listener for the control center, preview, browser events, and DJ transport.
- First-run administrator onboarding and local DJ connect-code operation.
- Authenticated loopback VJ-to-Paper rendering with JSON compatibility and negotiated SBE rendering.
- Safe clean install, idempotent restart, paired upgrade, automatic rollback, and manual rollback.
- Reproducible builds, signed release metadata, checksums, detached signatures, SBOMs, and evidence reports.
- A signed Windows DJ-client installer and an end-to-end packaged-client verification path.
- Migration from the current plugin-only and Pterodactyl-bundle layouts without deleting administrator-owned data.

### Excluded from this release

- Fabric automatically installing or supervising the VJ runtime. Fabric remains a separately built compatibility artifact.
- Silent core auto-update. Update discovery may notify an administrator, but replacing the plugin JAR is explicit.
- macOS VJ-server runtime support.
- Hosted MCAV infrastructure eliminating the need for a server port.
- Automatic creation of Pterodactyl allocations, DNS records, firewall rules, or publicly trusted certificates.
- Docker image or zero-install Docker demo re-publication unless their independent quarantine requirements also pass.
- Replacing the current VJ engine with a Java implementation.
- Deleting the current Pterodactyl package before the new installer has passed its rollback and migration gates.

## Supported Baseline

The initial supported server baseline is:

- Paper for Minecraft 1.21.11;
- Java 21, with any additional Java version listed only after it passes the same release matrix;
- Linux AMD64, Linux ARM64, or Windows AMD64;
- 4 modern virtual CPU cores;
- 8 GiB total memory, with a recommended 5 GiB Paper heap and the balance available to the runtime and host;
- 2 GiB free persistent storage; and
- one standard active stage with 160 display entities at the balanced render profile.

Servers below this baseline may use reduced profiles, but are not advertised as supported until measurements prove them. The installer warns about observable incompatibilities such as unsupported Java, OS, architecture, unwritable storage, or insufficient free disk. It does not guess total available CPU or memory and does not refuse startup solely from unreliable resource estimates.

## Architecture

### Paper plugin

`AudioViz.jar` remains a normal Paper plugin and adds four isolated responsibilities:

1. **Runtime resolver:** obtains and validates signed release metadata and selects one platform artifact compatible with the plugin's runtime API range.
2. **Runtime store:** downloads, safely extracts, records, activates, retains, and rolls back immutable runtime versions.
3. **Runtime supervisor:** launches one child, tracks readiness, applies restart policy, and stops the process during plugin shutdown.
4. **Operator surface:** exposes status, setup, retry, diagnostics, and rollback through commands and structured logs.

These components depend on small interfaces for HTTP fetching, signature verification, storage, process launching, time, and event reporting. Tests replace those interfaces without starting Paper or external processes. Bukkit-specific adapters stay at the boundary.

The installer owns no Minecraft world state. The renderer can enable and accept an authenticated VJ connection even while runtime installation is pending or failed.

### VJ runtime

The sidecar retains the current Python application, Lua pattern engine, NumPy audio/visual calculations, admin panel, preview assets, and protocol implementation. A packaged runtime is self-contained and never imports from system Python or installs packages on the game host.

The runtime adds:

- one `aiohttp` TLS ingress server;
- first-run onboarding endpoints;
- a startup readiness announcement over the authenticated Minecraft renderer connection;
- parent-process monitoring;
- portable TLS certificate generation;
- structured lifecycle diagnostics; and
- stable command-line and environment contracts for the supervisor.

No high-frequency frame crosses through HTTP routing middleware after it enters the VJ engine. DJ and browser WebSockets terminate directly in asynchronous route handlers, and the renderer uses its existing loopback connection.

### Runtime and plugin data flow

1. Paper loads the plugin and the plugin saves default configuration.
2. The plugin creates or loads a renderer shared secret and starts its loopback-only WebSocket listener.
3. A dedicated installer executor acquires the runtime-store lock and recovers interrupted transactions.
4. The resolver loads the release descriptor embedded in the JAR and the cached signed manifest, then fetches the official manifest when required.
5. The resolver selects the exact platform artifact whose runtime API range includes the plugin's required API.
6. The store reuses an already verified immutable version or downloads and stages the selected artifact.
7. The supervisor starts the runtime with explicit paths, ports, parent identity, and secrets passed through its environment.
8. The runtime binds the public ingress and internal metrics listeners before connecting to Paper.
9. The runtime authenticates to the plugin and reports its release, runtime API, protocol capabilities, and ready state.
10. The supervisor marks the version healthy only after the authenticated readiness report and a bounded stability window.
11. DJ frames enter `/ws/dj`, drive the VJ pattern loop, and travel to Paper through the existing JSON/SBE render connection.
12. Admin and preview sessions use `/ws/admin` and `/ws/preview` on the same public listener.

## Filesystem Layout

All installer-owned state lives below `plugins/AudioViz/runtime/`:

```text
plugins/AudioViz/
  config.yml
  runtime/
    install.lock
    current.json
    last-known-good.json
    manifest-cache/
    downloads/
    staging/
    versions/
      1.2.0-linux-x86_64/
      1.2.0-linux-aarch64/
      1.2.0-windows-x86_64/
    state/
      runtime.env
      auth.json
      tls.crt
      tls.key
      setup.json
    logs/
    backups/
```

Version directories are immutable after promotion. Their names are derived only from validated manifest fields and are not accepted as arbitrary paths. Downloads and staging entries use generated local identifiers rather than remote filenames.

`current.json` and `last-known-good.json` contain bounded identifiers, digests, runtime API versions, activation times, and health results. They contain no passwords, private keys, setup tokens, URLs with credentials, or untrusted absolute paths. Persistent metadata is written to a same-directory temporary file, flushed, and atomically replaced.

The store keeps the current version, last-known-good version, and one additional recent compatible version. It never deletes a version referenced by active, rollback, staging, or recovery metadata. Cleanup failure is nonfatal and is reported without affecting the active runtime.

## Release Metadata and Trust

### Embedded release descriptor

Every plugin build contains an immutable descriptor with:

- product and plugin version;
- required minimum and maximum runtime API;
- stable manifest URL;
- maximum manifest and archive sizes;
- accepted signing-key identifiers and Ed25519 public keys;
- minimum accepted manifest generation; and
- development-mode test-origin policy, disabled in release builds.

Administrators may select a signed stable or beta channel. Changing the origin cannot disable signature, generation, compatibility, size, or archive checks. Release builds never accept an unsigned local manifest through ordinary configuration.

### Signed manifest

The canonical JSON manifest is signed with a dedicated Ed25519 release key available only to the protected publication boundary and includes:

- schema version and monotonically increasing generation;
- release version and runtime API range;
- publication and expiration timestamps;
- artifact records for each supported platform;
- exact uncompressed and compressed size limits;
- SHA-256 digest;
- HTTPS download URL;
- executable entry point;
- packaged file manifest digest; and
- signing-key identifier.

Canonicalization is defined by the schema and shared golden fixtures. The Java verifier and publication tooling must accept identical bytes and signatures. Unknown fields, duplicate JSON keys, invalid Unicode, noncanonical numbers, expired metadata, old generations, unknown keys, and incompatible APIs fail closed.

Key rotation uses an overlap release: an existing trusted key signs metadata introducing the next public key, and a later plugin release embeds both. Revocation and a raised minimum generation require a plugin update. Private keys never ship in source, plugin JARs, runtime archives, logs, tests, or game-server state.

### Network download policy

The resolver uses HTTPS, connect/read/overall timeouts, a bounded response body, and at most two redirects. Every redirect must remain HTTPS and target an allowlisted release host from the embedded descriptor. Authentication headers and cookies are never sent. Proxy support uses standard JVM configuration without logging proxy credentials.

A downloaded archive is accepted only when its byte count and SHA-256 match the verified manifest. A cached archive is rehashed before reuse. Manifest failure never falls back to unsigned metadata or a newer unverified archive. Manifest expiration prevents new download or activation decisions, but it does not disable an installed version whose signature, generation, compatibility, archive digest, and packaged-file digests were verified while the manifest was valid. This rule permits offline restart without allowing expired metadata to introduce executable bytes.

## Safe Extraction and Activation

Archives use ZIP for all platforms. Before writing any member, the extractor validates the complete central directory and rejects:

- absolute, rooted, drive-qualified, UNC, empty, dot, or parent-traversal paths;
- backslash-based traversal and names that normalize differently across supported hosts;
- symbolic links, hard links, device entries, or other non-regular members;
- duplicate or case-colliding names;
- entries not declared by the signed packaged-file manifest;
- member or total sizes beyond signed limits;
- compression ratios beyond the configured bomb limit; and
- any output that escapes the unique staging directory after normalization.

Extraction streams each file through a bounded reader and verifies its declared digest and length. Linux entry points receive explicit owner execute permission after extraction. No archive-provided owner, group, ACL, or broad write permission is preserved.

Promotion occurs only after all files and metadata validate. Each staged file is forced through its `FileChannel`, the staged metadata is forced, and the completed directory is moved atomically into `versions/` when the filesystem exposes an atomic move. On a filesystem without atomic directory moves, promotion uses a transaction journal, a uniquely named complete directory, and an atomic `current.json` replacement; recovery never treats an uncommitted directory as active. An immutable-version collision succeeds only if the existing packaged-file manifest matches byte for byte; otherwise it is treated as tampering or corruption.

Activation changes metadata, not version-directory contents. The old current version remains available until the new child has completed readiness and its stability window. Only then does it become last known good.

## Runtime Lifecycle

### State model

The supervisor exposes these states:

```text
DISABLED
CHECKING
DOWNLOADING
VERIFYING
STAGING
STARTING
READY
DEGRADED
BACKING_OFF
ROLLING_BACK
FAILED
STOPPING
```

State changes are serialized by one coordinator. Worker completion events carry an operation generation; stale work cannot overwrite newer state after disable, retry, or rollback. Every state change emits a bounded structured event and updates command-visible status.

### Startup

Plugin enablement starts only lightweight local work synchronously. File locking, hashing, manifest access, downloads, extraction, process launch, and waits occur off the Paper main thread.

The supervisor launches the runtime directly with `ProcessBuilder`; it does not invoke a shell. The working directory is the selected immutable version. The environment contains explicit state paths, bind settings, renderer secret, one-time setup material when needed, parent PID, parent-start identity, and resource-profile settings. Command-line arguments contain no secrets.

The child starts from a fixed allowlist of inherited environment variables required for OS operation. `OMP_NUM_THREADS`, `OPENBLAS_NUM_THREADS`, `MKL_NUM_THREADS`, `NUMEXPR_NUM_THREADS`, and `VECLIB_MAXIMUM_THREADS` are set to `1`. Runtime stdout and stderr are read continuously on bounded log-forwarding threads so the child cannot block on full pipes.

### Readiness and health

The runtime must bind all configured listeners before initiating its authenticated Paper connection. Its readiness message contains the runtime version, runtime API, protocol capabilities, process identity, and a per-launch nonce. The plugin accepts readiness only on the authenticated connection for the current launch generation.

The default readiness timeout is 30 seconds. A runtime becomes last known good after remaining connected and responsive for a 60-second stability window while its process stays alive and its ingress loop reports healthy. Tests use injected shorter durations.

Health after readiness combines process liveness, authenticated renderer heartbeat, event-loop heartbeat, public-ingress state, and bounded render-loop progress. A missing DJ or browser client is not unhealthy.

### Shutdown and orphan prevention

Plugin disable closes the coordinator to new work, cancels pending downloads, asks the child to shut down, and waits at most 10 seconds during server shutdown. It then calls normal process termination and forcibly terminates only the known child and descendants returned by that live `ProcessHandle` if they remain. The renderer and Minecraft entities still follow their existing safe shutdown order. The bounded wait can lengthen shutdown but cannot delay an active gameplay tick because Paper has already entered plugin disablement.

The runtime monitors the exact parent PID plus a launch identity that distinguishes PID reuse. When the parent disappears or no longer matches, the child exits. A restarted plugin never kills an arbitrary process referenced by stale metadata. An existing process can be adopted only after it proves knowledge of the current renderer secret and launch record; otherwise the new supervisor reports the port conflict and waits for the parent monitor or administrator action.

### Crash and retry policy

Unexpected exits use exponential delays of 1, 2, 4, 8, 15, and 30 seconds with jitter. Five failures within ten minutes open the circuit breaker and leave MCAV in `FAILED` until `/audioviz runtime retry`, a configuration reload, or the next Paper restart. Stable operation for ten minutes resets the failure window.

The supervisor never runs two child launches concurrently. Disable, retry, upgrade, and rollback operations are mutually exclusive. Paper and the plugin renderer remain active while the VJ runtime is unavailable.

## Upgrade and Rollback

The plugin release descriptor pins a compatible VJ release or signed compatibility range. On a plugin replacement:

1. Preserve administrator configuration, credentials, TLS identity, scenes, recordings, and visual-pack state.
2. Resolve and verify the paired runtime.
3. If the cached current runtime declares compatibility with the new plugin API, start it as a temporary healthy runtime while the paired candidate downloads; otherwise remain in renderer-only mode during installation.
4. Stop the old child only when the new runtime is locally ready to launch.
5. Start the new child and wait for authenticated readiness and the stability window.
6. Commit it as current and move the prior current record to last known good.
7. If start or health fails, stop the candidate and restart the prior compatible runtime.

If no cached runtime is compatible with the new plugin, rollback cannot silently run an incompatible executable. The plugin reports a renderer-only degraded state and preserves all artifacts for administrator recovery.

`/audioviz runtime rollback` selects the recorded last-known-good compatible version, requires confirmation from an authorized sender, and uses the same activation transaction. Rollback works without network access. Rollback audit records contain actor, time, source and target versions, reason, and result, but no secrets.

Core runtime code never updates independently on a timer. The control center may display that a newer plugin release exists. Visual packs may use their separately approved signed staging and activation policy.

## Unified Public Ingress

The VJ runtime replaces the separate threaded static server and public WebSocket listeners with one `aiohttp` application and one public TCP allocation. The default port is `8080` and is configurable before launch through `plugins/AudioViz/config.yml`.

The route contract is:

- `GET /` serves the admin control center shell;
- `GET /preview/` serves the browser preview shell;
- `GET /assets/{path}` serves immutable versioned static assets;
- `GET|POST /setup/*` implements first-run setup while a valid setup session exists;
- `GET /ws/admin` upgrades authenticated VJ-operator connections;
- `GET /ws/preview` upgrades authenticated or explicitly public read-only preview connections according to configuration;
- `GET /ws/dj` upgrades DJ-client connections and then applies existing credential or connect-code authentication; and
- `GET /healthz` returns only a minimal unprivileged liveness result.

Metrics remain on a loopback-only listener and never share the public ingress. The Paper renderer listener remains loopback-only. Route handlers have distinct payload, message-rate, connection-count, authentication-attempt, idle, and lifetime limits. A slow static download or browser cannot starve DJ frame processing or the renderer loop.

Static files use the existing containment rules plus normalized POSIX URL paths, a fixed route-to-root mapping, and `Path.resolve()` containment on every request. Directory listings, arbitrary filesystem roots, dotfiles, range amplification, and user-controlled MIME inference are disabled. Responses set an explicit content type, `X-Content-Type-Options: nosniff`, a restrictive Content Security Policy, frame policy, referrer policy, and cache policy. HTML and setup responses are not cached; content-hashed assets are immutable.

WebSocket upgrades validate `Origin` for browser routes, while the native DJ route uses its explicit authentication protocol rather than trusting `Origin`. Authentication happens before any state, roster, scene, metric, or frame subscription is returned. Existing command authorization and rate limits remain fail closed.

### TLS

Public admin credentials and DJ traffic require TLS by default. First installation generates an ECDSA P-256 private key and self-signed certificate inside the packaged runtime using the pinned `cryptography` wheel. This removes the current dependency on system `openssl` and works on all supported platforms.

The generated key is owner-readable only where the filesystem supports permissions. The certificate includes `localhost`, loopback IP addresses, and configured public DNS/IP names when provided before generation. Its SHA-256 fingerprint is printed during setup. Administrators can replace the generated pair with a trusted certificate and key or configure a trusted reverse proxy; the runtime validates the pair before binding.

Plain HTTP is available only through an explicit insecure-development option that also restricts the bind address to loopback. Release defaults never expose credential authentication over public plaintext HTTP.

## First-Run Onboarding and Authentication

The plugin generates a cryptographically random 256-bit setup secret on first install. Persistent state stores only an HMAC-SHA-256 digest keyed by the renderer secret, creation time, expiration, attempt count, and consumed state. The raw secret is delivered once through the server console as a short-lived URL-safe code and is never written to ordinary logs or configuration.

The default setup session expires after 30 minutes, is single use, permits at most five failed attempts, and is invalidated immediately after successful administrator creation. The console URL places the raw token in the URI fragment, not the query string. Setup JavaScript submits it once in the authenticated request body and immediately removes the fragment with `history.replaceState`. `/audioviz setup` can rotate an unused or expired session from the server console or a sender with `audioviz.admin.setup`; rotation invalidates all previous setup material. In-game delivery avoids hover text, broadcast chat, and proxy-forwarded commands by default; console is the authoritative recovery surface.

Setup requires the administrator to create an account with a 3-32 character normalized username and a password containing 12-72 UTF-8 bytes. Passwords are bcrypt-hashed at cost 12 for release 1.2.0 before `auth.json` is atomically committed. A future cost change is a versioned authentication migration. The response never echoes the password. Recovery creates a new bounded setup session and does not reveal existing credentials.

After onboarding, VJ operators authenticate on `/ws/admin`. DJ users normally receive existing short-lived connect codes from an authenticated operator. Static DJ credentials remain supported for managed installations but are not generated as plaintext first-login files.

When the public listener uses a publicly trusted certificate, the DJ client applies normal platform trust. For a generated self-signed certificate, the admin panel produces a DJ invite containing the server URL, short-lived connect code, and exact SHA-256 certificate fingerprint. The DJ client requires the fingerprint before making the TLS connection, validates the presented leaf certificate against it, and stores the pin only in that named server profile after successful authentication. Manual entry requires the same fingerprint. There is no “accept any certificate” mode in release builds. Certificate replacement invalidates the stored pin and requires a new administrator-provided invite or explicit fingerprint update.

Browser credential state remains in memory for the session and is cleared on sign-out or tab close. No password, renderer secret, setup secret, private key, or reusable administrator token is stored in `localStorage`, URL query history after setup consumption, telemetry, metrics, or exception strings.

Authentication records, setup state, TLS identity, and administrator-owned content are persistent across plugin and runtime upgrades. Invalid partial identity state fails closed and provides recovery instructions; it is never silently replaced with new credentials.

## Configuration

The plugin remains the source of truth for installation and process settings. The VJ runtime receives a generated, minimal environment derived from validated configuration. The new configuration groups are:

```yaml
runtime:
  enabled: true
  channel: stable
  install-on-start: true
  public-port: 8080
  public-host: "0.0.0.0"
  public-url: ""
  readiness-timeout-seconds: 30
  resource-profile: auto
  retain-versions: 3
  tls:
    mode: generated
    certificate: ""
    private-key: ""

performance:
  profile: balanced
  entity-budget: 160
  target-render-fps: 20
  tps-load-shedding: true
```

Release-channel names are a closed set. Port values, host values, URLs, paths, timeouts, retention, entity budgets, and frame rates are validated with documented bounds before they reach the installer or child. Invalid external-boundary values produce actionable errors and retain the last valid running configuration.

The local renderer address remains loopback and cannot be changed to a non-loopback bind. The plugin generates and owns the renderer secret; configuration commands never print it. Remote VJ operation continues to require an encrypted tunnel terminating on loopback and is an advanced mode that disables local supervision explicitly.

Configuration migrations are versioned, back up the exact prior file, and patch only installer-owned YAML paths while preserving every unowned line and comment byte for byte. If a source structure cannot be patched without rewriting unowned content, migration fails with recovery instructions instead of serializing the full document. A migration never rewrites unrelated zones, stages, materials, effects, permissions, or administrator extensions.

## Operator Commands and Permissions

The command surface adds:

- `/audioviz status` — current plugin/runtime versions, lifecycle state, public address, renderer connection, health summary, and safe diagnostic reason;
- `/audioviz setup` — create or rotate a first-run/recovery setup session;
- `/audioviz runtime retry` — close a crash circuit and retry the current verified runtime;
- `/audioviz runtime check` — refresh signed metadata without activating executable code;
- `/audioviz runtime rollback` — begin the confirmed last-known-good rollback transaction; and
- `/audioviz diagnostics` — write a redacted bounded support bundle under the plugin data directory.

Status is available to `audioviz.status`; setup, retry, check, rollback, and diagnostics require explicit `audioviz.admin` sub-permissions, with console allowed. Rollback requires a time-limited confirmation nonce bound to sender identity and target version. Tab completion never reveals tokens, passwords, keys, private paths, or unavailable rollback identifiers.

Diagnostics include versions, normalized configuration with secrets removed, lifecycle events, bounded recent logs, manifest identifiers and digests, health counters, Paper/Java/platform metadata, and installer transaction state. They exclude auth hashes, setup material, renderer secrets, TLS private keys, environment variables, player chat, audio data, and arbitrary server files.

## Performance and Resource Isolation

The installer has no steady-state polling on the Paper main thread. Network, hashing, extraction, process I/O, readiness waits, diagnostics, and cleanup run on dedicated bounded executors. Completion callbacks schedule only the minimum Bukkit-safe state publication.

The existing render path remains authoritative:

- high-frequency state uses the negotiated SBE path when available and JSON fallback otherwise;
- each zone keeps only its freshest complete snapshot;
- transient beats use durable bounded latches;
- the main thread performs one bounded drain per tick; and
- entity pools and hot-loop allocations retain their existing performance gates.

The VJ runtime sets `OMP_NUM_THREADS=1`, `OPENBLAS_NUM_THREADS=1`, `MKL_NUM_THREADS=1`, and equivalent supported controls unless an advanced profile explicitly overrides them. Thread pools, WebSocket queues, command queues, log queues, pattern results, connections, and payloads are bounded.

The plugin samples Paper TPS and tick-duration information through supported Paper APIs. The runtime reports frame calculation, effects, Minecraft send, browser broadcast, sleep, queue, and entity counts. The balanced profile applies hysteretic load shedding:

1. Below 19.0 TPS for 10 seconds, reduce nonessential browser preview frequency.
2. Below 18.5 TPS for another 10 seconds, reduce the VJ render target toward 10 FPS and clamp optional particle work.
3. Below 18.0 TPS for another 10 seconds, clamp active entity work to the safe profile while preserving beats and control state.
4. Above 19.5 TPS for 60 seconds, restore one level at a time.

Load shedding is observable, rate-limited, and reversible. It never changes stored stage configuration or silently disables authentication, health checks, rollback, or renderer bounds. Administrators may choose a fixed safe, balanced, or performance profile, but hard safety limits remain.

The release baseline is proven in a container or VM constrained to 4 vCPU and 8 GiB total memory. A standard 160-entity show with one admin and one preview client must maintain at least 19.5 TPS after warm-up. VJ main-loop work excluding deliberate sleep must remain at or below 40 ms p95 and 50 ms p99 at the 20 FPS target. VJ peak resident memory must remain at or below 1.5 GiB, with less than 1 MiB/minute fitted growth over the final 20 minutes of the 30-minute run. The existing 256-entity renderer gates remain release-blocking: SBE decode/validation/publication at or below 1 ms p95, plugin main-thread render work at or below 3 ms p95, SBE payload at or below 25 percent of equivalent JSON, and zero per-entity steady-state binary decode allocations. A frame available before a zone drain must apply in that drain with no extra application scheduler tick. Exact p50, p95, p99, maximum, CPU, memory, allocation, and payload measurements are committed as release evidence.

## Failure Policy

Failures are contained as follows:

- **No internet, compatible cache present:** start the verified cached runtime and report that the manifest refresh was skipped.
- **No internet, first install:** keep Paper and the renderer online, retain pending state, and retry with bounded backoff.
- **Invalid or expired manifest:** reject it, retain the active runtime, and expose the signature/generation/expiration category without untrusted text.
- **Hash or size mismatch:** delete or quarantine the temporary download and never extract it.
- **Hostile or corrupt archive:** delete the unique staging transaction and retain all active metadata.
- **Disk full or permission error:** stop the transaction, preserve the active version, and name the safe operator-owned directory involved.
- **Unsupported platform:** disable local runtime installation while leaving renderer-only advanced operation available.
- **Public port conflict:** stop the candidate, retain the current version metadata, open the circuit after bounded retries, and report the configured port.
- **Renderer port conflict:** refuse a nonauthenticated process, never broaden the bind address, and keep Paper operational.
- **Candidate readiness failure:** terminate the candidate and start the previous compatible version.
- **Runtime crash after stability:** follow bounded restart policy; rollback automatically only when the current version has not completed the release health policy or an administrator invokes rollback.
- **Invalid partial identity:** fail closed, preserve every identity file, and require explicit recovery.
- **Plugin disable during work:** cancel or obsolete the operation generation, leave immutable completed downloads reusable, and never activate after disable.
- **Process termination during metadata commit:** recover from the last complete atomic record and reconcile immutable versions on next start.
- **Log or metrics failure:** keep rendering if safe, bound in-memory fallback, and surface degraded observability.

Failure messages use enumerated reason codes plus concise administrator guidance. Raw exception messages from networking, archives, authentication, or untrusted manifests are debug-only after redaction and never become metric labels.

## Migration and Compatibility

### Existing Paper plugin install

When `plugins/AudioViz/config.yml` exists, the installer preserves it and adds only missing installer groups through the versioned migration. Existing zones, stages, sequences, recordings, materials, renderer settings, and permissions remain intact. The existing renderer secret is retained when valid; otherwise recovery requires explicit administrator action rather than silent identity replacement.

### Existing Pterodactyl bundle

The new plugin detects the current `mcav-vj` managed layout but does not delete or rewrite it. Documentation gives a reversible migration:

1. Stop the server.
2. Remove the `bash mcav-vj/start-mcav.sh --` startup prefix while retaining the folder as rollback data.
3. Place the new plugin JAR in `plugins/`.
4. Start Paper and allow the plugin to import compatible auth, TLS, and content state from an explicitly configured source.
5. Verify the new supervised runtime before archiving the old folder.

Import validates every source file, copies rather than moves it, writes backups, and never imports executable binaries or unsigned metadata. The old bundle remains the rollback path until the administrator confirms the new installation.

### Protocol compatibility

The runtime API is distinct from product and WebSocket protocol versions. The manifest declares its runtime API range; the plugin declares the exact supported range. JSON control compatibility remains available across the supported range, and SBE activates only after normal capability negotiation. An incompatible runtime never launches merely because its product version is newer.

## Test Strategy

### Java unit and component tests

Tests cover:

- strict manifest parsing, canonical signature golden vectors, key selection, expiry, generation, and compatibility;
- platform detection for supported and unsupported OS/architecture combinations;
- HTTP timeouts, redirects, allowlists, size bounds, truncation, and cancellation;
- cache rehashing and immutable-version collision behavior;
- every unsafe ZIP path/member form, case collision, link type, digest mismatch, size mismatch, bomb limit, and partial extraction;
- atomic metadata commits and recovery after termination at every write boundary;
- install-lock contention and stale-operation generations;
- all supervisor states, readiness authentication, timeouts, backoff, circuit breaking, shutdown, orphan rules, and rollback;
- secret-free arguments, environment construction, logging, commands, permissions, and diagnostics;
- configuration migration preservation; and
- proof that installer work does not execute on the simulated Paper main thread.

Property and fuzz tests generate malformed manifest JSON, ZIP central directories, paths, metadata records, and lifecycle event orderings within fixed resource bounds.

### Python unit and component tests

Tests cover:

- unified HTTP and WebSocket routing, TLS-only policy, headers, static containment, and cache behavior;
- per-route origins, authentication, authorization, payload limits, rates, connections, and idle timeouts;
- setup-token creation, hashing, expiry, failed attempts, rotation, single use, recovery, and log redaction;
- cross-platform certificate generation and existing-certificate validation;
- parent PID/start-identity monitoring and graceful shutdown;
- readiness emitted only after all listeners bind;
- event-loop and render-loop health;
- DJ, admin, preview, metrics, and renderer isolation; and
- existing VJ behavior and protocol regression suites.

### Integration tests

A local signed-artifact fixture serves manifests, redirects, truncated files, corrupt archives, slow responses, and multiple compatible/incompatible releases. Tests use production Java parsing and extraction with test signing keys committed only under fixtures.

A fake sidecar fixture exercises process output, readiness, exit codes, hangs, child descendants, parent loss, port conflicts, health degradation, and every rollback boundary. A real packaged runtime integration then repeats the successful and principal failure paths.

### Real Paper matrix

Release-blocking jobs exercise Linux AMD64, Linux ARM64, and Windows AMD64 with real supported Paper and Java versions. Each platform covers:

1. empty-server clean installation from only `AudioViz.jar`;
2. onboarding and authenticated control-center access;
3. second start with byte-stable identity and no download;
4. offline restart;
5. plugin/runtime upgrade with state preservation;
6. failed candidate and automatic rollback;
7. manual offline rollback;
8. public and renderer port conflicts;
9. runtime crash, restart backoff, and circuit recovery;
10. Paper shutdown, forced JVM termination, and absence of an orphan runtime; and
11. uninstall/disable behavior without deleting administrator state.

### End-to-end and performance tests

The end-to-end harness starts a real Paper server, installs from the release JAR through the signed local release origin, completes onboarding, generates a connect code, and sends deterministic audio through the packaged DJ protocol. It asserts VJ processing, renderer authentication, negotiated SBE plus forced JSON fallback, zone updates, durable beat behavior, browser preview state, and authenticated admin commands.

Windows additionally installs the signed DJ-client package on a clean test image and drives its supported synthetic/test audio source. The test validates installer identity, application launch, server trust/onboarding flow, connect code, frame streaming, disconnect, reinstall, upgrade, rollback, and uninstall. Real audio-device behavior remains a documented hardware test alongside deterministic CI input.

Performance gates run after warm-up on the supported minimum for 30 minutes, with at least 500 deterministic frames per protocol mode and a sustained standard show. Results include Paper TPS/tick percentiles, receive-to-apply percentiles, VJ frame-stage percentiles, process CPU, resident memory, heap/allocation evidence, payload sizes, dropped/superseded frames, reconnects, and load-shedding transitions.

## Release Model and Artifacts

The next unified product release is `1.2.0`. Release candidates use `1.2.0-rc.N`; stable `1.2.0` is created only from a candidate commit whose exact artifacts passed every gate. Minecraft compatibility is release metadata, not the product version.

The release contains:

- `AudioViz-1.2.0.jar`;
- `mcav-vj-runtime-1.2.0-linux-x86_64.zip`;
- `mcav-vj-runtime-1.2.0-linux-aarch64.zip`;
- `mcav-vj-runtime-1.2.0-windows-x86_64.zip`;
- `mcav-runtime-manifest-v1.json` and its Ed25519 signature;
- the Windows DJ-client NSIS `.exe` and WiX `.msi` installers;
- the Fabric compatibility JAR;
- `SHA256SUMS.txt` and its detached GPG signature;
- artifact-specific CycloneDX SBOMs;
- build provenance/attestations;
- release notes and compatibility matrix; and
- clean-install, upgrade, rollback, performance, security, and end-to-end evidence reports.

The plugin, VJ package, DJ client, root package, and release metadata converge on the unified product version where their package ecosystems permit it. Protocol and runtime API versions remain independent.

Runtime archives are built from pinned standalone Python distributions and locked wheels for each target. Build jobs verify downloaded toolchain digests, produce deterministic packaged-file manifests, normalize timestamps and permissions, and rebuild artifacts to compare digests where the toolchain supports reproducibility. ARM artifacts are built and executed on ARM rather than merely cross-labeled.

## Publication and CI Gates

Release publication follows exact-commit provenance:

1. The candidate commit is reachable from protected `main`.
2. Required CI and security workflows succeeded for that exact `main` push.
3. Platform runtime builds and package tests succeeded.
4. Real-Paper clean-install, upgrade, rollback, and end-to-end matrices succeeded.
5. Performance results meet the documented thresholds.
6. Dependency audits, SAST, licenses, SBOM generation, and secret scans are blocking.
7. Publication tooling generates one canonical manifest from the already tested artifacts.
8. A protected release environment signs the manifest and checksum file; private keys are supplied only by the publication boundary.
9. A separate verifier downloads the candidate release artifacts, verifies signatures, rehashes every artifact and archive member, validates SBOM presence, and reruns package smoke checks.
10. The release job creates a draft release first. Stable publication requires the verified release manifest and evidence report.

The runtime manifest Ed25519 key is separate from the existing GPG release key. CI test keys are visibly marked fixtures and cannot satisfy production key identifiers. GitHub artifact attestations supplement but do not replace the offline-verifiable signatures used by the plugin and operators.

No release gate uses `continue-on-error`, `|| true`, an unpinned mutable artifact, or a report-only result as proof of safety. A failed or unavailable required platform, scanner, signing step, or clean-machine test leaves the release unpublished.

## Phase 0 Quarantine Exit

The existing Phase 0 containment remains in force during development. Public DJ binaries, updater metadata, Docker advertising, and claims of zero-install support remain disabled until the new evidence report proves the applicable gates.

This release authorizes public Paper and Windows DJ distribution only after:

- the server installer and signed runtime chain pass every gate in this design;
- the signed Windows DJ installer passes clean install, upgrade, rollback, and end-to-end tests;
- the security audit has no unresolved release-blocking finding;
- operator documentation and recovery procedures are complete; and
- the current no-bypass release rules are deliberately replaced with equally strict rules that permit only the new proven workflow.

The automatic DJ-client updater remains disabled in release 1.2.0. Operators distribute the signed NSIS or MSI package for explicit installation, upgrade, and rollback. Re-enabling an in-application updater requires its own signed metadata, clean-machine update, failed-update recovery, downgrade/rollback, and key-rotation design and evidence. Documentation and UI must not imply that automatic updating is available.

Docker image and zero-install Docker demo publication remain quarantined unless their separate re-enablement requirements are implemented and evidenced. This design does not weaken that boundary merely because the Paper experience is ready.

## Documentation

Release documentation includes:

- a five-step Paper installation guide;
- supported Paper, Minecraft, Java, OS, architecture, CPU, memory, disk, and port requirements;
- first-run setup and certificate-fingerprint instructions;
- Pterodactyl, generic host-panel, Linux, and Windows notes;
- trusted certificate/reverse-proxy configuration;
- status, retry, diagnostics, and rollback commands;
- offline operation and retention behavior;
- existing plugin and Pterodactyl-bundle migration;
- upgrade, automatic rollback, manual rollback, and emergency recovery;
- firewall and public-exposure security guidance;
- artifact signature, checksum, SBOM, and provenance verification;
- uninstall/disable behavior and retained data;
- performance profiles and load-shedding behavior; and
- a concise troubleshooting map from reason codes to actions.

The main README replaces source-build setup as the primary user path only after the stable artifacts exist. Development instructions remain clearly separated.

## Implementation Sequence

This design is a release-program specification rather than one monolithic coding task. Implementation is divided into four sequential plans, all governed by this specification:

1. **Trust and store foundation:** manifest/runtime API contracts, signatures, downloads, safe extraction, immutable storage, metadata, and recovery.
2. **Paper supervision:** process lifecycle, plugin integration, commands, configuration, diagnostics, upgrade, rollback, migration, and performance signaling.
3. **VJ product surface:** unified ingress, portable TLS, onboarding, parent monitoring, health, routing compatibility, and packaged-runtime integration.
4. **Release completion:** platform builders, Windows DJ distribution, real-Paper/end-to-end/performance matrices, signing, SBOMs, provenance, documentation, quarantine exit, and release-candidate assembly.

Each plan has its own test-first tasks and verification checkpoint. Passing an earlier plan is progress toward this objective but is never evidence that the release itself is complete. A new design cycle is required only if implementation uncovers a product or interface decision that contradicts or is absent from this specification.

Across those plans, implementation proceeds in these independently verified increments:

1. Define the manifest schema, runtime API contract, canonical signing fixtures, and Java verifier.
2. Implement the safe runtime store, ZIP validator, atomic metadata, recovery, and platform selection.
3. Implement the process abstraction and supervisor state machine with fake sidecar tests.
4. Integrate the manager into Paper commands, configuration, renderer readiness, shutdown, and diagnostics.
5. Consolidate VJ ingress on `aiohttp` while preserving current authentication and protocol behavior.
6. Implement cross-platform identity/onboarding, parent monitoring, health, and structured readiness.
7. Build signed-style test runtime archives for all platforms and run integration matrices.
8. Add paired upgrade, automatic/manual rollback, migration, retention, and failure recovery.
9. Add performance profiles, load shedding, telemetry, and constrained-host release gates.
10. Re-enable the signed Windows DJ release path with clean-install and rollback evidence.
11. Assemble publication, signature, SBOM, provenance, and independent verification workflows.
12. Run the complete release audit, commit evidence, update user documentation, and produce the verified release candidate.

Each increment leaves Paper operational and preserves the existing Pterodactyl path. Removal or quarantine changes occur only in the final release increment after equivalent recovery evidence exists.

## Acceptance Criteria

The release objective is complete only when evidence proves all of the following:

- A supported empty Paper server becomes a usable MCAV server after adding only `AudioViz.jar`, assigning one port, starting Paper, and completing setup.
- Installation performs no network, hashing, extraction, waiting, or child I/O on the Paper main thread.
- Every executable byte accepted by the plugin is covered by a verified signed manifest and per-file digest chain.
- Hostile metadata, downloads, and archives cannot escape bounds, alter the active runtime, or weaken signature enforcement.
- Paper remains online through install, download, identity, port, runtime, and rollback failures.
- Linux AMD64, Linux ARM64, and Windows AMD64 pass clean install, offline restart, upgrade, automatic rollback, and manual rollback.
- Runtime crashes cannot create an unbounded restart loop, duplicate process, or persistent orphan.
- Administrator identity, TLS state, zones, stages, scenes, recordings, and visual packs survive compatible upgrades and rollbacks.
- The public surface uses one TLS port with fail-closed route authentication and loopback-only metrics/rendering.
- A packaged Windows DJ client sends deterministic audio through the packaged VJ runtime into a real Paper renderer and browser preview.
- The supported-minimum performance test meets its Paper tick, latency, allocation, memory, and bounded-queue gates.
- Release artifacts include signatures, checksums, SBOMs, provenance, documentation, and committed verification evidence.
- Phase 0 distribution controls are changed only after the replacement workflow proves equal or stronger fail-closed provenance.
- A fresh administrator can follow the published guide without Python, shell scripts, Docker, source checkout, or a modified Paper startup command.
