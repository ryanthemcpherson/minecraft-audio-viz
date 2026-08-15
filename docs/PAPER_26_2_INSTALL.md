# MCAV Paper 26.2 Installation

This is the supported installation path for the MCAV Paper renderer. Complete it on a staging server before changing a production server.

## Supported versions

- Paper 26.2, validated against `26.2.build.112-stable`
- Java 25 or newer for the Paper server
- `mcav-paper-1.1.0.jar`
- MCAV VJ server installed from this release's source
- Python 3.11 or newer for the VJ server; the commands below use Python 3.12

Paper forks, Spigot, Purpur, Fabric, and older Minecraft releases are not compatibility claims for this release. The VJ server and Paper server must run on the same host unless the loopback connection is carried through an encrypted tunnel.

## Back up the server

1. Stop the VJ server, then stop Paper cleanly.
2. Back up the world directories and the entire `plugins/AudioViz/` directory.
3. Record the currently installed MCAV JAR name and SHA-256 hash.
4. Confirm the backup can be read before proceeding.

Do not replace the plugin while Paper is running.

## Install the Paper plugin

1. Confirm the server launches with Java 25 or newer using `java -version`.
2. Remove any older MCAV JAR from the server's `plugins/` directory, but retain `plugins/AudioViz/` for configuration and rollback.
3. Copy `mcav-paper-1.1.0.jar` from the GitHub release into `plugins/`.
4. Start Paper and wait for the server to finish loading.
5. Confirm the log reports that AudioViz 1.1.0 is enabled and its WebSocket listener is bound to `127.0.0.1:8765`.
6. In game, run `/audioviz status`. It should report that the WebSocket server is running with zero connected clients.

If the plugin reports an invalid bind address or cannot persist its generated secret, stop here. The listener intentionally stays offline in those cases.

## Retrieve the generated pairing secret

On its first successful start, the plugin generates a pairing secret and saves it as `ws-secret` in `plugins/AudioViz/config.yml`.

Open that file locally with access restricted to the server operator, copy the value, and close the file. Do not paste the secret into chat, shell history, tickets, screenshots, logs, or source control. Never commit a populated `ws-secret` or `MINECRAFT_WS_SECRET` value.

If the file still contains `ws-secret: ""`, review the Paper log for a configuration persistence error. Do not manually invent a weaker secret.

## Install the VJ server from source

Use WSL or a Linux host. From the checked-out MCAV release source:

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

The silent prompt keeps the value off screen and out of shell history. The exported value exists only in that shell; unset it when the VJ process stops or close the shell.

## Pair over loopback

The Paper listener only accepts an explicit loopback bind. A normal same-host setup therefore uses:

- Paper listener: `127.0.0.1:8765`
- VJ command: `--minecraft-host 127.0.0.1 --minecraft-port 8765`
- Identical secret values in Paper's `ws-secret` and the VJ process's `MINECRAFT_WS_SECRET`

After starting the VJ server, run `/audioviz status` again. `Connected Clients` should be `1`. An authentication error means the two secret values do not match; do not disable authentication to work around it.

For separate hosts, terminate an encrypted SSH tunnel at the Paper host's loopback interface and point the VJ server at the tunnel's local loopback port. Follow [Secure renderer transport](CONNECTIVITY.md#secure-renderer-transport). Never expose port 8765 directly to a LAN or the internet.

## Create a zone and start a show

The fastest supported setup uses the stage wizard:

1. Stand at the intended stage anchor and run `/audioviz stage create`.
2. Choose a template and stage name in the inventory UI.
3. Complete the placement prompts, then run `/audioviz stage list`.
4. Activate it with `/audioviz stage activate <name>`.
5. Run `/audioviz status` and confirm the total entity count is nonzero.
6. Start an authenticated DJ source or use the VJ controls to select a pattern. Confirm the entities react to incoming audio.

For a minimal manual smoke test, create a zone with `/audioviz zone create smoke`, initialize it with `/audioviz pool init smoke 64`, and run `/audioviz test smoke wave`.

## Connection and queue diagnostics

- `/audioviz status` reports zone count, total entities, listener state, and authenticated client count.
- `/audioviz metrics` toggles the in-game metrics sidebar for authorized players and shows connection, entities, active zones, and latency.
- The Paper log emits a WebSocket connection summary every five minutes.
- An authenticated diagnostic client can request `{"type":"get_ws_metrics"}`. The response includes processed batches, dropped messages, raw and parsed queue depth, and main-thread update average, p95, and maximum milliseconds.
- `server_busy` means the bounded parser queue rejected work. Reduce the incoming frame rate or renderer load and inspect queue depth and dropped counts; do not increase limits without a measured capacity test.
- Repeated `MessageQueue backpressure` warnings mean the tick consumer cannot keep up. Reduce entity or pixel counts and verify the Paper server is maintaining tick rate.

Authentication failures and invalid messages return sanitized errors. The pairing secret must never appear in either the Paper or VJ logs.

## Recover from a VJ disconnect

The default `connection.disconnect_grace_ticks: 100` leaves the current visuals in place for five seconds while a brief reconnect is attempted. A successful authenticated reconnect cancels cleanup and restores the active stage state. If no client reconnects before the grace period ends, the plugin removes active visualization entities on the main thread but retains saved zones and stages.

1. Leave Paper running.
2. Correct the VJ process or network tunnel failure.
3. Restart the VJ server with the same pairing secret.
4. Run `/audioviz status` and confirm one client reconnects.
5. Confirm the active stage and pattern resume. Reactivate the saved stage only if it was intentionally deactivated.

## Stop and uninstall cleanly

1. Stop the VJ server.
2. Wait at least the configured disconnect grace period.
3. Run `/audioviz status` and confirm `Total Entities: 0`.
4. Stop Paper cleanly.
5. Remove only `plugins/mcav-paper-1.1.0.jar`.
6. Retain or archive `plugins/AudioViz/`; it contains configuration, saved zones, stages, and the pairing secret.
7. Restart Paper and confirm AudioViz is absent and no MCAV display entities remain.

If entities remain after the grace period, do not remove the plugin while Paper is running. Reconnect, use `/audioviz stage deactivate <name>` or `/audioviz pool cleanup <zone>`, verify zero entities, and then stop Paper.

## Roll back a failed release

1. Stop the VJ server and wait for entity cleanup.
2. Stop Paper.
3. Preserve the failed release's log, JAR, and `plugins/AudioViz/` directory for diagnosis.
4. Restore the pre-upgrade `plugins/AudioViz/` backup and the last known-good MCAV JAR that explicitly supports Paper 26.2 and Java 25.
5. Restore the matching VJ server source release and dependencies.
6. Start Paper, pair the VJ server, and repeat the connection and manual smoke checks.

Do not roll back to an old Minecraft 1.21, Spigot, Purpur, or Fabric artifact on a Paper 26.2 server. If no known-good Paper 26.2 MCAV release exists, leave the plugin uninstalled and restore the server backup.

## Optional integrations

- Geyser/Floodgate can provide Bedrock client access. It is optional and not part of the core Paper 26.2 acceptance path.
- Simple Voice Chat support is optional and requires its own compatible server and client installation.
- Remote VJ operation is optional and requires an encrypted tunnel; the renderer listener remains loopback-only.
- Prometheus-compatible VJ metrics can be enabled on port 9001 and should remain access-controlled.

Validate optional integrations separately after the core Paper, VJ, stage, audio, disconnect, and uninstall checks pass.

## Known non-goals

- Compatibility promises for Spigot, Purpur, Fabric, or other Paper forks
- Compatibility with Minecraft 1.21.x or Java 21
- Direct plaintext renderer connections over a LAN or the internet
- Disabling the pairing secret in production
- A prebuilt VJ Docker image or zero-install demo
- A signed DJ-client installer in this plugin release
