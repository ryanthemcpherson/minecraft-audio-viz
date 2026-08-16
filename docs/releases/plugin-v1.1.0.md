# MCAV Paper Plugin 1.1.0

This release is the MCAV visualization renderer for **Minecraft/Paper 26.2**. It requires **Java 25 or newer**. The release candidate was built and tested with Java 25 against Paper 26.2 build 112.

## Supported release boundary

- Supported: the Paper 26.2 plugin JAR and the source-installed Python VJ server's authenticated JSON renderer path.
- Optional: Geyser, Floodgate, and Simple Voice Chat integrations. Their absence does not prevent the core plugin from loading.
- Not claimed: Spigot, Purpur, Fabric, Bedrock behavior, Minecraft 1.21, a prebuilt VJ container, hosted coordinator services, or DJ desktop installers.

## Install and pair

1. Back up the Paper world, `plugins/AudioViz/`, and the currently installed MCAV JAR before changing the server.
2. Confirm `java -version` reports Java 25 or newer and the server is Paper 26.2.
3. Stop Paper, remove any older MCAV JAR from `plugins/`, copy `mcav-paper-1.1.0.jar` into `plugins/`, and start Paper.
4. On first start, retrieve the generated `ws-secret` from `plugins/AudioViz/config.yml`. Keep it out of logs, chat, screenshots, shell history, and source control.
5. Check out this release's source, create a Python 3.11+ virtual environment in `vj_server/`, and install the VJ server with `python -m pip install -e '.[full]'`.
6. Provide the same secret to the VJ process as `MINECRAFT_WS_SECRET`. Paper and the VJ renderer must run on the same host, or communicate through an encrypted tunnel terminating on the Paper host's loopback interface.
7. Confirm `/audioviz status` reports one connected client before starting a show.

The complete staging, smoke-test, recovery, uninstall, and rollback procedure is in [`docs/PAPER_26_2_INSTALL.md`](https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/plugin-v1.1.0/docs/PAPER_26_2_INSTALL.md).

## Verify the download

Download the JAR, CycloneDX SBOM, `SHA256SUMS.txt`, and candidate manifest into one directory, then run:

```bash
sha256sum -c SHA256SUMS.txt
gh attestation verify mcav-paper-1.1.0.jar --repo ryanthemcpherson/minecraft-audio-viz
gh attestation verify mcav-paper-1.1.0.cdx.json --repo ryanthemcpherson/minecraft-audio-viz
```

The JAR hash must also match `artifact.sha256` in `candidate-manifest.json`.

## Rollback or uninstall

Stop the VJ server first, run `/audioviz cleanup all`, confirm plugin-owned display entities are gone, and stop Paper cleanly. A rollback must use a known-good MCAV build that explicitly supports Paper 26.2 and Java 25; do not install an older 1.21, Spigot, Purpur, or Fabric artifact on this server. If no compatible rollback exists, remove the MCAV JAR, retain or archive `plugins/AudioViz/`, restart Paper, and verify no AudioViz tasks, listeners, or display entities remain.

Published release assets and the `plugin-v1.1.0` tag are immutable. Any correction will use a new patch version rather than replacing these files.
