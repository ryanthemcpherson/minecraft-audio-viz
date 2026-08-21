# MCAV 26.1 — Pterodactyl Server Installation

This package runs the Paper plugin and VJ service together in the existing Java 25 server container. It does not require node access, Docker changes, system Python, npm, or a second Pterodactyl server.

## Upload

1. Download `mcav-pterodactyl-26.1.zip` and verify its SHA-256 against `mcav-pterodactyl-26.1.zip.sha256`.
2. If the Pterodactyl file manager has **Unarchive**, upload the ZIP to the server root and unarchive it there.
3. If only SFTP is available, extract the ZIP on your computer and upload the resulting `mcav-vj` folder to the SFTP root.

The required final path is:

```text
/home/container/mcav-vj/start-mcav.sh
```

Do not upload the contents directly into `plugins`. MCAV installs and configures its plugin safely on first start.

## Allocations

Assign exactly these two public TCP allocations to the same Pterodactyl server:

- `8080` — admin HTTPS, preview HTTPS, and same-origin browser WSS at `/ws`
- `25808` — DJ WSS

The Minecraft game allocation does not change.

## Private container services

- `8765` — Minecraft renderer, bound to loopback only
- `9001` — health and Prometheus metrics, bound to loopback only

Do not publicly allocate either private service. Port `8766` is a legacy split-browser development default and is disabled by this deployment.

## Public IP configuration

Copy `/home/container/mcav-vj/mcav.env.example` to `/home/container/mcav-vj/mcav.env`. Set the public IPv4 or IPv6 literal that operators and DJs actually use:

```text
MCAV_PUBLIC_HOST=<public-ip>
HTTP_PORT=8080
VJ_SERVER_PORT=25808
UNIFIED_WEB=true
```

Do not use a hostname, private address, brackets around an IPv6 value, or `BROADCAST_PORT`. Startup validates the address before creating credentials or a certificate. The generated certificate contains the public IP and both loopback addresses as subject alternative names.

## Startup command

In Pterodactyl **Startup**, prepend this exact text to the existing full Java command:

```text
bash mcav-vj/start-mcav.sh --
```

Example:

```text
bash mcav-vj/start-mcav.sh -- java -Xms128M -Xmx8G -jar server.jar nogui
```

Keep every existing Java flag, variable, JAR name, and final argument unchanged after `--`. Then restart once.

## Trust the admin certificate

After the first restart, download both files through SFTP:

```text
/home/container/mcav-vj/FIRST_LOGIN.txt
/home/container/mcav-vj/state/tls.crt
```

Before importing the certificate, verify that its SHA-256 fingerprint matches `TLS_SHA256_FINGERPRINT` in `FIRST_LOGIN.txt`. On Windows, run:

```powershell
$pem = Get-Content .\tls.crt -Raw
$base64 = $pem -replace '-----BEGIN CERTIFICATE-----|-----END CERTIFICATE-----|\s', ''
$der = [Convert]::FromBase64String($base64)
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    ($sha256.ComputeHash($der) | ForEach-Object { $_.ToString('x2') }) -join ''
} finally {
    $sha256.Dispose()
}
```

On Linux or macOS, run:

```bash
openssl x509 -in tls.crt -noout -fingerprint -sha256
```

Both commands hash the certificate's DER bytes. Compare the resulting 64 hexadecimal characters with `TLS_SHA256_FINGERPRINT` before trusting the certificate; hashing the downloaded PEM file itself produces a different value.

Import the verified `state/tls.crt` into the administrator machine's trusted root certificate store. Do this on every machine that opens the admin panel or preview. Do not click through a browser warning as trust-on-first-use; verify and import the downloaded certificate first.

Open the exact `ADMIN_URL` and `PREVIEW_URL` from `FIRST_LOGIN.txt`, then sign in with `ADMIN_USERNAME` and `ADMIN_PASSWORD`. Browser credentials remain in memory only and are cleared on sign-out or tab close. Live browser state and controls use the same `8080` origin at `/ws`; there is no third browser allocation.

## Configure each DJ

Create a DJ connection profile with the exact values from `FIRST_LOGIN.txt`:

- Server endpoint: `DJ_ENDPOINT` (`wss://<public-ip>:25808`)
- Username and password: `DJ_USERNAME` and `DJ_PASSWORD`
- Server certificate SHA-256 fingerprint: copy the 64 hexadecimal characters from `TLS_SHA256_FINGERPRINT`

The self-signed public-IP profile requires the explicit fingerprint. Never leave it blank, use plaintext DJ WebSockets, or accept a certificate on first use. The DJ client verifies the certificate pin before it sends authentication, connection codes, palette data, or audio.

## Rotate the public identity

Rotate when the public IP changes, the certificate expires, or a certificate/private-key pair is replaced. Stop Paper, select the bundled runtime for the server architecture (`linux-amd64` for x86_64 or `linux-arm64` for aarch64), and run this explicit command from a host shell or one-time Pterodactyl startup command:

```bash
/home/container/mcav-vj/bin/linux-amd64/audioviz-vj \
  --bootstrap-pterodactyl \
  --project-root /home/container/mcav-vj \
  --plugins-dir /home/container/plugins \
  --public-host <new-public-ip> \
  --http-port 8080 \
  --port 25808 \
  --unified-web \
  --rotate-tls-identity
```

On ARM64, replace only `linux-amd64` with `linux-arm64`. Restore the normal startup command afterward. Download the new `FIRST_LOGIN.txt` and `state/tls.crt`, verify and replace the administrator trust entry, and replace the saved fingerprint in every DJ profile before reconnecting. The old fingerprint must fail after rotation.

Do not delete or edit individual files under `state/identity-generations`. Rotation preserves usernames, passwords, the Minecraft shared secret, plugin configuration, and the prior complete identity generation.

## What startup does

On every start, the wrapper:

- selects the bundled AMD64 or ARM64 runtime;
- validates the exact two-port topology and public-IP certificate identity;
- preserves existing credentials and TLS identity;
- installs or upgrades `plugins/AudioViz.jar` with recoverable backups;
- preserves unrelated `plugins/AudioViz/config.yml` settings;
- synchronizes only the loopback renderer address, port, and shared secret;
- starts authenticated HTTPS/WSS VJ listeners; and
- executes the original Paper command as the foreground process.

## Failure recovery

The wrapper prints a specific `[MCAV VJ]` error and still starts Paper when VJ bootstrap or listener startup fails. Correct the reported cause, then restart; do not add public allocations as a workaround.

- **Missing or invalid public IP:** set `MCAV_PUBLIC_HOST` to the externally reachable public IP literal. No new identity is committed until validation succeeds.
- **Certificate does not cover the configured IP:** preserve `state/`, then run the explicit rotation command above with the new public IP. Never delete the old identity to force regeneration.
- **Topology rejected:** restore `HTTP_PORT=8080`, `VJ_SERVER_PORT=25808`, and `UNIFIED_WEB=true`; remove `BROADCAST_PORT` from `mcav.env` and Pterodactyl variables.
- **Port bind failed:** stop the duplicate process or correct the two allocations, then restart. Do not expose the renderer, metrics, or legacy browser port.
- **Partial or inconsistent identity:** stop and preserve the entire `mcav-vj/state` directory plus `FIRST_LOGIN.txt`. Restore those paths together from one known-good backup or identity generation; never mix files from different generations.
- **Plugin installation failed:** inspect the console error and restore the latest matching JAR/config backup from `/home/container/mcav-vj/backups/` before restarting.
- **Certificate pin error after rotation:** download the current `FIRST_LOGIN.txt`, verify the current certificate out of band, and update the DJ profile. Do not bypass or remove the pin.

Never post `FIRST_LOGIN.txt`, `state/runtime.env`, `state/dj_auth.json`, or `state/tls.key`. The release archive intentionally excludes all generated credentials, private keys, caches, bytecode, and test files.
