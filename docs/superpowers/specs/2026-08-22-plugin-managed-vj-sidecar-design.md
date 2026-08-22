# Plugin-Managed VJ Sidecar Design

**Date:** 2026-08-22

**Status:** Approved in chat; pending written review

## Objective

Ship an event release that starts MCAV from the normal Paper plugin lifecycle. The
server owner must not edit the Paper startup command or add custom JVM flags. MCAV
keeps the existing portable Python VJ runtime as a separate process so live audio
and rendering performance remain equivalent to the verified wrapper-based release.

The existing `event-2026-08-23-rc1` release remains published and unchanged as the
rollback package.

## Operator Experience

The server owner:

1. Stops Paper.
2. Uploads and extracts the new ZIP at `/home/container`.
3. Confirms public TCP allocations `8080` and `25808`.
4. Sets `MCAV_PUBLIC_HOST` to the server's public IP through a normal Pterodactyl
   environment variable.
5. Starts Paper with its original startup command.

No shell wrapper, egg change, custom startup flag, system Python installation, or
node-level access is required. First-login credentials and the DJ TLS fingerprint
remain available in `/home/container/mcav-vj/FIRST_LOGIN.txt`.

## Release Layout

The archive extracts into two intentional server-root paths:

```text
plugins/
  AudioViz.jar
mcav-vj/
  VERSION
  bin/
    linux-amd64/audioviz-vj
    linux-arm64/audioviz-vj
  release/
    AudioViz.jar
    plugin-config.default.yml
    runtime-lock.json
  admin_panel/
  preview_tool/frontend/
  patterns/
  configs/
```

The two JAR copies must be byte-identical. The `plugins/` copy gives Paper a normal
plugin installation on the first start. The `release/` copy remains the immutable
bootstrap and upgrade source. Existing generated state, credentials, configuration,
and backups are never included in the archive or overwritten during extraction.

## Plugin Lifecycle

A focused `VjSidecarManager` owns bootstrap, process startup, status reporting, and
shutdown. It is independent of visualization managers and exposes a small lifecycle
surface: `start()`, `status()`, and `stop()`.

During `AudioVizPlugin.onEnable()`:

1. The plugin creates or loads its existing loopback WebSocket secret and starts the
   Minecraft listener using the current secure behavior.
2. It detects `/home/container/mcav-vj` relative to Paper's plugins directory and
   maps `os.arch` to `linux-amd64` or `linux-arm64`.
3. On a dedicated worker, it invokes the bundled runtime's Pterodactyl bootstrap.
   `MINECRAFT_WS_SECRET` is provided only through the child environment, never a
   command-line argument or log entry.
4. After successful bootstrap, it launches the long-running authenticated HTTPS/WSS
   VJ process with the same arguments, ports, loopback Minecraft connection, TLS
   files, and auth file used by RC1.
5. Child output is continuously drained away from the Paper main thread so full
   pipes cannot deadlock either process. High-level lifecycle messages appear in the
   Paper console without printing credentials.

The manager does not add per-tick work or touch the audio-frame path. It performs no
automatic restart loop that could compete with Paper or Pterodactyl supervision. An
unexpected VJ exit is logged with its status while Paper stays online.

During `onDisable()`, the plugin rejects new sidecar work, requests graceful child
termination, and applies a short bounded force-stop fallback. Gameplay threads never
wait for bootstrap, process output, health polling, or normal VJ startup.

## Shared-Secret Consistency

Bootstrap gains an explicit plugin-managed mode. In that mode it requires
`MINECRAFT_WS_SECRET` from the child environment:

- A new identity is created with that exact secret.
- An existing identity must contain the same secret.
- A mismatch fails closed with a recovery message; it never silently rotates either
  side or starts an unauthenticated listener.

The wrapper-based mode remains backward compatible and continues generating a secret
when no plugin-managed secret is supplied. Bootstrap retains its existing atomic
identity, TLS, authentication, plugin-install, and configuration guarantees.

## Configuration and Detection

Sidecar startup defaults to automatic: it activates only when the bundled project
root and a supported executable are present. A normal standalone plugin installation
without `mcav-vj/` continues to behave as it does today and logs one concise message.

The event topology remains fixed:

```text
Minecraft WebSocket: 127.0.0.1:8765
Admin/preview HTTPS:  0.0.0.0:8080
DJ WSS:               0.0.0.0:25808
Metrics:              127.0.0.1:9001
```

`MCAV_PUBLIC_HOST` remains mandatory because certificate identity cannot be derived
safely from a container bind address. Existing advanced environment overrides are
preserved only where the two-port topology already permits them.

## Failure Handling

- Missing bundle or unsupported architecture: keep the plugin and Paper online;
  report the expected path or detected architecture.
- Missing `MCAV_PUBLIC_HOST`: keep Paper online and leave the public VJ service off.
- Bootstrap, identity, permission, or TLS failure: preserve prior state and leave VJ
  off for that start.
- Shared-secret mismatch: fail closed and report the configuration path without
  revealing either secret.
- Public port collision or early VJ exit: report the process exit and keep Paper
  online.
- Plugin disable or server shutdown: terminate only the process owned by this plugin;
  never scan for or kill unrelated processes.

## Packaging and Compatibility

The PowerShell and Linux builders both produce the new two-root layout. Release
verification requires both JARs, verifies their SHA-256 equality, checks executable
modes for both portable runtimes, and continues rejecting tests, caches, credentials,
keys, and development assets.

The changed plugin reports version `1.2.0-rc.1`; its release build remains installed
as `plugins/AudioViz.jar`. The plugin-managed bundle is published as
`26.2-event-rc2`; RC1 is not replaced.
Documentation removes the startup-wrapper step for RC2 and clearly identifies RC1 as
the rollback procedure. The old wrapper may remain in the bundle for rollback and
diagnostics, but RC2 documentation does not instruct the operator to use it.

## Verification

Implementation follows test-driven development.

- Java unit tests cover architecture selection, command construction, environment
  secrecy, asynchronous startup, output draining, early exit, idempotent start/stop,
  and owned-process shutdown.
- Plugin lifecycle tests prove `onEnable()` does not block on the sidecar and
  `onDisable()` stops it without affecting unrelated processes.
- Python tests cover plugin-managed identity creation, existing-secret agreement,
  mismatch refusal, and backward-compatible wrapper bootstrap.
- Packaging tests require `plugins/AudioViz.jar`, compare it with
  `mcav-vj/release/AudioViz.jar`, and exercise archive secret/development exclusions.
- The full Paper suite, focused/full VJ suite, frontend suites, release verifier, and
  packaged AMD64 runtime smoke test must pass before publishing RC2.
- A local rehearsal starts Paper with an unmodified Java command, confirms VJ reaches
  both public listeners, confirms the plugin/VJ loopback connection, then confirms a
  clean shutdown leaves no owned VJ process.

## Non-Goals

- Rewriting the VJ server or Lua engine in Java.
- Embedding both portable runtimes inside the plugin JAR.
- Downloading executable code during Paper startup.
- Changing the DJ protocol, TLS pinning rules, visualization behavior, or event
  topology.
- Automatically provisioning Pterodactyl allocations or discovering the public IP.
