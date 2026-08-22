# Packaged deployment smoke test

`packaged_smoke.py` is the retained, release-only integration harness for the
two-port Pterodactyl bundle. It is intentionally excluded from the archive.
Run it from WSL or Linux after building the release:

```bash
.venv/bin/python deploy/pterodactyl/tests/packaged_smoke.py \
  --archive /path/to/mcav-pterodactyl-26.1.zip \
  --rust-smoke-executable /path/to/packaged_pin_smoke \
  --output /path/to/packaged-smoke.json \
  --log /path/to/packaged-smoke.log
```

Build the retained production-path DJ probe from the repository source before
running the harness:

```bash
cargo build --release --manifest-path dj_client/src-tauri/Cargo.toml \
  --example packaged_pin_smoke
```

When that build runs on Windows and the harness runs in WSL, pass the WSL path
to `packaged_pin_smoke.exe` under `target/release/examples/`.

The host must provide `openssl` and `ss`, and ports `25927`, `25808`, `8765`,
`8766`, `19001`, `25809`, and `25810` must be free. The harness never stops an
existing process; it records conflicts and fails before launch. Ports `25809`
and `25810` are loopback-only recorders and are never deployment listeners.

The harness verifies and extracts the archive, launches its AMD64 runtime on
the exact public ports, and records process identity and listeners. It exercises
authenticated and no-auth browser protocols, static/origin/traversal handling,
state/control/bitmap/audio flow, matching and mismatching certificate pins,
pinned plaintext refusal, graceful shutdown, and port cleanup. Matching,
mismatch, authentication, and audio claims come from the shipped Rust
`DjClient`/`connect_verified` path. A loopback TLS forwarder records decrypted
application-byte, Upgrade, auth, and audio counts without retaining payloads;
its upstream connection validates the same temporary certificate. Its temporary
self-signed leaf is used only by explicit in-process clients: no trust store is
changed. HTTP probes verify its fingerprint, the Rust client exercises its
production leaf-pin plus SAN check, and the forwarder's upstream treats the
same leaf as a temporary local trust anchor.

Keep both JSON and raw service log outputs with release verification evidence.
