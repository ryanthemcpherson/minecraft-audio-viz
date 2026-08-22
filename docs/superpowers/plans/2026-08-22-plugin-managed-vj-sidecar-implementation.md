# Plugin-Managed VJ Sidecar Implementation Plan

> **Execution:** Follow this plan task-by-task with test-driven development and
> verification before publishing any replacement event artifact.

**Goal:** Publish `26.2-event-rc2`, which starts the bundled VJ service from
`AudioViz.jar` while Paper retains its unchanged startup command.

**Architecture:** Paper owns a dedicated asynchronous Java sidecar manager. The
manager runs the existing Python bootstrap in an explicit plugin-managed mode, then
starts and owns the existing portable VJ process. The release ZIP installs the plugin
at `plugins/AudioViz.jar` and keeps the portable service under `mcav-vj/`.

**Tech stack:** Java 25/Paper 26.2, JUnit 5/Mockito, Python 3.12/pytest, PowerShell,
Bash, portable Linux AMD64/ARM64 runtimes.

---

## Task 1: Add plugin-managed bootstrap secret agreement

**Files:**

- Modify: `vj_server/tests/test_pterodactyl.py`
- Modify: `vj_server/pterodactyl.py`
- Modify: `vj_server/cli.py`

1. Add failing tests proving that plugin-managed bootstrap creates a new identity
   with the exact supplied secret, accepts an existing matching identity, rejects an
   existing mismatch without modifying state, and leaves wrapper bootstrap behavior
   unchanged.
2. Add CLI tests proving `--plugin-managed` requires a valid
   `MINECRAFT_WS_SECRET` environment value and never prints it.
3. Run the focused tests and confirm the expected failures:

   ```bash
   cd vj_server
   .venv/bin/python -m pytest -q tests/test_pterodactyl.py
   ```

4. Thread an optional required secret through `bootstrap_pterodactyl()`,
   `_ensure_identity()`, and `_create_identity()`. Validate it before acquiring or
   mutating deployment state. A supplied secret must be at least 32 non-whitespace
   characters.
5. Add `--plugin-managed` to the CLI. Read the secret only from the child environment
   and pass it to bootstrap; never accept it as a command-line value.
6. Re-run the focused tests, then the full VJ suite.
7. Commit:

   ```text
   feat(vj): support plugin-managed bootstrap identity
   ```

## Task 2: Build a testable Java sidecar launch plan

**Files:**

- Create: `minecraft_plugin/src/main/java/com/audioviz/sidecar/VjSidecarLaunchPlan.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/sidecar/VjSidecarLaunchPlanTest.java`

1. Add failing pure-Java tests for:
   - `amd64`/`x86_64` and `arm64`/`aarch64` runtime selection;
   - unsupported architectures;
   - project-root detection relative to the plugin directory;
   - fixed public ports `8080` and `25808` and loopback ports `8765` and `9001`;
   - IPv4 and IPv6 public bind selection;
   - bootstrap command construction with `--plugin-managed`;
   - server command construction from the committed identity paths;
   - rejection of missing/malformed `FIRST_LOGIN.txt` or `runtime.env`;
   - absence of secrets from command arguments and printable descriptions.
2. Run only the new tests and confirm they fail because the launch-plan type does not
   exist.
3. Implement an immutable launch plan that performs path/architecture discovery and
   constructs argument lists without spawning processes. Keep secret material only in
   an environment map.
4. Re-run the new tests and `git diff --check`.
5. Commit:

   ```text
   feat(plugin): define VJ sidecar launch plan
   ```

## Task 3: Implement and integrate the sidecar lifecycle

**Files:**

- Create: `minecraft_plugin/src/main/java/com/audioviz/sidecar/VjSidecarManager.java`
- Create: `minecraft_plugin/src/test/java/com/audioviz/sidecar/VjSidecarManagerTest.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/AudioVizPluginLifecycleTest.java`
- Modify: `minecraft_plugin/pom.xml`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/ReleaseMetadataTest.java`

1. Add failing manager tests with fake worker and process adapters. Cover non-blocking
   start, bootstrap-before-service ordering, continuous output draining, early exit,
   double-start refusal, stop-during-start, owned-process-only shutdown, and bounded
   force-stop fallback.
2. Implement `VjSidecarManager` with injected process/worker boundaries for tests and
   a production `ProcessBuilder` adapter. Use one lifecycle state machine and never
   schedule bootstrap or I/O on the Paper main thread.
3. Add failing plugin lifecycle tests proving the sidecar starts only when secret
   persistence succeeds and stops before the local WebSocket listener during disable.
4. Integrate automatic sidecar discovery after secure WebSocket startup. Missing
   bundle, public host, or supported runtime logs one actionable message while Paper
   remains online.
5. Bump plugin metadata from `1.1.0` to `1.2.0-rc.1` and update the metadata assertion.
6. Run focused tests, then the full plugin suite and package:

   ```powershell
   cd minecraft_plugin
   .\mvnw.cmd -Dtest=VjSidecarLaunchPlanTest,VjSidecarManagerTest,AudioVizPluginLifecycleTest test
   .\mvnw.cmd package
   ```

7. Commit:

   ```text
   feat(plugin): manage bundled VJ sidecar lifecycle
   ```

## Task 4: Produce and verify the no-wrapper archive layout

**Files:**

- Modify: `deploy/pterodactyl/build-release.ps1`
- Modify: `deploy/pterodactyl/build-release.sh`
- Modify: `deploy/pterodactyl/release_archive.py`
- Modify: `deploy/pterodactyl/verify-release.ps1`
- Modify: `deploy/pterodactyl/test-build-release.ps1`
- Modify: `deploy/pterodactyl/test-build-release.sh`
- Modify: `deploy/pterodactyl/tests/test_release_verifier_parity.py`
- Modify: `deploy/pterodactyl/tests/test_release_builder.py`

1. Extend archive fixtures with `plugins/AudioViz.jar`. Add failing tests proving both
   JAR paths are mandatory, byte-identical, and the only permitted payload under the
   `plugins/` root.
2. Add mismatch, missing-JAR, extra-plugin, traversal, case-collision, secret, and
   development-asset rejection cases to both verifier parity paths.
3. Update both builders to copy the already-built JAR to
   `mcav-vj/release/AudioViz.jar` and `plugins/AudioViz.jar`, then archive both roots.
4. Update Python and PowerShell verification to allow only `mcav-vj/**` plus the exact
   plugin path, preserve the `mcav-vj` manifest model, and compare both JAR digests.
5. Run:

   ```powershell
   .\deploy\pterodactyl\test-build-release.ps1
   wsl bash deploy/pterodactyl/test-build-release.sh
   wsl bash -lc "vj_server/.venv/bin/python -m pytest -q deploy/pterodactyl/tests/test_release_builder.py deploy/pterodactyl/tests/test_release_verifier_parity.py"
   ```

6. Commit:

   ```text
   feat(release): install plugin without startup wrapper
   ```

## Task 5: Update the owner handoff and add a real startup rehearsal

**Files:**

- Modify: `docs/deployment/PTERODACTYL.md`
- Modify: `README.md`
- Create: `deploy/pterodactyl/tests/plugin_managed_smoke.py`
- Create: `deploy/pterodactyl/tests/test_plugin_managed_smoke.py`

1. Add failing harness tests for archive extraction, unchanged Paper command
   construction, required environment, readiness detection, bounded shutdown, and
   post-stop listener/process cleanup.
2. Implement a disposable rehearsal harness that extracts the release, starts a
   pinned Paper 26.2 server with its normal `java -jar ... nogui` command, waits for
   the plugin-managed VJ listeners, then shuts Paper down and proves the owned VJ
   process and listeners are gone.
3. Rewrite the RC2 owner steps: stop, extract at `/home/container`, confirm two public
   allocations, set `MCAV_PUBLIC_HOST`, and start with the existing Paper command.
   Retain a clearly labeled RC1 rollback section.
4. Run the harness unit tests and documentation link/path checks.
5. Commit:

   ```text
   docs(deploy): document plugin-managed Pterodactyl startup
   ```

## Task 6: Build, verify, publish, and hand off RC2

**Files:**

- Generated: `dist/mcav-pterodactyl-26.2-event-rc2.zip`
- Generated: `dist/mcav-pterodactyl-26.2-event-rc2.sha256`
- Generated: `dist/plugin-managed-smoke.json`
- Generated: `dist/plugin-managed-smoke.log`

1. Confirm the worktree is clean except ignored build outputs and inspect every event
   commit with `git log` and `git diff origin/main...HEAD --stat`.
2. Run the full Java, Python, admin-panel, frontend, schema, verifier, and builder
   gates. Record exact pass counts.
3. Build the RC2 archive:

   ```powershell
   .\deploy\pterodactyl\build-release.ps1 -Version 26.2-event-rc2
   .\deploy\pterodactyl\verify-release.ps1 -Archive .\dist\mcav-pterodactyl-26.2-event-rc2.zip
   ```

4. Run the packaged AMD64 DJ/TLS smoke test and the plugin-managed real-Paper
   rehearsal. Do not publish if either fails or leaves a process/listener behind.
5. Compute the final SHA-256 independently and confirm it matches the checksum file.
6. Push the event branch and publish a new GitHub prerelease tag
   `event-2026-08-23-rc2`. Upload the ZIP, checksum, updated owner guide, and smoke
   reports. Do not modify or remove RC1.
7. Re-read the remote release metadata and confirm asset names, sizes, uploaded state,
   and the server-reported ZIP digest.
8. Send the direct RC2 ZIP link, checksum, unchanged-startup instructions, two port
   allocations, and rollback link to the user.
