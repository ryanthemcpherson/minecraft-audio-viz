# Security Audit Design — Pre-Deployment Hardening

**Date:** 2026-03-04
**Approach:** Attack-surface-in (internet-facing → internal)
**Goal:** Fix all critical/high issues, harden medium, document low for follow-up

## Findings Summary

| Severity | Count |
|-|-|
| Critical | 2 |
| High | 7 |
| Medium | 12 |
| Low | 10 |

## Critical Findings

### C1 — Live Resend API Key Committed (`coordinator/.env`)
A live Resend API key (`re_DosoF1XX_...`) is committed in `coordinator/.env`. Must be rotated immediately. Ensure `coordinator/.env` is gitignored.

### C2 — Unauthenticated `/disconnect/{dj_session_id}` (`coordinator/app/routers/connect.py:280-324`)
Any caller who knows/guesses a UUID can disconnect DJs and decrement show counts. No auth required.

**Fix:** Require server JWT auth (same as other server-scoped endpoints).

## High Findings

### H1 — Plugin `bitmap_image` Path Traversal (`MessageHandler.java:2498-2505`)
`load_file` path from WS message is passed directly to `new File(path)` with zero sanitization. Full filesystem read.

**Fix:** Resolve against `plugin.getDataFolder()`, verify canonical path containment, reject absolute paths.

### H2 — No Auth on Plugin WS Port 8765 (`VizWebSocketServer.java:83-104`)
Any network client gets full command access. No credential check, no origin validation.

**Fix:** Shared-secret handshake on connect. Reject unauthenticated connections after 5s timeout.

### H3 — Unauthenticated `/servers/register` (`coordinator/app/routers/servers.py:80-122`)
Any caller can register servers and receive JWT secrets. No auth, insufficient rate limiting.

**Fix:** Require admin JWT or registration token. At minimum, aggressive rate limiting (3/hour per IP).

### H4 — OAuth Silent Account Takeover (`coordinator/app/services/auth_service.py:213-231, 304-319`)
OAuth login silently links to existing email-only accounts without confirmation, enabling account takeover if attacker controls a Discord/Google account with the same email.

**Fix:** Require confirmation (email verification or password entry) before linking OAuth to existing accounts.

### H5 — Unauthenticated Coordinator `/metrics` (`coordinator/app/routers/metrics.py:28`)
Exposes endpoint names, call volumes, error rates, latency data publicly.

**Fix:** Add optional bearer token auth. Require token in production.

### H6 — Rate Limiter Not Proxy-Aware (`coordinator/app/middleware/rate_limit.py:43`)
Uses `request.client.host` which is the proxy IP behind a reverse proxy. All users share one rate limit bucket.

**Fix:** Use `X-Forwarded-For` with trusted proxy list. Fall back to `client.host` when no proxy configured.

### H7 — Docker: Default Postgres Password Exposed (`docker-compose.yml:69-73`)
`POSTGRES_PASSWORD` defaults to `postgres`, port 5432 mapped to host.

**Fix:** Remove host port mapping. Remove default password fallback — require explicit env var.

## Medium Findings

### M1 — Plugin `bitmap_dj_logo` Absolute Path Bypass (`MessageHandler.java:2536-2542`)
Relative paths sandboxed correctly, but absolute paths bypass containment.

**Fix:** Apply canonical-path containment to all paths regardless of absolute/relative.

### M2 — Browser WS 10MB Message Size (`vj_server.py:6222`)
Single client can send 10MB messages, causing memory/parse overhead.

**Fix:** Reduce `max_size` to 64KB (matches DJ port).

### M3 — No WS Connection Limits (all 3 WS servers)
Unlimited concurrent connections on all ports.

**Fix:** Add limits: 5 DJ, 50 browser, 2 plugin connections.

### M4 — Lua Sandbox Missing CPU Timeout (`vj_server/patterns.py`)
Infinite loop in a pattern permanently blocks the Python event loop.

**Fix:** Run `calculate()` in a thread with 100ms timeout. Kill on expiry.

### M5 — VJ Metrics Binds 0.0.0.0 (`vj_server/metrics.py`)
No auth, binds to all interfaces.

**Fix:** Bind to `127.0.0.1` by default.

### M6 — Banner Pixel Path Unsanitized (`vj_server.py:2452`)
`dj_id` from auth config used in path without sanitization. Admin-controlled input but no defense-in-depth.

**Fix:** Regex sanitize `dj_id` + `is_relative_to` check.

### M7 — OAuth State Missing `redirect_uri` Binding (`coordinator/app/routers/auth.py:295-300`)
State JWT doesn't bind the redirect URI, allowing replay with a different redirect target.

**Fix:** Include `redirect_uri` in state JWT, verify on callback.

### M8 — Webhook Secret Non-Constant-Time Comparison (`coordinator/app/routers/discord_webhooks.py:37`)
Plain `!=` comparison vulnerable to timing attacks.

**Fix:** Use `hmac.compare_digest`.

### M9 — OAuth Account Deletion Without Confirmation (`coordinator/app/routers/auth.py:883-888`)
OAuth-only users can delete accounts without any credential confirmation.

**Fix:** Require re-authentication before account deletion.

### M10 — Coordinator Dockerfile Runs as Root (`coordinator/Dockerfile.local`)
No `USER` directive.

**Fix:** Add `RUN useradd -m -u 1000 mcav` + `USER mcav`.

### M11 — JWT Secret Insecure Default (`docker-compose.yml`)
JWT secret defaults to `CHANGE-ME-in-production` in dev mode with only a warning.

**Fix:** Fail hard on default JWT secret, or require explicit dev-mode opt-in.

### M12 — Demo Compose `--no-auth` Unguarded (`docker-compose.demo.yml:37`)
No safeguard preventing accidental production use of no-auth mode.

**Fix:** Require `MCAV_DEMO_MODE=true` env var to allow `--no-auth`.

## Low Findings

| ID | Component | Issue |
|-|-|-|
| L1 | Plugin | Zone name not validated before `zoneExists()` |
| L2 | Plugin | `init_pool` count unclamped at handler layer |
| L3 | Plugin | No per-tick aggregate particle cap |
| L4 | Plugin | Pong check uses full-body `contains()` |
| L5 | VJ Server | `pcall`/`xpcall` not removed from Lua sandbox |
| L6 | VJ Server | `string.rep` allows memory bomb in Lua |
| L7 | Coordinator | Refresh tokens hashed with fast SHA-256 |
| L8 | Coordinator | No Content-Security-Policy header |
| L9 | Coordinator | `MCAV_ENV` defaults to dev (fails open) |
| L10 | Docker | Nginx missing security headers |

## Confirmed Safe

- Root `.env` — correctly gitignored, not committed
- `vj_server/auth.py` — bcrypt, constant-time comparison, solid
- Scene file handling — regex + `is_relative_to` defense-in-depth
- RecordingManager — `SAFE_NAME` regex, no deserialization risk
- GUI menus — permission-gated, no user-controlled slot indices
- Rust DJ client — TLS on non-LAN, cert validation, 1MB message cap

## Remediation Phases

### Phase 1: Immediate (Critical + High)
C1, C2, H1–H7 — all internet-facing and network-exploitable issues.

### Phase 2: Hardening (Medium)
M1–M12 — defense-in-depth, DoS mitigation, auth tightening.

### Phase 3: Polish (Low)
L1–L10 — input validation tightening, header hardening, sandbox polish.

## Out of Scope
- Dependency CVE scanning (recommend `cargo audit`, `npm audit`, `pip-audit` in CI)
- Penetration testing of live deployment
- Git history scrubbing (beyond rotating C1)
