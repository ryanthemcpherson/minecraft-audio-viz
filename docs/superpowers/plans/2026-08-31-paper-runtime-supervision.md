# Paper Runtime Supervision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the verified runtime store into Paper with a generation-safe process supervisor, authenticated readiness, lifecycle commands, configuration migration, rollback, diagnostics, and TPS-aware performance signaling.

**Architecture:** A Bukkit-free supervisor state machine owns one injected process handle and consumes authenticated runtime-control events from the existing loopback renderer connection. A thin `PaperRuntimeManager` adapts plugin configuration, scheduler/lifecycle, permissions, command output, and Paper TPS into that core. Installation and supervision share one serialized coordinator so disable, retry, upgrade, and rollback cannot race.

**Tech Stack:** Java 21, Paper API 1.21.11, Java-WebSocket 1.6.0, Gson 2.13.2, the runtime foundation from `2026-08-31-runtime-trust-store-foundation.md`, JUnit 5.12.1, Mockito 5.23.0, MockBukkit 4.108.0.

**Spec:** `docs/superpowers/specs/2026-08-31-self-installing-paper-release-design.md`

## Global Constraints

- Complete the trust/store foundation plan first; do not duplicate its manifest, download, archive, or state logic.
- Paper owns Minecraft rendering; the VJ engine remains a separate child process.
- Process launch uses `ProcessBuilder` directly with no shell and no secret-bearing command arguments.
- Exactly one coordinator serializes install, launch, retry, rollback, and disable operations; stale generations cannot publish state.
- Readiness is accepted only from the authenticated renderer connection matching the current launch nonce and runtime API.
- The default readiness timeout is 30 seconds; the stability window is 60 seconds; five failures in ten minutes open the circuit; ten stable minutes reset it.
- Shutdown asks the known child to stop, waits at most ten seconds, then terminates only the live `ProcessHandle` and its returned descendants.
- The renderer listener stays loopback-only and authenticated.
- Runtime errors never disable Paper, broaden a bind address, reveal a secret, or delete administrator state.
- All installation, file, process, pipe, wait, diagnostic, and cleanup work runs off the Paper main thread.
- Preserve existing commands, aliases, permissions, zones, stages, config comments, and lifecycle ordering unless this plan explicitly changes them.

## File Structure

### Supervisor core

- `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/SupervisorState.java` — closed lifecycle enum.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeLaunch.java` — version, entrypoint, environment, nonce, generation, and expected API.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/ManagedRuntimeProcess.java` — minimal process abstraction.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeProcessLauncher.java` — production `ProcessBuilder` adapter.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeReady.java` — authenticated readiness value.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeHealth.java` — process/renderer/ingress/event-loop/render-loop health snapshot.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RestartPolicy.java` — jittered backoff and circuit calculations.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeSupervisor.java` — serialized generation-aware state machine.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/RuntimeSupervisorTest.java` — deterministic fake-clock/process state tests.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/RuntimeProcessLauncherTest.java` — argument, environment, pipe, and shutdown tests using a Java fixture child.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/FixtureChildProcess.java` — test-only child main.

### Renderer control contract

- `protocol/schemas/messages/runtime-ready.schema.json` — post-auth readiness message.
- `protocol/schemas/messages/runtime-health.schema.json` — bounded periodic health message.
- `protocol/schemas/messages/runtime-shutdown.schema.json` — plugin-to-runtime graceful stop request.
- `protocol/schemas/messages/runtime-performance.schema.json` — plugin-to-runtime load-shedding/profile instruction.
- `protocol/schemas/index.json` — registers the four messages.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/control/RuntimeControlChannel.java` — current-launch readiness/health sink and outbound control sender.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/control/RuntimeControlMessageHandler.java` — strict bounded message parsing after renderer authentication.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/control/RuntimeControlMessageHandlerTest.java` — auth, nonce, generation, limits, and routing tests.
- `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java` — delegates runtime control only after existing auth succeeds.
- `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java` — excludes runtime-control messages from tick data routing.

### Paper integration

- `minecraft_plugin/src/main/java/com/audioviz/runtime/config/RuntimeConfig.java` — validated installer/supervisor configuration.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/config/RuntimeConfigLoader.java` — config parsing and safe patch migration.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/config/ReleaseDescriptorLoader.java` — loads Maven-filtered embedded descriptor and production trust key.
- `minecraft_plugin/src/main/resources/runtime/release-descriptor.properties` — Maven-filtered product/runtime API, origin, limits, key ID, and Base64 public key properties; the release profile fails if any value is absent.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/config/RuntimeConfigLoaderTest.java` — bounds and byte-preserving migration tests.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/config/ReleaseDescriptorLoaderTest.java` — missing/test/production descriptor policy.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/PaperRuntimeManager.java` — owns installer/supervisor executor and plugin-facing lifecycle.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeStatusSnapshot.java` — secret-free immutable command/diagnostic status.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeDiagnostics.java` — bounded redacted support bundle.
- `minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimePerformanceController.java` — TPS hysteresis and outbound performance level.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/PaperRuntimeManagerTest.java` — manager orchestration and main-thread boundary tests.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/RuntimeDiagnosticsTest.java` — redaction and containment tests.
- `minecraft_plugin/src/test/java/com/audioviz/runtime/RuntimePerformanceControllerTest.java` — exact threshold/time restoration tests.
- `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java` — constructs, starts, and stops `PaperRuntimeManager` in the verified order.
- `minecraft_plugin/src/main/java/com/audioviz/commands/RuntimeCommandHandler.java` — status/check/retry/rollback/diagnostics subcommands.
- `minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java` — delegates runtime subcommands and tab completion.
- `minecraft_plugin/src/test/java/com/audioviz/commands/RuntimeCommandHandlerTest.java` — permissions, confirmation nonce, and secret-free output.
- `minecraft_plugin/src/main/resources/config.yml` — installer and performance defaults.
- `minecraft_plugin/src/main/resources/plugin.yml` — exact sub-permissions.
- `minecraft_plugin/pom.xml` — release descriptor properties/profile and package coverage.

---

### Task 1: Implement the deterministic process supervisor

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/SupervisorState.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeLaunch.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/ManagedRuntimeProcess.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeReady.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeHealth.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RestartPolicy.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeSupervisor.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/RuntimeSupervisorTest.java`

**Interfaces:**
- Consumes: `RuntimeStore`, an injected `RuntimeProcessLauncher`, `ScheduledExecutorService`, `Clock`, jitter source, and event sink.
- Produces: `start(InstalledRuntime)`, `ready(RuntimeReady)`, `health(RuntimeHealth)`, `processExited(long generation, int exitCode)`, `retry()`, `rollback()`, `stop(Duration)`, and `snapshot() -> SupervisorSnapshot`.

- [ ] **Step 1: Write failing state-transition tests**

Use a manual scheduler and fake clock. Assert exact transitions and single ownership:

```java
@Test
void candidateBecomesLastKnownGoodOnlyAfterReadyAndStabilityWindow() {
    supervisor.start(candidate);
    assertEquals(STARTING, supervisor.snapshot().state());
    FakeProcess process = launcher.onlyProcess();

    supervisor.ready(ready(process.launch().generation(), process.launch().nonce(), 1));
    assertEquals(READY, supervisor.snapshot().state());
    assertNotEquals(candidate.id(), store.lastKnownGood().orElseThrow().id());

    scheduler.advance(Duration.ofSeconds(59));
    assertNotEquals(candidate.id(), store.lastKnownGood().orElseThrow().id());
    scheduler.advance(Duration.ofSeconds(1));
    assertEquals(candidate.id(), store.lastKnownGood().orElseThrow().id());
}
```

Cover wrong nonce/API/generation, readiness timeout, process exit before/after readiness, health failure during the candidate window, duplicate exit callbacks, stale scheduled work, retry delays `1,2,4,8,15,30`, jitter bounds, five failures/ten-minute circuit, ten-minute reset, retry, rollback, disable during wait/start/backoff, and no concurrent launch.

- [ ] **Step 2: Run the focused test and confirm missing supervisor classes**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeSupervisorTest test
```

Expected: FAIL during compilation.

- [ ] **Step 3: Implement the closed state and event model**

Use the spec enum exactly:

```java
public enum SupervisorState {
    DISABLED, CHECKING, DOWNLOADING, VERIFYING, STAGING, STARTING,
    READY, DEGRADED, BACKING_OFF, ROLLING_BACK, FAILED, STOPPING
}
```

All public methods enqueue one coordinator action; only the coordinator thread mutates state. Every action captures `long generation`; scheduled callbacks compare it before doing work. `SupervisorSnapshot` contains no mutable process, secret, nonce, environment, or local path.

- [ ] **Step 4: Implement restart, circuit, readiness, stability, and rollback behavior**

`RestartPolicy` retains failure instants in a bounded deque of five and calculates delay by attempt index. Jitter is `base * uniform(0.8, 1.2)` with an injected deterministic source. The supervisor stores the candidate separately from last known good, calls `RuntimeStore.markHealthy()` only after the 60-second window, and calls `RuntimeStore.rollbackTarget(pluginApi)` on candidate failure.

Stop is idempotent. It invalidates the generation before requesting shutdown so late readiness/exit callbacks cannot restart. Tests must prove each fake process receives at most one graceful stop, one destroy, and one forced destroy.

- [ ] **Step 5: Run supervisor and runtime-foundation tests**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeSupervisorTest,RuntimeInstallerTest,RuntimeStoreTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the state machine**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/RuntimeSupervisorTest.java
git add -- minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/RuntimeSupervisorTest.java
git commit -m "feat(plugin): supervise VJ runtime lifecycle"
```

### Task 2: Launch and terminate one secret-safe child process

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeProcessLauncher.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/RuntimeProcessLauncherTest.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/FixtureChildProcess.java`

**Interfaces:**
- Consumes: `RuntimeLaunch` with a verified entrypoint, owned working directory, allowlisted environment, generation, nonce, and state paths.
- Produces: `RuntimeProcessLauncher.launch(RuntimeLaunch) -> ManagedRuntimeProcess`, whose process exposes `pid()`, `onExit()`, `isAlive()`, `requestShutdown()`, and `terminate(Duration graceful, Duration normal)`.

- [ ] **Step 1: Write failing command/environment/pipe tests**

Launch a Java fixture child using the same process adapter. It writes received argument names and an allowlisted subset of environment keys, emits more than one OS pipe buffer to stdout/stderr, spawns an optional descendant, and waits for a shutdown marker.

```java
@Test
void secretsAreEnvironmentOnlyAndInheritedEnvironmentIsAllowlisted() throws Exception {
    ManagedRuntimeProcess process = launcher.launch(launchFixture());
    FixtureReport report = awaitReport();
    assertFalse(report.arguments().stream().anyMatch(value -> value.contains(RENDERER_SECRET)));
    assertEquals(RENDERER_SECRET, report.environment().get("MCAV_RENDERER_SECRET"));
    assertFalse(report.environment().containsKey("AWS_SECRET_ACCESS_KEY"));
    assertEquals("1", report.environment().get("OPENBLAS_NUM_THREADS"));
}
```

Test paths with spaces, Windows `.exe`, POSIX executable, non-executable entrypoint, missing working directory, output redaction, 1 MiB burst output without deadlock, graceful exit, normal destroy, forced destroy, and descendant termination from the live handle.

- [ ] **Step 2: Run the focused test and confirm missing launcher**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeProcessLauncherTest test
```

Expected: FAIL during compilation.

- [ ] **Step 3: Implement direct `ProcessBuilder` launch**

Build arguments from fixed literals and nonsecret validated paths:

```java
List<String> command = List.of(
    launch.entrypoint().toString(),
    "--managed-by-paper",
    "--state-dir", launch.stateDirectory().toString(),
    "--public-host", launch.publicHost(),
    "--public-port", Integer.toString(launch.publicPort())
);
ProcessBuilder builder = new ProcessBuilder(command).directory(launch.workingDirectory().toFile());
Map<String, String> environment = builder.environment();
environment.clear();
environment.putAll(baseOsEnvironment());
environment.putAll(launch.secretEnvironment());
environment.put("OMP_NUM_THREADS", "1");
environment.put("OPENBLAS_NUM_THREADS", "1");
environment.put("MKL_NUM_THREADS", "1");
environment.put("NUMEXPR_NUM_THREADS", "1");
environment.put("VECLIB_MAXIMUM_THREADS", "1");
```

The base OS allowlist contains only `PATH`, Windows `SystemRoot`, `WINDIR`, `TEMP`, `TMP`, POSIX `LANG`, `LC_ALL`, and proxy values with credentials rejected. Use two bounded line readers with 8 KiB maximum line, rate-limited forwarding, and secret/path redaction. Overlong lines are truncated with a reason code.

- [ ] **Step 4: Implement graceful and forced termination**

`requestShutdown()` sends the control-channel request through an injected callback; it does not write a secret to stdin. `terminate()` waits the graceful duration, calls `destroy()`, waits two seconds, takes a snapshot from `process.descendants()`, terminates those live handles, then calls `destroyForcibly()` only on the known process tree. It never reads a PID from disk to select a process.

- [ ] **Step 5: Run launcher, supervisor, full plugin, and SpotBugs tests**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeProcessLauncherTest,RuntimeSupervisorTest test
.\mvnw.cmd -q test
.\mvnw.cmd -q spotbugs:check
```

Expected: PASS and no live `FixtureChildProcess` remains after the test JVM exits.

- [ ] **Step 6: Commit process launch**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor
git add -- minecraft_plugin/src/main/java/com/audioviz/runtime/supervisor/RuntimeProcessLauncher.java minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/RuntimeProcessLauncherTest.java minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/FixtureChildProcess.java
git commit -m "feat(plugin): launch managed VJ process safely"
```

### Task 3: Add the authenticated runtime-control protocol

**Files:**
- Create: `protocol/schemas/messages/runtime-ready.schema.json`
- Create: `protocol/schemas/messages/runtime-health.schema.json`
- Create: `protocol/schemas/messages/runtime-shutdown.schema.json`
- Create: `protocol/schemas/messages/runtime-performance.schema.json`
- Modify: `protocol/schemas/index.json`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/control/RuntimeControlChannel.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/control/RuntimeControlMessageHandler.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/control/RuntimeControlMessageHandlerTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerRoutingTest.java`

**Interfaces:**
- Consumes: existing renderer authentication state and current launch generation/nonce from `RuntimeControlChannel`.
- Produces: `RuntimeReady` and `RuntimeHealth` callbacks to the supervisor; `sendShutdown(generation)` and `sendPerformance(RuntimePerformanceLevel)` outbound JSON on the authenticated runtime connection.

- [ ] **Step 1: Write failing schema and routing tests**

Use exact readiness payload:

```json
{
  "type": "runtime_ready",
  "v": "1.0.0",
  "release_version": "1.2.0",
  "runtime_api": 1,
  "generation": 42,
  "launch_nonce": "43-base64url-characters-without-padding",
  "pid": 1234,
  "capabilities": ["unified-ingress.v1", "sbe.v1"]
}
```

Health contains generation, nonce, monotonic sequence, ingress/event-loop/render-loop booleans, last-render age milliseconds capped at 60,000, and bounded queue depths. Tests must prove unauthenticated, browser-origin, wrong-connection, wrong-nonce, stale-generation, oversized, duplicate-field, unknown-field, invalid-capability, and replayed health messages never reach the supervisor.

- [ ] **Step 2: Run protocol and focused plugin tests; confirm failure**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeControlMessageHandlerTest,VizWebSocketServerRoutingTest test
```

Expected: FAIL because runtime control classes and schemas are absent.

- [ ] **Step 3: Implement strict runtime-control parsing and current-connection binding**

Parse with the existing bounded Gson boundary but explicitly reject unknown/duplicate fields using the `StrictJson` streaming helper. `VizWebSocketServer` calls the runtime control handler only after `ClientInfo` is authenticated and only for the connection selected by `RuntimeControlChannel.expect(generation, nonce)`. Control messages never enter `MessageQueue` or Bukkit scheduling.

Use constant-time nonce comparison. Health sequence must strictly increase per connection. On disconnect, notify the supervisor for the current generation once.

- [ ] **Step 4: Implement bounded outbound control messages**

`runtime_shutdown` contains only type, generation, and reason code. `runtime_performance` contains generation, level (`NORMAL`, `PREVIEW_REDUCED`, `FPS_REDUCED`, `SAFE`), target FPS, entity budget, and particle-enabled boolean. Send only on the authenticated current connection; failure reports degraded health and never throws into the WebSocket selector.

- [ ] **Step 5: Run contract, routing, auth, and full plugin tests**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeControlMessageHandlerTest,VizWebSocketServerRoutingTest,VizWebSocketServerAuthTest test
.\mvnw.cmd -q test
Set-Location ..
wsl.exe bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz && python3 -m json.tool protocol/schemas/index.json >/dev/null'
```

Expected: PASS.

- [ ] **Step 6: Commit the control protocol**

```powershell
git status --short
git diff -- protocol/schemas minecraft_plugin/src/main/java/com/audioviz/runtime/control minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java
git add -- protocol/schemas/messages/runtime-ready.schema.json protocol/schemas/messages/runtime-health.schema.json protocol/schemas/messages/runtime-shutdown.schema.json protocol/schemas/messages/runtime-performance.schema.json protocol/schemas/index.json minecraft_plugin/src/main/java/com/audioviz/runtime/control minecraft_plugin/src/test/java/com/audioviz/runtime/control minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerRoutingTest.java
git commit -m "feat(protocol): authenticate runtime lifecycle control"
```

### Task 4: Load and migrate installer configuration safely

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/config/RuntimeConfig.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/config/RuntimeConfigLoader.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/config/ReleaseDescriptorLoader.java`
- Create: `minecraft_plugin/src/main/resources/runtime/release-descriptor.properties`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/config/RuntimeConfigLoaderTest.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/config/ReleaseDescriptorLoaderTest.java`
- Modify: `minecraft_plugin/src/main/resources/config.yml`
- Modify: `minecraft_plugin/pom.xml`

**Interfaces:**
- Consumes: Paper `FileConfiguration`, raw `config.yml` bytes for patch migration, Maven-filtered embedded descriptor properties, and plugin data directory.
- Produces: `RuntimeConfigLoader.load(FileConfiguration, Path dataDirectory) -> RuntimeConfig`, `migrate(Path configFile) -> MigrationResult`, and `ReleaseDescriptorLoader.load(ClassLoader) -> ReleaseDescriptor`.

- [ ] **Step 1: Write failing default, bounds, and byte-preservation tests**

Tests assert the exact YAML defaults from the spec and reject invalid channel, public bind, port, URL, timeout, retention, profile, entity, FPS, TLS path, and nonloopback renderer bind. Migration starts from a fixture containing comments and unrelated zone keys; compare every unowned line byte for byte after patch.

```java
@Test
void migrationAddsOnlyRuntimeBlocksAndPreservesUnownedBytes() throws Exception {
    byte[] before = fixture("legacy-config-with-comments.yml");
    Files.write(config, before);
    MigrationResult result = loader.migrate(config);
    assertTrue(result.changed());
    assertEquals(unownedLines(before), unownedLines(Files.readAllBytes(config)));
    assertArrayEquals(before, Files.readAllBytes(result.backup()));
}
```

Descriptor tests assert a release-mode build rejects blank URL/key ID/key bytes, a test key ID, HTTP origin, or runtime API mismatch. A test-mode descriptor can be injected only through test construction, not server configuration.

- [ ] **Step 2: Run focused tests and confirm missing loaders**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeConfigLoaderTest,ReleaseDescriptorLoaderTest test
```

Expected: FAIL during compilation.

- [ ] **Step 3: Add exact defaults and a line-preserving migrator**

Append the approved `runtime` and `performance` blocks to `config.yml`. The migrator identifies only top-level `runtime:` and installer-owned `performance` keys by indentation-aware line scanning. It creates `config.yml.backup-<UTC timestamp>` with `CREATE_NEW`, writes the patched file through same-directory temp/force/atomic replace, and fails rather than serializing the entire YAML when structure is ambiguous.

Validate `public-host` as `0.0.0.0`, `::`, or an explicit local interface; renderer `websocket.address` remains only `127.0.0.1`, `localhost`, or `::1`. Normalize but never log configured certificate/private-key paths.

- [ ] **Step 4: Add filtered descriptor build policy**

The source resource contains Maven tokens:

```properties
product.version=${project.version}
runtime.api=1
manifest.url=${runtime.manifest.url}
minimum.generation=${runtime.minimum.generation}
signing.key.id=${runtime.signing.key.id}
signing.public.key=${runtime.signing.public.key}
allowed.hosts=${runtime.allowed.hosts}
release.mode=${runtime.release.mode}
```

Normal development properties set `release.mode=false` and leave installer startup disabled unless tests inject a descriptor. Add Maven profile `production-release` requiring all runtime properties and `release.mode=true`; use `maven-enforcer-plugin` to fail on missing properties or `test-only-` key IDs. The final release plan supplies the production values.

- [ ] **Step 5: Run config, package, and existing lifecycle tests**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeConfigLoaderTest,ReleaseDescriptorLoaderTest,AudioVizPluginLifecycleTest test
.\mvnw.cmd -q package
```

Expected: PASS. Confirm the development JAR cannot start remote installation because `release.mode=false`.

- [ ] **Step 6: Commit configuration boundaries**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/runtime/config minecraft_plugin/src/test/java/com/audioviz/runtime/config minecraft_plugin/src/main/resources minecraft_plugin/pom.xml
git add -- minecraft_plugin/src/main/java/com/audioviz/runtime/config minecraft_plugin/src/test/java/com/audioviz/runtime/config minecraft_plugin/src/main/resources/config.yml minecraft_plugin/src/main/resources/runtime/release-descriptor.properties minecraft_plugin/pom.xml
git commit -m "feat(plugin): configure managed VJ runtime"
```

### Task 5: Integrate runtime management into plugin enable and disable

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/PaperRuntimeManager.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeStatusSnapshot.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/PaperRuntimeManagerTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/AudioVizPluginLifecycleTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`

**Interfaces:**
- Consumes: Task 1-4 foundation/supervisor/control/config objects, plugin logger, data directory, renderer secret, and a dedicated executor/scheduler.
- Produces: `start()`, `stop()`, `check()`, `retry()`, `rollback(RuntimeVersionId)`, `status()`, `onRuntimeReady()`, `onRuntimeHealth()`, and `onRendererDisconnect()` for commands/control integration.

- [ ] **Step 1: Write failing manager and lifecycle-order tests**

Prove `start()` returns without running installer work on the calling thread, status publishes immutable snapshots, concurrent commands serialize, and disable invalidates late install/ready callbacks. Update plugin lifecycle ordering:

```java
@Test
void disableStopsRuntimeBeforeRendererAndSubsystemCleanup() throws Exception {
    AudioVizPlugin plugin = mock(AudioVizPlugin.class, CALLS_REAL_METHODS);
    PaperRuntimeManager runtime = mock(PaperRuntimeManager.class);
    WebSocketStartupManager<VizWebSocketServer> websocket = mock(WebSocketStartupManager.class);
    MetricsDisplay metrics = mock(MetricsDisplay.class);
    setField(plugin, "runtimeManager", runtime);
    setField(plugin, "webSocketStartupManager", websocket);
    setField(plugin, "metricsDisplay", metrics);
    plugin.onDisable();
    InOrder order = inOrder(runtime, websocket, metrics);
    order.verify(runtime).stop();
    order.verify(websocket).stop();
    order.verify(metrics).stop();
}
```

- [ ] **Step 2: Run focused tests and confirm missing manager integration**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=PaperRuntimeManagerTest,AudioVizPluginLifecycleTest test
```

Expected: FAIL because `PaperRuntimeManager` is missing.

- [ ] **Step 3: Implement the manager coordinator and status publication**

Use one named single-thread executor `AudioViz-Runtime-Coordinator`, one bounded scheduled executor, and two bounded log-reader workers owned by the manager. `start()` captures validated config and queues recover/install/launch. It returns before network or disk access. Events update an `AtomicReference<RuntimeStatusSnapshot>` containing only enum state, versions, API, timestamps, public host/port, safe reason code, retry time, renderer connected, and health counters.

When `release.mode=false`, status is `DISABLED` with reason `DEVELOPMENT_DESCRIPTOR`; the existing externally started VJ path still works through the renderer listener.

- [ ] **Step 4: Wire renderer readiness and plugin lifecycle**

Construct the manager after default config and loopback renderer secret exist. Attach its control channel before accepting VJ traffic. Queue manager start after the renderer listener begins its startup attempt; readiness can arrive whenever authentication completes.

During disable, call `runtimeManager.stop()` before closing the renderer listener so the graceful shutdown message can be sent. The manager performs its bounded process wait and returns before remaining entity cleanup.

- [ ] **Step 5: Run manager, lifecycle, auth, and full plugin tests**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=PaperRuntimeManagerTest,AudioVizPluginLifecycleTest,VizWebSocketServerAuthTest,RuntimeSupervisorTest test
.\mvnw.cmd -q verify
```

Expected: PASS with no leaked runtime threads after tests.

- [ ] **Step 6: Commit plugin integration**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java minecraft_plugin/src/main/java/com/audioviz/runtime/PaperRuntimeManager.java minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeStatusSnapshot.java minecraft_plugin/src/test/java/com/audioviz/runtime/PaperRuntimeManagerTest.java minecraft_plugin/src/test/java/com/audioviz/AudioVizPluginLifecycleTest.java minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java
git add -- minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java minecraft_plugin/src/main/java/com/audioviz/runtime/PaperRuntimeManager.java minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeStatusSnapshot.java minecraft_plugin/src/test/java/com/audioviz/runtime/PaperRuntimeManagerTest.java minecraft_plugin/src/test/java/com/audioviz/AudioVizPluginLifecycleTest.java minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java
git commit -m "feat(plugin): manage VJ runtime from Paper"
```

### Task 6: Add operator commands, rollback confirmation, and diagnostics

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/commands/RuntimeCommandHandler.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/commands/RuntimeCommandHandlerTest.java`
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeDiagnostics.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/RuntimeDiagnosticsTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java`
- Modify: `minecraft_plugin/src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: `PaperRuntimeManager` methods/status, command sender UUID/name/console identity, injected clock/random source, plugin config, and bounded event/log snapshots.
- Produces: `/audioviz status`, a fail-closed `setup` response until the next plan supplies onboarding, `runtime check|retry|rollback`, and `diagnostics`; a ZIP support bundle below `plugins/AudioViz/runtime/diagnostics/`.

- [ ] **Step 1: Write failing permission, output, confirmation, and redaction tests**

Test each exact permission, console behavior, aliases, tab completion, unavailable actions, concurrent action messages, and no secret/path leakage. Rollback is two-step:

```java
@Test
void rollbackRequiresSenderBoundExpiringConfirmation() {
    handler.execute(sender, new String[]{"runtime", "rollback"});
    String nonce = messages.lastConfirmationNonce();
    handler.execute(otherSender, new String[]{"runtime", "rollback", "confirm", nonce});
    verify(manager, never()).rollback(any());
    clock.advance(Duration.ofSeconds(31));
    handler.execute(sender, new String[]{"runtime", "rollback", "confirm", nonce});
    verify(manager, never()).rollback(any());
}
```

Diagnostics tests seed secrets in config, environment, errors, URLs, paths, logs, auth, and TLS data; unzip the bundle and assert none appear. Assert every entry stays below the diagnostics root and total uncompressed bytes stay below 5 MiB.

- [ ] **Step 2: Run focused tests and confirm missing command handler**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeCommandHandlerTest,RuntimeDiagnosticsTest test
```

Expected: FAIL during compilation.

- [ ] **Step 3: Implement focused command delegation**

Keep `AudioVizCommand` as routing only:

```java
case "status" -> runtimeCommands.status(sender);
case "runtime" -> runtimeCommands.runtime(sender, Arrays.copyOfRange(args, 1, args.length));
case "diagnostics" -> runtimeCommands.diagnostics(sender);
case "setup" -> runtimeCommands.setupUnavailableUntilOnboardingPlan(sender);
```

Add `audioviz.status` default `op`, `audioviz.admin.runtime.check`, `.retry`, `.rollback`, `audioviz.admin.diagnostics`, and `audioviz.admin.setup`, all default `op`. `audioviz.admin` continues to grant all through explicit permission children in `plugin.yml`.

Confirmation nonce is 128 random bits, Base64 URL without padding, bound to sender identity and exact target, single use, and expires after 30 seconds. Store at most 32 outstanding confirmations.

- [ ] **Step 4: Implement bounded diagnostics**

Create a unique ZIP through a temporary file and atomic move. Include `summary.json`, `config-redacted.yml`, `lifecycle.jsonl`, `logs.txt`, and `manifest-summary.json`. Use allowlisted fields rather than regex-only redaction. Replace usernames/player IDs with counts, paths with owned relative names, and all exception text with reason code plus exception class.

- [ ] **Step 5: Run command, diagnostics, existing command, and full tests**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimeCommandHandlerTest,RuntimeDiagnosticsTest test
.\mvnw.cmd -q verify
```

Expected: PASS. Manually inspect one test bundle manifest to confirm the five allowlisted entries only.

- [ ] **Step 6: Commit operator controls**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/commands minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeDiagnostics.java minecraft_plugin/src/test/java/com/audioviz/commands minecraft_plugin/src/test/java/com/audioviz/runtime/RuntimeDiagnosticsTest.java minecraft_plugin/src/main/resources/plugin.yml
git add -- minecraft_plugin/src/main/java/com/audioviz/commands/RuntimeCommandHandler.java minecraft_plugin/src/main/java/com/audioviz/commands/AudioVizCommand.java minecraft_plugin/src/test/java/com/audioviz/commands/RuntimeCommandHandlerTest.java minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeDiagnostics.java minecraft_plugin/src/test/java/com/audioviz/runtime/RuntimeDiagnosticsTest.java minecraft_plugin/src/main/resources/plugin.yml
git commit -m "feat(plugin): expose runtime recovery controls"
```

### Task 7: Add TPS-aware bounded performance signaling

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimePerformanceController.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/runtime/RuntimePerformanceControllerTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/runtime/PaperRuntimeManager.java`
- Modify: `minecraft_plugin/src/main/resources/config.yml`

**Interfaces:**
- Consumes: one TPS sample per second, validated fixed/auto profile, current configured FPS/entity/particle limits, monotonic clock, and `RuntimeControlChannel`.
- Produces: `sample(double tps, Instant now) -> Optional<RuntimePerformanceInstruction>` with levels `NORMAL`, `PREVIEW_REDUCED`, `FPS_REDUCED`, and `SAFE`.

- [ ] **Step 1: Write failing threshold and hysteresis tests**

Use one-second samples and exact thresholds:

```java
@Test
void degradesAndRecoversOneLevelAtATime() {
    sampleFor(Duration.ofSeconds(10), 18.9);
    assertEquals(PREVIEW_REDUCED, controller.level());
    sampleFor(Duration.ofSeconds(10), 18.4);
    assertEquals(FPS_REDUCED, controller.level());
    sampleFor(Duration.ofSeconds(10), 17.9);
    assertEquals(SAFE, controller.level());
    sampleFor(Duration.ofSeconds(60), 19.6);
    assertEquals(FPS_REDUCED, controller.level());
}
```

Test boundary equality, flapping, missing/NaN/infinite samples, fixed profiles, disconnected runtime, no duplicate sends, and maximum/minimum FPS/entity/particle values.

- [ ] **Step 2: Run focused tests and confirm missing controller**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimePerformanceControllerTest test
```

Expected: FAIL during compilation.

- [ ] **Step 3: Implement the four-level controller**

Track continuous threshold duration, reset a degradation timer when TPS returns above that level's threshold, and require 60 continuous seconds above 19.5 for each recovery step. Map instructions:

```java
NORMAL          -> configured FPS, configured entities, particles enabled, preview full
PREVIEW_REDUCED -> configured FPS, configured entities, particles enabled, preview 5 FPS
FPS_REDUCED     -> min(configured FPS, 10), configured entities, particles clamped, preview 5 FPS
SAFE            -> min(configured FPS, 10), min(configured entities, 80), particles disabled, preview 2 FPS
```

Send only changed instructions and expose level/durations in status and diagnostics. Do not persist automatic level changes into `config.yml`.

- [ ] **Step 4: Schedule supported Paper TPS sampling**

Use a synchronous once-per-second Bukkit task only to read `Bukkit.getTPS()[0]` and enqueue the numeric sample to the runtime coordinator. Perform no logging, JSON, networking, or process calls in that task. Cancel it before manager shutdown.

- [ ] **Step 5: Run performance-controller, manager, and full verification**

Run:

```powershell
Set-Location minecraft_plugin
.\mvnw.cmd -q -Dtest=RuntimePerformanceControllerTest,PaperRuntimeManagerTest,AudioVizPluginLifecycleTest test
.\mvnw.cmd -q verify
.\mvnw.cmd -q spotbugs:check
```

Expected: PASS.

- [ ] **Step 6: Commit performance signaling**

```powershell
git status --short
git diff -- minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimePerformanceController.java minecraft_plugin/src/test/java/com/audioviz/runtime/RuntimePerformanceControllerTest.java minecraft_plugin/src/main/java/com/audioviz/runtime/PaperRuntimeManager.java minecraft_plugin/src/main/resources/config.yml
git add -- minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimePerformanceController.java minecraft_plugin/src/test/java/com/audioviz/runtime/RuntimePerformanceControllerTest.java minecraft_plugin/src/main/java/com/audioviz/runtime/PaperRuntimeManager.java minecraft_plugin/src/main/resources/config.yml
git commit -m "feat(plugin): shed VJ load when Paper slows"
```

## Plan Completion Evidence

Before starting the VJ product-surface plan, record:

- all task commit hashes and `git diff`/status scope;
- supervisor transition/circuit/rollback coverage;
- proof of no secret in process arguments, logs, status, command output, or diagnostics;
- proof no test child/descendant remains alive;
- authenticated readiness/replay test results;
- config migration byte-preservation fixtures;
- full Maven verify, SpotBugs, and JaCoCo results; and
- a MockBukkit lifecycle test proving manager stop precedes renderer shutdown.

This plan is complete when a test descriptor and fake sidecar can be installed, launched, authenticated, marked healthy, crash/restart, enter a circuit, retry, and roll back through real plugin lifecycle/commands without Python or a public ingress implementation.
