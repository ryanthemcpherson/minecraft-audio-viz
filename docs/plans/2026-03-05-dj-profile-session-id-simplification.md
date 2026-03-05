# DJ Profile Pipeline: Session ID Simplification

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace JWT token decode in the VJ server with direct `dj_session_id` passthrough, removing unnecessary JWT parsing from the profile hydration flow.

**Architecture:** The DJ client already receives `dj_session_id` from the coordinator's connect code response. Instead of passing the coordinator JWT and decoding it in the VJ server to extract `sub`, we send the `dj_session_id` directly in the `code_auth` WebSocket message. The VJ server passes it straight to the coordinator's existing `GET /internal/dj-profile/{dj_session_id}` endpoint. Security is maintained because that endpoint is gated by server API key auth.

**Tech Stack:** Rust/Tauri (DJ client), Python/websockets (VJ server), JSON Schema (protocol), TypeScript/React (DJ client frontend)

**Design doc:** `docs/plans/2026-02-22-dj-profile-pipeline-design.md`

**NOTE:** The DJ client frontend (`dj_client/src/`) is undergoing a UI overhaul. Frontend changes should be minimal and isolated to avoid conflicts with in-progress component extraction work.

---

### Task 1: Protocol — Update code-auth schema

**Files:**
- Modify: `protocol/schemas/messages/code-auth.schema.json`

**Step 1: Replace `token` property with `dj_session_id`**

Replace the `token` property in the schema with:

```json
"dj_session_id": {
  "type": "string",
  "description": "Coordinator DJ session ID for profile lookup (optional)"
}
```

Remove the `token` property entirely.

**Step 2: Verify schema is valid JSON**

Run: `python -c "import json; json.load(open('protocol/schemas/messages/code-auth.schema.json'))"`
Expected: No output (valid JSON)

**Step 3: Commit**

```bash
git add protocol/schemas/messages/code-auth.schema.json
git commit -m "feat(protocol): replace token with dj_session_id in code-auth schema"
```

---

### Task 2: Rust — Rename token to dj_session_id in CodeAuthMessage

**Files:**
- Modify: `dj_client/src-tauri/src/protocol/messages.rs:30-53`

**Step 1: Update CodeAuthMessage struct**

Change the `token` field to `dj_session_id`:

```rust
/// Connect code authentication message
#[derive(Debug, Clone, Serialize)]
pub struct CodeAuthMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub code: String,
    pub dj_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub direct_mode: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dj_session_id: Option<String>,
}

impl CodeAuthMessage {
    pub fn new(code: String, dj_name: String, dj_session_id: Option<String>) -> Self {
        Self {
            msg_type: "code_auth".to_string(),
            code,
            dj_name,
            direct_mode: Some(true),
            dj_session_id,
        }
    }
}
```

**Step 2: Verify it compiles**

Run: `cd dj_client/src-tauri && cargo check`
Expected: Success (the call site in client.rs passes the same `Option<String>` positionally)

**Step 3: Commit**

```bash
git add dj_client/src-tauri/src/protocol/messages.rs
git commit -m "feat(dj-client): rename token to dj_session_id in CodeAuthMessage"
```

---

### Task 3: Rust — Rename coordinator_token to dj_session_id in DjClientConfig and connect_with_code

**Files:**
- Modify: `dj_client/src-tauri/src/protocol/client.rs:57-58,79,226`
- Modify: `dj_client/src-tauri/src/lib.rs:153,162`

**Step 1: Update DjClientConfig**

In `dj_client/src-tauri/src/protocol/client.rs`, rename the field and its doc comment:

```rust
/// DJ session ID from coordinator (passed to VJ server in code_auth message for profile lookup)
pub dj_session_id: Option<String>,
```

Update the Default impl (line ~79):
```rust
dj_session_id: None,
```

Update the connect method (line ~226) where it's passed to `CodeAuthMessage::new`:
```rust
self.config.dj_session_id.clone(),
```

**Step 2: Update connect_with_code Tauri command**

In `dj_client/src-tauri/src/lib.rs`, rename the parameter:

```rust
async fn connect_with_code(
    app_handle: AppHandle,
    state: State<'_, AppStateWrapper>,
    code: String,
    dj_name: String,
    server_host: String,
    server_port: u16,
    block_palette: Option<Vec<Option<String>>>,
    dj_session_id: Option<String>,
) -> Result<(), String> {
```

And in the config construction:
```rust
let config = DjClientConfig {
    server_host: server_host.clone(),
    server_port,
    dj_name: dj_name.clone(),
    connect_code: Some(code.clone()),
    dj_session_id,
    ..Default::default()
};
```

**Step 3: Verify it compiles**

Run: `cd dj_client/src-tauri && cargo check`
Expected: Success

**Step 4: Commit**

```bash
git add dj_client/src-tauri/src/protocol/client.rs dj_client/src-tauri/src/lib.rs
git commit -m "feat(dj-client): rename coordinator_token to dj_session_id in config and commands"
```

---

### Task 4: Frontend — Send dj_session_id instead of token

**Files:**
- Modify: `dj_client/src/App.tsx:458,474,477-484`

**Step 1: Update handleConnect to pass dj_session_id**

In `dj_client/src/App.tsx`, find the `handleConnect` function. Change:

```typescript
// Before (around line 458):
let coordinatorToken: string | null = null;

// After:
let djSessionId: string | null = null;
```

```typescript
// Before (around line 474):
coordinatorToken = resolved.token ?? null;

// After:
djSessionId = resolved.dj_session_id ?? null;
```

```typescript
// Before (around line 477-484):
await invoke('connect_with_code', {
  code: formattedCode,
  djName: djName.trim(),
  serverHost: connHost,
  serverPort: connPort,
  blockPalette: auth?.user?.dj_profile?.block_palette ?? null,
  coordinatorToken,
});

// After:
await invoke('connect_with_code', {
  code: formattedCode,
  djName: djName.trim(),
  serverHost: connHost,
  serverPort: connPort,
  blockPalette: auth?.user?.dj_profile?.block_palette ?? null,
  djSessionId,
});
```

**Step 2: Verify frontend compiles**

Run: `cd dj_client && npm run check` (or `npx tsc --noEmit`)
Expected: Success

**Step 3: Commit**

```bash
git add dj_client/src/App.tsx
git commit -m "feat(dj-client): send dj_session_id instead of coordinator token"
```

---

### Task 5: VJ Server — Simplify _hydrate_dj_profile to accept dj_session_id directly

**Files:**
- Modify: `vj_server/vj_server.py:1088-1123` (`_hydrate_dj_profile`)

**Step 1: Rewrite _hydrate_dj_profile**

Replace the current method that decodes a JWT with a simpler version that takes `dj_session_id` directly:

```python
async def _hydrate_dj_profile(self, dj: DJConnection, dj_session_id: str) -> None:
    """Fetch DJ profile from coordinator and populate DJConnection fields.

    Takes the dj_session_id directly (from the code_auth message) and
    calls the coordinator's internal API. Never raises — logs warnings
    on failure.
    """
    try:
        profile = await asyncio.wait_for(
            self._coordinator.fetch_dj_profile(dj_session_id),
            timeout=5.0,
        )
        if profile:
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
        logger.warning("[DJ PROFILE] Failed to load profile for %s: %s", dj.dj_id, exc)
```

This removes the `import base64` usage and the JWT payload decoding logic.

**Step 2: Verify import of `base64` can be removed**

Search for other uses of `base64` in vj_server.py:
Run: `grep -n "base64" vj_server/vj_server.py`

If `_hydrate_dj_profile` was the only user, remove the `import base64` line. If other code uses it, leave the import.

**Step 3: Commit**

```bash
git add vj_server/vj_server.py
git commit -m "refactor(vj-server): simplify _hydrate_dj_profile to accept dj_session_id directly"
```

---

### Task 6: VJ Server — Update callers to pass dj_session_id instead of token

**Files:**
- Modify: `vj_server/vj_server.py:1442` (pending DJ info in code_auth handler)
- Modify: `vj_server/vj_server.py:1697-1699` (dj_auth path profile hydration)
- Modify: `vj_server/vj_server.py:4007-4009` (`_approve_pending_dj`)

**Step 1: Update pending DJ info dict (code_auth handler, ~line 1442)**

Change:
```python
"coordinator_token": data.get("token"),  # Coordinator JWT for profile lookup
```
To:
```python
"dj_session_id": data.get("dj_session_id"),  # For profile lookup
```

**Step 2: Update dj_auth path (~line 1697-1699)**

Change:
```python
coordinator_token = data.get("token")
if coordinator_token and self._coordinator:
    await self._hydrate_dj_profile(dj, coordinator_token)
```
To:
```python
dj_session_id = data.get("dj_session_id")
if dj_session_id and self._coordinator:
    await self._hydrate_dj_profile(dj, dj_session_id)
```

**Step 3: Update _approve_pending_dj (~line 4007-4009)**

Change:
```python
coordinator_token = info.get("coordinator_token")
if coordinator_token and self._coordinator:
    await self._hydrate_dj_profile(dj, coordinator_token)
```
To:
```python
dj_session_id = info.get("dj_session_id")
if dj_session_id and self._coordinator:
    await self._hydrate_dj_profile(dj, dj_session_id)
```

**Step 4: Verify no remaining references to "coordinator_token" in vj_server.py**

Run: `grep -n "coordinator_token" vj_server/vj_server.py`
Expected: No output

**Step 5: Verify VJ server imports cleanly**

Run: `cd vj_server && python -c "from vj_server.vj_server import VJServer; print('OK')"`
Expected: `OK`

**Step 6: Commit**

```bash
git add vj_server/vj_server.py
git commit -m "refactor(vj-server): read dj_session_id from code_auth message instead of JWT token"
```

---

### Task 7: Run existing tests

**Step 1: Run VJ server tests**

Run: `cd vj_server && python -m pytest tests/ -v`
Expected: All tests pass

**Step 2: Run Rust check**

Run: `cd dj_client/src-tauri && cargo check`
Expected: Success

**Step 3: Run coordinator tests (no changes, but verify nothing broke)**

Run: `cd coordinator && python -m pytest tests/ -v`
Expected: All tests pass

**Step 4: Final commit if any fixups needed**

Only if tests revealed issues:
```bash
git add -A
git commit -m "fix: test fixups for dj_session_id rename"
```
