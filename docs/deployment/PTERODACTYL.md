# MCAV 26.1 — Pterodactyl Server Installation

This package runs the Paper plugin and VJ service together in the existing Java 25 server container. It does not require node access, Docker changes, system Python, npm, or a second Pterodactyl server.

## Upload

1. Download `mcav-pterodactyl-26.1.zip` and verify its SHA-256 against `mcav-pterodactyl-26.1.sha256`.
2. If the Pterodactyl file manager has **Unarchive**, upload the ZIP to the server root and unarchive it there.
3. If only SFTP is available, extract the ZIP on your computer and upload the resulting `mcav-vj` folder to the SFTP root.

The required final path is:

```text
/home/container/mcav-vj/start-mcav.sh
```

Do not upload the contents directly into `plugins`. MCAV installs and configures its plugin safely on first start.

## Allocations

Add or assign these TCP ports to the same Pterodactyl server:

- `8080` — admin panel and 3D preview over HTTPS
- `8766` — authenticated browser data over WSS
- `9000` — DJ client connection

The Minecraft game port does not change. Ports `8765` and `9001` remain internal to the container and should not be publicly allocated.

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

## First login

After the first restart, download this generated file through SFTP:

```text
/home/container/mcav-vj/FIRST_LOGIN.txt
```

It contains separate admin and DJ usernames/passwords plus the TLS certificate fingerprint. It does not contain the Minecraft shared secret or TLS private key.

Open:

```text
https://SERVER_ADDRESS:8080/
https://SERVER_ADDRESS:8080/preview/
```

The generated certificate is self-signed. On the first visit, compare the browser certificate's SHA-256 fingerprint with `TLS_SHA256_FINGERPRINT` in `FIRST_LOGIN.txt`, then accept the warning. Log in with `ADMIN_USERNAME` and `ADMIN_PASSWORD`. Credentials stay in browser memory only and are cleared on sign-out or tab close.

## What startup does

On every start, the wrapper:

- selects the bundled AMD64 or ARM64 runtime;
- preserves existing credentials and TLS identity;
- installs or upgrades `plugins/AudioViz.jar` with recoverable backups;
- preserves unrelated `plugins/AudioViz/config.yml` settings;
- synchronizes only the loopback renderer address, port, and shared secret;
- starts authenticated HTTPS/WSS VJ listeners; and
- executes the original Paper command as the foreground process.

If bootstrap, TLS, architecture selection, or a VJ port fails, the error is printed in the Pterodactyl console and Paper still starts. Backups are retained under `/home/container/mcav-vj/backups/`.

## Security notes

- Do not expose the admin panel without the generated login and HTTPS configuration.
- For an internet-facing domain, replace `state/tls.crt` and `state/tls.key` with a trusted certificate/key pair and retain owner-only permissions on the key.
- Port `9000` is the native DJ transport. Restrict it to trusted source networks/VPN unless the deployed DJ client is configured for trusted WSS.
- Never post `FIRST_LOGIN.txt`, `state/runtime.env`, `state/dj_auth.json`, or `state/tls.key` publicly.
