# DJ Client Overhaul Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Overhaul the DJ client with reliable auth, single-screen dashboard layout, queue awareness, visual cleanup, and performance improvements.

**Architecture:** Replace deep-link OAuth with polling-based flow (coordinator changes + DJ client rewrite). Restructure App.tsx from vertical scroll to CSS Grid dashboard. Add roster push to DJ port for queue panel. Replace React-rendered frequency meter with canvas.

**Tech Stack:** React 19, TypeScript, Tauri v2, CSS Grid, HTML5 Canvas, Python (coordinator FastAPI + VJ server)

**Design doc:** `docs/plans/2026-02-23-dj-client-overhaul-design.md`

---

### Task 1: Coordinator — Polling-Based Desktop Auth

**Files:**
- Modify: `coordinator/app/routers/auth.py` (lines 227-329 Discord flow, 339-440 Google flow, 450-485 exchange)
- Modify: `coordinator/app/config.py` (add env var for poll TTL)
- Create: `coordinator/tests/test_desktop_poll.py`

The coordinator already has a working desktop OAuth flow that redirects to `mcav://` deep links. We need to add a polling alternative.

**Step 1: Add poll endpoint and modify authorize response**

In `coordinator/app/routers/auth.py`:

a) Modify `GET /auth/discord` (line 227) and `GET /auth/google` (line 339): when `desktop=True`, include the state JWT's nonce in the response as `poll_token`:

```python
# In the discord endpoint, after creating state_token:
if desktop:
    # Extract nonce from state for polling
    state_payload = jwt.decode(state_token, settings.jwt_secret, algorithms=["HS256"])
    return {"authorize_url": authorize_url, "poll_token": state_payload["nonce"]}
return {"authorize_url": authorize_url}
```

Same change for the Google endpoint.

b) Add new poll endpoint after the exchange endpoint (~line 486):

```python
@router.get("/auth/desktop-poll/{poll_token}")
async def desktop_poll(poll_token: str, db: AsyncSession = Depends(get_db)):
    """Poll for desktop OAuth completion. Returns exchange_code when ready."""
    # Look up DesktopExchangeCode by nonce (poll_token)
    from app.models.auth import DesktopExchangeCode
    result = await db.execute(
        select(DesktopExchangeCode).where(
            DesktopExchangeCode.nonce == poll_token,
            DesktopExchangeCode.expires_at > func.now(),
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        return {"status": "pending"}
    return {"status": "complete", "exchange_code": row.code}
```

c) Modify the OAuth callbacks (lines 308-329 for Discord, 420-440 for Google): when `desktop=True`, store the nonce alongside the exchange code in the `DesktopExchangeCode` row so the poll endpoint can look it up. Then instead of redirecting to `mcav://`, return an HTML page saying "You can close this tab."

Check the `DesktopExchangeCode` model — if it doesn't have a `nonce` column, add one (nullable, for backward compat).

**Step 2: Write tests**

```python
# coordinator/tests/test_desktop_poll.py

import pytest
from httpx import AsyncClient

@pytest.mark.asyncio
async def test_desktop_poll_pending(client: AsyncClient):
    """Poll with unknown token returns pending."""
    resp = await client.get("/api/v1/auth/desktop-poll/nonexistent")
    assert resp.status_code == 200
    assert resp.json()["status"] == "pending"

@pytest.mark.asyncio
async def test_discord_desktop_returns_poll_token(client: AsyncClient):
    """Discord authorize with desktop=true returns poll_token."""
    resp = await client.get("/api/v1/auth/discord", params={"desktop": "true"})
    assert resp.status_code == 200
    data = resp.json()
    assert "authorize_url" in data
    assert "poll_token" in data
    assert len(data["poll_token"]) > 0
```

**Step 3: Run tests**

```bash
cd coordinator && pytest tests/test_desktop_poll.py -v
```

**Step 4: Commit**

```bash
git add coordinator/app/routers/auth.py coordinator/tests/test_desktop_poll.py
git commit -m "feat(coordinator): add polling-based desktop OAuth flow"
```

---

### Task 2: DJ Client — Replace Deep Link Auth with Polling

**Files:**
- Modify: `dj_client/src/hooks/useAuth.ts` (full file, 163 lines)
- Modify: `dj_client/src/lib/api.ts` (add poll function)
- Modify: `dj_client/src/lib/token-store.ts` (add fallback persistence)
- Modify: `dj_client/src-tauri/tauri.conf.json` (remove deep-link config)
- Modify: `dj_client/src-tauri/Cargo.toml` (remove deep-link dependency)
- Modify: `dj_client/src-tauri/capabilities/default.json` (remove deep-link permission)
- Modify: `dj_client/src-tauri/src/lib.rs` (remove deep-link plugin init and handler)
- Modify: `dj_client/package.json` (remove @tauri-apps/plugin-deep-link)

**Step 1: Add poll API function**

In `dj_client/src/lib/api.ts`, add:

```typescript
export async function pollDesktopAuth(pollToken: string): Promise<{
  status: 'pending' | 'complete' | 'expired';
  exchange_code?: string;
}> {
  const resp = await fetch(`${API_BASE}/auth/desktop-poll/${pollToken}`);
  if (!resp.ok) throw new Error('Poll failed');
  return resp.json();
}
```

Also modify `getDiscordAuthorizeUrl` and `getGoogleAuthorizeUrl` return types to include `poll_token: string`.

**Step 2: Rewrite useAuth polling flow**

Replace the `onOpenUrl` deep link listener (lines 51-76 of `useAuth.ts`) with a polling loop:

```typescript
// New: polling-based OAuth
const signInWithDiscord = useCallback(async () => {
  setError(null);
  try {
    const { authorize_url, poll_token } = await api.getDiscordAuthorizeUrl(true);
    await open(authorize_url);
    // Start polling
    setIsLoading(true);
    const maxAttempts = 150; // 5 minutes at 2s intervals
    for (let i = 0; i < maxAttempts; i++) {
      await new Promise(r => setTimeout(r, 2000));
      const result = await api.pollDesktopAuth(poll_token);
      if (result.status === 'complete' && result.exchange_code) {
        await api.exchangeDesktopCode(result.exchange_code);
        const profile = await api.getProfile();
        setUser(profile);
        return;
      }
      if (result.status === 'expired') break;
    }
    setError('Sign-in timed out. Please try again.');
  } catch (e) {
    setError(e instanceof Error ? e.message : String(e));
  } finally {
    setIsLoading(false);
  }
}, []);
```

Same pattern for `signInWithGoogle`. Remove the `onOpenUrl` useEffect entirely.

**Step 3: Remove deep-link plugin**

- `dj_client/src-tauri/tauri.conf.json` line 60-63: Remove the `"deep-link"` section
- `dj_client/src-tauri/Cargo.toml` line 36: Remove `tauri-plugin-deep-link = "2"`
- `dj_client/src-tauri/capabilities/default.json` line 10: Remove `"deep-link:default"` from permissions array
- `dj_client/src-tauri/src/lib.rs` line 1033: Remove `.plugin(tauri_plugin_deep_link::init())`
- `dj_client/src-tauri/src/lib.rs` lines 1135-1148: Remove the `deep_link().on_open_url` handler and `deep_link().register("mcav")` call
- `dj_client/package.json`: Remove `@tauri-apps/plugin-deep-link`
- `dj_client/src/hooks/useAuth.ts` line 2: Remove `import { onOpenUrl } from '@tauri-apps/plugin-deep-link'`

**Step 4: Add token refresh interval**

In `useAuth.ts`, add a 30-second interval that checks `isTokenExpiringSoon()` and refreshes proactively:

```typescript
useEffect(() => {
  if (!isSignedIn) return;
  const interval = setInterval(async () => {
    if (api.isTokenExpiringSoon()) {
      try {
        await api.refreshTokens();
      } catch {
        // Silent failure — will retry next interval
      }
    }
  }, 30_000);
  return () => clearInterval(interval);
}, [isSignedIn]);
```

Re-export `isTokenExpiringSoon` from `api.ts` (it's in `token-store.ts`).

**Step 5: Verify**

```bash
cd dj_client && npm run check
cd dj_client/src-tauri && cargo check
```

**Step 6: Commit**

```bash
git add -A dj_client/
git commit -m "feat(dj): replace deep-link OAuth with polling flow"
```

---

### Task 3: Simplify Connect Code Input

**Files:**
- Rewrite: `dj_client/src/components/ConnectCode.tsx` (123 lines → ~60 lines)

**Step 1: Replace 8-field input with single auto-formatting input**

Replace the entire ConnectCode component with a single `<input>` that:
- Accepts hex characters + digits (same charset: `ABCDEFGHIJKLMNOPQRSTUVWXYZ23456789`)
- Auto-inserts hyphen after 4th character for display
- Strips non-valid chars on input
- Handles paste (strips whitespace and hyphens, validates)
- Calls `onChange` with the raw 8-char value (no hyphen)

```tsx
interface ConnectCodeProps {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}

export default function ConnectCode({ value, onChange, disabled }: ConnectCodeProps) {
  const VALID = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ23456789';

  const formatDisplay = (raw: string) => {
    const clean = raw.toUpperCase().split('').filter(c => VALID.includes(c)).join('').slice(0, 8);
    if (clean.length > 4) return clean.slice(0, 4) + '-' + clean.slice(4);
    return clean;
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value.replace(/-/g, '').toUpperCase()
      .split('').filter(c => VALID.includes(c)).join('').slice(0, 8);
    onChange(raw);
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    e.preventDefault();
    const pasted = e.clipboardData.getData('text').replace(/[\s-]/g, '').toUpperCase()
      .split('').filter(c => VALID.includes(c)).join('').slice(0, 8);
    onChange(pasted);
  };

  return (
    <input
      type="text"
      className="input connect-code-input"
      value={formatDisplay(value)}
      onChange={handleChange}
      onPaste={handlePaste}
      placeholder="ABCD-EF12"
      maxLength={9}
      disabled={disabled}
      spellCheck={false}
      autoComplete="off"
    />
  );
}
```

**Step 2: Update App.tsx**

The ConnectCode component interface changes from `label` prop to simpler props. Update the usage in App.tsx (~line 849) to match the new interface. The `value`/`onChange` pattern stays the same.

**Step 3: Update CSS**

Replace the `.connect-code` grid styles in `app.css` with a single `.connect-code-input` style:

```css
.connect-code-input {
  font-family: 'JetBrains Mono', monospace;
  font-size: 1.25rem;
  letter-spacing: 0.15em;
  text-align: center;
  text-transform: uppercase;
}
```

Remove the old `.code-field`, `.code-separator`, `.code-group` styles.

**Step 4: Verify**

```bash
cd dj_client && npm run check
```

**Step 5: Commit**

```bash
git add dj_client/src/components/ConnectCode.tsx dj_client/src/App.tsx dj_client/src/styles/app.css
git commit -m "refactor(dj): simplify connect code to single auto-format input"
```

---

### Task 4: Layout Restructure — Single-Screen Dashboard

**Files:**
- Modify: `dj_client/src/App.tsx` (major restructure of render, ~lines 800-1070)
- Modify: `dj_client/src/styles/app.css` (layout overhaul)

This is the largest task. The goal is to restructure the render from a vertical scroll wizard into a CSS Grid dashboard.

**Step 1: Restructure disconnected state (App.tsx ~lines 809-944)**

Replace the step-by-step wizard with a compact form:

```tsx
{!status.connected ? (
  <div className="dashboard disconnected">
    <div className="top-bar">
      <input
        type="text"
        className="input dj-name-input"
        value={djName}
        onChange={e => setDjName(e.target.value)}
        placeholder="DJ Name"
        maxLength={32}
      />
      <div className="top-bar-right">
        {/* ProfileChip or Sign In button */}
      </div>
    </div>

    <div className="connect-form">
      <div className="connect-row">
        <div className="field-group">
          <label className="field-label">Code</label>
          <ConnectCode value={connectCode} onChange={setConnectCode} />
        </div>
        <div className="field-group">
          <label className="field-label">Audio</label>
          <AudioSourceSelect ... />
        </div>
      </div>

      {directConnect && (
        <div className="direct-connect-row">
          <input placeholder="Host" ... />
          <input placeholder="Port" ... />
        </div>
      )}

      <label className="checkbox-label">
        <input type="checkbox" checked={directConnect} onChange={...} />
        Direct connect (self-hosted)
      </label>

      <button className="btn btn-connect full-width" onClick={handleConnect}>
        Connect
      </button>
    </div>
  </div>
) : ( /* connected state */ )}
```

**Step 2: Restructure connected state (App.tsx ~lines 945-1070)**

Replace vertical stack with two-column grid:

```tsx
<div className="dashboard connected">
  <div className="top-bar">
    <span className="dj-label">{djName}</span>
    {showName && <span className="show-label">{showName}</span>}
    <div className="top-bar-right">
      {/* ProfileChip */}
    </div>
  </div>

  <div className="main-grid">
    <div className="col-left">
      <FrequencyMeter bands={bands} isBeat={isBeat} beatIntensity={beatIntensity} bpm={bpm} />
      <div className="preset-row">
        {presets.map(p => (
          <button
            key={p}
            className={`preset-chip ${activePreset === p ? 'active' : ''}`}
            onClick={() => setPreset(p)}
          >{p}</button>
        ))}
      </div>
    </div>

    <div className="col-right">
      <StatusPanel status={status} />
      <QueuePanel roster={roster} />
    </div>
  </div>

  <div className="bottom-bar">
    <AudioSourceSelect ... compact />
    <button className="voice-toggle" ...>🎤 Voice</button>
    <button className="btn btn-disconnect" onClick={handleDisconnect}>Disconnect</button>
  </div>
</div>
```

**Step 3: CSS Grid layout**

Add to `app.css`:

```css
.dashboard {
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding: 12px;
  gap: 8px;
}

.top-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
}

.top-bar-right {
  margin-left: auto;
}

.main-grid {
  display: grid;
  grid-template-columns: 1fr 240px;
  gap: 8px;
  flex: 1;
  min-height: 0;
}

.bottom-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
}

.connect-form {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
  max-width: 500px;
  margin: 0 auto;
}

.connect-row {
  display: flex;
  gap: 12px;
}

.connect-row .field-group {
  flex: 1;
}

.preset-row {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.preset-chip {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 0.75rem;
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s;
}

.preset-chip.active {
  background: rgba(0, 204, 255, 0.12);
  border-color: rgba(0, 204, 255, 0.3);
  color: var(--accent);
}
```

**Step 4: Remove wizard/step styles**

Remove from `app.css`: `.step-header`, `.step-number`, `.step-title`, `.hero-banner`, and any step-related styling. Remove the `max-width: 680px` constraint on the main container.

**Step 5: Set default window size**

In `dj_client/src-tauri/tauri.conf.json`, update the window config:

```json
"windows": [{
  "width": 700,
  "height": 520,
  "minWidth": 600,
  "minHeight": 400
}]
```

**Step 6: Verify**

```bash
cd dj_client && npm run check
```

**Step 7: Commit**

```bash
git add dj_client/src/App.tsx dj_client/src/styles/app.css dj_client/src-tauri/tauri.conf.json
git commit -m "feat(dj): restructure to single-screen dashboard layout"
```

---

### Task 5: Visual Polish — Remove Blur, Reduce Glow, Tighten Spacing

**Files:**
- Modify: `dj_client/src/styles/app.css`

**Step 1: Remove glassmorphism blur**

Search `app.css` for all `backdrop-filter` and `background.*rgba.*0\.(7|8|9)` (translucent panels). Replace with flat dark surfaces:

```css
/* Before */
.panel {
  background: rgba(15, 17, 24, 0.88);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(98, 188, 255, 0.28);
}

/* After */
.panel {
  background: #0f1118;
  border: 1px solid rgba(255, 255, 255, 0.06);
}
```

Apply to all card/panel classes: `.section`, `.status-panel`, `.meter-container`, etc.

**Step 2: Reduce glow effects**

Keep accent glow ONLY on:
- `.meter-bar` (frequency meter bars) — existing dynamic glow via inline styles is fine
- `.live-badge` / `.status-dot.live` — pulsing glow
- `.btn-connect` — the connect button

Remove glow from all other elements. Search for `box-shadow.*rgba.*0, *204, *255` and `box-shadow.*cyan` and remove non-essential ones.

**Step 3: Tighten spacing**

Global pass:
- Panel padding: `padding: 20px` → `padding: 12px`
- Section gaps: `gap: 16px` → `gap: 8px`
- Heading font sizes: reduce by ~15%
- Remove decorative margins/padding that don't aid readability

**Step 4: Typography cleanup**

Remove redundant labels. In App.tsx, remove label text where the input placeholder or dropdown content makes it obvious:
- "Audio Source:" → remove (dropdown shows "System Audio")
- "DJ Name:" → remove (placeholder "DJ Name")
- Section headers like "Step 1", "Step 2", "Step 3" → already removed in layout task

**Step 5: Commit**

```bash
git add dj_client/src/styles/app.css dj_client/src/App.tsx
git commit -m "style(dj): clean visual polish — flat surfaces, reduced glow, tighter spacing"
```

---

### Task 6: Frequency Meter — Canvas Rendering

**Files:**
- Rewrite: `dj_client/src/components/FrequencyMeter.tsx` (65 lines → ~100 lines)
- Modify: `dj_client/src/App.tsx` (change audio data from useState to useRef)

**Step 1: Convert audio data to useRef in App.tsx**

In App.tsx, the audio data (`bands`, `isBeat`, `bpm`, `beatIntensity`) is currently stored in useState (lines 72-79), causing re-renders on every frame. Change to useRef:

```typescript
// Before (lines 72-79):
const [bands, setBands] = useState<number[]>([0, 0, 0, 0, 0]);
const [isBeat, setIsBeat] = useState(false);
const [bpm, setBpm] = useState(0);
const [beatIntensity, setBeatIntensity] = useState(0);

// After:
const audioRef = useRef({ bands: [0,0,0,0,0], isBeat: false, bpm: 0, beatIntensity: 0 });
```

Update the Tauri event listener (lines 278-284) to write to the ref instead of calling setState:

```typescript
listen<AudioLevels>('audio-levels', (event) => {
  audioRef.current.bands = event.payload.bands;
  audioRef.current.isBeat = event.payload.is_beat;
  audioRef.current.bpm = event.payload.bpm;
  audioRef.current.beatIntensity = event.payload.beat_intensity;
});
```

Pass `audioRef` to FrequencyMeter instead of individual props.

**Step 2: Rewrite FrequencyMeter with canvas**

```tsx
import { useRef, useEffect } from 'react';

const BAND_NAMES = ['Bass', 'Low', 'Mid', 'High', 'Air'];
const BAND_COLORS = ['#ff9f43', '#ffd166', '#2fe098', '#43c5ff', '#c77dff'];

interface AudioData {
  bands: number[];
  isBeat: boolean;
  bpm: number;
  beatIntensity: number;
}

interface FrequencyMeterProps {
  audioRef: React.RefObject<AudioData>;
}

export default function FrequencyMeter({ audioRef }: FrequencyMeterProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animRef = useRef<number>(0);
  // Smoothed values for interpolation
  const smoothed = useRef<number[]>([0, 0, 0, 0, 0]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const draw = () => {
      const { bands, isBeat, bpm, beatIntensity } = audioRef.current;
      const w = canvas.width;
      const h = canvas.height;
      const barHeight = Math.floor((h - 30) / 5); // 5 bars + BPM area
      const barGap = 2;
      const labelWidth = 40;

      ctx.clearRect(0, 0, w, h);

      for (let i = 0; i < 5; i++) {
        // Smooth toward target
        const target = bands[i] ?? 0;
        smoothed.current[i] += (target - smoothed.current[i]) * 0.3;
        const val = smoothed.current[i];

        const y = i * (barHeight + barGap);
        const barW = val * (w - labelWidth - 8);

        // Bar fill
        ctx.fillStyle = BAND_COLORS[i];
        ctx.globalAlpha = 0.85;
        ctx.fillRect(labelWidth, y, barW, barHeight - barGap);

        // Glow on high values
        if (val > 0.5) {
          ctx.globalAlpha = (val - 0.5) * 0.4;
          ctx.shadowColor = BAND_COLORS[i];
          ctx.shadowBlur = 8;
          ctx.fillRect(labelWidth, y, barW, barHeight - barGap);
          ctx.shadowBlur = 0;
        }
        ctx.globalAlpha = 1;

        // Label
        ctx.fillStyle = '#a1a1aa';
        ctx.font = '11px Inter, sans-serif';
        ctx.textBaseline = 'middle';
        ctx.fillText(BAND_NAMES[i], 0, y + (barHeight - barGap) / 2);
      }

      // BPM display
      const bpmY = 5 * (barHeight + barGap) + 4;
      ctx.fillStyle = isBeat ? '#ffaa00' : '#a1a1aa';
      ctx.font = 'bold 14px "JetBrains Mono", monospace';
      ctx.fillText(`${Math.round(bpm)} BPM`, 0, bpmY + 8);

      animRef.current = requestAnimationFrame(draw);
    };

    animRef.current = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(animRef.current);
  }, [audioRef]);

  return (
    <canvas
      ref={canvasRef}
      className="frequency-canvas"
      width={400}
      height={160}
    />
  );
}
```

**Step 3: Add canvas CSS**

```css
.frequency-canvas {
  width: 100%;
  height: auto;
  border-radius: 8px;
}
```

Remove old `.meter-bars`, `.meter-bar`, `.meter-label`, `.meter-bar-fill` styles from `app.css`.

**Step 4: Verify**

```bash
cd dj_client && npm run check
```

**Step 5: Commit**

```bash
git add dj_client/src/components/FrequencyMeter.tsx dj_client/src/App.tsx dj_client/src/styles/app.css
git commit -m "perf(dj): canvas-based frequency meter, useRef for audio data"
```

---

### Task 7: Queue Panel — VJ Server Roster Push + UI

**Files:**
- Modify: `vj_server/vj_server.py` (lines 3798-3813, `_broadcast_dj_roster`)
- Create: `dj_client/src/components/QueuePanel.tsx`
- Modify: `dj_client/src/App.tsx` (add roster state, handle dj_roster messages)
- Modify: `dj_client/src/styles/app.css` (queue panel styles)

**Step 1: VJ server — push roster to DJs**

In `vj_server/vj_server.py`, modify `_broadcast_dj_roster` (line 3798) to also send to connected DJs:

```python
async def _broadcast_dj_roster(self):
    """Broadcast DJ roster to all browser clients and connected DJs."""
    roster = self._get_dj_roster()
    active_dj = self._active_dj_id

    # Browser clients get full roster
    browser_msg = _json_str({
        "type": "dj_roster",
        "roster": roster,
        "active_dj": active_dj,
    })
    dead_clients = set()
    for client in list(self._broadcast_clients):
        try:
            await client.send(browser_msg)
        except Exception:
            dead_clients.add(client)
    self._broadcast_clients -= dead_clients

    # DJs get a lightweight roster (no admin-level stats)
    for dj_id, dj in dict(self._djs).items():
        try:
            dj_roster = [
                {
                    "dj_id": r["dj_id"],
                    "dj_name": r["dj_name"],
                    "is_active": r["is_active"],
                    "avatar_url": r.get("avatar_url"),
                    "queue_position": r["queue_position"],
                }
                for r in roster
            ]
            await dj.websocket.send(_json_str({
                "type": "dj_roster",
                "djs": dj_roster,
                "active_dj_id": active_dj,
                "your_position": next(
                    (r["queue_position"] for r in roster if r["dj_id"] == dj_id), 999
                ),
                "rotation_interval_sec": getattr(self, '_rotation_interval_sec', 0),
            }))
        except Exception:
            pass
```

**Step 2: DJ client — handle roster messages**

In `dj_client/src-tauri/src/lib.rs`, the WebSocket message handler already forwards certain messages as Tauri events. Add `dj_roster` to the set of forwarded message types. Find where messages are matched by type and add:

```rust
"dj_roster" => {
    let _ = app_handle.emit("dj-roster", &msg_value);
}
```

In `dj_client/src/App.tsx`, add roster state and listener:

```typescript
interface RosterDJ {
  dj_id: string;
  dj_name: string;
  is_active: boolean;
  avatar_url: string | null;
  queue_position: number;
}

interface RosterUpdate {
  djs: RosterDJ[];
  active_dj_id: string | null;
  your_position: number;
  rotation_interval_sec: number;
}

const [roster, setRoster] = useState<RosterUpdate | null>(null);

// In useEffect with other listeners:
listen<RosterUpdate>('dj-roster', (event) => {
  setRoster(event.payload);
});
```

**Step 3: Create QueuePanel component**

```tsx
// dj_client/src/components/QueuePanel.tsx

interface RosterDJ {
  dj_id: string;
  dj_name: string;
  is_active: boolean;
  avatar_url: string | null;
  queue_position: number;
}

interface QueuePanelProps {
  roster: {
    djs: RosterDJ[];
    active_dj_id: string | null;
    your_position: number;
    rotation_interval_sec: number;
  } | null;
}

export default function QueuePanel({ roster }: QueuePanelProps) {
  if (!roster || roster.djs.length === 0) {
    return (
      <div className="queue-panel">
        <div className="queue-header">Queue</div>
        <div className="queue-empty">No other DJs connected</div>
      </div>
    );
  }

  const sorted = [...roster.djs].sort((a, b) => a.queue_position - b.queue_position);

  return (
    <div className="queue-panel">
      <div className="queue-header">Queue</div>
      {sorted.map(dj => (
        <div key={dj.dj_id} className={`queue-dj ${dj.is_active ? 'active' : ''}`}>
          <div className="queue-avatar">
            {dj.avatar_url
              ? <img src={dj.avatar_url} alt="" />
              : <span>{dj.dj_name.charAt(0).toUpperCase()}</span>
            }
          </div>
          <span className="queue-name">{dj.dj_name}</span>
          {dj.is_active && <span className="queue-live">LIVE</span>}
        </div>
      ))}
    </div>
  );
}
```

**Step 4: Add queue panel CSS**

```css
.queue-panel {
  background: #0f1118;
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 8px;
  padding: 12px;
}

.queue-header {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--text-secondary);
  margin-bottom: 8px;
}

.queue-dj {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.queue-dj.active {
  color: var(--success);
}

.queue-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: rgba(255,255,255,0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.65rem;
  overflow: hidden;
}

.queue-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.queue-name {
  flex: 1;
  font-size: 0.8rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.queue-live {
  font-size: 0.6rem;
  font-weight: 700;
  color: var(--success);
  letter-spacing: 0.05em;
}

.queue-empty {
  font-size: 0.75rem;
  color: var(--text-secondary);
  font-style: italic;
}
```

**Step 5: Verify**

```bash
cd dj_client && npm run check
```

**Step 6: Commit**

```bash
git add vj_server/vj_server.py dj_client/src/components/QueuePanel.tsx dj_client/src/App.tsx dj_client/src/styles/app.css
git commit -m "feat(dj): add queue panel with DJ roster from VJ server"
```

---

### Task 8: Memoization Pass

**Files:**
- Modify: `dj_client/src/components/StatusPanel.tsx`
- Modify: `dj_client/src/components/QueuePanel.tsx`
- Modify: `dj_client/src/App.tsx`

**Step 1: Wrap stable components with React.memo**

```typescript
// StatusPanel.tsx — wrap export
export default React.memo(StatusPanel);

// QueuePanel.tsx — wrap export
export default React.memo(QueuePanel);
```

**Step 2: Verify audio data no longer causes full re-renders**

After Task 6, audio data is in a ref, so the main App component doesn't re-render on every frame. Confirm StatusPanel and QueuePanel only re-render when their props change (connection status changes, roster updates — both low-frequency events).

**Step 3: Commit**

```bash
git add dj_client/src/components/StatusPanel.tsx dj_client/src/components/QueuePanel.tsx
git commit -m "perf(dj): memoize StatusPanel and QueuePanel"
```

---

## Task Dependencies

```
Task 1 (coordinator auth) → Task 2 (DJ client auth) — sequential
Task 3 (connect code) — independent
Task 4 (layout) — independent, but should be done before Task 7
Task 5 (visual polish) — after Task 4
Task 6 (canvas meter) — independent
Task 7 (queue panel) — after Task 4 (needs layout grid), requires VJ server change
Task 8 (memoization) — after Tasks 6 and 7
```

Recommended execution order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8
