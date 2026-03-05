# DJ Client UI Overhaul Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Redesign the DJ client's connected dashboard for compact sidebar window usage with improved visual polish and component extraction.

**Architecture:** Compact-first single-column layout that stacks status → frequency strip → presets → queue, expanding to 2-column at >720px. Extract 6 components from the monolithic App.tsx. Apply MCAV brand polish (glassmorphism, glow effects, typography fixes).

**Tech Stack:** React 18, TypeScript, CSS (no framework), Tauri v2, shared design tokens from `shared/tokens.css`

---

### Task 1: Extract ConnectForm Component

Extract the disconnected-state connect form from App.tsx into its own component.

**Files:**
- Create: `dj_client/src/components/ConnectForm.tsx`
- Modify: `dj_client/src/App.tsx:700-773`

**Step 1: Create ConnectForm component**

Create `dj_client/src/components/ConnectForm.tsx`:

```tsx
import ConnectCode from './ConnectCode';
import AudioSourceSelect from './AudioSourceSelect';

interface AudioSource {
  id: string;
  name: string;
  source_type: 'system_audio' | 'application' | 'input_device';
}

interface ConnectFormProps {
  connectCode: string;
  onConnectCodeChange: (code: string) => void;
  selectedSource: string | null;
  onSourceChange: (sourceId: string | null) => void;
  audioSources: AudioSource[];
  onRefreshSources: () => void;
  directConnect: boolean;
  onDirectConnectChange: (checked: boolean) => void;
  serverHost: string;
  onServerHostChange: (host: string) => void;
  serverPort: number;
  onServerPortChange: (port: number) => void;
  error: string | null;
  isConnecting: boolean;
  djName: string;
  onConnect: () => void;
}

export default function ConnectForm({
  connectCode, onConnectCodeChange,
  selectedSource, onSourceChange, audioSources, onRefreshSources,
  directConnect, onDirectConnectChange,
  serverHost, onServerHostChange, serverPort, onServerPortChange,
  error, isConnecting, djName, onConnect,
}: ConnectFormProps) {
  return (
    <div className="connect-form">
      <div className="connect-row">
        <div className="field-group">
          <label className="field-label">Code</label>
          <ConnectCode value={connectCode} onChange={onConnectCodeChange} />
        </div>
        <div className="field-group">
          <label className="field-label">Audio</label>
          <AudioSourceSelect
            sources={audioSources}
            value={selectedSource}
            onChange={onSourceChange}
            onRefresh={onRefreshSources}
          />
        </div>
      </div>

      <label className="checkbox-label">
        <input type="checkbox" checked={directConnect} onChange={e => onDirectConnectChange(e.target.checked)} />
        Direct connect (self-hosted)
      </label>

      {directConnect && (
        <div className="direct-connect-row">
          <input
            type="text"
            className="input input-sm"
            value={serverHost}
            onChange={e => onServerHostChange(e.target.value)}
            placeholder="Host"
          />
          <input
            type="number"
            className="input input-sm input-port"
            value={serverPort}
            onChange={e => onServerPortChange(parseInt(e.target.value, 10) || 9000)}
            placeholder="Port"
          />
        </div>
      )}

      {error && <div className="error-message">{error}</div>}

      <button
        className="btn btn-connect full-width"
        onClick={onConnect}
        disabled={isConnecting || connectCode.length !== 8 || !djName.trim()}
      >
        {isConnecting ? 'Connecting...' : 'Connect'}
      </button>
    </div>
  );
}
```

**Step 2: Update App.tsx to use ConnectForm**

In `dj_client/src/App.tsx`, add import and replace the connect form JSX (lines 722-772) with:

```tsx
import ConnectForm from './components/ConnectForm';

// ... in the disconnected dashboard section, replace <div className="connect-form">...</div> with:
<ConnectForm
  connectCode={connectCode}
  onConnectCodeChange={setConnectCode}
  selectedSource={selectedSource}
  onSourceChange={setSelectedSource}
  audioSources={audioSources}
  onRefreshSources={loadAudioSources}
  directConnect={directConnect}
  onDirectConnectChange={setDirectConnect}
  serverHost={serverHost}
  onServerHostChange={setServerHost}
  serverPort={serverPort}
  onServerPortChange={setServerPort}
  error={status.error}
  isConnecting={isConnecting}
  djName={djName}
  onConnect={handleConnect}
/>
```

**Step 3: Verify the app builds**

Run: `cd dj_client && npx vite build`
Expected: Build succeeds with no TypeScript errors.

**Step 4: Commit**

```bash
git add dj_client/src/components/ConnectForm.tsx dj_client/src/App.tsx
git commit -m "refactor(dj): extract ConnectForm component from App.tsx"
```

---

### Task 2: Extract TopBar Component

Extract the top bar (DJ name, show label, beat indicator, profile chip) into its own component.

**Files:**
- Create: `dj_client/src/components/TopBar.tsx`
- Modify: `dj_client/src/App.tsx`

**Step 1: Create TopBar component**

Create `dj_client/src/components/TopBar.tsx`:

```tsx
import BeatIndicator from './BeatIndicator';
import ProfileChip from './ProfileChip';

interface TopBarProps {
  djName: string;
  onDjNameChange?: (name: string) => void;
  showName: string | null;
  isBeat: boolean;
  isConnected: boolean;
  user: any | null;
  isSignedIn: boolean;
  onSignOut: () => void;
  onSignIn: () => void;
}

export default function TopBar({
  djName, onDjNameChange, showName, isBeat, isConnected,
  user, isSignedIn, onSignOut, onSignIn,
}: TopBarProps) {
  return (
    <div className="top-bar">
      {onDjNameChange ? (
        <input
          type="text"
          className="input dj-name-input"
          value={djName}
          onChange={e => onDjNameChange(e.target.value)}
          placeholder="DJ Name"
          maxLength={32}
        />
      ) : (
        <span className="dj-label">{djName}</span>
      )}
      {showName && <span className="show-label">{showName}</span>}
      <div className="top-bar-right">
        {isSignedIn && user ? (
          <ProfileChip user={user} onSignOut={onSignOut} />
        ) : !isConnected ? (
          <button className="btn-signin" onClick={onSignIn} type="button">
            Sign In
          </button>
        ) : null}
        {isConnected && <BeatIndicator active={isBeat} />}
      </div>
    </div>
  );
}
```

**Step 2: Update App.tsx to use TopBar**

Replace both top-bar sections in App.tsx (disconnected and connected) with:

```tsx
import TopBar from './components/TopBar';

// Disconnected state:
<TopBar
  djName={djName}
  onDjNameChange={setDjName}
  showName={null}
  isBeat={false}
  isConnected={false}
  user={auth.user}
  isSignedIn={auth.isSignedIn}
  onSignOut={auth.signOut}
  onSignIn={() => setShowAuthModal(true)}
/>

// Connected state:
<TopBar
  djName={djName}
  showName={showName}
  isBeat={isBeatForUI && status.connected}
  isConnected={true}
  user={auth.user}
  isSignedIn={auth.isSignedIn}
  onSignOut={auth.signOut}
  onSignIn={() => setShowAuthModal(true)}
/>
```

**Step 3: Verify build**

Run: `cd dj_client && npx vite build`
Expected: Build succeeds.

**Step 4: Commit**

```bash
git add dj_client/src/components/TopBar.tsx dj_client/src/App.tsx
git commit -m "refactor(dj): extract TopBar component from App.tsx"
```

---

### Task 3: Extract PresetBar Component

Extract the preset chip row into its own component.

**Files:**
- Create: `dj_client/src/components/PresetBar.tsx`
- Modify: `dj_client/src/App.tsx`

**Step 1: Create PresetBar component**

Create `dj_client/src/components/PresetBar.tsx`:

```tsx
const PRESETS = ['auto', 'edm', 'chill', 'rock', 'folk', 'hiphop', 'classical'];

interface PresetBarProps {
  active: string;
  onChange: (name: string) => void;
}

export default function PresetBar({ active, onChange }: PresetBarProps) {
  return (
    <div className="preset-row">
      {PRESETS.map(name => (
        <button
          key={name}
          className={`preset-chip ${active === name ? 'active' : ''}`}
          onClick={() => onChange(name)}
          type="button"
        >{name}</button>
      ))}
    </div>
  );
}

export { PRESETS };
```

**Step 2: Update App.tsx**

Replace the preset-row div in the connected dashboard with:

```tsx
import PresetBar, { PRESETS } from './components/PresetBar';

// Remove the local PRESETS constant from App.tsx
// Replace the preset-row JSX with:
<PresetBar active={activePreset} onChange={handlePresetChange} />
```

**Step 3: Verify build**

Run: `cd dj_client && npx vite build`

**Step 4: Commit**

```bash
git add dj_client/src/components/PresetBar.tsx dj_client/src/App.tsx
git commit -m "refactor(dj): extract PresetBar component from App.tsx"
```

---

### Task 4: Create StatusStrip Component (Compact Status)

Create a new compact status strip that shows connection state, show name, queue position, and BPM in a dense 2-line format. This replaces the full StatusPanel in compact mode.

**Files:**
- Create: `dj_client/src/components/StatusStrip.tsx`
- Modify: `dj_client/src/styles/app.css`

**Step 1: Create StatusStrip component**

Create `dj_client/src/components/StatusStrip.tsx`:

```tsx
interface StatusStripProps {
  connected: boolean;
  isActive: boolean;
  showName: string | null;
  queuePosition: number;
  totalDjs: number;
  activeDjName: string | null;
  latencyMs: number;
  mcConnected: boolean;
  bpm: number;
}

export default function StatusStrip({
  connected, isActive, showName, queuePosition, totalDjs,
  activeDjName, latencyMs, mcConnected, bpm,
}: StatusStripProps) {
  if (!connected) return null;

  return (
    <div className={`status-strip ${isActive ? 'status-strip--live' : ''}`}>
      <div className="status-strip-row">
        <span className={`status-strip-dot ${connected ? 'connected' : 'disconnected'}`} />
        {isActive ? (
          <span className="status-strip-badge">LIVE</span>
        ) : (
          <span className="status-strip-standby">
            Standby{activeDjName ? ` · ${activeDjName} is live` : ''}
          </span>
        )}
        {showName && <span className="status-strip-show">{showName}</span>}
      </div>
      <div className="status-strip-row status-strip-meta">
        <span>Queue: {queuePosition}/{totalDjs}</span>
        {bpm > 0 && <span className="status-strip-bpm">{Math.round(bpm)} BPM</span>}
        <span className="status-strip-latency">{Math.round(latencyMs)}ms</span>
        {mcConnected && <span className="status-strip-mc">MC</span>}
      </div>
    </div>
  );
}
```

**Step 2: Add StatusStrip CSS**

Append to `dj_client/src/styles/app.css`:

```css
/* Status Strip (compact mode) */
.status-strip {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.03);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.06);
  transition: border-color 0.3s ease, box-shadow 0.3s ease;
}

.status-strip--live {
  border-color: rgba(47, 224, 152, 0.3);
  box-shadow: 0 0 12px rgba(47, 224, 152, 0.08);
}

.status-strip-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.78rem;
}

.status-strip-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-strip-dot.connected {
  background: var(--success);
  box-shadow: 0 0 8px rgba(47, 224, 152, 0.7);
  animation: pulse 1s ease-in-out infinite;
}

.status-strip-dot.disconnected {
  background: var(--danger);
}

.status-strip-badge {
  font-size: 0.65rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #032718;
  background: var(--success);
  padding: 1px 6px;
  border-radius: 4px;
}

.status-strip-standby {
  color: var(--text-secondary);
  font-size: 0.76rem;
}

.status-strip-show {
  color: var(--text-secondary);
  font-size: 0.76rem;
  margin-left: auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-strip-meta {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 0.7rem;
  gap: 12px;
}

.status-strip-bpm {
  color: var(--accent);
  font-weight: 600;
}

.status-strip-latency {
  margin-left: auto;
}

.status-strip-mc {
  font-weight: 700;
  color: var(--success);
  font-size: 0.65rem;
}
```

**Step 3: Verify build**

Run: `cd dj_client && npx vite build`

**Step 4: Commit**

```bash
git add dj_client/src/components/StatusStrip.tsx dj_client/src/styles/app.css
git commit -m "feat(dj): add StatusStrip component for compact layout"
```

---

### Task 5: Create FrequencyStrip Component (Compact Meter)

Create a thin horizontal frequency meter for compact mode — 5 bars side by side, ~48px tall.

**Files:**
- Create: `dj_client/src/components/FrequencyStrip.tsx`
- Modify: `dj_client/src/styles/app.css`

**Step 1: Create FrequencyStrip component**

Create `dj_client/src/components/FrequencyStrip.tsx`:

```tsx
import { useRef, useEffect } from 'react';

interface AudioData {
  bands: number[];
  isBeat: boolean;
  bpm: number;
  beatIntensity: number;
}

interface FrequencyStripProps {
  audioRef: React.RefObject<AudioData>;
}

const BAND_LABELS = ['B', 'LM', 'M', 'HM', 'H'];
const BAND_COLORS = [
  [0, 204, 255],    // Bass: cyan
  [40, 160, 255],   // Low-mid: blue-cyan
  [91, 106, 255],   // Mid: indigo
  [160, 100, 255],  // High-mid: purple
  [255, 170, 0],    // High: amber
];

export default function FrequencyStrip({ audioRef }: FrequencyStripProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const smoothedRef = useRef<number[]>([0, 0, 0, 0, 0]);
  const animFrameRef = useRef<number>(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const draw = () => {
      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();
      const w = rect.width * dpr;
      const h = rect.height * dpr;

      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
      }

      ctx.clearRect(0, 0, w, h);
      ctx.scale(dpr, dpr);
      const cw = rect.width;
      const ch = rect.height;

      const bands = audioRef.current.bands;
      const smoothed = smoothedRef.current;
      const isBeat = audioRef.current.isBeat;

      const barGap = 3;
      const barWidth = (cw - barGap * (bands.length - 1)) / bands.length;

      for (let i = 0; i < bands.length; i++) {
        // Smooth the values
        const target = Math.min(1, bands[i]);
        smoothed[i] += (target - smoothed[i]) * 0.25;
        const val = smoothed[i];

        const x = i * (barWidth + barGap);
        const barH = val * (ch - 12); // 12px for label at bottom
        const y = ch - 12 - barH;

        const [r, g, b] = BAND_COLORS[i];

        // Bar fill with gradient
        const grad = ctx.createLinearGradient(x, y, x, ch - 12);
        grad.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0.9)`);
        grad.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0.4)`);
        ctx.fillStyle = grad;
        ctx.beginPath();
        ctx.roundRect(x, y, barWidth, barH, 2);
        ctx.fill();

        // Glow when value > 0.5
        if (val > 0.5) {
          ctx.shadowColor = `rgba(${r}, ${g}, ${b}, ${(val - 0.5) * 0.8})`;
          ctx.shadowBlur = 8;
          ctx.fillStyle = `rgba(${r}, ${g}, ${b}, 0.3)`;
          ctx.fillRect(x, y, barWidth, barH);
          ctx.shadowBlur = 0;
        }

        // Beat flash: amber overlay
        if (isBeat && i === 0) {
          ctx.fillStyle = 'rgba(255, 170, 0, 0.2)';
          ctx.fillRect(0, 0, cw, ch - 12);
        }

        // Label at bottom
        ctx.fillStyle = val > 0.3 ? `rgb(${r}, ${g}, ${b})` : '#6d87a1';
        ctx.font = `600 ${Math.max(8, Math.min(10, cw / 40))}px Inter, sans-serif`;
        ctx.textAlign = 'center';
        ctx.fillText(BAND_LABELS[i], x + barWidth / 2, ch - 1);
      }

      ctx.setTransform(1, 0, 0, 1, 0, 0);
      animFrameRef.current = requestAnimationFrame(draw);
    };

    animFrameRef.current = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(animFrameRef.current);
  }, [audioRef]);

  return (
    <canvas
      ref={canvasRef}
      className="frequency-strip"
      style={{ width: '100%', height: '48px' }}
    />
  );
}
```

**Step 2: Add FrequencyStrip CSS**

Append to `dj_client/src/styles/app.css`:

```css
/* Frequency Strip (compact horizontal meter) */
.frequency-strip {
  display: block;
  width: 100%;
  border-radius: var(--radius-sm);
  background: rgba(0, 0, 0, 0.2);
}
```

**Step 3: Verify build**

Run: `cd dj_client && npx vite build`

**Step 4: Commit**

```bash
git add dj_client/src/components/FrequencyStrip.tsx dj_client/src/styles/app.css
git commit -m "feat(dj): add FrequencyStrip component for compact layout"
```

---

### Task 6: Rework Connected Dashboard Layout

Replace the 2-column `main-grid` with a compact-first single-column layout. Use StatusStrip, FrequencyStrip, PresetBar, and QueuePanel in the new stacked order.

**Files:**
- Modify: `dj_client/src/App.tsx`
- Modify: `dj_client/src/styles/app.css`

**Step 1: Update connected dashboard JSX in App.tsx**

Replace the entire connected dashboard section (the `<div className="dashboard connected">` block) with:

```tsx
import StatusStrip from './components/StatusStrip';
import FrequencyStrip from './components/FrequencyStrip';

// In the connected state:
<div className="dashboard connected">
  <TopBar
    djName={djName}
    showName={showName}
    isBeat={isBeatForUI && status.connected}
    isConnected={true}
    user={auth.user}
    isSignedIn={auth.isSignedIn}
    onSignOut={auth.signOut}
    onSignIn={() => setShowAuthModal(true)}
  />

  <div className="main-content">
    <StatusStrip
      connected={status.connected}
      isActive={status.is_active}
      showName={showName}
      queuePosition={status.queue_position}
      totalDjs={status.total_djs}
      activeDjName={status.active_dj_name}
      latencyMs={status.latency_ms}
      mcConnected={status.mc_connected}
      bpm={audioRef.current.bpm}
    />

    <FrequencyStrip audioRef={audioRef} />

    <PresetBar active={activePreset} onChange={handlePresetChange} />

    {/* Full StatusPanel and FrequencyMeter show in expanded mode only */}
    <div className="expanded-panels">
      <div className="expanded-left">
        <FrequencyMeter audioRef={audioRef} />
      </div>
      <div className="expanded-right">
        <StatusPanel status={status} />
        <QueuePanel roster={roster} />
      </div>
    </div>

    {/* Compact queue always visible */}
    <div className="compact-queue">
      <QueuePanel roster={roster} />
    </div>
  </div>

  <div className="bottom-bar">
    <AudioSourceSelect
      sources={audioSources}
      value={selectedSource}
      onChange={handleSourceChange}
      onRefresh={loadAudioSources}
    />
    {captureMode && captureMode.mode === 'process_loopback' && (
      <span className="capture-info">{captureMode.name}</span>
    )}
    <button
      className={`btn voice-toggle ${voiceEnabled ? 'voice-on' : 'voice-off'}`}
      onClick={handleToggleVoice}
      type="button"
    >
      {voiceEnabled ? 'Voice Off' : 'Voice'}
    </button>
    <button className="btn btn-disconnect" onClick={handleDisconnect}>
      Disconnect
    </button>
  </div>
</div>
```

**Step 2: Update CSS for compact-first layout**

Replace the `.main-grid` rules and add new layout CSS in `dj_client/src/styles/app.css`:

```css
/* Main content - compact-first single column */
.main-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

/* In compact mode (<720px): hide expanded panels, show compact elements */
.expanded-panels {
  display: none;
}

.compact-queue {
  display: block;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

/* Expanded mode (>720px): show 2-column panels, hide compact elements */
@media (min-width: 721px) {
  .expanded-panels {
    display: grid;
    grid-template-columns: 1fr 240px;
    gap: 8px;
    flex: 1;
    min-height: 0;
  }

  .expanded-left {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-height: 0;
    overflow: hidden;
  }

  .expanded-right {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-height: 0;
    overflow-y: auto;
  }

  .compact-queue {
    display: none;
  }

  /* Hide compact strip and freq in expanded mode */
  .main-content > .status-strip {
    display: none;
  }

  .main-content > .frequency-strip {
    display: none;
  }

  /* Show preset bar in expanded left column instead */
  .main-content > .preset-row {
    display: none;
  }

  .expanded-left .preset-row {
    display: flex;
  }
}
```

Wait — this approach has presets in main-content AND expanded-left. Let me simplify:

Actually, use a simpler approach. In compact mode show: StatusStrip + FrequencyStrip + PresetBar + QueuePanel (all stacked). In expanded mode (>720px) show the original 2-column grid with FrequencyMeter + presets on left, StatusPanel + QueuePanel on right.

Replace the above CSS with this cleaner version:

```css
/* Connected layout */
.main-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

/* Compact-only elements (visible <720px) */
.compact-only {
  display: block;
}

/* Expanded-only elements (visible >720px) */
.expanded-only {
  display: none;
}

@media (min-width: 721px) {
  .compact-only {
    display: none;
  }

  .expanded-only {
    display: grid;
    grid-template-columns: 1fr 240px;
    gap: 8px;
    flex: 1;
    min-height: 0;
  }

  .expanded-only .col-left {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-height: 0;
    overflow: hidden;
  }

  .expanded-only .col-right {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-height: 0;
    overflow-y: auto;
  }
}
```

Update the JSX to use `.compact-only` / `.expanded-only` wrappers:

```tsx
<div className="main-content">
  {/* Compact layout (<720px) */}
  <div className="compact-only">
    <StatusStrip
      connected={status.connected}
      isActive={status.is_active}
      showName={showName}
      queuePosition={status.queue_position}
      totalDjs={status.total_djs}
      activeDjName={status.active_dj_name}
      latencyMs={status.latency_ms}
      mcConnected={status.mc_connected}
      bpm={audioRef.current.bpm}
    />
    <FrequencyStrip audioRef={audioRef} />
    <PresetBar active={activePreset} onChange={handlePresetChange} />
    <QueuePanel roster={roster} />
  </div>

  {/* Expanded layout (>720px) */}
  <div className="expanded-only">
    <div className="col-left">
      <FrequencyMeter audioRef={audioRef} />
      <PresetBar active={activePreset} onChange={handlePresetChange} />
    </div>
    <div className="col-right">
      <StatusPanel status={status} />
      <QueuePanel roster={roster} />
    </div>
  </div>
</div>
```

**Step 3: Remove old `.main-grid` CSS rules**

Remove these rules from `app.css` (they are replaced by the new layout):
- `.main-grid` (lines ~96-102)
- `.col-left` (lines ~104-110)
- `.col-right` (lines ~112-118)
- The `@media (max-width: 720px)` rule for `.main-grid` (line ~851)

**Step 4: Verify build and visual check**

Run: `cd dj_client && npx vite build`

**Step 5: Commit**

```bash
git add dj_client/src/App.tsx dj_client/src/styles/app.css
git commit -m "feat(dj): compact-first connected dashboard layout"
```

---

### Task 7: Visual Polish — Typography and Glow Effects

Fix typography (body → Inter, headings → Space Grotesk only), add glow effects to active elements, and polish the preset chips.

**Files:**
- Modify: `dj_client/src/styles/app.css`

**Step 1: Fix typography**

In `dj_client/src/styles/app.css`, change the body font-family rule:

```css
/* Change from: */
body {
  font-family: 'Space Grotesk', 'Segoe UI', sans-serif;
}

/* To: */
body {
  font-family: var(--font-sans);
}
```

Note: `--font-sans` is `var(--mcav-font-sans)` which is `"Inter", ui-sans-serif, system-ui, -apple-system, sans-serif`.

Add a heading-specific rule:

```css
.dj-label,
.welcome-modal h2,
.auth-heading {
  font-family: 'Space Grotesk', var(--font-sans);
}
```

**Step 2: Polish preset chips with colored dots and glow**

Update `.preset-chip.active` in `app.css`:

```css
.preset-chip.active {
  background: rgba(0, 204, 255, 0.12);
  border-color: rgba(0, 204, 255, 0.3);
  color: var(--accent);
  box-shadow: 0 0 8px rgba(0, 204, 255, 0.2);
}
```

**Step 3: Add beat indicator sizing improvements**

Update `.beat-indicator` to be slightly larger:

```css
.beat-indicator {
  width: 38px;
  height: 38px;
  position: relative;
}
```

**Step 4: Enhance queue panel active DJ border**

Add a left accent border for the active DJ in the queue:

```css
.queue-dj.active {
  color: var(--success);
  border-left: 2px solid var(--accent);
  padding-left: 6px;
}
```

**Step 5: Darken the bottom bar**

```css
.bottom-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 6px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}
```

**Step 6: Verify build**

Run: `cd dj_client && npx vite build`

**Step 7: Commit**

```bash
git add dj_client/src/styles/app.css
git commit -m "style(dj): visual polish — typography, glows, and layout refinements"
```

---

### Task 8: Final Integration and Cleanup

Remove unused imports from App.tsx, verify the full app runs in dev mode, and ensure both compact and expanded modes work.

**Files:**
- Modify: `dj_client/src/App.tsx`

**Step 1: Clean up App.tsx imports**

At the top of `dj_client/src/App.tsx`, ensure the imports are minimal. After extraction, the imports should be:

```tsx
import { useState, useEffect, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { check, type Update } from '@tauri-apps/plugin-updater';
import TopBar from './components/TopBar';
import ConnectForm from './components/ConnectForm';
import StatusStrip from './components/StatusStrip';
import FrequencyStrip from './components/FrequencyStrip';
import FrequencyMeter from './components/FrequencyMeter';
import PresetBar, { PRESETS } from './components/PresetBar';
import StatusPanel from './components/StatusPanel';
import QueuePanel from './components/QueuePanel';
import AudioSourceSelect from './components/AudioSourceSelect';
import AuthModal from './components/AuthModal';
import { useAuth } from './hooks/useAuth';
import * as api from './lib/api';
```

Remove any old imports that are no longer directly used in App.tsx (ConnectCode, BeatIndicator, ProfileChip — these are now imported by their parent components).

**Step 2: Remove the local PRESETS array**

If not already done, remove `const PRESETS = [...]` from App.tsx body since it now comes from `PresetBar`.

**Step 3: Verify build**

Run: `cd dj_client && npx vite build`
Expected: Clean build, no warnings about unused imports.

**Step 4: Run dev mode**

Run: `cd dj_client && npx vite dev`
Test by resizing the window:
- At <720px: Should see stacked StatusStrip → FrequencyStrip → PresetBar → QueuePanel
- At >720px: Should see 2-column layout with FrequencyMeter on left, StatusPanel+QueuePanel on right

**Step 5: Commit**

```bash
git add dj_client/src/App.tsx
git commit -m "refactor(dj): clean up App.tsx imports after component extraction"
```

---

### Task 9: Build and Deploy

Build the production DJ client with all UI changes.

**Step 1: Build**

Run: `cd dj_client && npm run tauri build`
Expected: Successful build producing exe and installer.

**Step 2: Commit any remaining changes**

```bash
git status
# If any uncommitted changes, add and commit
```

**Step 3: Push**

```bash
git push origin main
```
