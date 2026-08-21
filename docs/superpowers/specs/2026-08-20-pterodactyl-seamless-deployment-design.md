# Seamless Pterodactyl Deployment Design

**Date:** 2026-08-20

**Status:** Approved for implementation

## Objective

Produce one MCAV deployment ZIP that a server-level Pterodactyl administrator can upload over SFTP, extract at `/home/container`, activate by prepending one wrapper to the existing Paper startup command, and start without installing Python or obtaining node-level access.

The deployment targets the official `ghcr.io/pterodactyl/yolks:java_25` family on Linux AMD64 and ARM64. Paper remains the foreground process and must still start when the optional VJ process cannot start.

## Operator Experience

The administrator performs four actions:

1. Upload and extract `mcav-pterodactyl-26.1.zip` at the SFTP root.
2. Ensure the server has TCP allocations for ports `8080`, `8766`, and `9000`.
3. Prepend `bash mcav-vj/start-mcav.sh -- ` to the existing Paper startup command without changing the Java arguments.
4. Restart the server once, then retrieve generated credentials from `/mcav-vj/FIRST_LOGIN.txt`.

No Python installation, package-manager command, node-level Docker change, manual shared-secret synchronization, or manual plugin configuration edit is required.

The startup command has this form:

```bash
bash mcav-vj/start-mcav.sh -- java <the server's existing Java arguments>
```

The separator and Paper command are mandatory. The wrapper must reject an empty Paper command with a concise corrective message before making deployment changes.

## Release Layout

The ZIP owns one top-level directory and does not place files directly in the live server's `plugins` directory during extraction:

```text
mcav-vj/
  start-mcav.sh
  VERSION
  FIRST_LOGIN.txt                 generated on first start
  bin/
    linux-amd64/audioviz-vj
    linux-arm64/audioviz-vj
  release/
    AudioViz.jar
    plugin-config.default.yml
  admin_panel/
  preview_tool/frontend/
  patterns/
  configs/
    dj_auth.example.json
    scenes/
    banners/
  state/
    runtime.env                   generated on first start
    dj_auth.json                  generated on first start
  backups/                        generated only when replacing files
```

Tests, source-only coverage artifacts, Python bytecode, development dependencies, live credentials, `.git`, worktrees, and `node_modules` are excluded.

## Portable VJ Runtime

The deployment contains self-contained Linux executables for `x86_64` and `aarch64`. `start-mcav.sh` maps `uname -m` to the matching runtime and invokes it without a system Python installation. An unsupported architecture produces an explicit VJ diagnostic while still launching Paper.

The executable contains the Python VJ application and its runtime dependencies, including NumPy, Lupa, bcrypt, msgspec, and websockets. Static web assets, Lua patterns, scenes, and banners remain external beside the executable so an updated ZIP can replace them without rebuilding executable code.

The VJ CLI gains an explicit project-root option, supplied as `/home/container/mcav-vj`, rather than deriving assets from the executable's temporary or bundled module path. The modern CLI also gains an explicit HTTP-port option so deployment ports are not hidden constants.

Release executables are built in reproducible Linux builder containers against a glibc baseline no newer than the Java 25 yolk's runtime. The release process builds and labels AMD64 and ARM64 artifacts independently, then packages both into the ZIP.

## First-Run Bootstrap

The portable executable exposes a non-interactive Pterodactyl bootstrap command used by `start-mcav.sh`. Bootstrap owns structured file and JAR changes because those operations are easier to validate safely in Python than in shell text-processing commands.

On first start, bootstrap:

1. Creates `/mcav-vj/state` and `/mcav-vj/backups` with owner-only write access.
2. Generates a URL-safe 32-byte Minecraft/VJ shared secret.
3. Generates independent URL-safe first-login passwords for one DJ and one VJ operator.
4. Stores only bcrypt password hashes in `state/dj_auth.json`.
5. Stores the shared secret in `state/runtime.env` with owner-only permissions.
6. Writes the generated human-readable credentials to `FIRST_LOGIN.txt` with owner-only permissions.
7. Locates existing plugin JARs by reading `plugin.yml` or `paper-plugin.yml` from each JAR and matching the plugin name `AudioViz`.
8. Moves differing AudioViz JARs into a timestamped backup directory and atomically installs `release/AudioViz.jar` as `/plugins/AudioViz.jar`.
9. Preserves an existing `/plugins/AudioViz/config.yml`, creates a timestamped backup before modification, and updates only the loopback WebSocket address, port `8765`, and root `ws-secret` value.
10. Uses `release/plugin-config.default.yml` when the plugin configuration does not yet exist.

The default configuration is copied from the same plugin source revision used to build the included JAR. Bootstrap must not serialize the entire YAML document or remove comments and unrelated administrator settings.

All generated or replaced files use write-to-temporary-file followed by atomic replacement. A bootstrap failure must leave the last complete configuration and plugin JAR recoverable.

## Idempotency and Upgrades

The wrapper invokes bootstrap on every container start. Bootstrap compares release and installed SHA-256 digests and performs no plugin copy when they match.

Existing valid `runtime.env`, authentication configuration, and first-login credentials are retained. Credentials and the shared secret are never regenerated merely because the server restarts or the deployment ZIP is refreshed.

When the included plugin changes, bootstrap creates one timestamped recovery directory containing the prior JAR and any configuration changed during that operation. It never deletes backups automatically. Re-extracting the same release and restarting therefore has no persistent effect beyond normal process startup.

Malformed or partial state is reported with an exact file path. Bootstrap repairs only safely reconstructable state; it does not silently replace an unreadable authentication file or rotate credentials.

## Process Lifecycle

After bootstrap, `start-mcav.sh` starts the VJ executable in the background with these defaults:

```text
Project root:       /home/container/mcav-vj
Minecraft host:    127.0.0.1
Minecraft port:    8765
Admin HTTP host:   0.0.0.0
Admin HTTP port:   8080
Browser WebSocket: 8766
DJ WebSocket:      9000
Metrics:           127.0.0.1:9001
Authentication:    /home/container/mcav-vj/state/dj_auth.json
```

The wrapper loads the shared secret from `state/runtime.env`, starts VJ with authentication enabled, verifies that the VJ process remains alive through its initial bind phase, and prints concise portal, preview, and DJ endpoints. It then executes the exact command following `--` with `exec`, leaving Paper attached to the Pterodactyl console for commands, signals, resource monitoring, and crash detection.

The VJ process inherits container stdout and stderr so its logs appear in the same console with an `[MCAV VJ]` prefix where the wrapper emits status. Minecraft connection failure at VJ startup is nonfatal because the existing VJ reconnection loop connects after Paper enables the plugin listener.

If bootstrap or VJ startup fails, the wrapper prints the failing phase and recovery guidance, disables VJ for that start, and still executes Paper. An invalid or absent Paper command is the only wrapper error that prevents Paper startup.

Container shutdown remains owned by Paper as the foreground process. The container runtime terminates the background VJ process with the container. The wrapper does not introduce an independent restart loop that could fight Pterodactyl's process supervision.

## Configuration Surface

Safe defaults require no environment variables. Advanced overrides use existing environment variables where available and a small `/mcav-vj/mcav.env` file only if an administrator creates it deliberately. Supported overrides are limited to the VJ HTTP, browser, DJ, and metrics ports and entity count. The Minecraft renderer host remains loopback and its port remains `8765` in this deployment mode.

The wrapper never enables `--no-auth`. The generated DJ and VJ credentials are distinct. The Minecraft shared secret is not printed to the console or written to `FIRST_LOGIN.txt`.

Because the current browser and DJ transports are not a TLS termination layer, public deployments must restrict the three external allocations to trusted networks or place a compatible TLS/VPN layer in front of them. TLS proxy provisioning is outside this SFTP-only deployment package.

## Failure Handling

- Missing bundled executable: report expected architecture/path, then launch Paper.
- Unsupported architecture: report the `uname -m` value, then launch Paper.
- Port collision: identify the VJ port that failed, then launch Paper.
- Missing or invalid plugin release JAR: leave installed plugins unchanged, then launch Paper.
- Unreadable existing plugin configuration: leave it unchanged, report its path, then launch Paper.
- Malformed authentication or runtime state: preserve it, report the recovery action, then launch Paper without VJ.
- Failed atomic replacement: retain or restore the previous complete file and report the backup location.
- VJ exits during initial bind: report its exit status, then launch Paper.
- VJ exits after Paper starts: Paper remains online and the exit is visible in the shared console; Pterodactyl continues supervising Paper.

## Verification Strategy

### Unit tests

- Project-root and HTTP-port CLI parsing and propagation.
- Secure credential generation and bcrypt verification.
- First-run state creation with restrictive permissions.
- Idempotent repeated bootstrap with byte-identical secrets and credentials.
- Existing configuration preservation with only the three renderer security fields changed.
- Plugin JAR identification, same-digest no-op, differing-version backup, and atomic install.
- Malformed and partial state refusal without credential rotation.
- AMD64, ARM64, and unsupported architecture selection.

### Shell integration tests

- The wrapper passes the Paper command and arguments through unchanged.
- Missing `--` or missing Paper command fails before bootstrap.
- Bootstrap failure still launches the Paper sentinel command.
- VJ bind failure still launches the Paper sentinel command.
- Repeated starts do not modify persistent state.

### Container tests

For both supported architectures where the build environment provides emulation or native runners:

1. Start the extracted bundle in the official Java 25 yolk.
2. Confirm no system Python is required.
3. Confirm the portable VJ serves the admin panel and preview.
4. Confirm ports `8080`, `8766`, and `9000` bind and metrics remain loopback-only on `9001`.
5. Start a Paper test server with the included plugin.
6. Confirm VJ reconnects to `127.0.0.1:8765` using the generated shared secret.
7. Restart the container and confirm credentials, configuration, and installed JAR remain unchanged.
8. Inject bootstrap and VJ failures and confirm the Paper sentinel still starts.

### Release checks

- ZIP contains one `mcav-vj/` root and every required runtime asset.
- ZIP contains neither live credentials nor development artifacts.
- SHA-256 manifest covers the ZIP, both executables, and included plugin JAR.
- Plugin JAR test suite, VJ unit suite, package smoke checks, and container deployment tests pass before publishing.

## Non-Goals

- Installing or changing the Pterodactyl node's Docker image or egg.
- Creating Pterodactyl allocations through panel or node APIs.
- Replacing Paper's existing JVM flags or JAR selection.
- Running VJ in a second container without an encrypted loopback tunnel.
- Provisioning a public TLS reverse proxy or VPN.
- Automatically deleting backups or first-login credentials.
