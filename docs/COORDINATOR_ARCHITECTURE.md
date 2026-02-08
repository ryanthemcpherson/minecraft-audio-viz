# Central DJ Coordinator -- Architecture Document

> **Status:** Proposed
> **Version:** 0.1.0-draft
> **Last updated:** 2026-02-07

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Architecture Overview](#2-architecture-overview)
3. [Resolution Chain](#3-resolution-chain)
4. [Connect Code Design](#4-connect-code-design)
5. [API Endpoints](#5-api-endpoints)
6. [Data Model](#6-data-model)
7. [Authentication Flow](#7-authentication-flow)
8. [Rate Limiting](#8-rate-limiting)
9. [Security Considerations](#9-security-considerations)
10. [Implementation Phases](#10-implementation-phases)
11. [Deployment](#11-deployment)
12. [VJ Server Changes Required](#12-vj-server-changes-required)
13. [DJ Client Changes Required](#13-dj-client-changes-required)

Appendices:
- [A. FastAPI Application Skeleton](#appendix-a-fastapi-application-skeleton)
- [B. Configuration Module](#appendix-b-configuration-module)
- [C. Migration Guide](#appendix-c-migration-guide)

---

## 1. Problem Statement

### Current State

The existing MCAV system requires DJs to know the exact hostname and port of the VJ server before connecting. Two authentication paths exist today:

1. **Credential auth (`dj_auth`)** -- The DJ client sends `{ "type": "dj_auth", "dj_id": "...", "dj_key": "...", "dj_name": "..." }` directly to the VJ server WebSocket at port 9000. The server verifies credentials against `DJAuthConfig` loaded from `configs/dj_auth.json`, which calls `auth.verify_password()` (bcrypt or SHA256).

2. **Connect code auth (`code_auth`)** -- The admin panel generates a temporary code via the browser WebSocket (`generate_connect_code` handler). The DJ client sends `{ "type": "code_auth", "code": "BEAT-7K3M", "dj_name": "..." }`. The VJ server validates the code against its in-memory `connect_codes` dict. Codes are single-use with a 30-minute TTL.

### Limitations

| Limitation | Impact |
|---|---|
| **Server discovery is manual** | DJs must know the IP/hostname and port. For LAN this is manageable; for WAN it is fragile and requires dynamic DNS or manual coordination. |
| **Connect codes are server-local** | Codes are generated and validated within a single `VJServer` instance. There is no way to resolve a code to a server endpoint without already being connected to that server. |
| **No multi-show support** | A single VJ operator running two shows (e.g. two Minecraft servers) has no namespace separation. All DJs connect to the same port and share the same pool. |
| **No centralized DJ registry** | Each VJ server maintains its own `dj_auth.json`. A DJ who plays at multiple venues needs separate credentials for each. |
| **No public discoverability** | There is no API that a mobile app, web client, or third-party integration can query to find active shows or resolve codes. |

### Goal

Introduce a **Central DJ Coordinator** -- a lightweight REST service that:

- Resolves connect codes to VJ server WebSocket URLs
- Manages shows (logical groupings of a VJ server instance + metadata)
- Mints short-lived JWTs that the DJ client presents to the VJ server
- Provides a public API for discoverability without exposing server infrastructure
- Remains fully **optional** -- existing direct `dj_auth` and `code_auth` flows continue to work unchanged
---

## 2. Architecture Overview

### System Diagram

```
                  +-------------------+
                  | Central DJ        |
  DJ Client ----->| Coordinator       |-----> JWT mint
  (Tauri/Web)     | (FastAPI, :8090)  |
                  +--------+----------+
                           |
                           | REST API
                           |
            +--------------+--------------+
            |              |              |
     +------v------+ +----v-----+ +------v------+
     | VJ Server A | | VJ Srv B | | VJ Server C |
     | (WS :9000)  | | (WS:9000)| | (WS :9000)  |
     +------+------+ +----+-----+ +------+------+
            |              |              |
         MC Plugin      MC Plugin      MC Plugin
```

### Technology Stack

| Layer | Technology | Rationale |
|---|---|---|
| HTTP framework | **FastAPI** (Python 3.10+) | Already in the project ecosystem; async-native; automatic OpenAPI docs |
| Database (dev) | **SQLite** via `aiosqlite` | Zero-config, file-based, sufficient for single-coordinator deployment |
| Database (prod) | **PostgreSQL** via `asyncpg` | For multi-instance HA deployments behind a load balancer |
| Auth tokens | **PyJWT** with HS256 | Lightweight; per-server secrets avoid a shared signing key |
| Rate limiting | In-memory sliding window (dev) / Redis (prod) | Simple, effective, low overhead |
| Config | `pydantic-settings` | Type-safe env-var loading, consistent with FastAPI patterns |

### Design Principles

1. **Stateless coordinator** -- The coordinator never proxies audio data. It resolves codes and mints tokens. All audio still flows directly from the DJ client to the VJ server over WebSocket.
2. **Backward compatible** -- All existing auth paths (`dj_auth`, `code_auth`) remain functional. The coordinator adds a third path (`token_auth`) that VJ servers can opt into.
3. **Minimal trust surface** -- The coordinator does not store DJ passwords. It stores server API keys (hashed) and code-to-server mappings. JWTs are short-lived (15 min) and scoped to a single server.
4. **Fail-open for local** -- If the coordinator is unreachable, DJ clients fall back to direct connection using the existing `code_auth` or `dj_auth` paths.

---

## 3. Resolution Chain

The coordinator implements a three-level resolution chain:

```
Connect Code  --->  Show  --->  VJ Server  --->  ws_url + JWT
   BEAT-7K3M        "Friday Night"   server_abc    ws://1.2.3.4:9000 + eyJ...
```

### Step-by-Step Flow

```
DJ Client                     Coordinator                    VJ Server
   |                              |                              |
   |  POST /resolve {"code":"BEAT-7K3M"}                         |
   |----------------------------->|                              |
   |                              | look up code -> show -> server
   |                              | mint JWT(sub=dj, aud=server_id, exp=15m)
   |                              |                              |
   |  200 {"ws_url":"ws://...","token":"eyJ..."}                 |
   |<-----------------------------|                              |
   |                              |                              |
   |  WS connect to ws_url                                       |
   |----------------------------------------------------->|     |
   |  {"type":"token_auth","token":"eyJ...","dj_name":"X"}       |
   |----------------------------------------------------->|     |
   |                              |                        | verify JWT
   |                              |                        | (HS256 w/ server secret)
   |  {"type":"auth_success",...}                                 |
   |<-----------------------------------------------------|     |
   |                              |                              |
   |  Normal audio frame flow (unchanged)                        |
   |<==========================================================>|
```

### Resolution Rules

1. A connect code maps to exactly one show.
2. A show maps to exactly one VJ server (the `primary_server_id`).
3. A VJ server has exactly one `ws_url` registered with the coordinator.
4. If the code is expired, used, or unknown, the coordinator returns `404`.
5. If the server's `ws_url` is not set, the coordinator returns `503` (server registered but offline).
---

## 4. Connect Code Design

### Format

Connect codes follow the existing format established in `vj_server.py`:

```
WORD-XXXX
```

- **WORD**: One of 24 music-themed words from the existing `CONNECT_CODE_WORDS` list:
  `BEAT, BASS, DROP, WAVE, KICK, SYNC, LOOP, VIBE, RAVE, FUNK, JAZZ, ROCK, FLOW, PEAK, PUMP, TUNE, PLAY, SPIN, FADE, RISE, BOOM, DRUM, HIGH, DEEP`

- **XXXX**: Four characters from the existing `CODE_CHARS` alphabet:
  `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (30 characters; excludes confusables O/0/I/1/L)

### Keyspace

```
24 words x 30^4 suffixes = 24 x 810,000 = 19,440,000 unique codes
```

At any given time, active codes are a tiny fraction of the keyspace (typically < 100 concurrent), making collisions negligible.

### Generation

Codes are generated on the **VJ server** (as today) and **registered** with the coordinator via the API. The coordinator does not generate codes -- it only stores the mapping.

```python
# VJ server generates code (existing logic in ConnectCode.generate())
code = ConnectCode.generate(ttl_minutes=30)

# VJ server registers with coordinator (new)
requests.post(f"{coordinator_url}/api/v1/codes", json={
    "code": code.code,
    "show_id": current_show_id,
    "ttl_seconds": 1800,
}, headers={"Authorization": f"Bearer {server_api_key}"})
```

### Lifecycle

| State | Meaning |
|---|---|
| `active` | Code is valid and resolvable |
| `used` | Code was resolved by a DJ (single-use) |
| `expired` | TTL elapsed without resolution |
| `revoked` | Manually revoked by VJ operator |

### Validation Regex

Consistent with `protocol/schemas/messages/code-auth.schema.json`:

```
^[A-Z]{4}-[A-Z2-9]{4}$
```

---

## 5. API Endpoints

All endpoints are prefixed with `/api/v1`.

### 5.1 `POST /resolve`

Resolve a connect code to a WebSocket URL and JWT. This is the primary endpoint used by DJ clients.

**Authentication:** None (public endpoint, rate-limited)

**Request:**
```json
{
  "code": "BEAT-7K3M",
  "dj_name": "DJ Spark"
}
```

**Success Response (200):**
```json
{
  "ws_url": "ws://vj.example.com:9000",
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "show_name": "Friday Night Beats",
  "server_name": "Main Stage",
  "expires_in": 900
}
```

**Error Responses:**
- `404` -- Code not found, expired, or already used
- `429` -- Rate limit exceeded
- `503` -- Server registered but offline (no `ws_url`)

**Implementation Notes:**
- Marks the code as `used` atomically (single-use)
- Mints a JWT with `sub=dj_name`, `aud=server_id`, `exp=now+15min`, `show_id=...`
- The JWT secret is the target server's `jwt_secret` stored in the `servers` table

---

### 5.2 `POST /servers`

Register a new VJ server with the coordinator. Called once during VJ server setup.

**Authentication:** Admin API key (via `X-API-Key` header)

**Request:**
```json
{
  "name": "Main Stage",
  "ws_url": "ws://vj.example.com:9000",
  "owner_name": "VJ Alice"
}
```

**Success Response (201):**
```json
{
  "server_id": "srv_a1b2c3d4",
  "api_key": "mcav_sk_Tq8x...",
  "jwt_secret": "jws_Kp4m...",
  "name": "Main Stage"
}
```

**Error Responses:**
- `401` -- Missing or invalid admin API key
- `409` -- Server name already registered for this owner

**Implementation Notes:**
- `server_id` is a prefixed random ID: `srv_` + 8 hex chars
- `api_key` is generated via `secrets.token_urlsafe(32)`, prefixed with `mcav_sk_`
- `jwt_secret` is generated via `secrets.token_urlsafe(32)`, prefixed with `jws_`
- The API key is returned in plaintext exactly once; stored as a bcrypt hash
---

### 5.3 `POST /shows`

Create a new show (a named session associated with a server).

**Authentication:** Server API key (via `Authorization: Bearer <api_key>`)

**Request:**
```json
{
  "name": "Friday Night Beats",
  "server_id": "srv_a1b2c3d4"
}
```

**Success Response (201):**
```json
{
  "show_id": "shw_e5f6g7h8",
  "name": "Friday Night Beats",
  "server_id": "srv_a1b2c3d4",
  "created_at": "2026-02-07T20:00:00Z"
}
```

**Error Responses:**
- `401` -- Invalid API key
- `403` -- API key does not belong to the specified server
- `409` -- Active show with this name already exists for this server

---

### 5.4 `POST /codes`

Register a connect code generated by the VJ server.

**Authentication:** Server API key

**Request:**
```json
{
  "code": "BEAT-7K3M",
  "show_id": "shw_e5f6g7h8",
  "ttl_seconds": 1800
}
```

**Success Response (201):**
```json
{
  "code": "BEAT-7K3M",
  "show_id": "shw_e5f6g7h8",
  "expires_at": "2026-02-07T20:30:00Z",
  "status": "active"
}
```

**Error Responses:**
- `401` -- Invalid API key
- `403` -- API key does not own the show
- `409` -- Code already registered and still active
- `422` -- Invalid code format

---

### 5.5 `DELETE /codes/{code}`

Revoke an active connect code.

**Authentication:** Server API key

**Success Response (200):**
```json
{
  "code": "BEAT-7K3M",
  "status": "revoked"
}
```

**Error Responses:**
- `401` -- Invalid API key
- `403` -- API key does not own the code's show
- `404` -- Code not found

---

### 5.6 `GET /health`

Health check endpoint for load balancers and monitoring.

**Authentication:** None

**Response (200):**
```json
{
  "status": "ok",
  "version": "0.1.0",
  "uptime_seconds": 3600,
  "active_servers": 3,
  "active_shows": 2,
  "active_codes": 12
}
```
---

## 6. Data Model

### Entity Relationship

```
servers  1---*  shows  1---*  codes
   |
   +-- server_id (PK)
   +-- name
   +-- ws_url
   +-- api_key_hash
   +-- jwt_secret
   +-- owner_name
   +-- created_at
   +-- last_heartbeat

shows
   +-- show_id (PK)
   +-- server_id (FK -> servers)
   +-- name
   +-- is_active
   +-- created_at
   +-- ended_at

codes
   +-- code (PK)
   +-- show_id (FK -> shows)
   +-- status (active | used | expired | revoked)
   +-- created_at
   +-- expires_at
   +-- resolved_by_name
   +-- resolved_at

rate_limits
   +-- ip_address (PK component)
   +-- window_start (PK component)
   +-- request_count
```

### SQLite Schema

```sql
CREATE TABLE IF NOT EXISTS servers (
    server_id   TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    ws_url      TEXT,
    api_key_hash TEXT NOT NULL,
    jwt_secret  TEXT NOT NULL,
    owner_name  TEXT NOT NULL DEFAULT '',
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    last_heartbeat TEXT
);

CREATE TABLE IF NOT EXISTS shows (
    show_id     TEXT PRIMARY KEY,
    server_id   TEXT NOT NULL REFERENCES servers(server_id),
    name        TEXT NOT NULL,
    is_active   INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    ended_at    TEXT
);

CREATE INDEX IF NOT EXISTS idx_shows_server ON shows(server_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_shows_active_name
    ON shows(server_id, name) WHERE is_active = 1;

CREATE TABLE IF NOT EXISTS codes (
    code            TEXT PRIMARY KEY,
    show_id         TEXT NOT NULL REFERENCES shows(show_id),
    status          TEXT NOT NULL DEFAULT 'active'
                        CHECK(status IN ('active', 'used', 'expired', 'revoked')),
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    expires_at      TEXT NOT NULL,
    resolved_by_name TEXT,
    resolved_at     TEXT
);

CREATE INDEX IF NOT EXISTS idx_codes_show ON codes(show_id);
CREATE INDEX IF NOT EXISTS idx_codes_status ON codes(status) WHERE status = 'active';

CREATE TABLE IF NOT EXISTS rate_limits (
    ip_address   TEXT NOT NULL,
    window_start TEXT NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (ip_address, window_start)
);
```

### Automatic Expiry

A background task runs every 60 seconds to mark expired codes:

```sql
UPDATE codes
SET status = 'expired'
WHERE status = 'active'
  AND expires_at < datetime('now');
```
---

## 7. Authentication Flow

### Three Auth Paths (after coordinator integration)

```
Path 1: dj_auth     (existing, unchanged)
  DJ --[dj_id, dj_key]--> VJ Server --[verify against dj_auth.json]--> OK

Path 2: code_auth   (existing, unchanged)
  DJ --[code]--> VJ Server --[verify against in-memory codes]--> OK

Path 3: token_auth  (NEW, coordinator-minted JWT)
  DJ --[code]--> Coordinator --[resolve, mint JWT]--> DJ
  DJ --[token]--> VJ Server --[verify JWT signature]--> OK
```

### JWT Structure

**Header:**
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

**Payload:**
```json
{
  "sub": "DJ Spark",
  "aud": "srv_a1b2c3d4",
  "iss": "mcav-coordinator",
  "iat": 1738972800,
  "exp": 1738973700,
  "show_id": "shw_e5f6g7h8",
  "show_name": "Friday Night Beats",
  "code": "BEAT-7K3M"
}
```

**Signing:** HS256 using the target server's `jwt_secret` stored in the coordinator database. This means each VJ server only needs its own secret to verify tokens -- no shared signing key.

### Token Auth WebSocket Message

New message type added to the protocol:

```json
{
  "type": "token_auth",
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "dj_name": "DJ Spark"
}
```

**VJ Server Verification:**

```python
import jwt

def verify_token(token: str, jwt_secret: str, server_id: str) -> dict:
    """Verify a coordinator-minted JWT.

    Returns decoded payload on success, raises on failure.
    """
    payload = jwt.decode(
        token,
        jwt_secret,
        algorithms=["HS256"],
        audience=server_id,
        issuer="mcav-coordinator",
    )
    return payload
```

### Token Refresh

JWTs have a 15-minute TTL. For long sessions, the DJ client can re-resolve the code (if it is a multi-use variant in a future version) or the VJ server can issue session tokens independently after the initial JWT handshake. In v1, the JWT is only used for the initial connection; the WebSocket session persists independently.
---

## 8. Rate Limiting

### Strategy

Rate limiting protects the public `/resolve` endpoint from brute-force code guessing and abuse.

**Algorithm:** Sliding window counter per IP address.

### Configuration

| Parameter | Default (dev) | Production |
|---|---|---|
| Window size | 60 seconds | 60 seconds |
| Max requests per window (resolve) | 10 | 10 |
| Max requests per window (other) | 60 | 60 |
| Failure cooldown threshold | 5 consecutive failures | 5 |
| Cooldown duration | 300 seconds (5 min) | 300 seconds |
| Backend | In-memory dict | Redis |

### In-Memory Implementation (Development)

```python
import time
from collections import defaultdict
from dataclasses import dataclass, field

@dataclass
class RateLimitEntry:
    timestamps: list = field(default_factory=list)
    consecutive_failures: int = 0
    cooldown_until: float = 0.0

class InMemoryRateLimiter:
    def __init__(self, window_seconds: int = 60, max_requests: int = 10):
        self.window = window_seconds
        self.max_requests = max_requests
        self.entries: dict[str, RateLimitEntry] = defaultdict(RateLimitEntry)

    def check(self, ip: str) -> bool:
        """Return True if request is allowed, False if rate-limited."""
        entry = self.entries[ip]
        now = time.time()

        # Check cooldown
        if now < entry.cooldown_until:
            return False

        # Prune old timestamps
        cutoff = now - self.window
        entry.timestamps = [t for t in entry.timestamps if t > cutoff]

        if len(entry.timestamps) >= self.max_requests:
            return False

        entry.timestamps.append(now)
        return True

    def record_failure(self, ip: str):
        """Record a failed resolution attempt."""
        entry = self.entries[ip]
        entry.consecutive_failures += 1
        if entry.consecutive_failures >= 5:
            entry.cooldown_until = time.time() + 300  # 5-minute cooldown

    def record_success(self, ip: str):
        """Reset failure counter on success."""
        self.entries[ip].consecutive_failures = 0
```

### Redis Implementation (Production)

```python
import redis.asyncio as redis

class RedisRateLimiter:
    def __init__(self, redis_url: str, window: int = 60, max_requests: int = 10):
        self.redis = redis.from_url(redis_url)
        self.window = window
        self.max_requests = max_requests

    async def check(self, ip: str) -> bool:
        key = f"rate:{ip}"
        pipe = self.redis.pipeline()
        now = time.time()

        pipe.zremrangebyscore(key, 0, now - self.window)
        pipe.zcard(key)
        pipe.zadd(key, {str(now): now})
        pipe.expire(key, self.window)

        results = await pipe.execute()
        count = results[1]
        return count < self.max_requests
```

### Response Headers

All rate-limited endpoints include standard headers:

```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 7
X-RateLimit-Reset: 1738972860
```

When rate-limited, the response is:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 45
Content-Type: application/json

{"detail": "Rate limit exceeded. Try again in 45 seconds."}
```
---

## 9. Security Considerations

### 9.1 API Key Security

- Server API keys are generated via `secrets.token_urlsafe(32)` (43 characters of URL-safe base64, 256 bits of entropy).
- Keys are prefixed with `mcav_sk_` for easy identification in logs and config files.
- The plaintext key is returned exactly once (at server registration) and never stored.
- The database stores only the bcrypt hash (using `auth.hash_password()`).
- Verification uses `auth.verify_password()` with constant-time comparison.

### 9.2 JWT Security

- **Algorithm:** HS256 only. The `algorithms` parameter is always explicit to prevent algorithm confusion attacks.
- **Per-server secrets:** Each VJ server has its own `jwt_secret`. A compromised secret affects only that server.
- **Short TTL:** 15-minute expiry limits the window of a stolen token.
- **Audience claim:** JWTs include `aud=server_id`, preventing use against a different server even if the secret were reused.
- **Issuer claim:** `iss=mcav-coordinator` prevents confusion with other JWT issuers.
- **No refresh tokens:** In v1, JWTs are single-use for the initial connection. The WebSocket session has its own lifetime.

### 9.3 Connect Code Security

- **Brute-force resistance:** 19.4M keyspace + rate limiting (10 attempts/min) + cooldown after 5 failures = ~32,400 hours to enumerate at max rate.
- **Single-use:** Each code is marked `used` atomically on first resolution.
- **Short TTL:** Default 30-minute expiry. Configurable per code.
- **No code recycling:** Expired/used codes are never reassigned.

### 9.4 Transport Security

- **Production:** All coordinator endpoints must be served over HTTPS (TLS 1.2+).
- **Development:** HTTP is acceptable for `localhost` only.
- **WebSocket:** The `ws_url` returned by the coordinator may be `ws://` (LAN) or `wss://` (WAN). The coordinator does not enforce this -- it is the VJ server operator's responsibility.

### 9.5 CORS Policy

```python
# FastAPI CORS configuration
origins = [
    "http://localhost:8080",      # Local admin panel
    "http://localhost:5173",      # Vite dev server
    "https://admin.mcav.live",    # Production admin panel (future)
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=["Authorization", "X-API-Key", "Content-Type"],
    max_age=3600,
)
```

### 9.6 Input Validation

All inputs are validated using Pydantic models:

```python
from pydantic import BaseModel, Field, field_validator
import re

CODE_PATTERN = re.compile(r'^[A-Z]{4}-[A-Z2-9]{4}$')

class ResolveRequest(BaseModel):
    code: str = Field(..., min_length=9, max_length=9)
    dj_name: str = Field(..., min_length=1, max_length=64)

    @field_validator('code')
    @classmethod
    def validate_code_format(cls, v: str) -> str:
        if not CODE_PATTERN.match(v):
            raise ValueError(
                'Code must match format WORD-XXXX '
                '(4 uppercase letters, dash, 4 alphanumeric characters)'
            )
        return v

class RegisterServerRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=128)
    ws_url: str = Field(..., pattern=r'^wss?://.+')
    owner_name: str = Field('', max_length=128)
```
---

## 10. Implementation Phases

### Phase 1: Core Coordinator (Week 1-2)

**Goal:** Working `/resolve` endpoint with SQLite backend.

**Tasks:**
- [ ] Create `coordinator/` package directory
- [ ] Implement FastAPI application with health endpoint
- [ ] Implement SQLite database layer (`coordinator/db.py`)
- [ ] Implement server registration endpoint (`POST /servers`)
- [ ] Implement show management endpoint (`POST /shows`)
- [ ] Implement code registration endpoint (`POST /codes`, `DELETE /codes/{code}`)
- [ ] Implement code resolution endpoint (`POST /resolve`)
- [ ] Implement JWT minting with PyJWT
- [ ] Implement in-memory rate limiter
- [ ] Add Pydantic request/response models
- [ ] Write unit tests for all endpoints
- [ ] Write integration test: register server -> create show -> register code -> resolve code

### Phase 2: VJ Server Integration (Week 2-3)

**Goal:** VJ server registers with coordinator and validates JWTs.

**Tasks:**
- [ ] Add `--coordinator-url` and `--server-api-key` CLI flags to `audioviz-vj`
- [ ] Implement code registration on generation (push new codes to coordinator)
- [ ] Implement code revocation sync (push revocations to coordinator)
- [ ] Add `token_auth` message handler to `_handle_dj_connection()`
- [ ] Add `--jwt-secret` CLI flag (or read from coordinator registration response)
- [ ] Add show management commands to admin panel
- [ ] Write contract tests against protocol schemas

### Phase 3: DJ Client Integration (Week 3-4)

**Goal:** DJ client resolves codes via coordinator before connecting.

**Tasks:**
- [ ] Add `coordinator_url` field to `DjClientConfig` (Rust)
- [ ] Implement HTTP resolution step before WebSocket connection
- [ ] Add `TokenAuthMessage` struct to `messages.rs`
- [ ] Update `DjClient::connect()` to try coordinator resolution first, fall back to direct
- [ ] Update Tauri frontend to show resolution status
- [ ] Add coordinator URL to settings UI
- [ ] Write Rust unit tests for resolution and fallback

### Phase 4: Production Hardening (Week 4+)

**Goal:** Production-ready deployment with monitoring.

**Tasks:**
- [ ] Add PostgreSQL support via `asyncpg` (optional, behind config flag)
- [ ] Add Redis rate limiter backend
- [ ] Add Prometheus metrics endpoint (`/metrics`)
- [ ] Add structured JSON logging
- [ ] Write Dockerfile and docker-compose configuration
- [ ] Write Fly.io deployment configuration
- [ ] Add CI pipeline step for coordinator tests
- [ ] Write deployment documentation
- [ ] Load test the `/resolve` endpoint (target: 100 req/s)
---

## 11. Deployment

### Docker

```dockerfile
# coordinator/Dockerfile
FROM python:3.12-slim

WORKDIR /app

COPY coordinator/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY coordinator/ ./coordinator/
COPY audio_processor/auth.py ./audio_processor/auth.py

EXPOSE 8090

CMD ["uvicorn", "coordinator.main:app", "--host", "0.0.0.0", "--port", "8090"]
```

### Docker Compose (Full Stack)

```yaml
# docker-compose.yml
version: "3.9"

services:
  coordinator:
    build:
      context: .
      dockerfile: coordinator/Dockerfile
    ports:
      - "8090:8090"
    environment:
      COORDINATOR_ADMIN_KEY: "${COORDINATOR_ADMIN_KEY}"
      COORDINATOR_DB_URL: "sqlite:///data/coordinator.db"
    volumes:
      - coordinator-data:/app/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8090/api/v1/health"]
      interval: 30s
      timeout: 5s
      retries: 3

  vj-server:
    build:
      context: .
      dockerfile: Dockerfile.vj
    ports:
      - "9000:9000"
      - "8080:8080"
    environment:
      COORDINATOR_URL: "http://coordinator:8090"
      SERVER_API_KEY: "${VJ_SERVER_API_KEY}"
      JWT_SECRET: "${VJ_JWT_SECRET}"
    depends_on:
      coordinator:
        condition: service_healthy

volumes:
  coordinator-data:
```

### Fly.io

```toml
# coordinator/fly.toml
app = "mcav-coordinator"
primary_region = "iad"

[build]
  dockerfile = "coordinator/Dockerfile"

[env]
  COORDINATOR_DB_URL = "sqlite:///data/coordinator.db"

[http_service]
  internal_port = 8090
  force_https = true
  auto_stop_machines = false
  auto_start_machines = true

[mounts]
  source = "coordinator_data"
  destination = "/app/data"

[[vm]]
  size = "shared-cpu-1x"
  memory = "256mb"
```

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `COORDINATOR_ADMIN_KEY` | Yes | -- | Admin API key for server registration |
| `COORDINATOR_DB_URL` | No | `sqlite:///coordinator.db` | Database connection URL |
| `COORDINATOR_PORT` | No | `8090` | HTTP listen port |
| `COORDINATOR_JWT_DEFAULT_TTL` | No | `900` | Default JWT TTL in seconds |
| `COORDINATOR_RATE_LIMIT_WINDOW` | No | `60` | Rate limit window in seconds |
| `COORDINATOR_RATE_LIMIT_MAX` | No | `10` | Max requests per window for `/resolve` |
| `COORDINATOR_REDIS_URL` | No | -- | Redis URL for production rate limiting |
| `COORDINATOR_LOG_LEVEL` | No | `INFO` | Logging level |
| `COORDINATOR_CORS_ORIGINS` | No | `http://localhost:8080` | Comma-separated CORS origins |
---

## 12. VJ Server Changes Required

### 12.1 New CLI Flags

Add to `vj_server.py` argument parser:

```python
# Coordinator integration
parser.add_argument('--coordinator-url', type=str, default=None,
                    help='Central DJ Coordinator URL (e.g., http://coordinator:8090)')
parser.add_argument('--server-api-key', type=str, default=None,
                    help='API key for coordinator registration')
parser.add_argument('--jwt-secret', type=str, default=None,
                    help='JWT secret for token_auth verification')
parser.add_argument('--show-name', type=str, default=None,
                    help='Show name for coordinator registration')
```

These can also be set via environment variables: `COORDINATOR_URL`, `SERVER_API_KEY`, `JWT_SECRET`, `SHOW_NAME`.

### 12.2 Coordinator Registration on Startup

When `--coordinator-url` is provided, the VJ server registers itself on startup:

```python
async def _register_with_coordinator(self):
    """Register this server with the coordinator on startup."""
    if not self.coordinator_url:
        return

    # Determine our external WebSocket URL
    # Use explicit flag or auto-detect
    ws_url = self.config.get('external_ws_url') or f"ws://{self.host}:{self.dj_port}"

    try:
        async with aiohttp.ClientSession() as session:
            # Check if already registered (idempotent)
            resp = await session.post(
                f"{self.coordinator_url}/api/v1/servers",
                json={
                    "name": self.server_name,
                    "ws_url": ws_url,
                    "owner_name": self.config.get('owner_name', ''),
                },
                headers={"X-API-Key": self.admin_api_key},
                timeout=aiohttp.ClientTimeout(total=10),
            )
            if resp.status in (200, 201):
                data = await resp.json()
                self.server_id = data['server_id']
                # Store jwt_secret if not already configured
                if not self.jwt_secret:
                    self.jwt_secret = data.get('jwt_secret')
                logger.info(f"Registered with coordinator as {self.server_id}")
            else:
                logger.warning(f"Coordinator registration failed: {resp.status}")
    except Exception as e:
        logger.warning(f"Could not reach coordinator: {e}")
```

### 12.3 Code Registration Sync

When a connect code is generated (via admin panel), also register it with the coordinator:

```python
async def _sync_code_to_coordinator(self, code: ConnectCode):
    """Push a newly generated connect code to the coordinator."""
    if not self.coordinator_url or not self.server_api_key:
        return

    try:
        async with aiohttp.ClientSession() as session:
            await session.post(
                f"{self.coordinator_url}/api/v1/codes",
                json={
                    "code": code.code,
                    "show_id": self.current_show_id,
                    "ttl_seconds": int(code.expires_at - code.created_at),
                },
                headers={"Authorization": f"Bearer {self.server_api_key}"},
                timeout=aiohttp.ClientTimeout(total=5),
            )
    except Exception as e:
        logger.debug(f"Failed to sync code to coordinator: {e}")
```
### 12.4 Token Auth Handler

Add a third authentication path to `_handle_dj_connection()`:

```python
# In _handle_dj_connection(), after existing code_auth and dj_auth handlers:

elif msg_type == 'token_auth':
    token = data.get('token', '')
    dj_name = data.get('dj_name', 'DJ')

    if not self.jwt_secret:
        await ws.send(json.dumps({
            'type': 'auth_error',
            'error': 'Token auth not configured on this server'
        }))
        return

    try:
        import jwt as pyjwt
        payload = pyjwt.decode(
            token,
            self.jwt_secret,
            algorithms=["HS256"],
            audience=self.server_id,
            issuer="mcav-coordinator",
        )
        # Token is valid -- create DJ connection
        dj_id = f"token_{payload.get('code', 'unknown')}"
        dj = DJConnection(
            dj_id=dj_id,
            dj_name=payload.get('sub', dj_name),
            websocket=ws,
            priority=10,
        )
        # ... (rest of connection setup, same as code_auth success path)

    except pyjwt.ExpiredSignatureError:
        await ws.send(json.dumps({
            'type': 'auth_error',
            'error': 'Token expired'
        }))
        return
    except pyjwt.InvalidTokenError as e:
        await ws.send(json.dumps({
            'type': 'auth_error',
            'error': f'Invalid token: {e}'
        }))
        return
```

### 12.5 Show Management

Add show lifecycle management to the VJ server:

```python
async def _create_show(self, name: str) -> Optional[str]:
    """Create a new show via the coordinator."""
    if not self.coordinator_url:
        return None

    try:
        async with aiohttp.ClientSession() as session:
            resp = await session.post(
                f"{self.coordinator_url}/api/v1/shows",
                json={
                    "name": name,
                    "server_id": self.server_id,
                },
                headers={"Authorization": f"Bearer {self.server_api_key}"},
                timeout=aiohttp.ClientTimeout(total=5),
            )
            if resp.status == 201:
                data = await resp.json()
                self.current_show_id = data['show_id']
                logger.info(f"Created show: {name} ({self.current_show_id})")
                return self.current_show_id
    except Exception as e:
        logger.warning(f"Failed to create show: {e}")
    return None
```

### 12.6 Backward Compatibility

All changes are additive:

- The `token_auth` handler is a new `elif` branch -- existing `dj_auth` and `code_auth` branches are untouched.
- Coordinator registration is skipped if `--coordinator-url` is not provided.
- Code sync to coordinator is best-effort (failures are logged and ignored).
- The VJ server continues to work standalone with zero coordinator configuration.
---

## 13. DJ Client Changes Required

### 13.1 Rust: Coordinator Resolution

Add to `DjClientConfig`:

```rust
pub struct DjClientConfig {
    // ... existing fields ...

    /// Central coordinator URL (optional)
    pub coordinator_url: Option<String>,
}
```

Add resolution function:

```rust
use reqwest::Client;
use serde::{Deserialize, Serialize};

#[derive(Serialize)]
struct ResolveRequest {
    code: String,
    dj_name: String,
}

#[derive(Deserialize)]
struct ResolveResponse {
    ws_url: String,
    token: String,
    show_name: String,
    server_name: String,
    expires_in: u64,
}

impl DjClient {
    /// Resolve a connect code via the coordinator.
    /// Returns (ws_url, token) on success.
    async fn resolve_via_coordinator(
        &self,
        code: &str,
    ) -> Result<(String, String), ClientError> {
        let coordinator_url = self.config.coordinator_url.as_ref()
            .ok_or(ClientError::ConnectionFailed("No coordinator URL configured".into()))?;

        let client = Client::builder()
            .timeout(Duration::from_secs(10))
            .build()
            .map_err(|e| ClientError::ConnectionFailed(e.to_string()))?;

        let resp = client
            .post(format!("{}/api/v1/resolve", coordinator_url))
            .json(&ResolveRequest {
                code: code.to_string(),
                dj_name: self.config.dj_name.clone(),
            })
            .send()
            .await
            .map_err(|e| ClientError::ConnectionFailed(
                format!("Coordinator request failed: {}", e)
            ))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(ClientError::ConnectionFailed(
                format!("Coordinator returned {}: {}", status, body)
            ));
        }

        let data: ResolveResponse = resp.json().await
            .map_err(|e| ClientError::ConnectionFailed(e.to_string()))?;

        Ok((data.ws_url, data.token))
    }
}
```

### 13.2 Rust: Token Auth Message

Add to `messages.rs`:

```rust
/// Token-based authentication message (coordinator-minted JWT)
#[derive(Debug, Clone, Serialize)]
pub struct TokenAuthMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub token: String,
    pub dj_name: String,
}

impl TokenAuthMessage {
    pub fn new(token: String, dj_name: String) -> Self {
        Self {
            msg_type: "token_auth".to_string(),
            token,
            dj_name,
        }
    }
}
```
### 13.3 Rust: Updated Connect Flow

Update `DjClient::connect()` to try coordinator resolution first:

```rust
pub async fn connect(&mut self) -> Result<(), ClientError> {
    if self.state.lock().connected {
        return Err(ClientError::AlreadyConnected);
    }

    // Determine connection target and auth method
    let (ws_url, auth_msg) = if let Some(ref code) = self.config.connect_code {
        // Try coordinator resolution first
        if self.config.coordinator_url.is_some() {
            match self.resolve_via_coordinator(code).await {
                Ok((resolved_url, token)) => {
                    log::info!("Resolved code via coordinator -> {}", resolved_url);
                    let auth = serde_json::to_string(
                        &TokenAuthMessage::new(token, self.config.dj_name.clone())
                    ).unwrap();
                    (resolved_url, auth)
                }
                Err(e) => {
                    log::warn!("Coordinator resolution failed: {}, falling back to direct", e);
                    // Fall back to direct code_auth
                    let url = format!(
                        "ws://{}:{}",
                        self.config.server_host, self.config.server_port
                    );
                    let auth = serde_json::to_string(
                        &CodeAuthMessage::new(code.clone(), self.config.dj_name.clone())
                    ).unwrap();
                    (url, auth)
                }
            }
        } else {
            // No coordinator, use direct code_auth
            let url = format!(
                "ws://{}:{}",
                self.config.server_host, self.config.server_port
            );
            let auth = serde_json::to_string(
                &CodeAuthMessage::new(code.clone(), self.config.dj_name.clone())
            ).unwrap();
            (url, auth)
        }
    } else if let (Some(ref id), Some(ref key)) = (&self.config.dj_id, &self.config.dj_key) {
        // Credential-based auth (unchanged)
        let url = format!(
            "ws://{}:{}",
            self.config.server_host, self.config.server_port
        );
        let auth = serde_json::to_string(
            &DjAuthMessage::new(id.clone(), key.clone(), self.config.dj_name.clone())
        ).unwrap();
        (url, auth)
    } else {
        // Anonymous (development)
        let url = format!(
            "ws://{}:{}",
            self.config.server_host, self.config.server_port
        );
        let auth = serde_json::to_string(
            &DjAuthMessage::new("anon".into(), "".into(), self.config.dj_name.clone())
        ).unwrap();
        (url, auth)
    };

    // ... rest of connect() is unchanged (WebSocket connect, send auth, handle handshake) ...
    // except use `ws_url` instead of constructing the URL inline
}
```

### 13.4 TypeScript/Frontend: Resolution

For the Tauri frontend (`App.tsx`), add coordinator resolution before invoking the Rust connect command:

```typescript
async function connectWithCode(code: string, djName: string) {
  const coordinatorUrl = settings.coordinatorUrl;

  if (coordinatorUrl) {
    try {
      setConnectionStatus("Resolving code...");
      const resp = await fetch(`${coordinatorUrl}/api/v1/resolve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code, dj_name: djName }),
      });

      if (resp.ok) {
        const data = await resp.json();
        setConnectionStatus(`Connecting to ${data.server_name}...`);
        // Pass resolved URL and token to Tauri backend
        await invoke("connect_with_token", {
          wsUrl: data.ws_url,
          token: data.token,
          djName,
        });
        return;
      }

      // Fall through to direct connection on non-200
      console.warn("Coordinator resolution failed, trying direct connection");
    } catch (e) {
      console.warn("Coordinator unreachable, trying direct connection:", e);
    }
  }

  // Fallback: direct code_auth
  setConnectionStatus("Connecting directly...");
  await invoke("connect_with_code", { code, djName });
}
```

### 13.5 UI Changes

The DJ client settings panel should add an optional field:

```
Coordinator URL: [________________________] (optional)
                  e.g., https://coordinator.mcav.live

[ ] Remember coordinator URL
```

When a coordinator URL is configured:
- The connect code input remains the same
- A "Resolving..." spinner appears between entering the code and connecting
- On success, the resolved show name and server name are displayed
- On coordinator failure, a toast notification indicates fallback to direct connection
---

## Appendix A: FastAPI Application Skeleton

```python
"""
MCAV Central DJ Coordinator

Lightweight REST service for connect code resolution and show management.
"""

from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Depends, Header, Request
from fastapi.middleware.cors import CORSMiddleware
import aiosqlite
import secrets
import time
import jwt as pyjwt

from coordinator.config import Settings
from coordinator.db import Database
from coordinator.models import (
    ResolveRequest, ResolveResponse,
    RegisterServerRequest, RegisterServerResponse,
    CreateShowRequest, CreateShowResponse,
    RegisterCodeRequest, RegisterCodeResponse,
    HealthResponse,
)
from coordinator.rate_limiter import InMemoryRateLimiter


settings = Settings()
db = Database(settings.db_url)
rate_limiter = InMemoryRateLimiter(
    window_seconds=settings.rate_limit_window,
    max_requests=settings.rate_limit_max,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    await db.initialize()
    # Start background task for code expiry
    import asyncio
    async def expire_codes():
        while True:
            await db.expire_codes()
            await asyncio.sleep(60)
    task = asyncio.create_task(expire_codes())
    yield
    task.cancel()
    await db.close()


app = FastAPI(
    title="MCAV DJ Coordinator",
    version="0.1.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=["Authorization", "X-API-Key", "Content-Type"],
)


async def verify_admin_key(x_api_key: str = Header(...)):
    if not secrets.compare_digest(x_api_key, settings.admin_key):
        raise HTTPException(status_code=401, detail="Invalid admin key")


async def verify_server_key(authorization: str = Header(...)):
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Invalid authorization header")
    api_key = authorization[7:]
    server = await db.verify_server_api_key(api_key)
    if not server:
        raise HTTPException(status_code=401, detail="Invalid server API key")
    return server


@app.post("/api/v1/resolve", response_model=ResolveResponse)
async def resolve_code(req: ResolveRequest, request: Request):
    client_ip = request.client.host
    if not rate_limiter.check(client_ip):
        raise HTTPException(status_code=429, detail="Rate limit exceeded")

    result = await db.resolve_code(req.code, req.dj_name)
    if not result:
        rate_limiter.record_failure(client_ip)
        raise HTTPException(status_code=404, detail="Code not found or expired")

    rate_limiter.record_success(client_ip)

    server, show = result
    if not server['ws_url']:
        raise HTTPException(status_code=503, detail="Server offline")

    # Mint JWT
    now = time.time()
    token = pyjwt.encode(
        {
            "sub": req.dj_name,
            "aud": server['server_id'],
            "iss": "mcav-coordinator",
            "iat": int(now),
            "exp": int(now + settings.jwt_default_ttl),
            "show_id": show['show_id'],
            "show_name": show['name'],
            "code": req.code,
        },
        server['jwt_secret'],
        algorithm="HS256",
    )

    return ResolveResponse(
        ws_url=server['ws_url'],
        token=token,
        show_name=show['name'],
        server_name=server['name'],
        expires_in=settings.jwt_default_ttl,
    )


@app.post("/api/v1/servers", response_model=RegisterServerResponse,
          status_code=201)
async def register_server(
    req: RegisterServerRequest,
    _=Depends(verify_admin_key),
):
    server_id = f"srv_{''.join(secrets.token_hex(4))}"
    api_key = f"mcav_sk_{secrets.token_urlsafe(32)}"
    jwt_secret = f"jws_{secrets.token_urlsafe(32)}"

    from audio_processor.auth import hash_password
    api_key_hash = hash_password(api_key)

    await db.create_server(server_id, req.name, req.ws_url,
                           api_key_hash, jwt_secret, req.owner_name)

    return RegisterServerResponse(
        server_id=server_id,
        api_key=api_key,
        jwt_secret=jwt_secret,
        name=req.name,
    )


@app.post("/api/v1/shows", response_model=CreateShowResponse, status_code=201)
async def create_show(
    req: CreateShowRequest,
    server=Depends(verify_server_key),
):
    if server['server_id'] != req.server_id:
        raise HTTPException(status_code=403, detail="Server ID mismatch")

    show_id = f"shw_{''.join(secrets.token_hex(4))}"
    await db.create_show(show_id, req.server_id, req.name)

    return CreateShowResponse(
        show_id=show_id,
        name=req.name,
        server_id=req.server_id,
    )


@app.post("/api/v1/codes", response_model=RegisterCodeResponse,
          status_code=201)
async def register_code(
    req: RegisterCodeRequest,
    server=Depends(verify_server_key),
):
    # Verify server owns the show
    show = await db.get_show(req.show_id)
    if not show or show['server_id'] != server['server_id']:
        raise HTTPException(status_code=403, detail="Show not owned by this server")

    await db.create_code(req.code, req.show_id, req.ttl_seconds)

    return RegisterCodeResponse(
        code=req.code,
        show_id=req.show_id,
        status="active",
    )


@app.delete("/api/v1/codes/{code}")
async def revoke_code(code: str, server=Depends(verify_server_key)):
    result = await db.revoke_code(code, server['server_id'])
    if not result:
        raise HTTPException(status_code=404, detail="Code not found")
    return {"code": code, "status": "revoked"}


@app.get("/api/v1/health", response_model=HealthResponse)
async def health():
    stats = await db.get_stats()
    return HealthResponse(
        status="ok",
        version="0.1.0",
        **stats,
    )
```
---

## Appendix B: Configuration Module

```python
"""
Coordinator configuration via environment variables.
"""

from pydantic_settings import BaseSettings
from typing import List


class Settings(BaseSettings):
    """Coordinator settings loaded from environment variables."""

    # Required
    admin_key: str

    # Database
    db_url: str = "sqlite:///coordinator.db"

    # Server
    port: int = 8090
    log_level: str = "INFO"

    # JWT
    jwt_default_ttl: int = 900  # 15 minutes

    # Rate limiting
    rate_limit_window: int = 60
    rate_limit_max: int = 10
    redis_url: str | None = None

    # CORS
    cors_origins: List[str] = ["http://localhost:8080", "http://localhost:5173"]

    model_config = {
        "env_prefix": "COORDINATOR_",
        "env_file": ".env",
    }
```

---

## Appendix C: Migration Guide

### For VJ Server Operators

**Before (standalone):**
```bash
audioviz-vj --config configs/dj_auth.json
```

**After (with coordinator):**
```bash
# One-time: register server with coordinator
# (coordinator admin provides the admin key)
curl -X POST https://coordinator.mcav.live/api/v1/servers \
  -H "X-API-Key: $ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name": "Main Stage", "ws_url": "ws://my-server.com:9000"}'

# Save the returned api_key and jwt_secret

# Start VJ server with coordinator integration
audioviz-vj \
  --config configs/dj_auth.json \
  --coordinator-url https://coordinator.mcav.live \
  --server-api-key mcav_sk_Tq8x... \
  --jwt-secret jws_Kp4m... \
  --show-name "Friday Night Beats"
```

Existing `dj_auth` and `code_auth` flows continue to work. The coordinator is additive.

### For DJ Client Users

**Before:**
```
Server: my-vj-server.com
Port: 9000
Code: BEAT-7K3M
```

**After (with coordinator):**
```
Coordinator: https://coordinator.mcav.live  (optional, set once in settings)
Code: BEAT-7K3M
```

When a coordinator URL is configured, the client resolves the code to discover the server automatically. No hostname or port needed. If the coordinator is unreachable, the client falls back to the previous direct-connection flow.

### For Developers

1. **New dependency:** `PyJWT>=2.8.0` (already in `pyproject.toml` extras)
2. **New optional dependency:** `aiohttp>=3.9.0` (for VJ server coordinator sync)
3. **New optional dependency:** `pyjwt>=2.8.0` (for VJ server token verification)
4. **New Rust dependency:** `reqwest` (for DJ client HTTP resolution, already available in Tauri)
5. **Protocol addition:** `token_auth` message type (see Section 7)
6. **No breaking changes:** All existing message types and auth flows are preserved