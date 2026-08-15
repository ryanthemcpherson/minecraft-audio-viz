# Paper 26.2 Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a Java 25 Paper 26.2 plugin that is secure on first run, retains the stable JSON VJ path, degrades safely, exposes bounded-queue evidence, and cleans up predictably through reconnect and shutdown.

**Architecture:** The existing plugin is source-compatible with Paper 26.2, proven by the no-edit dependency override probe. The port therefore changes release metadata and adds focused pure-Java lifecycle/security components around the established WebSocket, queue, entity-pool, and VJ rehydration paths instead of restructuring the renderer.

**Tech Stack:** Java 25, Maven Wrapper 3.9.6, Paper API 26.2, JUnit 5, Mockito, Java-WebSocket, Gson, Python 3.12, pytest

**Spec:** `docs/superpowers/specs/2026-08-15-paper-26-2-release-design.md`

## Global Constraints

- Use `io.papermc.paper:paper-api:26.2.build.112-stable`, Java release 25, plugin API `26.2`, and version `1.1.0`.
- The final JAR name is `mcav-paper-1.1.0.jar`.
- Do not add a 1.21 compatibility layer or merge the low-latency binary worktree.
- Retain JSON WebSocket protocol compatibility and the source-installed VJ server.
- Bind only to loopback; never log or return the WebSocket secret.
- All Bukkit entity/world mutations stay on the main thread.
- Preserve saved zones and stages when transient entities are cleared.
- Existing tests are migrated, not deleted to hide incompatibility.
- Use test-driven development and one logical conventional commit per task.

---

### Task 1: Lock Paper 26.2, Java 25, and release artifact metadata

**Files:**
- Create: `minecraft_plugin/src/test/java/com/audioviz/ReleaseMetadataTest.java`
- Modify: `minecraft_plugin/pom.xml`
- Modify: `minecraft_plugin/src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: current Maven wrapper and resource filtering
- Produces: `target/mcav-paper-1.1.0.jar` containing `plugin.yml` with version `1.1.0` and API `26.2`

- [ ] **Step 1: Write the failing release metadata test**

  ```java
  package com.audioviz;

  import org.junit.jupiter.api.Test;

  import java.io.IOException;
  import java.io.InputStream;
  import java.nio.charset.StandardCharsets;

  import static org.junit.jupiter.api.Assertions.assertNotNull;
  import static org.junit.jupiter.api.Assertions.assertTrue;

  class ReleaseMetadataTest {
      @Test
      void filteredPluginMetadataTargetsPaper262() throws IOException {
          try (InputStream stream = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
              assertNotNull(stream);
              String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
              assertTrue(metadata.contains("version: 1.1.0"));
              assertTrue(metadata.contains("api-version: '26.2'"));
              assertTrue(metadata.contains("Paper 26.2"));
          }
      }
  }
  ```

- [ ] **Step 2: Run the test and verify failure**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw -Dtest=ReleaseMetadataTest test'
  ```

  Expected: FAIL because the filtered version is `1.0.0-SNAPSHOT`, API is `1.21`, and description does not state Paper 26.2.

- [ ] **Step 3: Update Maven release properties**

  Use these exact values:

  ```xml
  <version>1.1.0</version>
  <properties>
      <maven.compiler.release>25</maven.compiler.release>
      <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
      <paper.version>26.2.build.112-stable</paper.version>
      <mockito.version>5.23.0</mockito.version>
  </properties>
  ```

  Set compiler `<release>${maven.compiler.release}</release>`, set `<finalName>mcav-paper-${project.version}</finalName>`, and add both shade settings:

  ```xml
  <createDependencyReducedPom>false</createDependencyReducedPom>
  <useDependencyReducedPomInJar>false</useDependencyReducedPomInJar>
  ```

  Remove unused direct test dependencies `mockbukkit-v1.21`, `byte-buddy`, and `byte-buddy-agent`; repository search proves no project source imports them. Mockito retains its own tested transitive Byte Buddy versions.

- [ ] **Step 4: Configure Mockito as the Java agent for Java 25 tests**

  Add to Surefire before the existing `--add-opens` entries:

  ```xml
  -javaagent:${settings.localRepository}/org/mockito/mockito-core/${mockito.version}/mockito-core-${mockito.version}.jar
  ```

  This removes runtime self-attachment and future-proofs tests against dynamic-agent loading being disabled.

- [ ] **Step 5: Update plugin metadata**

  ```yaml
  name: AudioViz
  version: ${project.version}
  main: com.audioviz.AudioVizPlugin
  api-version: '26.2'
  description: Real-time audio visualization for Paper 26.2 using Display Entities
  ```

- [ ] **Step 6: Run metadata, full tests, and package checks**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw clean verify'
  Test-Path minecraft_plugin/target/mcav-paper-1.1.0.jar
  Test-Path minecraft_plugin/dependency-reduced-pom.xml
  ```

  Expected: 178 existing tests plus `ReleaseMetadataTest` pass; final JAR exists; source-tree reduced POM prints `False`.

- [ ] **Step 7: Commit**

  ```powershell
  git add minecraft_plugin/pom.xml minecraft_plugin/src/main/resources/plugin.yml minecraft_plugin/src/test/java/com/audioviz/ReleaseMetadataTest.java
  git commit -m "feat(plugin): target Paper 26.2 and Java 25"
  ```

---

### Task 2: Make the 256-entity release limit authoritative

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/gui/menus/SettingsMenu.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/protocol/MessageHandlerTest.java`

**Interfaces:**
- Consumes: canonical `performance.max_entities_per_zone` configuration
- Produces: identical command, protocol, GUI, and pool limits with accurate `pool_initialized.count`

- [ ] **Step 1: Write a failing protocol limit test**

  Configure `performance.max_entities_per_zone` as 256, send `init_pool` with count 500, and assert both `initializeBlockPool("main", 256, Material.GLOWSTONE)` and response count 256. Add a second assertion that a configured limit of 64 is honored.

- [ ] **Step 2: Run the focused test and observe failure**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw -Dtest=MessageHandlerTest test'
  ```

  Expected: FAIL because `MessageHandler` currently reads the nonexistent root key `max-entities-per-zone` and reports 500 while `EntityPoolManager` independently caps at 256.

- [ ] **Step 3: Read the canonical nested key everywhere**

  Change `MessageHandler` to:

  ```java
  int maxEntities = plugin.getConfig().getInt("performance.max_entities_per_zone", 256);
  count = Math.max(0, Math.min(count, maxEntities));
  ```

  Change `SettingsMenu`'s remaining fallback from 100 to 256. Keep `EntityPoolManager` on the same nested key and fallback.

- [ ] **Step 4: Run focused and full tests**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw -Dtest=MessageHandlerTest test'
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw test -q'
  ```

- [ ] **Step 5: Commit**

  ```powershell
  git add minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java minecraft_plugin/src/main/java/com/audioviz/gui/menus/SettingsMenu.java minecraft_plugin/src/test/java/com/audioviz/protocol/MessageHandlerTest.java
  git commit -m "fix(plugin): enforce canonical entity limit"
  ```

---

### Task 3: Generate and persist a secure first-run WebSocket secret

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/websocket/WebSocketSecretManager.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/websocket/WebSocketSecretManagerTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/main/resources/config.yml`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/AudioVizPluginLifecycleTest.java`

**Interfaces:**
- Consumes: configured `ws-secret`, `SecureRandom`, Bukkit configuration persistence
- Produces: `WebSocketSecretManager.SecretResolution(String secret, boolean generated)` and a persisted 43-character URL-safe secret before listener startup

- [ ] **Step 1: Write failing pure-Java secret tests**

  Cover null, empty, whitespace-only, existing normalized secret, 32 random bytes, URL-safe Base64 without padding, uniqueness, and no secret in `toString()` output:

  ```java
  @Test
  void blankSecretGeneratesUrlSafeThirtyTwoByteValue() {
      WebSocketSecretManager manager = new WebSocketSecretManager(new SecureRandom());
      var result = manager.resolve("   ");

      assertTrue(result.generated());
      assertEquals(32, Base64.getUrlDecoder().decode(result.secret()).length);
      assertFalse(result.secret().contains("="));
      assertTrue(result.secret().matches("[A-Za-z0-9_-]{43}"));
      assertFalse(result.toString().contains(result.secret()));
  }

  @Test
  void configuredSecretIsNormalizedWithoutReplacement() {
      var result = new WebSocketSecretManager(new SecureRandom()).resolve("  stable-secret  ");
      assertFalse(result.generated());
      assertEquals("stable-secret", result.secret());
  }
  ```

- [ ] **Step 2: Verify tests fail because the manager does not exist**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw -Dtest=WebSocketSecretManagerTest test'
  ```

- [ ] **Step 3: Implement the focused secret manager**

  ```java
  public final class WebSocketSecretManager {
      private static final int SECRET_BYTES = 32;
      private final SecureRandom secureRandom;

      public WebSocketSecretManager(SecureRandom secureRandom) {
          this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
      }

      public SecretResolution resolve(String configuredSecret) {
          if (configuredSecret != null && !configuredSecret.isBlank()) {
              return new SecretResolution(configuredSecret.strip(), false);
          }
          byte[] bytes = new byte[SECRET_BYTES];
          secureRandom.nextBytes(bytes);
          return new SecretResolution(
              Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
              true
          );
      }

      public record SecretResolution(String secret, boolean generated) {
          @Override
          public String toString() {
              return "SecretResolution[generated=" + generated + "]";
          }
      }
  }
  ```

- [ ] **Step 4: Integrate persistence before manager/listener initialization**

  Immediately after `saveDefaultConfig()`, resolve the secret. When generated, set `ws-secret`, call `saveConfig()`, and log only:

  ```text
  Generated a WebSocket pairing secret in plugins/AudioViz/config.yml. Set MINECRAFT_WS_SECRET to that value before starting the VJ server.
  ```

  If persistence throws, log a sanitized severe message, keep the plugin enabled for diagnostics, and skip WebSocket listener startup. Never pass the secret into a formatted log message or exception.

- [ ] **Step 5: Update lifecycle tests**

  Verify generated-secret persistence occurs before WebSocket construction, a stable existing secret is not rewritten, and a simulated save failure prevents listener startup without throwing from `onEnable`.

- [ ] **Step 6: Update config guidance**

  Change the `ws-secret` comment to state that empty values are generated and persisted on first start; operators copy the value into `MINECRAFT_WS_SECRET`. Keep the YAML default empty so distributed configuration never contains a shared static secret.

- [ ] **Step 7: Run focused and full tests**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw -Dtest=WebSocketSecretManagerTest,AudioVizPluginLifecycleTest,VizWebSocketServerAuthTest,WebSocketSecurityPolicyTest test'
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw test -q'
  ```

- [ ] **Step 8: Commit**

  ```powershell
  git add minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java minecraft_plugin/src/main/java/com/audioviz/websocket/WebSocketSecretManager.java minecraft_plugin/src/main/resources/config.yml minecraft_plugin/src/test/java/com/audioviz/AudioVizPluginLifecycleTest.java minecraft_plugin/src/test/java/com/audioviz/websocket/WebSocketSecretManagerTest.java
  git commit -m "feat(plugin): secure WebSocket pairing by default"
  ```

---

### Task 4: Add disconnect grace and deterministic entity cleanup

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/connection/DisconnectCleanupController.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/connection/DisconnectCleanupControllerTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/connection/ConnectionStateListener.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/connection/ConnectionStateListenerTest.java`
- Modify: `minecraft_plugin/src/main/resources/config.yml`

**Interfaces:**
- Consumes: last-client connect/disconnect callbacks from `VizWebSocketServer`
- Produces: `connected()`, `disconnected()`, and `stop()` lifecycle methods with one generation-safe scheduled cleanup

- [ ] **Step 1: Write failing controller tests**

  Tests use a fake scheduler and verify: disconnect schedules once; reconnect cancels; repeated disconnect replaces the old task; a stale task cannot clean; expiry runs cleanup once; stop cancels and blocks later expiry.

  ```java
  @Test
  void reconnectCancelsPendingCleanup() {
      FakeScheduler scheduler = new FakeScheduler();
      AtomicInteger cleanups = new AtomicInteger();
      DisconnectCleanupController controller =
          new DisconnectCleanupController(scheduler, cleanups::incrementAndGet, 100L);

      controller.disconnected();
      controller.connected();
      scheduler.runAll();

      assertEquals(0, cleanups.get());
  }
  ```

- [ ] **Step 2: Run the focused test and verify failure**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw -Dtest=DisconnectCleanupControllerTest test'
  ```

- [ ] **Step 3: Implement the generation-safe controller**

  Define small nested interfaces:

  ```java
  interface Scheduler {
      ScheduledTask schedule(Runnable action, long delayTicks);
  }

  interface ScheduledTask {
      void cancel();
  }
  ```

  Synchronize generation/pending/stopped state. Run the cleanup outside the synchronized block only when the scheduled generation remains current.

- [ ] **Step 4: Integrate with the existing last-client callbacks**

  `ConnectionStateListener.start()` creates the controller with Bukkit's main-thread `runTaskLater`, `plugin.getEntityPoolManager()::cleanupAllSync`, and the configured grace ticks. `onDjConnect` calls `connected()`. `onDjDisconnect` calls `disconnected()`. `stop()` cancels both the tick task and pending cleanup.

- [ ] **Step 5: Add configuration**

  ```yaml
  connection:
    # Delay before active visualization entities are removed after the last VJ disconnects.
    disconnect_grace_ticks: 100
  ```

  Clamp the value to `[0, 1200]`. Zero performs cleanup on the next main-thread scheduling opportunity.

- [ ] **Step 6: Verify saved state is not deleted**

  In `ConnectionStateListenerTest`, mock `EntityPoolManager` and verify expiry calls only `cleanupAllSync()`. Verify neither `ZoneManager.saveZones()` nor `StageManager.saveStages()` is called by disconnect cleanup.

- [ ] **Step 7: Run focused and full tests**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw -Dtest=DisconnectCleanupControllerTest,ConnectionStateListenerTest,VizWebSocketServerAuthTest test'
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw test -q'
  ```

- [ ] **Step 8: Commit**

  ```powershell
  git add minecraft_plugin/src/main/java/com/audioviz/connection minecraft_plugin/src/main/resources/config.yml minecraft_plugin/src/test/java/com/audioviz/connection
  git commit -m "feat(plugin): clean visuals after disconnect grace"
  ```

---

### Task 5: Make queue saturation explicit and expose structured metrics

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageQueue.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/latency/LatencyTracker.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/protocol/MessageQueueBackpressureTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerRoutingTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerAuthTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/latency/LatencyTrackerTest.java`

**Interfaces:**
- Consumes: parsed/transient queue state and raw parser rejection callbacks
- Produces: `MessageQueue.QueueMetrics`, `server_busy` errors for unadmitted frames, and main-thread duration p95

- [ ] **Step 1: Write failing overload and metric tests**

  Verify raw parser saturation invokes the failure callback with `RejectedExecutionException`, high-frequency parsed updates remain drop-oldest, and structured metrics contain:

  ```java
  public record QueueMetrics(
      long processed,
      long batches,
      long dropped,
      int parsedQueueDepth,
      int rawQueueDepth
  ) { }
  ```

  Add a WebSocket routing assertion that overload returns exactly:

  ```json
  {"type":"error","code":"server_busy","message":"Server is busy; retry control messages"}
  ```

  No payload, token, remote content, or exception stack is returned.

- [ ] **Step 2: Run focused tests and observe failure**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw -Dtest=MessageQueueBackpressureTest,VizWebSocketServerRoutingTest,LatencyTrackerTest test'
  ```

- [ ] **Step 3: Notify instead of silently swallowing raw-executor rejection**

  In `parseAndDispatch`, keep `recordDroppedMessage()` and then call the existing failure handler with:

  ```java
  new RejectedExecutionException("WebSocket parser queue is full")
  ```

  In `VizWebSocketServer.handleMessageFailure`, branch on `RejectedExecutionException`, log one rate-limited warning without a stack trace, and send the stable `server_busy` response. Other parse/handler failures keep sanitized `invalid_message` responses; never return `exception.getMessage()` directly.

- [ ] **Step 4: Add structured queue metrics**

  `MessageQueue.getMetrics()` reads the atomics, `messageQueue.size()`, and `jsonExecutor.getQueue().size()`. `VizWebSocketServer.getMetrics()` adds fields `queueProcessed`, `queueBatches`, `queueDropped`, `parsedQueueDepth`, and `rawQueueDepth`.

- [ ] **Step 5: Record actual main-thread update duration**

  Extend `LatencyTracker` with a `mainThreadUpdateDuration` rolling window and methods:

  ```java
  public void recordMainThreadUpdateDuration(double milliseconds)
  public RollingWindow getMainThreadUpdateStats()
  ```

  Wrap `MessageQueue.processTick()` in `long started = System.nanoTime()` and record elapsed milliseconds in `finally`. Include exact fields `mainThreadUpdateAvgMs`, `mainThreadUpdateP95Ms`, and `mainThreadUpdateMaxMs` in `get_ws_metrics`.

- [ ] **Step 6: Run focused and full tests**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw -Dtest=MessageQueueBackpressureTest,VizWebSocketServerRoutingTest,VizWebSocketServerAuthTest,LatencyTrackerTest test'
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw test -q'
  ```

- [ ] **Step 7: Commit**

  ```powershell
  git add minecraft_plugin/src/main/java/com/audioviz/protocol/MessageQueue.java minecraft_plugin/src/main/java/com/audioviz/websocket/VizWebSocketServer.java minecraft_plugin/src/main/java/com/audioviz/latency/LatencyTracker.java minecraft_plugin/src/test/java/com/audioviz/protocol/MessageQueueBackpressureTest.java minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerRoutingTest.java minecraft_plugin/src/test/java/com/audioviz/websocket/VizWebSocketServerAuthTest.java minecraft_plugin/src/test/java/com/audioviz/latency/LatencyTrackerTest.java
  git commit -m "fix(plugin): report queue overload and render timing"
  ```

---

### Task 6: Prove VJ authentication and reconnect rehydration under the release contract

**Files:**
- Modify: `vj_server/tests/test_viz_client_auth.py`
- Modify: `vj_server/tests/test_reconnect_stage_rehydrate.py`
- Modify if a failing test proves a gap: `vj_server/viz_client.py`
- Modify if a failing test proves a gap: `vj_server/relay.py`

**Interfaces:**
- Consumes: generated plugin secret supplied as `MINECRAFT_WS_SECRET`, existing `auth_required` handshake, reconnect stage state
- Produces: proof that every reconnect reauthenticates before traffic and sends a complete zone snapshot

- [ ] **Step 1: Add a reconnect handshake-order test**

  Use the existing fake WebSocket fixture to assert the order after reconnect is welcome, auth, `auth_ok`, post-handshake setup, then render/control traffic. Assert the stable token is reused but absent from captured logs.

- [ ] **Step 2: Add full-state rehydration assertions**

  Extend `test_reconnect_stage_rehydrate.py` so a stage with pattern, preset, palette, entity count, renderer backend, and visibility produces each corresponding VJ command after reconnect.

- [ ] **Step 3: Run tests and diagnose any contract gap**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest vj_server/tests/test_viz_client_auth.py vj_server/tests/test_reconnect_stage_rehydrate.py -q'
  ```

  If the new tests already pass, commit only the stronger tests. If they fail, change only the handshake/rehydration code demonstrated by the failure.

- [ ] **Step 4: Run the complete VJ suite**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest vj_server/tests -q'
  ```

- [ ] **Step 5: Commit**

  ```powershell
  git add vj_server/tests/test_viz_client_auth.py vj_server/tests/test_reconnect_stage_rehydrate.py vj_server/viz_client.py vj_server/relay.py
  git commit -m "test(vj): prove authenticated reconnect rehydration"
  ```

  Before committing, unstage `vj_server/viz_client.py` or `vj_server/relay.py` when unchanged; the commit must contain only files required by the demonstrated behavior.

---

### Task 7: Write Paper 26.2 installation, recovery, and rollback documentation

**Files:**
- Create: `docs/PAPER_26_2_INSTALL.md`
- Modify: `README.md`
- Modify: `.env.example`

**Interfaces:**
- Consumes: final config keys, source VJ commands, artifact name, supported boundary
- Produces: clean-machine operator path with no hidden hosted-service dependency

- [ ] **Step 1: Write installation sections**

  Use these exact headings:

  ```markdown
  # MCAV Paper 26.2 Installation
  ## Supported versions
  ## Back up the server
  ## Install the Paper plugin
  ## Retrieve the generated pairing secret
  ## Install the VJ server from source
  ## Pair over loopback
  ## Create a zone and start a show
  ## Connection and queue diagnostics
  ## Recover from a VJ disconnect
  ## Stop and uninstall cleanly
  ## Roll back a failed release
  ## Optional integrations
  ## Known non-goals
  ```

- [ ] **Step 2: Include exact source-install commands**

  Linux/WSL commands use a venv and Python 3.11+:

  ```bash
  cd vj_server
  python3.12 -m venv .venv
  . .venv/bin/activate
  python -m pip install --upgrade pip
  python -m pip install -e '.[full]'
  read -rsp 'Paste ws-secret from plugins/AudioViz/config.yml: ' MINECRAFT_WS_SECRET
  printf '\n'
  export MINECRAFT_WS_SECRET
  audioviz-vj --minecraft-host 127.0.0.1 --minecraft-port 8765
  ```

  State explicitly that the secret is entered locally and never committed or pasted into logs.

- [ ] **Step 3: Document uninstall proof**

  Operators stop the VJ server, wait for the configured grace period, confirm entity count returns to zero, stop Paper, remove `mcav-paper-1.1.0.jar`, retain or archive `plugins/AudioViz/`, then restart and confirm no display entities remain.

- [ ] **Step 4: Update root entry points**

  Link the new guide from the README. Keep `.env.example` value empty and add a comment stating it must match the generated plugin value.

- [ ] **Step 5: Verify support claims and commit**

  ```powershell
  rg -n "1\.21|Spigot|Purpur|Fabric|Java 21" docs/PAPER_26_2_INSTALL.md README.md
  git diff --check
  git add docs/PAPER_26_2_INSTALL.md README.md .env.example
  git commit -m "docs(plugin): add Paper 26.2 operator guide"
  ```

  Expected: historical references in the broader README are labeled historical/quarantined; the new guide makes no unsupported compatibility claim.

---

### Task 8: Run the complete port gate

**Files:**
- Create: `docs/superpowers/reports/2026-08-15-paper-26-2-port-verification.md`

**Interfaces:**
- Consumes: Tasks 1-7
- Produces: G2 evidence and a clean runtime commit series ready for the verification/release plan

- [ ] **Step 1: Run Java 25 clean verification**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw clean verify'
  ```

- [ ] **Step 2: Run VJ and protocol verification**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest vj_server/tests -q'
  node --test protocol/tests/phase0-schemas.test.mjs
  ```

- [ ] **Step 3: Inspect the JAR contract**

  ```powershell
  jar tf minecraft_plugin/target/mcav-paper-1.1.0.jar | Select-String 'plugin.yml|org/bukkit|io/papermc|org/java_websocket|com/audioviz/libs/websocket'
  Get-FileHash minecraft_plugin/target/mcav-paper-1.1.0.jar -Algorithm SHA256
  Test-Path minecraft_plugin/dependency-reduced-pom.xml
  ```

  Expected: filtered `plugin.yml` and relocated WebSocket classes are present; Paper/Bukkit API classes are absent; reduced POM is absent.

- [ ] **Step 4: Record exact output and commit the report**

  The report records commit SHA, Java image digest, test counts, skipped tests, JAR size/hash, and any non-failing warnings.

  ```powershell
  git add docs/superpowers/reports/2026-08-15-paper-26-2-port-verification.md
  git commit -m "docs(plugin): record Paper 26.2 port verification"
  ```
