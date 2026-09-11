# Local Paper rendering and restart gate

Run `scripts/release/paper_matrix.py` from WSL/Linux with the project `.release-venv` and Python 3.12+. The harness downloads the exact Paper 1.21.11 build 132 and Linux x86_64 Temurin Java 21 distribution in `deploy/paper/paper-lock.json`, verifies both SHA-256 digests, and extracts Java with Python's data-only tar filter. Paper's own first-run dependency downloads still require network access; this is not an offline or fully hermetic release check.

Paper build metadata comes from the [official downloads service](https://docs.papermc.io/misc/downloads-service/). The Java archive and published checksum come from the [Adoptium API](https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux). Updating pins is a reviewed change, not an automatic part of each run.

Preparation does not accept the Minecraft EULA or launch a server:

```bash
.release-venv/bin/python scripts/release/paper_matrix.py --prepare-only
.release-venv/bin/python -m pytest scripts/release/test_paper_matrix.py -q
```

After accepting the [Minecraft EULA](https://www.minecraft.net/eula), run against the exact plugin bytes you intend to test:

```bash
plugin=minecraft_plugin/target/audioviz-plugin-1.2.0.jar
digest=$(sha256sum "$plugin" | cut -d' ' -f1)
.release-venv/bin/python scripts/release/paper_matrix.py \
  --plugin "$plugin" --plugin-sha256 "$digest" --accept-eula
```

The default cache and results live under ignored `minecraft_plugin/target/`. Each run gets a newly created directory; the harness refuses to populate a nonempty server directory and never deletes a world or prior evidence. It caps the Java heap at 1.5 GiB, binds game and plugin listeners to loopback on separate dynamically selected ports, and disables RCON/query. Keep the output local if server logs contain machine paths.

The gate performs these checks:

1. Start a clean world with the plugin and confirm there are no persisted stages.
2. Create and activate a club stage through the plugin's WebSocket API; wait for eight main-stage entities (the fixture's pool cap).
3. Tag one actual Minecraft BlockDisplay at the stage origin. Send two deterministic JSON entity batches and poll Minecraft console NBT for the exact world positions and display scales. Acknowledgements alone cannot pass.
4. Stop the process gracefully, then launch a new process against the same world and plugin data.
5. Confirm the persisted stage is active and the eight entities exist without sending `activate_stage` or `init_pool` again. Verify movement and scale again.
6. Stop gracefully and require the persisted stage configuration/history digest to remain unchanged across restart.

Each process has a separate full console log. `evidence.json` records pass/fail, exact download pins, plugin digest, ports, observed positions/scales, clean shutdown and persistence checks. A failed run returns a nonzero exit code and retains its evidence. Startup, protocol, console and shutdown waits are bounded; a shutdown timeout kills only the harness-owned process group and fails the run.

This gate deliberately disables the managed VJ runtime. It establishes Paper-side entity lifecycle and application of synthetic render batches. It does **not** establish signed-runtime download/install, DJ audio capture, Lua output, network latency, a visually inspected Minecraft client, Pterodactyl compatibility, or the full release candidate's readiness. Those remain separate acceptance gates.
