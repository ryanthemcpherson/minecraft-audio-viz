# DJ Profile Pipeline Design

**Date**: 2026-02-22
**Status**: Approved

## Problem

DJ profile data (avatar, color palette, block palette, bio, genres) exists in the coordinator's `DJProfile` model but never flows to the VJ server or downstream consumers. After a DJ connects, the system only knows their name and session ID — all the rich profile data stays locked in the coordinator database.

## Goals

- DJ profile data flows from coordinator to VJ server on connect
- Profile is available on all four consumer surfaces: admin panel, 3D preview, mcav.live, Minecraft (Lua patterns)
- Lua patterns can opt-in to using DJ color/block palettes
- Graceful degradation: if profile fetch fails, DJ can still stream audio

## Non-Goals

- Avatar upload UI (separate coordinator/site feature)
- MC player heads / skin integration
- DJ palette overriding pattern colors (patterns opt-in only)
- Profile editing from the DJ client (done on mcav.live)

## Architecture

### Data Flow

```
DJ Client connects with code_auth (JWT)
        |
        v
VJ Server authenticates JWT, extracts dj_session_id
        |
        v
VJ Server calls Coordinator: GET /api/v1/internal/dj-profile/{dj_session_id}
        |
        v
Profile hydrated into DJConnection
        |
        |---> Admin panel: roster includes profile data (avatar, colors)
        |---> Browser preview: DJ overlay with avatar + colors
        |---> mcav.live: "now playing" widget via port 8766 WebSocket
        '---> Lua patterns: DJ palette available via config.dj_colors / config.dj_blocks
```

### Key Decision: VJ Server Fetches from Coordinator

The VJ server fetches profile data from the coordinator API after JWT auth succeeds. This was chosen over embedding profile data in JWT claims because:

- JWT stays lean (auth-only)
- Profile can be arbitrarily large (palettes, bio, URLs)
- Profile updates mid-session are possible (future)
- Single source of truth stays in the coordinator database

## Component Changes

### 1. Coordinator

**Database migration**: Add `user_id` (nullable FK to `users`) on the `dj_sessions` table. The connect endpoint currently creates DJSession records with a placeholder name (`DJ-{code}`) and no user link. When the connect flow is initiated by an authenticated user (JWT from the site/DJ client), we thread the user's ID into the session.

**New endpoint**: `GET /api/v1/internal/dj-profile/{dj_session_id}`

- Server-to-server auth via existing API key (Bearer token)
- Looks up: `DJSession.user_id -> User -> DJProfile`
- Returns profile payload or 404 if no profile exists
- Response shape:

```json
{
  "dj_name": "DJ Nova",
  "avatar_url": "https://cdn.mcav.live/avatars/abc123.jpg",
  "color_palette": ["#ff0055", "#00ccff", "#ffaa00"],
  "block_palette": ["diamond_block", "gold_block", "emerald_block"],
  "slug": "dj-nova",
  "bio": "Electronic music producer",
  "genres": "EDM, House, Techno"
}
```

**Connect endpoint update** (`POST /connect/{code}`): Accept optional `Authorization` header with user JWT. If present, decode it and set `DJSession.user_id` to the authenticated user's ID. If not present (anonymous/legacy), leave `user_id` null.

### 2. VJ Server

**CoordinatorClient** (`coordinator_client.py`):
- New method: `async def fetch_dj_profile(dj_session_id: str) -> Optional[dict]`
- Calls `GET /api/v1/internal/dj-profile/{dj_session_id}`
- Returns parsed profile dict or None on failure
- 5s timeout, logs warning on failure, never blocks DJ connection

**DJConnection** (`vj_server.py`):
- New fields on DJConnection dataclass:

```python
avatar_url: Optional[str] = None
color_palette: Optional[List[str]] = None   # hex color strings
block_palette: Optional[List[str]] = None   # Minecraft block IDs
slug: Optional[str] = None
bio: Optional[str] = None
genres: Optional[str] = None
```

**Auth flow** (in `_handle_dj_connection`):
- After JWT validation succeeds, if coordinator is configured:
  1. Call `fetch_dj_profile(dj_session_id)` (fire-and-forget with timeout)
  2. If profile returned, populate DJConnection fields
  3. If fetch fails, proceed with defaults (name from auth message, no palette)
- Use `dj_name` from profile if available (overrides auth message name)

**DJ roster** (`_get_dj_roster`):
- Add to roster dict: `avatar_url`, `color_palette`, `block_palette`, `slug`
- Sent to admin panel and browser clients in existing status updates

**State broadcast** (port 8766 to browser clients):
- Add optional `active_dj` field to state broadcast message:

```json
{
  "type": "state",
  "bands": [...],
  "active_dj": {
    "dj_name": "DJ Nova",
    "avatar_url": "...",
    "color_palette": ["#ff0055", "#00ccff"],
    "slug": "dj-nova"
  }
}
```

**DJ presence events** (new messages on port 8766):
- `dj_joined`: broadcast when a DJ connects, includes profile
- `dj_left`: broadcast when a DJ disconnects, includes dj_id and dj_name

### 3. Protocol Schema Changes

**New schema**: `protocol/schemas/types/dj-profile.schema.json`

```json
{
  "title": "DJProfile",
  "type": "object",
  "properties": {
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
  "required": ["dj_name"]
}
```

**New schemas**: `protocol/schemas/messages/dj-joined.schema.json`, `dj-left.schema.json`

**Updated**: `protocol/schemas/messages/state-broadcast.schema.json` — add optional `active_dj` field referencing dj-profile type.

### 4. Lua Pattern Integration

DJ palette is injected into the Lua `config` table when a DJ with a profile is active:

- `config.dj_colors`: list of hex color strings, or `nil` if no palette
- `config.dj_blocks`: list of Minecraft block IDs, or `nil` if no palette

Patterns opt-in by checking for these fields:

```lua
function calculate(audio, config, dt)
    -- Use DJ's primary color if available, otherwise default cyan
    local color = config.dj_colors and config.dj_colors[1] or "#00CCFF"
    local block = config.dj_blocks and config.dj_blocks[1] or "diamond_block"

    for i = 1, config.entity_count do
        entities[i] = {
            id = "block_" .. (i - 1),
            x = 0.5, y = audio.bands[1] * 0.5, z = 0.5,
            scale = 0.2, color = color, block = block,
            visible = true,
        }
    end
    return entities
end
```

### 5. Consumer Surfaces

**Admin panel** (`admin_panel/index.html`):
- DJ roster cards show avatar thumbnail (small circle, fallback to initials)
- Color palette displayed as small swatches next to DJ name
- Active DJ highlighted with their primary palette color as accent

**3D Preview** (`preview_tool/frontend/`):
- DJ info overlay in corner (name + avatar) when a DJ is active
- Listen for `dj_joined`/`dj_left` events

**mcav.live** (`site/`):
- "Now Playing" component connects to VJ server WebSocket on port 8766
- Shows active DJ: avatar, name, genres
- Links to DJ's profile page (`/dj/[slug]`)
- Reacts to `dj_joined`/`dj_left` events for live updates
- Graceful handling when no VJ server is reachable (hidden/disabled state)

## Migration Path

1. Coordinator: Add `user_id` column to `dj_sessions` (nullable, no existing data breaks)
2. Coordinator: Add internal profile endpoint
3. Coordinator: Update connect endpoint to thread user identity
4. VJ server: Add profile fields to DJConnection + fetch logic
5. Protocol: Add new schemas
6. VJ server: Broadcast profile in roster + state + presence events
7. Lua engine: Inject DJ palette into config
8. Admin panel: Render profile data in roster
9. 3D preview: Add DJ overlay
10. Site: Add "Now Playing" component

Each step is independently deployable. Steps 1-3 are coordinator-only. Steps 4-7 are VJ server. Steps 8-10 are frontend consumers.
