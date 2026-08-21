# Two-Port Pterodactyl Gateway Design

**Date:** 2026-08-21
**Status:** Approved in chat; written-spec review pending
**Target branch:** `feature/vj-control-panel`

## Summary

MCAV's Pterodactyl deployment will require only two public TCP allocations:

- `8080`: admin and preview HTTPS plus the authenticated browser WebSocket at `/ws`
- `25808`: remote DJ WebSocket over TLS

Minecraft renderer traffic, metrics, and any legacy browser listener remain private to the container. The design preserves the redesigned VJ panel and existing browser/DJ authentication boundaries while removing public port `8766` from the Pterodactyl contract.

The deployment is addressed by public IP and uses a generated self-signed certificate. The certificate will contain that public IP as a subject alternative name. Native DJ clients will pin its SHA-256 fingerprint and verify it before sending credentials or audio. Plaintext public DJ connections and trust-on-first-use are explicitly prohibited.

## Context

The current VJ server exposes separate listeners:

- `8080` for admin and preview static HTTP(S)
- `8766` for authenticated browser WebSocket traffic
- `9000` for remote DJ WebSocket traffic
- `8765` for the Minecraft renderer
- `9001` for metrics

The Pterodactyl host can expose `8080` for the web panel and `25808` for remote connections, but it cannot expose a third browser WebSocket port. Serving the panel on `8080` without a browser WebSocket route produces a shell that cannot display live state or send controls.

The Pterodactyl implementation also currently generates a certificate valid only for `localhost` and `127.0.0.1`. The Rust DJ client selects WSS for non-local hosts, while the DJ listener doesn't currently receive the server TLS context. A public-IP deployment therefore needs an explicit TLS and trust correction, not only a port-number change.

## Goals

1. Require exactly two public Pterodactyl allocations: `8080` and `25808`.
2. Serve the admin panel, preview, and authenticated browser WebSocket from the same HTTPS origin on `8080`.
3. Serve authenticated DJ traffic over WSS on `25808`.
4. Support a self-signed certificate safely by pinning its SHA-256 fingerprint in the native DJ client.
5. Preserve the current VJ/browser/DJ message handlers, authentication rules, rate limits, and control authority semantics.
6. Preserve safe static-file behavior: canonical containment, exact routing, correct MIME types, GET/HEAD, redirects, 404s, and `Cache-Control: no-store`.
7. Preserve legacy split-port development mode outside the Pterodactyl bundle.
8. Reconcile the Pterodactyl work currently on `main` with the redesigned admin/preview work on `feature/vj-control-panel` without losing either surface.

## Non-Goals

- Changing the global development DJ default from `9000` to `25808`
- Exposing Minecraft renderer port `8765` or metrics port `9001`
- Sending credentials or audio over plaintext public WebSockets
- Automatically trusting the first certificate presented by a server
- Bundling or managing Caddy, Nginx, or another external reverse proxy
- Changing coordinator, Minecraft, bitmap, emergency-control, or voice protocol semantics
- Replacing the browser or DJ authentication models

## Network Topology

### Public listeners

- `0.0.0.0:8080`
  - HTTPS admin panel at `/`
  - HTTPS preview at `/preview/`
  - WSS browser control channel at `/ws`
  - runtime browser configuration at `/runtime-config.js`
- `0.0.0.0:25808`
  - WSS DJ client channel

### Private listeners

- `127.0.0.1:8765`: Minecraft plugin connection
- `127.0.0.1:9001`: metrics, when enabled
- `8766`: not started in unified Pterodactyl mode

Legacy development mode retains separate `8080` HTTP and `8766` browser WebSocket listeners. The global DJ default remains `9000`. Pterodactyl selects unified mode and `25808` explicitly through its environment and launch wrapper.

## Unified Web Gateway

### Framework

Add `aiohttp` to the VJ server runtime and release lock. It will own the unified `8080` HTTPS listener in Pterodactyl mode. This is preferred over `websockets.process_request`, whose HTTP support is intentionally limited and isn't intended to be a complete static HTTP server.

The existing threaded static server remains available for legacy split-port mode. Unified mode does not start that server or the public `8766` browser listener.

### Routes

- `GET|HEAD /` and admin assets: serve from `admin_panel`
- `GET|HEAD /preview` and `/preview/`: canonical redirect/serve behavior
- `GET|HEAD /preview/{asset}`: serve from `preview_tool/frontend`
- `GET /runtime-config.js`: return a small non-secret configuration object selecting same-origin `/ws`
- `GET /ws` with a valid WebSocket upgrade: enter the existing browser authentication/control handler
- all other paths: bounded 404 response

Every response, including redirects and errors, sends exactly one `Cache-Control: no-store` header. File paths are URL-decoded, resolved canonically, and required to remain relative to their selected root before opening. Null bytes, absolute paths, encoded traversal, encoded separators, and look-alike prefixes are rejected.

### Browser WebSocket adapter

Create a focused adapter around `aiohttp.web.WebSocketResponse` that provides the interface consumed by `_handle_browser_client`: `send`, `recv`, `close`, asynchronous message iteration, and `remote_address`. It converts text and binary frames to the existing handler's expected values and maps closure/error conditions without inventing application messages.

The existing `_handle_browser_client`, authentication negotiation, message router, rate limits, emergency authority, bitmap frames, voice status, and cleanup collections remain the sole application owners. The gateway does not duplicate these behaviors.

### Origin and resource policy

WebSocket upgrades are accepted only for exact `/ws` requests whose `Origin` matches the configured public HTTPS origin. Missing origins may be allowed only for explicit non-browser test/health clients under a dedicated configuration; Pterodactyl production requires an origin.

The route applies the existing 64 KiB message ceiling, handshake timeout, authentication timeout, and no-store policy. Static disk reads are offloaded from the asyncio loop so browser asset traffic cannot stall visualization updates.

## Browser Runtime Configuration

Both admin and preview HTML load `/runtime-config.js` before their module entry point. In unified mode it provides:

```js
window.MCAV_RUNTIME_CONFIG = {
  browserWebSocketMode: "same-origin",
  browserWebSocketPath: "/ws"
};
```

The clients derive scheme and authority from `window.location`, producing `wss://<public-ip>:8080/ws` when loaded over HTTPS. They do not copy the `Host` header into executable configuration.

The checked-in legacy fallback preserves the current query-string override and port `8766` for standalone development. `WebSocketService` receives an explicit URL or path rather than embedding deployment-specific port logic in the UI.

## DJ Transport and Certificate Trust

### Server certificate

Pterodactyl requires `MCAV_PUBLIC_HOST` to contain the public IPv4 or IPv6 address. Bootstrap validates it with Python's `ipaddress` module before passing it to OpenSSL. IPv6 addresses are bracketed when rendered in URLs. The generated certificate contains:

- the configured public IP SAN
- `DNS:localhost`
- `IP:127.0.0.1`

The same TLS identity is used on `8080` and `25808`. Existing owner-only key permissions, atomic identity creation, partial-identity refusal, and SHA-256 fingerprint generation remain intact.

`FIRST_LOGIN.txt` records:

- `ADMIN_URL=https://<public-ip>:8080/`
- `PREVIEW_URL=https://<public-ip>:8080/preview/`
- `DJ_ENDPOINT=wss://<public-ip>:25808`
- normalized `TLS_SHA256_FINGERPRINT=<64 lowercase hex characters>`

Administrators may import `tls.crt` into the browser machine's trust store to remove self-signed warnings. This doesn't replace DJ certificate pinning.

### DJ listener

When a server TLS context exists, the DJ `ws_serve` listener receives it and logs a WSS endpoint. Pterodactyl binds that listener to `25808`. Local development without TLS retains plaintext loopback/LAN behavior under existing configuration.

### Native DJ client pinning

The DJ connection profile gains an optional SHA-256 certificate fingerprint. For the Pterodactyl public-IP profile, it is required.

Connection order is strict:

1. Parse and normalize the configured fingerprint.
2. Open the TLS connection without sending application data. In pinned mode, the native TLS connector may permit the self-signed chain failure only so the peer certificate can be inspected; hostname verification remains enabled against the public-IP SAN.
3. Read the peer's leaf certificate DER bytes.
4. Hash them with SHA-256 and compare with the configured fingerprint.
5. Continue to DJ authentication only after an exact match.

A mismatch, missing peer certificate, malformed fingerprint, or TLS failure closes the transport before `dj_auth`, `code_auth`, credentials, palette data, or audio frames are sent. The UI reports a specific certificate error and leaves the profile untrusted. There is no trust-on-first-use path and no “accept anyway” control.

If no fingerprint is configured, the client uses normal platform certificate validation. It never silently falls back from WSS to WS.

The non-secret fingerprint may be persisted with host and port. DJ passwords and private keys remain unpersisted under the existing session rules.

## Pterodactyl Packaging

The implementation first brings the existing Pterodactyl commits from `main` into the feature branch and resolves admin/preview HTML conflicts in favor of the redesigned surfaces plus the new runtime configuration script.

Pterodactyl service defaults become:

```text
HTTP_PORT=8080
VJ_SERVER_PORT=25808
UNIFIED_WEB=true
METRICS_PORT=9001
```

`BROADCAST_PORT` isn't publicly allocated in unified mode. Legacy mode may still accept it.

The release builder includes the new Python dependency, runtime configuration asset, updated lock metadata, and unchanged platform-specific VJ binaries. Startup validates that `8080` and `25808` differ, the public IP is configured, the certificate covers that IP, and both listeners bind successfully. As today, a VJ bootstrap/listener failure is logged clearly and must not prevent Paper from starting.

Documentation lists only `8080` and `25808` as public allocations. It identifies `8765` and `9001` as loopback-only and no longer instructs operators to expose `8766`.

## Error Handling and Recovery

- Port conflict: fail the VJ side with the exact listener and port; Paper continues.
- Invalid or missing public IP: refuse new identity generation with a corrective message; never generate an incorrectly scoped certificate.
- Existing localhost-only identity: detect SAN mismatch and require an explicit certificate rotation command; never overwrite the trusted identity automatically.
- Browser origin mismatch: reject before authentication with HTTP 403.
- Invalid static path: return 404 without revealing filesystem paths.
- Browser adapter failure: close that browser connection and clean all existing tracking state.
- DJ fingerprint mismatch: close before authentication and show expected/observed fingerprints only in the local DJ client log/UI.
- Certificate rotation: generate a new identity through an explicit operator action, update `FIRST_LOGIN.txt`, and require DJs to replace the saved fingerprint.
- Unified-mode regression: operators may temporarily select legacy split mode, which again requires an internal/public `8766` route. This is a recovery mode, not the documented Pterodactyl default.

## Security Properties

- Exactly two public listeners, both protected by TLS.
- Browser controls remain behind VJ operator authentication.
- DJ traffic remains behind DJ authentication.
- Browser and DJ roles remain separate handlers and ports.
- No public plaintext downgrade.
- No first-use certificate trust.
- Fingerprint verification occurs before credentials or audio.
- Browser WebSocket origin is constrained to the configured panel origin.
- Static paths remain canonically contained and non-cacheable.
- Metrics and Minecraft renderer services remain loopback-only.
- Existing authentication rate limits, request correlation, authority epochs/revisions, and session cleanup remain unchanged.

## Testing Strategy

### Python gateway and deployment tests

- Real TLS GET and HEAD for admin and preview assets
- redirects, MIME types, 404s, and exactly one no-store header
- raw, nested, percent-encoded, and backslash traversal rejection
- successful `/ws` upgrade with allowed origin
- rejection for wrong origin, wrong path, malformed upgrade, and oversized messages
- real browser auth/no-auth negotiation through the adapter
- browser disconnect cleanup and superseded-session behavior
- unified mode starts no `8766` listener
- exact Pterodactyl launch arguments for `8080` and `25808`
- `8080` and `25808` listener bind failures remain isolated from Paper startup
- public-IP SAN generation, existing SAN validation, and explicit rotation requirement
- release archive and runtime-lock integrity

### Rust DJ client tests

- fingerprint normalization and malformed input rejection
- local TLS fixture with matching self-signed fingerprint
- mismatch, missing certificate, and rotated certificate rejection
- proof that no auth/application frame is sent before fingerprint verification
- normal platform-trusted certificate path without a pin
- no fallback from WSS to WS
- saved host/port/fingerprint profile behavior

### Browser tests

- unified runtime config selects same-origin `wss://.../ws`
- legacy runtime config preserves query override/`8766`
- admin and preview login/session behavior through the new URL construction
- bitmap frames, particles, emergency controls, voice status, and lifecycle routing remain unchanged

### Full verification

- complete admin Node suite
- WebSocket/auth/protocol suites
- complete WSL VJ pytest suite
- Rust `cargo test`
- Ruff check and format check
- configured Bandit
- Vite production build and first-party JavaScript syntax checks
- Pterodactyl release build/verify tests
- real two-port browser smoke on `8080` plus DJ TLS smoke on `25808`
- listener audit proving only `8080` and `25808` bind publicly

## Acceptance Criteria

1. A remote operator forwards only TCP `8080` and `25808`.
2. After the generated certificate is installed or explicitly trusted on the administrator's machine, `https://<public-ip>:8080/` and `/preview/` load completely.
3. Both browser surfaces connect to `wss://<public-ip>:8080/ws` and retain all authenticated live-control behavior.
4. No process publicly binds `8766`, `8765`, or `9001` in Pterodactyl unified mode.
5. A DJ connects to `wss://<public-ip>:25808` only with the configured matching fingerprint.
6. A mismatched or rotated certificate prevents all DJ credentials and audio from leaving the client.
7. Static traversal, mixed-origin WebSockets, plaintext downgrade, and malformed certificate pins are rejected.
8. Existing non-Pterodactyl split-port development remains supported.
9. The redesigned five-workspace VJ control panel and final bitmap/particle rendering remain intact.
10. All automated, packaging, security, and real two-port smoke checks pass before branch integration.
