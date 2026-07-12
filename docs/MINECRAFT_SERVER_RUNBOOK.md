# Minecraft Dev Server Runbook (192.168.1.204)

Last reviewed: 2026-02-11

## Port Forwarding

For a private server with 1-10 players, forward only what is required:

| Port | Forward from router? | Why |
|-|-|-|
| `25565/tcp` | Yes | Player Minecraft connections |
| `25575/tcp` (RCON) | No | Admin-only control; keep local/VPN only |
| `8765/tcp` (AudioViz WS) | Never | Renderer control is loopback-only; tunnel it when VJ runs elsewhere |
| `8766/tcp` | No | Browser preview channel |
| `9000/tcp` | No | DJ relay input |
| `8080/tcp`, `8081/tcp` | No | Admin/preview HTTP |

If the VJ server runs elsewhere, use an encrypted TCP tunnel that terminates at `127.0.0.1:8765` on the Minecraft host. A VPN route by itself is not sufficient because the renderer intentionally rejects non-loopback binds. Never forward port 8765 from the router.

## Recommended Server Settings (1-10 players)

Use these values in `server.properties` for a small private server:

```properties
max-players=10
white-list=true
enforce-whitelist=true
view-distance=8
simulation-distance=8
```

Notes:
- Keep `online-mode=true`.
- RCON must use a strong password and should never be internet-exposed.

## Java Flags

For an 8 GB host dedicated primarily to Minecraft + AudioViz:

```bash
java -Xms4G -Xmx4G \
  -XX:+UseZGC -XX:+ZGenerational \
  -XX:+AlwaysPreTouch \
  -XX:+UseStringDeduplication \
  -jar paper.jar nogui
```

| Flag | Purpose |
|-|-|
| `-Xms4G -Xmx4G` | Fixed heap — avoids resize pauses |
| `UseZGC + ZGenerational` | Sub-millisecond GC pauses (Java 21+). Critical for AudioViz where the 50ms tick budget leaves no room for G1's 10-50ms stop-the-world pauses |
| `AlwaysPreTouch` | Pre-faults memory pages at startup — eliminates page fault latency during ticks |
| `UseStringDeduplication` | Reduces heap pressure from duplicate block ID / zone name strings |

Use `scripts/start-minecraft-server.sh` or `scripts/start-minecraft-server.ps1` which include these flags.

**Previous flags (kept for reference):** The old G1GC flags (`-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200`) work on Java 17 but ZGC is strongly preferred on Java 21+ for AudioViz workloads.

## Dev Deployment Status

`.github/workflows/deploy.yml` is quarantined and manual-only. Its hosted build and test jobs are retained for diagnosis, but the self-hosted mutation job is hard-disabled and every manual run ends with `MCAV_DEV_DEPLOYMENT_QUARANTINED`.

The previous workflow no longer matches the live host:

- The live server is Dockerized Minecraft 26.2 under `/home/ryan/mc`.
- The MCAV Fabric mod currently targets Minecraft 1.21.11.
- The retained deploy implementation targets the unused legacy `/home/ryan/minecraft-server` systemd service.

Do not re-enable deployment until a compatible Paper-first target is selected and verified, or the Fabric mod is deliberately ported to the live Minecraft version. The replacement workflow must deploy to the actual runtime, restart only its owning service/container, and require a renderer-connected health check before success.

## Legacy Jar Updater Status

`.github/workflows/update-minecraft-jar.yml` is also manual-only and quarantined. Its self-hosted update job is hard-disabled because it writes to the unused legacy `/home/ryan/minecraft-server` installation and restarts `minecraft.service`, which competes with the live Docker container for port 25565. Every dispatch ends with `MCAV_JAR_UPDATE_QUARANTINED` and cannot mutate the host.

The underlying `scripts/update-minecraft-jar.sh` utility remains available for deliberate, operator-controlled recovery of the legacy installation. It must not be run against the live host until the intended runtime, Minecraft version, backup/rollback procedure, and owning service or container have been explicitly validated.

Legacy recovery examples (not live deployment commands):

```bash
# Paper (auto-detect current MC version from version_history.json)
bash scripts/update-minecraft-jar.sh

# Explicit Paper MC version
bash scripts/update-minecraft-jar.sh --mc-version 1.21.11

# Custom modded jar
bash scripts/update-minecraft-jar.sh --provider url --jar-url https://example.com/server.jar --sha256 <hash>
```
