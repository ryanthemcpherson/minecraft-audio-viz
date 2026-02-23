# DJ Client Overhaul Design

**Date:** 2026-02-23
**Status:** Approved

## Problem

The DJ client (Tauri/React desktop app) has functional coverage but several pain points:

1. **Auth flow is broken** — OAuth deep link redirect (`mcav://`) fails on Windows; session tokens don't persist reliably
2. **Layout requires scrolling** — vertical form layout with step-by-step wizard framing doesn't fit on screen; DJs scroll during performance
3. **No queue awareness** — connected DJs can't see who else is in the queue or when they'll go live
4. **Visual noise** — glassmorphism blur, heavy glow borders, and generous spacing add clutter without aiding usability
5. **Performance jank** — React state-driven frequency meter causes unnecessary re-renders at 60fps

## Goals

- Auth that works reliably on all platforms without deep link registration
- Single-screen dashboard layout (no scrolling at default window size)
- Real-time queue/rotation awareness for connected DJs
- Cleaner, more minimal visual style
- Smoother real-time audio feedback

## Non-Goals

- Full component library or design system extraction
- Mobile/responsive layout (desktop-only Tauri app)
- Chat or social features between DJs
- Pattern preview in the DJ client

## 1. Auth Flow — Polling-Based Desktop OAuth

### Problem

Current flow uses `tauri-plugin-deep-link` to register `mcav://` protocol. After browser OAuth, the coordinator redirects to `mcav://callback?exchange_code=...`. This fails because:

- Windows protocol registration is unreliable (silent failure, requires admin in some configs)
- Some browsers block or prompt on custom protocol URLs
- If the app isn't running when the redirect fires, the callback is lost

### Solution

Replace deep link callback with a polling-based flow (similar to device authorization grant):

1. User clicks "Sign in with Discord/Google"
2. App calls `POST /auth/desktop-session` on the coordinator → returns `{ session_id, authorize_url }`
3. App opens `authorize_url` in the default browser
4. App starts polling `GET /auth/desktop-callback/{session_id}` every 2 seconds
5. User completes OAuth in browser; coordinator stores tokens keyed by `session_id`
6. Next poll returns tokens; app stores them and stops polling
7. Timeout after 5 minutes if no callback received

### Coordinator Changes

**New endpoint:** `POST /auth/desktop-session`
- Creates a pending desktop auth session with a random `session_id`
- Returns `{ session_id, authorize_url }` where the authorize URL includes `session_id` in the OAuth state parameter
- Session expires after 5 minutes

**New endpoint:** `GET /auth/desktop-callback/{session_id}`
- Returns `{ status: "pending" }` while waiting
- Returns `{ status: "complete", access_token, refresh_token, expires_in }` once OAuth completes
- Returns `{ status: "expired" }` after timeout

**Updated OAuth callback:** When the OAuth callback fires with a `session_id` in the state parameter, store the resulting tokens in the pending session instead of redirecting to a deep link. Show a "You can close this tab" page in the browser.

### DJ Client Changes

- Remove `@tauri-apps/plugin-deep-link` dependency
- Replace `onOpenUrl` listener with polling loop in `useAuth`
- Show "Waiting for browser sign-in..." spinner with cancel button during polling

### Session Persistence Fix

Keep `@tauri-apps/plugin-store` as primary. Add fallback: if store init fails, use `@tauri-apps/plugin-fs` to read/write `auth.json` in the app data directory. Add proactive token refresh: check `isTokenExpiringSoon()` on a 30-second interval and refresh silently.

## 2. Layout — Single-Screen Dashboard

### Target Window

Default: 700x520px. Everything visible without scrolling. Resizable, content adapts.

### Disconnected State

```
┌─────────────────────────────────────────────────┐
│  [DJ Name input]                  [Profile Chip] │  ← top bar
├─────────────────────────────────────────────────┤
│                                                  │
│   Connect Code: [ABCD-EF12]   Audio: [▼ System] │  ← side by side
│                                                  │
│   ☐ Direct connect (self-hosted)                 │
│     [Host: ___________] [Port: ____]             │  ← conditional
│                                                  │
│   [ ●  Connect ]                                 │  ← full width
│                                                  │
└─────────────────────────────────────────────────┘
```

Key changes from current:
- Remove "Step 1/2/3" wizard framing and hero text
- DJ name, connect code, and audio source on same screen (no stacking)
- Connect code: single input with auto-format (`ABCD-EF12`) instead of 8 separate hex fields
- Direct connect fields only appear when toggled (same as today)

### Connected State

```
┌─────────────────────────────────────────────────┐
│  DJ Name ─ Show Name                [Profile ▼] │  ← top bar
├────────────────────────┬────────────────────────┤
│                        │  Status                 │
│  ■■■■■ Bass    120 BPM │  ● LIVE  12ms           │
│  ■■■■  Low-Mid         │  Route: direct          │
│  ■■■   Mid             │  MC: connected          │
│  ■■    High-Mid        ├────────────────────────┤
│  ■     High            │  Queue                  │
│                        │  ► DJ Nova (LIVE)       │
│  Preset: [auto] [edm]  │    DJ Pulse (#2)        │
│  [chill] [rock] [hip]  │    You (#3) — ~2:30     │
│                        │                         │
├────────────────────────┴────────────────────────┤
│ Source: [▼ System Audio]  🎤 Voice [off]  [Disc] │  ← bottom bar
└─────────────────────────────────────────────────┘
```

Key changes:
- Two-column layout: frequency meter + presets (left), status + queue (right)
- Frequency meter: horizontal bars (grow left-to-right), compact height
- Preset selector: row of small chips below frequency meter
- Status panel: minimal — dot + state + latency + route + MC status
- Queue panel: new (see section 3)
- Bottom bar: audio source dropdown, voice toggle, disconnect button — all in one row

### Implementation

- App.tsx restructured with CSS Grid: `grid-template-columns: 1fr auto` for the two-column connected layout
- Bottom bar is a fixed footer row
- No new component library needed — same React + CSS approach

## 3. Queue / DJ Rotation Panel

### What It Shows

- **Your status**: "YOU ARE LIVE" badge or "Queue position #N"
- **DJ list**: Compact rows — avatar circle (or initials), name, status (live/queued)
- **Rotation countdown**: If server has auto-rotation, show "Next up in ~M:SS" based on rotation interval

### Protocol Addition

Add roster push to the DJ port (9000). Currently only browser clients (8766) receive roster updates.

**New message on DJ port:**
```json
{
  "type": "dj_roster",
  "djs": [
    { "dj_id": "abc", "dj_name": "DJ Nova", "avatar_url": "...", "is_active": true },
    { "dj_id": "def", "dj_name": "DJ Pulse", "avatar_url": null, "is_active": false }
  ],
  "active_dj_id": "abc",
  "queue_position": 2,
  "rotation_interval_sec": 300
}
```

VJ server change: in `_broadcast_dj_roster`, also send to connected DJs on port 9000 (currently only sends to `_broadcast_clients`).

DJ client change: parse `dj_roster` messages in the existing WebSocket handler and update a `roster` state variable.

### No New WebSocket Connection

The DJ client stays on a single connection to port 9000. The roster is pushed alongside existing messages (heartbeat, audio frames, etc.).

## 4. Visual Polish

### Remove Glassmorphism

Replace translucent blur cards with flat dark surfaces:
- Cards: `background: #0f1118` (bg-secondary) with `border: 1px solid rgba(255,255,255,0.06)`
- Remove all `backdrop-filter: blur()` — saves GPU compositing cost and reduces visual noise
- Keep subtle box-shadow for depth on the main panels only

### Reduce Glow Effects

- Accent glow (cyan border-glow) only on: frequency meter bars, LIVE badge, connect button
- All other panels: flat borders, no glow
- Remove hover glow from panels/cards (keep on buttons)

### Tighten Spacing

- Panel inner padding: 20px → 12px
- Gap between sections: 16px → 8px
- Frequency meter height: reduce by ~30% (horizontal bars are more compact)
- Bottom bar height: ~44px total

### Typography Cleanup

- Remove redundant labels where the control is self-explanatory
- "Audio Source:" label → just the dropdown (it says "System Audio" inside)
- "DJ Name:" label → placeholder text in the input
- "Connect Code:" → just "Code" or placeholder text
- Reduce heading sizes throughout

### Simplify Connect Code Input

Replace 8 separate hex input fields with a single input:
- Auto-formats as user types: `ABCDEF12` → displays as `ABCD-EF12`
- Paste support: strips whitespace and dashes, validates hex
- Single focus target instead of 8 tab stops

## 5. Performance Feel

### Frequency Meter — Canvas Rendering

The biggest jank source. Currently the meter re-renders React components on every audio frame (~60fps). Replace with:

- HTML5 Canvas element (fixed size, no React re-renders)
- `requestAnimationFrame` loop reads latest audio data from a ref (not state)
- React only manages the canvas element lifecycle, not per-frame updates
- Canvas draws horizontal bars with gradient fills

This eliminates ~60 React re-render cycles per second from the hottest path.

### Memoize Stable Panels

In the connected state, audio data changes trigger re-renders of everything. Fix with:
- `React.memo` on StatusPanel (only re-renders on connection state changes)
- `React.memo` on queue panel (only re-renders on roster changes)
- Audio data stored in `useRef` instead of `useState` for the frequency meter
- Preset selector and voice controls are already low-frequency updates

### CSS Transitions Over JS

- Glow pulse on LIVE badge: CSS `@keyframes` (already done, just verify)
- Bar height/width transitions: handled by canvas, not CSS
- Panel visibility toggles: CSS `transition: opacity 0.15s` instead of conditional renders where possible

## Implementation Order

1. **Auth polling flow** — coordinator endpoints + DJ client useAuth rewrite (unblocks reliable sign-in)
2. **Layout restructure** — CSS Grid dashboard, remove wizard framing, single-screen fit
3. **Connect code simplification** — single auto-format input replacing 8 fields
4. **Visual polish** — remove blur, reduce glow, tighten spacing, typography cleanup
5. **Frequency meter canvas** — canvas-based rendering, ref-based audio data
6. **Queue panel** — VJ server roster push + new UI component
7. **Memoization pass** — React.memo on stable panels, useRef for audio

Steps 1-5 are DJ client focused. Step 6 requires a VJ server change. Step 7 is a polish pass after everything else works.
