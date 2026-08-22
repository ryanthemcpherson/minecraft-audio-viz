# Packaged deployment smoke test

`packaged_smoke.py` is the retained, release-only integration harness for the
two-port Pterodactyl bundle. It is intentionally excluded from the archive.
Run it from WSL or Linux after building the release:

```bash
.venv/bin/python deploy/pterodactyl/tests/packaged_smoke.py \
  --archive /path/to/mcav-pterodactyl-26.1.zip \
  --output /path/to/packaged-smoke.json \
  --log /path/to/packaged-smoke.log
```

The host must provide `openssl` and `ss`, and ports `8080`, `25808`, `8765`,
`8766`, and `19001` must be free. The harness never stops an existing process;
it records conflicts and fails before launch.

The harness verifies and extracts the archive, launches its AMD64 runtime on
the exact public ports, and records process identity and listeners. It exercises
authenticated and no-auth browser protocols, static/origin/traversal handling,
state/control/bitmap/audio flow, matching and mismatching certificate pins,
plaintext refusal, graceful shutdown, and port cleanup. Its temporary
self-signed leaf is used only by explicit in-process TLS clients: no trust store
is changed and certificate verification is never disabled.

Keep both JSON and raw service log outputs with release verification evidence.
