# Minecraft Dev Server Runbook (192.168.1.204)

Last reviewed: 2026-02-11

## Port Forwarding

For a private server with 1-10 players, forward only what is required:

| Port | Forward from router? | Why |
|---|---|---|
| `25565/tcp` | Yes | Player Minecraft connections |
| `25575/tcp` (RCON) | No | Admin-only control; keep local/VPN only |
| `8765/tcp` (AudioViz WS) | No (usually) | Needed only between trusted MCAV components |
| `8766/tcp` | No | Browser preview channel |
| `9000/tcp` | No | DJ relay input |
| `8080/tcp`, `8081/tcp` | No | Admin/preview HTTP |

If you need remote AudioViz ingestion, prefer a private tunnel/VPN instead of direct router forwarding.

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

For an 8 GB host dedicated primarily to Minecraft:

```bash
java -Xms4G -Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -jar paper.jar nogui
```

Using equal `Xms`/`Xmx` avoids heap resizing pauses.

## Plugin Auto-Update Status

`deploy.yml` now:
- builds plugin jar on every push to `main`
- removes old `audioviz-plugin-*.jar`
- copies the new jar
- restarts `minecraft.service`
- verifies `AudioViz` is loaded via RCON when `MCAV_RCON_PASSWORD` secret is set

Required secret for plugin verification:
- `MCAV_RCON_PASSWORD`

## Paper/Modded Jar Auto-Update

Use workflow: `.github/workflows/update-minecraft-jar.yml`

- Scheduled daily (`09:17 UTC`)
- Manual dispatch supported
- Supports:
  - `provider=paper` (default, pulls latest Paper build)
  - `provider=url` (custom modded jar URL, optional SHA256)

Core updater script:
- `scripts/update-minecraft-jar.sh`

Example manual runs:

```bash
# Paper (auto-detect current MC version from version_history.json)
bash scripts/update-minecraft-jar.sh

# Explicit Paper MC version
bash scripts/update-minecraft-jar.sh --mc-version 1.21.11

# Custom modded jar
bash scripts/update-minecraft-jar.sh --provider url --jar-url https://example.com/server.jar --sha256 <hash>
```
