# DJ Profile Pipeline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Flow DJ profile data (avatar, colors, blocks, bio) from the coordinator to all consumer surfaces when a DJ connects.

**Architecture:** DJ client sends user token when resolving connect code, coordinator links DJSession to user. DJ client passes coordinator JWT in code_auth message to VJ server. VJ server decodes JWT, fetches profile from coordinator API, and broadcasts profile data to all consumers (admin panel, preview, site, Lua patterns).

**Tech Stack:** Python/FastAPI (coordinator), Python/websockets (VJ server), Rust/Tauri (DJ client), TypeScript/React (DJ client frontend), Lua (patterns), vanilla JS (admin panel), Three.js (preview), Next.js (site)

**Design doc:** `docs/plans/2026-02-22-dj-profile-pipeline-design.md`

---

### Task 1: Coordinator — Add user_id to DJSession

**Files:**
- Create: `coordinator/alembic/versions/014_dj_session_user_id.py`
- Modify: `coordinator/app/models/db.py:221-233` (DJSession model)
- Test: `coordinator/tests/test_models.py` (or existing test file)

**Step 1: Add user_id column to DJSession model**

In `coordinator/app/models/db.py`, add a nullable FK to the DJSession class:

```python
class DJSession(Base):
    __tablename__ = "dj_sessions"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    show_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("shows.id"), nullable=False)
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("users.id"), nullable=True
    )
    dj_name: Mapped[str] = mapped_column(String(100), nullable=False)
    connected_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    disconnected_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    ip_address: Mapped[str] = mapped_column(String(45), nullable=False, default="")

    # Relationships
    show: Mapped[Show] = relationship("Show", back_populates="dj_sessions")
    user: Mapped[User | None] = relationship("User", foreign_keys=[user_id])
```

**Step 2: Create Alembic migration**

```bash
cd coordinator
alembic revision --autogenerate -m "add user_id to dj_sessions"
```

Verify the generated migration adds:
```python
op.add_column('dj_sessions', sa.Column('user_id', sa.Uuid(), nullable=True))
op.create_foreign_key(None, 'dj_sessions', 'users', ['user_id'], ['id'])
```

**Step 3: Run migration and verify**

```bash
cd coordinator
alembic upgrade head
```

**Step 4: Commit**

```bash
git add coordinator/app/models/db.py coordinator/alembic/versions/014_*
git commit -m "feat(coordinator): add user_id FK to dj_sessions table"
```

---

### Task 2: Coordinator — Thread user identity through connect endpoint

**Files:**
- Modify: `coordinator/app/routers/connect.py:35-124` (resolve_connect_code)
- Modify: `coordinator/app/models/schemas.py` (ConnectCodeResponse — add dj_session_id)
- Test: `coordinator/tests/test_connect.py`

**Step 1: Write the failing test**

In `coordinator/tests/test_connect.py` (create if needed):

```python
import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_connect_with_user_auth_sets_user_id(
    client: AsyncClient, active_show, user_token, db_session
):
    """When a logged-in user resolves a connect code, the DJSession should link to their user_id."""
    resp = await client.get(
        f"/connect/{active_show.connect_code}",
        headers={"Authorization": f"Bearer {user_token}"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert "dj_session_id" in data

    # Verify DJSession has user_id set
    from sqlalchemy import select
    from app.models.db import DJSession
    stmt = select(DJSession).where(DJSession.id == data["dj_session_id"])
    result = await db_session.execute(stmt)
    session = result.scalar_one()
    assert session.user_id is not None


@pytest.mark.asyncio
async def test_connect_without_auth_leaves_user_id_null(
    client: AsyncClient, active_show, db_session
):
    """Anonymous connect code resolution should leave user_id null."""
    resp = await client.get(f"/connect/{active_show.connect_code}")
    assert resp.status_code == 200
    data = resp.json()
    assert "dj_session_id" in data

    from sqlalchemy import select
    from app.models.db import DJSession
    stmt = select(DJSession).where(DJSession.id == data["dj_session_id"])
    result = await db_session.execute(stmt)
    session = result.scalar_one()
    assert session.user_id is None
```

**Step 2: Run test to verify it fails**

```bash
cd coordinator && pytest tests/test_connect.py -v
```

**Step 3: Update connect endpoint to accept optional user auth**

In `coordinator/app/routers/connect.py`:

```python
from app.services.user_jwt import verify_user_token

@router.get("/connect/{code}", ...)
async def resolve_connect_code(
    code: str,
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> ConnectCodeResponse:
    # ... existing code to find show and server ...

    # Try to extract user identity from optional Authorization header
    user_id = None
    auth_header = request.headers.get("authorization", "")
    if auth_header.startswith("Bearer "):
        try:
            from app.services.user_jwt import verify_user_token
            payload = verify_user_token(auth_header[7:], settings.user_jwt_secret)
            user_id = payload.sub
        except Exception:
            pass  # Anonymous is fine — don't fail the connect flow

    # Create DJ session with user_id if available
    dj_session = DJSession(
        id=dj_session_id,
        show_id=show.id,
        user_id=user_id,
        dj_name=f"DJ-{normalised}",
        ip_address=client_ip,
    )

    # ... rest of existing code ...

    return ConnectCodeResponse(
        websocket_url=server.websocket_url,
        token=token,
        show_name=show.name,
        dj_count=show.current_djs,
        dj_session_id=str(dj_session_id),
    )
```

**Step 4: Add dj_session_id to ConnectCodeResponse schema**

In `coordinator/app/models/schemas.py`, find `ConnectCodeResponse` and add:

```python
class ConnectCodeResponse(BaseModel):
    websocket_url: str
    token: str
    show_name: str
    dj_count: int
    dj_session_id: str
```

**Step 5: Run tests to verify they pass**

```bash
cd coordinator && pytest tests/test_connect.py -v
```

**Step 6: Commit**

```bash
git add coordinator/app/routers/connect.py coordinator/app/models/schemas.py coordinator/tests/test_connect.py
git commit -m "feat(coordinator): thread user identity through connect code flow"
```

---

### Task 3: Coordinator — Internal profile lookup endpoint

**Files:**
- Create: `coordinator/app/routers/internal.py`
- Modify: `coordinator/app/main.py` (register router)
- Test: `coordinator/tests/test_internal.py`

**Step 1: Write the failing test**

```python
# coordinator/tests/test_internal.py
import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_internal_profile_returns_profile_for_linked_session(
    client: AsyncClient, dj_session_with_profile, server_api_key
):
    """Internal endpoint returns DJ profile when session has a linked user with profile."""
    resp = await client.get(
        f"/internal/dj-profile/{dj_session_with_profile.id}",
        headers={"Authorization": f"Bearer {server_api_key}"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert "dj_name" in data
    assert "avatar_url" in data
    assert "color_palette" in data
    assert "block_palette" in data
    assert "slug" in data


@pytest.mark.asyncio
async def test_internal_profile_returns_404_for_anonymous_session(
    client: AsyncClient, anonymous_dj_session, server_api_key
):
    """Internal endpoint returns 404 when session has no linked user."""
    resp = await client.get(
        f"/internal/dj-profile/{anonymous_dj_session.id}",
        headers={"Authorization": f"Bearer {server_api_key}"},
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_internal_profile_requires_server_auth(
    client: AsyncClient, dj_session_with_profile
):
    """Internal endpoint rejects requests without valid server API key."""
    resp = await client.get(
        f"/internal/dj-profile/{dj_session_with_profile.id}",
    )
    assert resp.status_code == 401
```

**Step 2: Run test to verify it fails**

```bash
cd coordinator && pytest tests/test_internal.py -v
```

**Step 3: Create the internal router**

```python
# coordinator/app/routers/internal.py
"""Internal server-to-server endpoints (VJ server -> coordinator)."""

from __future__ import annotations

import json
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_session
from app.models.db import DJProfile, DJSession, User
from app.routers.servers import _authenticate_server

router = APIRouter(prefix="/internal", tags=["internal"])


@router.get(
    "/dj-profile/{dj_session_id}",
    summary="Get DJ profile for a session (server-to-server)",
)
async def get_dj_profile_for_session(
    dj_session_id: uuid.UUID,
    server=Depends(_authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Look up DJSession -> User -> DJProfile and return profile data."""
    stmt = select(DJSession).where(DJSession.id == dj_session_id)
    dj_session = (await session.execute(stmt)).scalar_one_or_none()

    if dj_session is None or dj_session.user_id is None:
        raise HTTPException(status_code=404, detail="No profile for this session")

    stmt = (
        select(User)
        .where(User.id == dj_session.user_id)
        .options(selectinload(User.dj_profile))
    )
    user = (await session.execute(stmt)).scalar_one_or_none()

    if user is None or user.dj_profile is None:
        raise HTTPException(status_code=404, detail="No profile for this session")

    profile = user.dj_profile

    # Parse JSON palette fields
    color_palette = None
    if profile.color_palette:
        try:
            color_palette = json.loads(profile.color_palette)
        except (json.JSONDecodeError, TypeError):
            pass

    block_palette = None
    if profile.block_palette:
        try:
            block_palette = json.loads(profile.block_palette)
        except (json.JSONDecodeError, TypeError):
            pass

    return {
        "dj_name": profile.dj_name,
        "avatar_url": profile.avatar_url,
        "color_palette": color_palette,
        "block_palette": block_palette,
        "slug": profile.slug,
        "bio": profile.bio,
        "genres": profile.genres,
    }
```

**Step 4: Register the router in main.py**

In `coordinator/app/main.py`, add:

```python
from app.routers.internal import router as internal_router
app.include_router(internal_router, prefix="/api/v1")
```

**Step 5: Run tests to verify they pass**

```bash
cd coordinator && pytest tests/test_internal.py -v
```

**Step 6: Commit**

```bash
git add coordinator/app/routers/internal.py coordinator/app/main.py coordinator/tests/test_internal.py
git commit -m "feat(coordinator): add internal dj-profile lookup endpoint for VJ servers"
```

---

### Task 4: Protocol — Add DJ profile schemas

**Files:**
- Create: `protocol/schemas/types/dj-profile.schema.json`
- Create: `protocol/schemas/messages/dj-joined.schema.json`
- Create: `protocol/schemas/messages/dj-left.schema.json`
- Modify: `protocol/schemas/messages/state-broadcast.schema.json`
- Modify: `protocol/schemas/messages/code-auth.schema.json`

**Step 1: Create dj-profile type schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "dj-profile.schema.json",
  "title": "DJProfile",
  "type": "object",
  "properties": {
    "dj_id": { "type": "string" },
    "dj_name": { "type": "string" },
    "avatar_url": { "type": ["string", "null"] },
    "color_palette": {
      "type": ["array", "null"],
      "items": { "type": "string" }
    },
    "block_palette": {
      "type": ["array", "null"],
      "items": { "type": "string" }
    },
    "slug": { "type": ["string", "null"] },
    "bio": { "type": ["string", "null"] },
    "genres": { "type": ["string", "null"] }
  },
  "required": ["dj_id", "dj_name"]
}
```

**Step 2: Create dj-joined message schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "dj-joined.schema.json",
  "title": "DjJoinedMessage",
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "type": { "const": "dj_joined" },
    "v": { "type": "string" },
    "dj": { "$ref": "../types/dj-profile.schema.json" }
  },
  "required": ["type", "dj"]
}
```

**Step 3: Create dj-left message schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "dj-left.schema.json",
  "title": "DjLeftMessage",
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "type": { "const": "dj_left" },
    "v": { "type": "string" },
    "dj_id": { "type": "string" },
    "dj_name": { "type": "string" }
  },
  "required": ["type", "dj_id", "dj_name"]
}
```

**Step 4: Update state-broadcast schema**

Add `active_dj` to `state-broadcast.schema.json` properties:

```json
"active_dj": {
  "oneOf": [
    { "$ref": "../types/dj-profile.schema.json" },
    { "type": "null" }
  ]
}
```

**Step 5: Update code-auth schema**

Add optional `token` field to `code-auth.schema.json` properties:

```json
"token": {
  "type": "string",
  "description": "Coordinator JWT for profile lookup (optional)"
}
```

**Step 6: Commit**

```bash
git add protocol/schemas/
git commit -m "feat(protocol): add DJ profile type and presence message schemas"
```

---

### Task 5: DJ Client — Send user token with connect code + coordinator token to VJ server

**Files:**
- Modify: `dj_client/src/lib/api.ts:262-268` (resolveConnectCode — send user auth)
- Modify: `dj_client/src/App.tsx:441-455` (pass coordinator token to Rust)
- Modify: `dj_client/src-tauri/src/protocol/messages.rs:30-50` (CodeAuthMessage — add token field)
- Modify: `dj_client/src-tauri/src/protocol/client.rs:40-65` (DjClientConfig — add token field)
- Modify: `dj_client/src-tauri/src/protocol/client.rs:214-220` (connect — pass token)
- Modify: `dj_client/src-tauri/src/lib.rs` (connect_with_code — accept token param)

**Step 1: Update resolveConnectCode to send user auth**

In `dj_client/src/lib/api.ts`, change `apiFetch` to `authedFetch` so the user's access token is sent:

```typescript
export async function resolveConnectCode(
  code: string,
): Promise<ResolvedConnectCode> {
  return authedFetch<ResolvedConnectCode>(
    `/connect/${encodeURIComponent(code)}`,
  );
}
```

Also add `dj_session_id` to `ResolvedConnectCode` type (find the type definition and add the field).

**Step 2: Pass coordinator token to Rust backend**

In `dj_client/src/App.tsx`, capture the coordinator token from the resolved response and pass it:

```typescript
const resolved = await api.resolveConnectCode(formattedCode);
const wsUrl = new URL(resolved.websocket_url);
connHost = wsUrl.hostname;
connPort = parseInt(wsUrl.port, 10) || (wsUrl.protocol === 'wss:' ? 443 : 80);
setShowName(resolved.show_name);

await invoke('connect_with_code', {
  code: formattedCode,
  djName: djName.trim(),
  serverHost: connHost,
  serverPort: connPort,
  blockPalette: auth?.user?.dj_profile?.block_palette ?? null,
  coordinatorToken: resolved.token,  // Pass coordinator JWT
});
```

**Step 3: Add token field to CodeAuthMessage (Rust)**

In `dj_client/src-tauri/src/protocol/messages.rs`:

```rust
#[derive(Debug, Clone, Serialize)]
pub struct CodeAuthMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub code: String,
    pub dj_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub direct_mode: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
}

impl CodeAuthMessage {
    pub fn new(code: String, dj_name: String, token: Option<String>) -> Self {
        Self {
            msg_type: "code_auth".to_string(),
            code,
            dj_name,
            direct_mode: Some(true),
            token,
        }
    }
}
```

**Step 4: Thread token through DjClientConfig and connect()**

In `dj_client/src-tauri/src/protocol/client.rs`, add to `DjClientConfig`:

```rust
pub coordinator_token: Option<String>,
```

Default to `None`. Update the `connect()` method to pass token to `CodeAuthMessage::new()`:

```rust
let auth_msg = if let Some(ref code) = self.config.connect_code {
    serde_json::to_string(&CodeAuthMessage::new(
        code.clone(),
        self.config.dj_name.clone(),
        self.config.coordinator_token.clone(),
    ))
    // ...
```

**Step 5: Update Tauri command to accept coordinatorToken**

In `dj_client/src-tauri/src/lib.rs`, find the `connect_with_code` command and add the `coordinator_token` parameter, threading it into `DjClientConfig`.

**Step 6: Run Rust tests**

```bash
cd dj_client/src-tauri && cargo test
```

**Step 7: Commit**

```bash
git add dj_client/
git commit -m "feat(dj-client): send user auth with connect code and coordinator token to VJ server"
```

---

### Task 6: VJ Server — Profile fields on DJConnection + fetch from coordinator

**Files:**
- Modify: `vj_server/vj_server.py:318-377` (DJConnection — add profile fields)
- Modify: `vj_server/vj_server.py:1165-1265` (code_auth handler — store token, pass to pending)
- Modify: `vj_server/vj_server.py:3529-3570` (_approve_pending_dj — fetch profile on approve)
- Modify: `vj_server/coordinator_client.py` (add fetch_dj_profile method)

**Step 1: Add profile fields to DJConnection**

In `vj_server/vj_server.py`, add after the existing fields on `DJConnection`:

```python
    # DJ profile data (populated from coordinator on connect)
    avatar_url: Optional[str] = None
    color_palette: Optional[List[str]] = None
    block_palette: Optional[List[str]] = None
    slug: Optional[str] = None
    bio: Optional[str] = None
    genres: Optional[str] = None
```

**Step 2: Add fetch_dj_profile to CoordinatorClient**

In `vj_server/coordinator_client.py`, add method:

```python
    async def fetch_dj_profile(self, dj_session_id: str) -> Optional[dict]:
        """Fetch DJ profile for a session from the coordinator.

        Returns profile dict or None if unavailable.
        """
        try:
            return await self._request(
                "GET",
                f"/internal/dj-profile/{dj_session_id}",
            )
        except Exception as exc:
            logger.warning("Failed to fetch DJ profile for session %s: %s", dj_session_id, exc)
            return None
```

**Step 3: Store coordinator token in pending DJ info**

In the `code_auth` handler (around line 1227), add token to pending_info:

```python
pending_info = {
    "dj_id": dj_id,
    "dj_name": dj_name,
    "websocket": websocket,
    "waiting_since": time.time(),
    "direct_mode": direct_mode,
    "priority": priority,
    "code": code,
    "coordinator_token": data.get("token"),  # Coordinator JWT for profile lookup
}
```

**Step 4: Decode JWT and fetch profile on DJ approval**

In `_approve_pending_dj` (around line 3529), after creating the DJConnection, fetch the profile:

```python
    async def _approve_pending_dj(self, dj_id: str):
        """Approve a pending DJ and move them to the active DJ list."""
        async with self._dj_lock:
            # ... existing approval code that creates DJConnection ...

        # Fetch DJ profile from coordinator (non-blocking)
        coordinator_token = info.get("coordinator_token")
        if coordinator_token and self._coordinator:
            try:
                # Decode the coordinator JWT to get dj_session_id
                # JWT is base64url encoded: header.payload.signature
                import base64
                payload_b64 = coordinator_token.split(".")[1]
                # Add padding
                payload_b64 += "=" * (4 - len(payload_b64) % 4)
                payload = json.loads(base64.urlsafe_b64decode(payload_b64))
                dj_session_id = payload.get("sub")

                if dj_session_id:
                    profile = await asyncio.wait_for(
                        self._coordinator.fetch_dj_profile(dj_session_id),
                        timeout=5.0,
                    )
                    if profile:
                        dj = self._djs.get(dj_id)
                        if dj:
                            dj.dj_name = profile.get("dj_name", dj.dj_name)
                            dj.avatar_url = profile.get("avatar_url")
                            dj.color_palette = profile.get("color_palette")
                            dj.block_palette = profile.get("block_palette")
                            dj.slug = profile.get("slug")
                            dj.bio = profile.get("bio")
                            dj.genres = profile.get("genres")
                            logger.info(
                                "[DJ PROFILE] Loaded profile for %s: slug=%s, %d colors, %d blocks",
                                dj.dj_name,
                                dj.slug,
                                len(dj.color_palette) if dj.color_palette else 0,
                                len(dj.block_palette) if dj.block_palette else 0,
                            )
            except Exception as exc:
                logger.warning("[DJ PROFILE] Failed to load profile for %s: %s", dj_id, exc)
```

Also apply the same logic in the `dj_auth` path (around line 1460) for DJs that authenticate with credentials and skip the approval queue.

**Step 5: Run VJ server manually to verify no crashes**

```bash
cd vj_server && python -c "from vj_server.vj_server import DJConnection; print('OK')"
```

**Step 6: Commit**

```bash
git add vj_server/vj_server.py vj_server/coordinator_client.py
git commit -m "feat(vj-server): add DJ profile fields and coordinator profile fetch"
```

---

### Task 7: VJ Server — Broadcast profile in roster, state, and presence events

**Files:**
- Modify: `vj_server/vj_server.py:921-964` (_get_dj_roster — include profile fields)
- Modify: `vj_server/vj_server.py` (state broadcast — add active_dj)
- Modify: `vj_server/vj_server.py` (_approve_pending_dj — broadcast dj_joined)
- Modify: `vj_server/vj_server.py` (disconnect handler — broadcast dj_left)

**Step 1: Add profile fields to DJ roster**

In `_get_dj_roster`, add to the roster dict (around line 937):

```python
roster.append(
    {
        # ... existing fields ...
        "avatar_url": dj.avatar_url,
        "color_palette": dj.color_palette,
        "block_palette": dj.block_palette,
        "slug": dj.slug,
    }
)
```

**Step 2: Helper to build DJ profile dict**

Add a helper method:

```python
def _dj_profile_dict(self, dj: DJConnection) -> dict:
    """Build a profile dict for broadcasting."""
    return {
        "dj_id": dj.dj_id,
        "dj_name": dj.dj_name,
        "avatar_url": dj.avatar_url,
        "color_palette": dj.color_palette,
        "block_palette": dj.block_palette,
        "slug": dj.slug,
        "bio": dj.bio,
        "genres": dj.genres,
    }
```

**Step 3: Add active_dj to state broadcast**

Find the state broadcast code (search for `"type": "state"` sent to browser clients) and add:

```python
active_dj_profile = None
if self._active_dj_id and self._active_dj_id in self._djs:
    active_dj = self._djs[self._active_dj_id]
    active_dj_profile = self._dj_profile_dict(active_dj)

state_msg["active_dj"] = active_dj_profile
```

**Step 4: Broadcast dj_joined on approval**

At the end of `_approve_pending_dj`, after sending auth_success:

```python
# Broadcast DJ joined to browser clients
dj = self._djs.get(dj_id)
if dj:
    await self._broadcast_to_browsers(
        mjson.encode({
            "type": "dj_joined",
            "dj": self._dj_profile_dict(dj),
        })
    )
```

**Step 5: Broadcast dj_left on disconnect**

In the DJ disconnect handler (search for `_dj_queue.remove`), add:

```python
await self._broadcast_to_browsers(
    mjson.encode({
        "type": "dj_left",
        "dj_id": dj_id,
        "dj_name": dj.dj_name,
    })
)
```

**Step 6: Commit**

```bash
git add vj_server/vj_server.py
git commit -m "feat(vj-server): broadcast DJ profile in roster, state, and presence events"
```

---

### Task 8: VJ Server — Inject DJ palette into Lua config

**Files:**
- Modify: `vj_server/patterns.py:156-164` (config table creation)
- Modify: `vj_server/patterns.py:233-241` (config table update before calculate())
- Modify: `vj_server/vj_server.py` (pass active DJ palette to pattern engine)

**Step 1: Add DJ palette fields to Lua config table setup**

In `patterns.py`, update the config table initialization (around line 156):

```python
self._config_table = self._lua.table_from({
    "entity_count": 0,
    "zone_size": 0,
    "beat_boost": 0.0,
    "base_scale": 0.0,
    "max_scale": 0.0,
    # DJ palette (nil by default, populated when a DJ with a profile is active)
    # "dj_colors" and "dj_blocks" are set dynamically before each calculate() call
})
```

**Step 2: Update config before calculate() with DJ palette**

In the method that calls `calculate()`, add DJ palette injection before the call. The pattern engine needs to receive the active DJ's palette. Add a method or parameters:

```python
def set_dj_palette(self, color_palette: Optional[List[str]], block_palette: Optional[List[str]]):
    """Update the DJ palette fields in the Lua config table."""
    if color_palette:
        self._config_table["dj_colors"] = self._lua.table_from(color_palette)
    else:
        self._config_table["dj_colors"] = None

    if block_palette:
        self._config_table["dj_blocks"] = self._lua.table_from(block_palette)
    else:
        self._config_table["dj_blocks"] = None
```

**Step 3: Call set_dj_palette from VJ server before pattern calculation**

In `vj_server.py`, find where the pattern's `calculate()` is called and add:

```python
# Inject active DJ palette into pattern config
active_dj = self._get_active_dj()
if active_dj and self._pattern:
    self._pattern.set_dj_palette(active_dj.color_palette, active_dj.block_palette)
elif self._pattern:
    self._pattern.set_dj_palette(None, None)
```

**Step 4: Commit**

```bash
git add vj_server/patterns.py vj_server/vj_server.py
git commit -m "feat(vj-server): inject DJ color and block palettes into Lua pattern config"
```

---

### Task 9: Admin Panel — Render DJ profile data in roster

**Files:**
- Modify: `admin_panel/js/admin-app.js` (_renderDJQueue — add avatar + color swatches)
- Modify: `admin_panel/css/` (styles for avatar and palette swatches)

**Step 1: Update _renderDJQueue to show avatar and palette**

In `admin_panel/js/admin-app.js`, find `_renderDJQueue` (around line 1803). Update the DJ item rendering to include:

- Avatar circle (img if avatar_url exists, initials fallback)
- Color palette swatches (small colored circles)

```javascript
// Inside the DJ item creation
const avatarHtml = dj.avatar_url
  ? `<img src="${dj.avatar_url}" class="dj-avatar" alt="${dj.dj_name}" />`
  : `<div class="dj-avatar dj-avatar-initials">${dj.dj_name.charAt(0).toUpperCase()}</div>`;

const paletteHtml = (dj.color_palette || [])
  .slice(0, 5)
  .map(c => `<span class="palette-swatch" style="background:${c}"></span>`)
  .join('');
```

**Step 2: Add CSS styles**

```css
.dj-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid rgba(255,255,255,0.1);
}
.dj-avatar-initials {
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255,255,255,0.08);
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 600;
}
.palette-swatch {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-left: 3px;
}
```

**Step 3: Commit**

```bash
git add admin_panel/
git commit -m "feat(admin-panel): show DJ avatar and color palette in roster"
```

---

### Task 10: 3D Preview — DJ info overlay

**Files:**
- Modify: `preview_tool/frontend/js/app.js` (handle dj_joined/dj_left, show DJ overlay)
- Modify: `preview_tool/frontend/index.html` (add DJ overlay container)

**Step 1: Add DJ overlay HTML**

In `preview_tool/frontend/index.html`, add a DJ info overlay container:

```html
<div id="dj-overlay" class="dj-overlay" style="display:none;">
  <img id="dj-overlay-avatar" class="dj-overlay-avatar" />
  <div id="dj-overlay-name" class="dj-overlay-name"></div>
</div>
```

**Step 2: Handle dj_joined/dj_left and state.active_dj in JS**

In `preview_tool/frontend/js/app.js`, add message handlers:

```javascript
case 'dj_joined':
  console.log('DJ joined:', msg.dj.dj_name);
  break;

case 'dj_left':
  console.log('DJ left:', msg.dj_name);
  break;

case 'state':
  // ... existing state handling ...
  updateDJOverlay(msg.active_dj);
  break;
```

```javascript
function updateDJOverlay(activeDj) {
  const overlay = document.getElementById('dj-overlay');
  if (!activeDj) {
    overlay.style.display = 'none';
    return;
  }
  overlay.style.display = 'flex';
  const avatar = document.getElementById('dj-overlay-avatar');
  const name = document.getElementById('dj-overlay-name');
  if (activeDj.avatar_url) {
    avatar.src = activeDj.avatar_url;
    avatar.style.display = 'block';
  } else {
    avatar.style.display = 'none';
  }
  name.textContent = activeDj.dj_name;
}
```

**Step 3: Add overlay CSS**

```css
.dj-overlay {
  position: fixed;
  bottom: 20px;
  left: 20px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  background: rgba(8, 9, 13, 0.8);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 8px;
  z-index: 100;
}
.dj-overlay-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  object-fit: cover;
}
.dj-overlay-name {
  color: #f5f5f5;
  font-size: 13px;
  font-weight: 500;
}
```

**Step 4: Commit**

```bash
git add preview_tool/
git commit -m "feat(preview): add DJ info overlay with avatar and name"
```

---

### Task 11: mcav.live — "Now Playing" component

**Files:**
- Create: `site/src/components/NowPlaying.tsx`
- Modify: `site/src/app/dj/[slug]/page.tsx` (or layout where NowPlaying should appear)

**Step 1: Create NowPlaying component**

```typescript
// site/src/components/NowPlaying.tsx
'use client';

import { useEffect, useState, useRef } from 'react';
import Image from 'next/image';
import Link from 'next/link';

interface DJProfile {
  dj_id: string;
  dj_name: string;
  avatar_url: string | null;
  color_palette: string[] | null;
  slug: string | null;
  genres: string | null;
}

interface NowPlayingProps {
  wsUrl: string;  // e.g. "ws://localhost:8766"
}

export default function NowPlaying({ wsUrl }: NowPlayingProps) {
  const [activeDj, setActiveDj] = useState<DJProfile | null>(null);
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!wsUrl) return;

    const connect = () => {
      const ws = new WebSocket(wsUrl);
      wsRef.current = ws;

      ws.onopen = () => setConnected(true);
      ws.onclose = () => {
        setConnected(false);
        // Reconnect after 5s
        setTimeout(connect, 5000);
      };

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'state' && msg.active_dj !== undefined) {
            setActiveDj(msg.active_dj);
          } else if (msg.type === 'dj_joined') {
            // Could update a DJ list here
          } else if (msg.type === 'dj_left') {
            // Could remove from DJ list
          }
        } catch {
          // Ignore parse errors
        }
      };
    };

    connect();
    return () => wsRef.current?.close();
  }, [wsUrl]);

  if (!connected || !activeDj) return null;

  const primaryColor = activeDj.color_palette?.[0] ?? '#00CCFF';

  return (
    <div className="fixed bottom-6 right-6 z-50 flex items-center gap-3 rounded-xl border border-white/[0.06] bg-[#08090d]/80 px-4 py-3 backdrop-blur-xl">
      <div
        className="h-2 w-2 animate-pulse rounded-full"
        style={{ background: primaryColor }}
      />
      {activeDj.avatar_url ? (
        <Image
          src={activeDj.avatar_url}
          alt={activeDj.dj_name}
          width={32}
          height={32}
          className="rounded-full object-cover"
        />
      ) : (
        <div className="flex h-8 w-8 items-center justify-center rounded-full bg-white/[0.08] text-sm font-semibold text-[#a1a1aa]">
          {activeDj.dj_name.charAt(0).toUpperCase()}
        </div>
      )}
      <div>
        <div className="text-sm font-medium text-[#f5f5f5]">
          {activeDj.slug ? (
            <Link href={`/dj/${activeDj.slug}`} className="hover:underline">
              {activeDj.dj_name}
            </Link>
          ) : (
            activeDj.dj_name
          )}
        </div>
        {activeDj.genres && (
          <div className="text-xs text-[#a1a1aa]">{activeDj.genres}</div>
        )}
      </div>
    </div>
  );
}
```

**Step 2: Add NowPlaying to a layout or page**

Import and use in an appropriate layout (e.g., the main site layout or the dashboard):

```typescript
<NowPlaying wsUrl={process.env.NEXT_PUBLIC_VJ_WS_URL || ''} />
```

Only renders when `NEXT_PUBLIC_VJ_WS_URL` is configured and a DJ is active.

**Step 3: Commit**

```bash
git add site/src/components/NowPlaying.tsx site/src/app/
git commit -m "feat(site): add NowPlaying component with live DJ profile display"
```

---

### Task 12: Final integration test and cleanup

**Step 1: Run all coordinator tests**

```bash
cd coordinator && pytest -v
```

**Step 2: Run Rust tests**

```bash
cd dj_client/src-tauri && cargo test
```

**Step 3: Build site to verify no TS errors**

```bash
cd site && npm run build
```

**Step 4: Final commit with any fixes**

```bash
git add -A
git commit -m "chore: integration fixes for DJ profile pipeline"
```
