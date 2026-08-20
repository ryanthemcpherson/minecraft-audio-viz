# MCAV DJ App Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an end-to-end, stage-ready DJ client with an integrated preflight flow, a unified live-performance cockpit, resilient user-facing errors, responsive/accessibility behavior, and unchanged backend contracts.

**Architecture:** Keep `App.tsx` as the orchestration boundary and retain `useConnection` and `useAudioSources` as the two runtime state authorities. Replace duplicated compact/expanded presentation with one semantic component tree composed from focused preflight, audio-stage, system-rail, and control-dock units; audio-rate visuals continue reading refs instead of driving React renders.

**Tech Stack:** React 19, TypeScript, Vite 7, Tauri 2, CSS, Canvas 2D, Vitest with jsdom, React Testing Library, Rust/Cargo.

**Spec:** `docs/superpowers/specs/2026-08-20-dj-app-redesign-design.md`

## Global Constraints

- Preserve all Rust command names, WebSocket messages, authentication behavior, audio processing, preset behavior, queue behavior, and voice-streaming contracts.
- Keep exactly five protocol bands in this order: bass, low-mid, mid, high-mid, high.
- Use the canonical MCAV tokens from `shared/tokens.css`; dark-only UI with cyan `#00CCFF`, indigo `#5B6AFF`, amber `#FFAA00`, success `#2fe098`, warning `#ffd166`, and danger `#ff6767`.
- Load Space Grotesk for identity/state headings, Inter for interface text, and JetBrains Mono for codes and machine data.
- Set the Tauri default window to 960 by 640 and the minimum to 640 by 480; retain native decorations and resizing.
- Do not place audio-frame values in React state at frame rate. Canvas and signal animation read `audioRef` directly; accessible summaries may update at most once per second.
- Add no runtime dependencies. UI test tooling is development-only.
- Keep user-visible I/O failures in the interface; console logging alone is insufficient.
- Do not delete legacy component files or unrelated selectors during this plan. Removing obsolete files requires separate user confirmation.
- Preserve `prefers-reduced-motion`, keyboard access, visible focus, and non-color state labels.

## File Structure

### New files

- `dj_client/vitest.config.ts` — isolated Vitest/jsdom configuration.
- `dj_client/src/test/setup.ts` — DOM cleanup plus Canvas, ResizeObserver, and animation-frame test shims.
- `dj_client/src/lib/ui-state.ts` — pure preflight-readiness, latency, and source-label helpers.
- `dj_client/src/lib/ui-state.test.ts` — unit coverage for those helpers.
- `dj_client/src/components/InlineNotice.tsx` — reusable actionable status/error notice.
- `dj_client/src/components/PreflightView.test.tsx` — user-level preflight behavior and accessibility coverage.
- `dj_client/src/hooks/useAudioSources.test.tsx` — source enumeration, test capture, rollback, and error coverage.
- `dj_client/src/components/AudioStage.tsx` — responsive five-band Canvas, BPM, beat, presets, and signal bus.
- `dj_client/src/components/AudioStage.test.tsx` — semantic and control coverage for the audio stage.
- `dj_client/src/components/SystemRail.tsx` — live/standby, route, capture, latency, and queue composition.
- `dj_client/src/components/SystemRail.test.tsx` — live/standby/degraded/queue coverage.
- `dj_client/src/hooks/useConnection.test.tsx` — partial-connection cleanup and confirmed-operation error coverage.
- `dj_client/src/hooks/useDisconnectGuard.ts` — three-second two-step disconnect state machine.
- `dj_client/src/hooks/useDisconnectGuard.test.tsx` — arming, confirmation, timeout, and cancellation coverage.
- `dj_client/src/components/ControlDock.tsx` — source, voice, capture, and guarded disconnect controls.
- `dj_client/src/components/ControlDock.test.tsx` — dock semantics and state coverage.
- `dj_client/src/dev/DjAppPreview.tsx` — development-only deterministic preflight/live/standby/degraded visual fixtures.
- `dj_client/tests/ui-contract.test.mjs` — static contract checks for fonts, window bounds, responsive rules, and reduced motion.

### Modified files

- `dj_client/package.json` and `dj_client/package-lock.json` — UI test dependencies and scripts.
- `dj_client/tsconfig.node.json` — include the Vitest configuration.
- `dj_client/src/hooks/useAudioSources.ts` — expose test levels/loading/errors and rollback failed connected source changes.
- `dj_client/src/hooks/useConnection.ts` — expose voice/operation state, clean partial connections, and confirm UI state only after successful commands.
- `dj_client/src/App.tsx` — remove blocking onboarding, orchestrate disconnect guard, and route keyboard shortcuts.
- `dj_client/src/main.tsx` — development-only preview selection.
- `dj_client/src/components/DisconnectedView.tsx` — preflight composition.
- `dj_client/src/components/ConnectForm.tsx` — readiness sequence, test input, advanced disclosure, and primary connect action.
- `dj_client/src/components/ConnectCode.tsx` — stable labeling and code semantics.
- `dj_client/src/components/AudioSourceSelect.tsx` — shared types, stable IDs, loading/empty state, and source metadata.
- `dj_client/src/components/ConnectedView.tsx` — one cockpit tree with audio stage, system rail, and control dock.
- `dj_client/src/components/TopBar.tsx` — typed shared header and explicit live/standby state.
- `dj_client/src/components/PresetBar.tsx` — accessible pressed-state selector.
- `dj_client/src/components/StatusPanel.tsx` and `dj_client/src/components/QueuePanel.tsx` — typed, semantic subpanels used by `SystemRail`.
- `dj_client/src/styles/app.css` — active preflight/cockpit visual system and responsive rules while retaining unrelated auth/profile styles.
- `dj_client/index.html` — load Inter alongside Space Grotesk and JetBrains Mono.
- `dj_client/src-tauri/tauri.conf.json` — new default/minimum window dimensions.

---

### Task 1: UI Test Foundation and Pure State Contracts

**Files:**
- Create: `dj_client/vitest.config.ts`
- Create: `dj_client/src/test/setup.ts`
- Create: `dj_client/src/lib/ui-state.ts`
- Create: `dj_client/src/lib/ui-state.test.ts`
- Modify: `dj_client/package.json`
- Modify: `dj_client/package-lock.json`
- Modify: `dj_client/tsconfig.node.json`

**Interfaces:**
- Produces: `getPreflightReadiness(input: PreflightReadinessInput): PreflightReadiness`
- Produces: `getLatencyState(latencyMs: number): LatencyState`
- Produces: `getSourceTypeLabel(sourceType: AudioSource['source_type']): string`
- Consumes: `AudioSource` from `src/types.ts`

- [ ] **Step 1: Install the development-only test dependencies**

Run from `dj_client`:

```powershell
npm install --save-dev vitest jsdom @testing-library/react @testing-library/dom @testing-library/user-event
```

Expected: `package.json` and `package-lock.json` change; no production dependency is added.

- [ ] **Step 2: Add deterministic Vitest configuration and DOM shims**

Add `test:ui` to `package.json`:

```json
"test:ui": "vitest run"
```

Create `vitest.config.ts`:

```ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    clearMocks: true,
    restoreMocks: true,
  },
});
```

Change `tsconfig.node.json` to include both configuration files:

```json
"include": ["vite.config.ts", "vitest.config.ts"]
```

Create `src/test/setup.ts`:

```ts
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => cleanup());

class ResizeObserverMock implements ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

vi.stubGlobal('ResizeObserver', ResizeObserverMock);
vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) =>
  window.setTimeout(() => callback(performance.now()), 16));
vi.stubGlobal('cancelAnimationFrame', (handle: number) => window.clearTimeout(handle));

Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
  configurable: true,
  value: vi.fn(() => null),
});
```

- [ ] **Step 3: Write failing pure-state tests**

Create `src/lib/ui-state.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import {
  getLatencyState,
  getPreflightReadiness,
  getSourceTypeLabel,
} from './ui-state';

describe('getPreflightReadiness', () => {
  it('requires identity, a complete code, and an audio source', () => {
    expect(getPreflightReadiness({
      djName: 'DJ Nova',
      connectCode: 'BEAT7K3M',
      selectedSource: 'app:spotify',
      sourceError: null,
    })).toEqual({ ready: true, missing: [] });

    expect(getPreflightReadiness({
      djName: ' ',
      connectCode: 'BEAT',
      selectedSource: null,
      sourceError: 'Audio sources unavailable.',
    })).toEqual({
      ready: false,
      missing: ['identity', 'show', 'audio'],
    });
  });
});

describe('getLatencyState', () => {
  it('classifies normal, warning, and degraded latency', () => {
    expect(getLatencyState(48)).toBe('normal');
    expect(getLatencyState(101)).toBe('warning');
    expect(getLatencyState(201)).toBe('degraded');
  });
});

it('formats source types for DJs', () => {
  expect(getSourceTypeLabel('system_audio')).toBe('System audio');
  expect(getSourceTypeLabel('application')).toBe('Application');
  expect(getSourceTypeLabel('input_device')).toBe('Input device');
});
```

- [ ] **Step 4: Run the test and verify the missing module failure**

Run:

```powershell
npm run test:ui -- src/lib/ui-state.test.ts
```

Expected: FAIL because `src/lib/ui-state.ts` does not exist.

- [ ] **Step 5: Implement the pure state helpers**

Create `src/lib/ui-state.ts`:

```ts
import type { AudioSource } from '../types';

export type ReadinessRequirement = 'identity' | 'show' | 'audio';
export type LatencyState = 'normal' | 'warning' | 'degraded';

export interface PreflightReadinessInput {
  djName: string;
  connectCode: string;
  selectedSource: string | null;
  sourceError: string | null;
}

export interface PreflightReadiness {
  ready: boolean;
  missing: ReadinessRequirement[];
}

export function getPreflightReadiness(input: PreflightReadinessInput): PreflightReadiness {
  const missing: ReadinessRequirement[] = [];
  if (!input.djName.trim()) missing.push('identity');
  if (input.connectCode.length !== 8) missing.push('show');
  if (!input.selectedSource || input.sourceError) missing.push('audio');
  return { ready: missing.length === 0, missing };
}

export function getLatencyState(latencyMs: number): LatencyState {
  if (latencyMs > 200) return 'degraded';
  if (latencyMs > 100) return 'warning';
  return 'normal';
}

export function getSourceTypeLabel(sourceType: AudioSource['source_type']): string {
  if (sourceType === 'system_audio') return 'System audio';
  if (sourceType === 'application') return 'Application';
  return 'Input device';
}
```

- [ ] **Step 6: Verify tests and production build**

Run:

```powershell
npm run test:ui -- src/lib/ui-state.test.ts
npm run build
```

Expected: both commands PASS.

- [ ] **Step 7: Commit the foundation**

```powershell
git add dj_client/package.json dj_client/package-lock.json dj_client/tsconfig.node.json dj_client/vitest.config.ts dj_client/src/test/setup.ts dj_client/src/lib/ui-state.ts dj_client/src/lib/ui-state.test.ts
git commit -m "test(dj-client): add UI test foundation"
```

### Task 2: Reliable Audio Source and Test-Capture State

**Files:**
- Create: `dj_client/src/hooks/useAudioSources.test.tsx`
- Modify: `dj_client/src/hooks/useAudioSources.ts`

**Interfaces:**
- Produces additions to `UseAudioSourcesReturn`: `testBands: number[]`, `isLoadingSources: boolean`, `sourceError: string | null`, `clearSourceError(): void`
- Preserves: `handleSourceChange(sourceId: string | null, isConnected: boolean): Promise<void>`
- Consumes: unchanged Tauri commands `list_audio_sources`, `start_capture`, `get_audio_levels`, `stop_capture`, and `change_audio_source`

- [ ] **Step 1: Write failing hook tests for visible failures and rollback**

Create `src/hooks/useAudioSources.test.tsx`:

```tsx
import { act, renderHook } from '@testing-library/react';
import { invoke } from '@tauri-apps/api/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAudioSources } from './useAudioSources';

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }));
const invokeMock = vi.mocked(invoke);

describe('useAudioSources', () => {
  beforeEach(() => {
    localStorage.clear();
    invokeMock.mockReset();
  });

  it('surfaces enumeration failures and clears loading state', async () => {
    invokeMock.mockRejectedValueOnce(new Error('WASAPI unavailable'));
    const { result } = renderHook(() => useAudioSources());

    await act(() => result.current.loadAudioSources());

    expect(result.current.isLoadingSources).toBe(false);
    expect(result.current.sourceError).toBe('Audio sources unavailable. Check your audio devices and try again.');
  });

  it('rolls back a connected source change rejected by Tauri', async () => {
    invokeMock.mockResolvedValueOnce([
      { id: 'system', name: 'System audio', source_type: 'system_audio' },
      { id: 'spotify', name: 'Spotify', source_type: 'application' },
    ]);
    const { result } = renderHook(() => useAudioSources());
    await act(() => result.current.loadAudioSources());
    expect(result.current.selectedSource).toBe('system');

    invokeMock.mockRejectedValueOnce(new Error('capture switch failed'));
    await act(() => result.current.handleSourceChange('spotify', true));

    expect(result.current.selectedSource).toBe('system');
    expect(result.current.sourceError).toBe('Could not switch audio sources. The previous source is still active.');
  });

  it('exposes zeroed test bands when test capture cannot start', async () => {
    invokeMock.mockResolvedValueOnce([
      { id: 'system', name: 'System audio', source_type: 'system_audio' },
    ]);
    const { result } = renderHook(() => useAudioSources());
    await act(() => result.current.loadAudioSources());

    invokeMock.mockRejectedValueOnce(new Error('capture failed'));
    await act(() => result.current.handleStartTest());

    expect(result.current.isTestingAudio).toBe(false);
    expect(result.current.testBands).toEqual([0, 0, 0, 0, 0]);
    expect(result.current.sourceError).toBe('Could not test this audio source. Choose another source or try again.');
  });
});
```

- [ ] **Step 2: Run the hook tests and verify interface failures**

Run:

```powershell
npm run test:ui -- src/hooks/useAudioSources.test.tsx
```

Expected: FAIL because the returned loading, error, and test-band fields do not exist.

- [ ] **Step 3: Implement explicit source-operation state**

Add the following state and return contract in `useAudioSources.ts`:

```ts
const [testBands, setTestBands] = useState<number[]>([0, 0, 0, 0, 0]);
const [isLoadingSources, setIsLoadingSources] = useState(false);
const [sourceError, setSourceError] = useState<string | null>(null);
const clearSourceError = () => setSourceError(null);
```

Wrap `loadAudioSources` with `setIsLoadingSources(true)`, `setSourceError(null)`, a user-facing catch, and `finally { setIsLoadingSources(false); }`. In `handleSourceChange`, save `const previousSource = selectedSource`, optimistically select the requested source, and restore `previousSource` plus the exact rollback message from the test when `change_audio_source` rejects. In `handleStartTest`, set the exact test failure message and reset bands when `start_capture` rejects. In `handleStopTest`, clear timers first and reset `isTestingAudio` and `testBands` in `finally` even when `stop_capture` fails.

Return all four new public members:

```ts
return {
  audioSources,
  selectedSource,
  setSelectedSource,
  testBands,
  isTestingAudio,
  isLoadingSources,
  sourceError,
  clearSourceError,
  loadAudioSources,
  handleSourceChange,
  handleStartTest,
  handleStopTest,
};
```

- [ ] **Step 4: Verify hook behavior and build**

```powershell
npm run test:ui -- src/hooks/useAudioSources.test.tsx
npm run build
```

Expected: PASS, with no unhandled timer warnings.

- [ ] **Step 5: Commit source reliability**

```powershell
git add dj_client/src/hooks/useAudioSources.ts dj_client/src/hooks/useAudioSources.test.tsx
git commit -m "fix(dj-client): surface audio source failures"
```

### Task 3: Integrated Preflight and Non-blocking Onboarding

**Files:**
- Create: `dj_client/src/components/InlineNotice.tsx`
- Create: `dj_client/src/components/PreflightView.test.tsx`
- Modify: `dj_client/src/App.tsx`
- Modify: `dj_client/src/components/DisconnectedView.tsx`
- Modify: `dj_client/src/components/ConnectForm.tsx`
- Modify: `dj_client/src/components/ConnectCode.tsx`
- Modify: `dj_client/src/components/AudioSourceSelect.tsx`
- Modify: `dj_client/src/styles/app.css`

**Interfaces:**
- Consumes: `getPreflightReadiness`, all `UseAudioSourcesReturn` fields from Task 2, and existing connection fields/callbacks.
- Produces: accessible labeled preflight controls and embedded onboarding callout.
- Preserves: `mcav.onboardingComplete`, code normalization, direct-connect settings, and `onConnect()`.

- [ ] **Step 1: Write failing preflight behavior tests**

Create `src/components/PreflightView.test.tsx` around `ConnectForm`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import ConnectForm from './ConnectForm';

const sources = [
  { id: 'system', name: 'System audio', source_type: 'system_audio' as const },
];

function renderForm(overrides: Partial<React.ComponentProps<typeof ConnectForm>> = {}) {
  const props: React.ComponentProps<typeof ConnectForm> = {
    connectCode: '',
    onConnectCodeChange: vi.fn(),
    selectedSource: null,
    onSourceChange: vi.fn(),
    audioSources: sources,
    onRefreshSources: vi.fn(),
    directConnect: false,
    onDirectConnectChange: vi.fn(),
    serverHost: '127.0.0.1',
    onServerHostChange: vi.fn(),
    serverPort: 9000,
    onServerPortChange: vi.fn(),
    error: null,
    isConnecting: false,
    djName: '',
    onDjNameChange: vi.fn(),
    testBands: [0, 0, 0, 0, 0],
    isTestingAudio: false,
    isLoadingSources: false,
    sourceError: null,
    onStartAudioTest: vi.fn(),
    onStopAudioTest: vi.fn(),
    onConnect: vi.fn(),
    ...overrides,
  };
  render(<ConnectForm {...props} />);
  return props;
}

describe('preflight', () => {
  it('labels the three readiness requirements and disables connect until ready', () => {
    renderForm();
    expect(screen.getByText('Identity')).toBeTruthy();
    expect(screen.getByText('Show')).toBeTruthy();
    expect(screen.getByText('Audio input')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Connect to show' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByLabelText('DJ name')).toBeTruthy();
    expect(screen.getByLabelText('Connect code')).toBeTruthy();
    expect(screen.getByLabelText('Audio source')).toBeTruthy();
  });

  it('enables connect with a complete identity, show code, and source', () => {
    renderForm({ djName: 'DJ Nova', connectCode: 'BEAT7K3M', selectedSource: 'system' });
    expect((screen.getByRole('button', { name: 'Connect to show' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('keeps advanced host and port controls collapsed initially', async () => {
    const user = userEvent.setup();
    renderForm();
    expect(screen.queryByLabelText('Server host')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Advanced connection' }));
    expect(screen.getByLabelText('Server host')).toBeTruthy();
    expect(screen.getByLabelText('Server port')).toBeTruthy();
  });

  it('exposes audio test state and source errors', () => {
    renderForm({ selectedSource: 'system', sourceError: 'Audio sources unavailable.' });
    expect(screen.getByRole('alert').textContent).toContain('Audio sources unavailable.');
    expect(screen.getByRole('button', { name: 'Test input' })).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run the preflight tests and verify prop/semantic failures**

```powershell
npm run test:ui -- src/components/PreflightView.test.tsx
```

Expected: FAIL because the new props and labeled readiness structure do not exist.

- [ ] **Step 3: Build reusable notice and accessible field contracts**

Create `InlineNotice.tsx`:

```tsx
import type { ReactNode } from 'react';

export interface InlineNoticeProps {
  tone: 'info' | 'success' | 'warning' | 'error';
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}

export default function InlineNotice({ tone, title, children, action }: InlineNoticeProps) {
  return (
    <div className={`inline-notice inline-notice--${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      <div>
        <strong>{title}</strong>
        {children && <p>{children}</p>}
      </div>
      {action && <div className="inline-notice__action">{action}</div>}
    </div>
  );
}
```

Add an `id` prop to `AudioSourceSelect`, import `AudioSource` from `../types`, connect `<label htmlFor={id}>` to `<select id={id}>`, use `disabled={isLoading || sources.length === 0}`, and add a disabled **No audio sources found** option when empty. Add `id="connect-code"` and `aria-label="Connect code"` to `ConnectCode`; keep raw eight-character state and formatted display unchanged.

- [ ] **Step 4: Replace the connect form with the readiness sequence**

Use `getPreflightReadiness` to derive the button state. Render three `.preflight-step` sections in this order: DJ name, connect code, audio source/test. The audio test meter uses five semantic bars:

```tsx
<div className="input-test-meter" aria-label="Audio input test levels">
  {testBands.map((level, index) => (
    <span
      key={index}
      className="input-test-meter__bar"
      style={{ '--test-level': Math.max(0, Math.min(1, level)) } as React.CSSProperties}
    />
  ))}
</div>
```

Render Advanced connection with a native disclosure button using `aria-expanded`; only mount labeled host/port fields while open. Render `InlineNotice` for `sourceError` and the existing connection error. Label the action **Connect to show** and **Connecting…**.

- [ ] **Step 5: Replace the blocking welcome overlay with embedded onboarding**

Delete only the approved blocking overlay markup from `App.tsx`; retain its local-storage preference. Pass `showOnboarding={showWelcomeOverlay}` and `onDismissOnboarding={handleDismissWelcome}` into `DisconnectedView`. In `DisconnectedView`, place an informational `InlineNotice` above `ConnectForm`:

```tsx
{showOnboarding && (
  <InlineNotice
    tone="info"
    title="Three checks, then you are ready"
    action={<button type="button" className="btn-link-inline" onClick={onDismissOnboarding}>Got it</button>}
  >
    Add your DJ name, enter the show code, and confirm the audio input you want MCAV to hear.
  </InlineNotice>
)}
```

Move DJ-name editing from `TopBar` into `ConnectForm`. The disconnected header keeps product identity and account controls only.

- [ ] **Step 6: Add preflight layout styles without removing auth/profile rules**

Add `.preflight`, `.preflight-shell`, `.preflight-intro`, `.preflight-grid`, `.preflight-step`, `.preflight-step__label`, `.input-test-meter`, `.advanced-connection`, and `.inline-notice` rules. Use existing shared tokens and a cyan-to-indigo primary button gradient. Add a 720-pixel media rule that stacks preflight columns and keeps all fields within the viewport.

- [ ] **Step 7: Verify preflight tests and build**

```powershell
npm run test:ui -- src/components/PreflightView.test.tsx src/hooks/useAudioSources.test.tsx
npm run build
```

Expected: PASS.

- [ ] **Step 8: Commit the preflight flow**

```powershell
git add dj_client/src/App.tsx dj_client/src/components/InlineNotice.tsx dj_client/src/components/DisconnectedView.tsx dj_client/src/components/ConnectForm.tsx dj_client/src/components/ConnectCode.tsx dj_client/src/components/AudioSourceSelect.tsx dj_client/src/components/PreflightView.test.tsx dj_client/src/styles/app.css
git commit -m "feat(dj-client): build integrated preflight flow"
```

### Task 4: Responsive Audio Stage and Signal Bus

**Files:**
- Create: `dj_client/src/components/AudioStage.tsx`
- Create: `dj_client/src/components/AudioStage.test.tsx`
- Modify: `dj_client/src/components/PresetBar.tsx`
- Modify: `dj_client/src/styles/app.css`

**Interfaces:**
- Produces: `AudioStage({ audioRef, activePreset, onPresetChange, connected, mcConnected, isActive })`
- Consumes: `AudioData` and the existing preset names/handler.
- Performance invariant: Canvas and signal bus read `audioRef.current` inside animation callbacks; only the accessible summary is copied into state on a one-second interval.

- [ ] **Step 1: Write failing semantic audio-stage tests**

Create `src/components/AudioStage.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import type { AudioData } from '../types';
import AudioStage from './AudioStage';

it('exposes one signal region, BPM, band labels, and pressed preset state', async () => {
  const user = userEvent.setup();
  const onPresetChange = vi.fn();
  const audioRef = {
    current: { bands: [0.8, 0.6, 0.4, 0.3, 0.2], isBeat: true, bpm: 128, beatIntensity: 0.9 },
  } as React.RefObject<AudioData>;

  render(
    <AudioStage
      audioRef={audioRef}
      activePreset="edm"
      onPresetChange={onPresetChange}
      connected
      mcConnected
      isActive
    />,
  );

  expect(screen.getByRole('region', { name: 'Audio signal' })).toBeTruthy();
  expect(screen.getByText('Bass')).toBeTruthy();
  expect(screen.getByText('Low-mid')).toBeTruthy();
  expect(screen.getByText('High-mid')).toBeTruthy();
  expect(screen.getByText('128')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'EDM preset' }).getAttribute('aria-pressed')).toBe('true');

  await user.click(screen.getByRole('button', { name: 'Rock preset' }));
  expect(onPresetChange).toHaveBeenCalledWith('rock');
});
```

- [ ] **Step 2: Run the test and verify the component-not-found failure**

```powershell
npm run test:ui -- src/components/AudioStage.test.tsx
```

Expected: FAIL because `AudioStage.tsx` does not exist.

- [ ] **Step 3: Implement the responsive audio stage**

Create the component with these stable contracts:

```tsx
const BAND_LABELS = ['Bass', 'Low-mid', 'Mid', 'High-mid', 'High'];
const BAND_COLORS = ['#00CCFF', '#28A0FF', '#5B6AFF', '#A064FF', '#FFAA00'];

export interface AudioStageProps {
  audioRef: React.RefObject<AudioData>;
  activePreset: string;
  onPresetChange: (name: string) => void;
  connected: boolean;
  mcConnected: boolean;
  isActive: boolean;
}
```

Use a `ResizeObserver` to store the canvas's CSS width/height in refs, set backing dimensions with device-pixel ratio, reset the transform before every draw, and draw five vertical energy lanes plus stable labels. Use one animation loop for Canvas drawing and signal-bus CSS-variable updates; cancel that loop on unmount.

Update a summary object once per second:

```ts
const [summary, setSummary] = useState(() => ({
  bpm: Math.round(audioRef.current.bpm),
  bands: audioRef.current.bands.map((value) => Math.round(value * 100)),
}));

useEffect(() => {
  const interval = window.setInterval(() => {
    setSummary({
      bpm: Math.round(audioRef.current.bpm),
      bands: audioRef.current.bands.map((value) => Math.round(value * 100)),
    });
  }, 1000);
  return () => window.clearInterval(interval);
}, [audioRef]);
```

Render one `<section aria-label="Audio signal">`, a Canvas marked `aria-hidden="true"`, visible band labels, visible BPM number, a screen-reader summary, and a `.signal-bus` whose route class reflects `connected`, `mcConnected`, and `isActive`.

- [ ] **Step 4: Make preset state accessible**

In `PresetBar.tsx`, add a visible **Response preset** label and set each button's accessible name and pressed state:

```tsx
aria-label={`${name === 'edm' ? 'EDM' : name[0].toUpperCase() + name.slice(1)} preset`}
aria-pressed={active === name}
```

Continue calling the existing `onChange(name)` handler; do not add presets.

- [ ] **Step 5: Add the stage and signal-bus visual rules**

Add `.audio-stage`, `.audio-stage__canvas-wrap`, `.audio-stage__canvas`, `.audio-stage__bands`, `.audio-stage__bpm`, `.signal-bus`, and route/live variants. Concentrate beat animation into the signal bus and bass lane. Add non-animated reduced-motion overrides; do not animate large surfaces or layout properties.

- [ ] **Step 6: Verify audio-stage tests and build**

```powershell
npm run test:ui -- src/components/AudioStage.test.tsx
npm run build
```

Expected: PASS without Canvas or unhandled-timer warnings.

- [ ] **Step 7: Commit the audio stage**

```powershell
git add dj_client/src/components/AudioStage.tsx dj_client/src/components/AudioStage.test.tsx dj_client/src/components/PresetBar.tsx dj_client/src/styles/app.css
git commit -m "feat(dj-client): add responsive audio stage"
```

### Task 5: Unified Cockpit and System Rail

**Files:**
- Create: `dj_client/src/components/SystemRail.tsx`
- Create: `dj_client/src/components/SystemRail.test.tsx`
- Modify: `dj_client/src/components/ConnectedView.tsx`
- Modify: `dj_client/src/components/DisconnectedView.tsx`
- Modify: `dj_client/src/components/StatusPanel.tsx`
- Modify: `dj_client/src/components/QueuePanel.tsx`
- Modify: `dj_client/src/components/TopBar.tsx`
- Modify: `dj_client/src/styles/app.css`

**Interfaces:**
- Produces: `SystemRail({ status, captureMode, roster })`
- Consumes: shared `ConnectionStatus`, `CaptureMode`, `RosterUpdate`, `getLatencyState`, and Task 4 `AudioStage`.
- Layout invariant: `ConnectedView` renders one `AudioStage`, one `SystemRail`, and one control region; there are no compact/expanded duplicate subtrees.

- [ ] **Step 1: Write failing system-rail tests**

Create `src/components/SystemRail.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import SystemRail from './SystemRail';

it('describes live state, routes, latency quality, capture, and ordered queue', () => {
  render(
    <SystemRail
      status={{
        connected: true,
        is_active: true,
        latency_ms: 212,
        route_mode: 'relay',
        mc_connected: false,
        queue_position: 2,
        total_djs: 2,
        active_dj_name: 'DJ Nova',
        error: null,
      }}
      captureMode={{ mode: 'process_loopback', name: 'Spotify' }}
      roster={{
        djs: [
          { dj_id: '2', dj_name: 'Second', is_active: false, avatar_url: null, queue_position: 2 },
          { dj_id: '1', dj_name: 'First', is_active: true, avatar_url: null, queue_position: 1 },
        ],
        active_dj_id: '1',
        your_position: 2,
        rotation_interval_sec: 900,
      }}
    />,
  );

  expect(screen.getByText('You are live')).toBeTruthy();
  expect(screen.getByText('Minecraft unavailable')).toBeTruthy();
  expect(screen.getByText('212 ms · Degraded')).toBeTruthy();
  expect(screen.getByText('Spotify')).toBeTruthy();
  expect(screen.getAllByTestId('queue-dj').map((node) => node.textContent)).toEqual(['FirstLIVE', 'Second']);
});

it('names standby and the active DJ without relying on color', () => {
  render(
    <SystemRail
      status={{
        connected: true,
        is_active: false,
        latency_ms: 42,
        route_mode: 'relay',
        mc_connected: true,
        queue_position: 2,
        total_djs: 2,
        active_dj_name: 'DJ Spark',
        error: null,
      }}
      captureMode={{ mode: 'system_loopback' }}
      roster={null}
    />,
  );
  expect(screen.getByText('Standby')).toBeTruthy();
  expect(screen.getByText('DJ Spark is live')).toBeTruthy();
});
```

- [ ] **Step 2: Run the tests and verify component failure**

```powershell
npm run test:ui -- src/components/SystemRail.test.tsx
```

Expected: FAIL because `SystemRail.tsx` does not exist.

- [ ] **Step 3: Type and strengthen the existing status/queue panels**

Import `ConnectionStatus` and `RosterUpdate` from `../types` instead of redeclaring them. In `StatusPanel`, use `getLatencyState` and render exact text labels **Normal**, **Warning**, or **Degraded** next to latency. Render **You are live** or **Standby** in text. Render **Minecraft connected** or **Minecraft unavailable** in text.

In `QueuePanel`, retain queue-position sorting, add `data-testid="queue-dj"`, use a semantic list, and keep decorative avatars at `alt=""`. The empty state remains **No other DJs connected**.

- [ ] **Step 4: Implement SystemRail composition**

Create a labeled `<aside className="system-rail" aria-label="Show and routing status">` that derives capture copy and renders:

```tsx
const captureModeLabel = captureMode?.mode === 'process_loopback'
  ? 'Application'
  : captureMode?.mode === 'system_loopback'
    ? 'System audio'
    : captureMode?.mode === 'input_device'
      ? 'Input device'
      : 'Starting audio…';

<StatusPanel status={status} />
<section className="capture-card" aria-labelledby="capture-heading">
  <span id="capture-heading">Capture</span>
  <strong>{captureMode?.name ?? captureModeLabel}</strong>
</section>
<QueuePanel roster={roster} />
```

Map `process_loopback` to **Application**, `system_loopback` to **System audio**, `input_device` to **Input device**, and `pending` or null to **Starting audio…**.

- [ ] **Step 5: Replace duplicate connected layouts with one cockpit tree**

In `ConnectedView.tsx`, remove the approved compact/expanded duplicate markup and render:

```tsx
<div className="dashboard connected">
  <TopBar
    djName={connection.djName}
    showName={connection.showName}
    connectionState={connection.status.is_active ? 'live' : 'standby'}
    user={auth.user}
    isSignedIn={auth.isSignedIn}
    onSignOut={auth.signOut}
    onSignIn={onSignIn}
  />
  <main className="cockpit-main">
    <AudioStage
      audioRef={connection.audioRef}
      activePreset={connection.activePreset}
      onPresetChange={connection.handlePresetChange}
      connected={connection.status.connected}
      mcConnected={connection.status.mc_connected}
      isActive={connection.status.is_active}
    />
    <SystemRail
      status={connection.status}
      captureMode={connection.captureMode}
      roster={connection.roster}
    />
  </main>
  <div className="bottom-bar">
    <AudioSourceSelect
      id="connected-audio-source"
      sources={audioSources.audioSources}
      value={audioSources.selectedSource}
      onChange={handleSourceChange}
      onRefresh={audioSources.loadAudioSources}
      isLoading={audioSources.isLoadingSources}
    />
    <button type="button" className="btn voice-toggle" onClick={connection.handleToggleVoice}>
      {connection.voiceEnabled ? 'Mute voice' : 'Voice chat'}
    </button>
    <button type="button" className="btn btn-disconnect" onClick={connection.handleDisconnect}>
      Disconnect
    </button>
  </div>
</div>
```

Update `TopBar` to use `UserProfileResponse` instead of `any`, render **MCAV / DJ**, and accept `connectionState: 'disconnected' | 'live' | 'standby'`. Show a textual **LIVE** or **STANDBY** badge when connected. Update the `DisconnectedView` call to pass `connectionState="disconnected"`, `showName={null}`, and its existing account callbacks.

- [ ] **Step 6: Add cockpit and responsive rail styles**

At 860 pixels and above, `.cockpit-main` uses `grid-template-columns: minmax(0, 1fr) minmax(260px, 280px)`. Below 860 pixels it becomes one column, with the system rail below the audio stage. Remove display rules that make `.compact-only` or `.expanded-only` active; leave unrelated legacy selectors in place.

- [ ] **Step 7: Verify system rail, audio stage, and build**

```powershell
npm run test:ui -- src/components/SystemRail.test.tsx src/components/AudioStage.test.tsx
npm run build
```

Expected: PASS.

- [ ] **Step 8: Commit the cockpit structure**

```powershell
git add dj_client/src/components/SystemRail.tsx dj_client/src/components/SystemRail.test.tsx dj_client/src/components/ConnectedView.tsx dj_client/src/components/DisconnectedView.tsx dj_client/src/components/StatusPanel.tsx dj_client/src/components/QueuePanel.tsx dj_client/src/components/TopBar.tsx dj_client/src/styles/app.css
git commit -m "feat(dj-client): unify performance cockpit"
```

### Task 6: Confirmed Connection Operations and Visible Errors

**Files:**
- Create: `dj_client/src/hooks/useConnection.test.tsx`
- Modify: `dj_client/src/hooks/useConnection.ts`
- Modify: `dj_client/src/components/ConnectedView.tsx`

**Interfaces:**
- Produces additions to `UseConnectionReturn`: `voiceStatus`, `operationError`, `clearOperationError()`.
- Changes internal handler results to `Promise<boolean>` for `handleDisconnect`, `handlePresetChange`, and `handleToggleVoice`; existing Rust commands and payloads remain unchanged.
- Guarantees: capture-start failure after WebSocket connect invokes best-effort `disconnect`; failed preset/voice operations retain last confirmed UI state.

- [ ] **Step 1: Write failing connection-boundary tests**

Create `src/hooks/useConnection.test.tsx` with Tauri modules mocked before importing the hook:

```tsx
import { act, renderHook } from '@testing-library/react';
import { invoke } from '@tauri-apps/api/core';
import { beforeEach, expect, it, vi } from 'vitest';
import type { UseAuthReturn } from './useAuth';
import { useConnection } from './useConnection';

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn(async () => () => {}) }));
const invokeMock = vi.mocked(invoke);

const auth: UseAuthReturn = {
  isLoading: false,
  isSignedIn: false,
  user: null,
  error: null,
  login: vi.fn(async () => {}),
  register: vi.fn(async () => {}),
  signInWithDiscord: vi.fn(async () => {}),
  signInWithGoogle: vi.fn(async () => {}),
  resendVerification: vi.fn(async () => {}),
  verificationMessage: null,
  signOut: vi.fn(async () => {}),
  clearError: vi.fn(),
};

beforeEach(() => {
  localStorage.clear();
  invokeMock.mockReset();
});

it('disconnects a partially connected session when capture fails', async () => {
  invokeMock.mockImplementation(async (command) => {
    if (command === 'connect_with_code') return undefined;
    if (command === 'start_capture') throw new Error('capture unavailable');
    if (command === 'disconnect') return undefined;
    return undefined;
  });
  const { result } = renderHook(() => useConnection(auth));
  act(() => {
    result.current.setDirectConnect(true);
    result.current.setDjName('DJ Nova');
    result.current.setConnectCode('BEAT7K3M');
  });

  await act(() => result.current.handleConnect('system', false, async () => {}));

  expect(invokeMock).toHaveBeenCalledWith('disconnect');
  expect(result.current.status.connected).toBe(false);
  expect(result.current.status.error).toContain('Audio capture could not start');
});

it('keeps the confirmed preset when the backend rejects a change', async () => {
  invokeMock.mockRejectedValueOnce(new Error('preset rejected'));
  const { result } = renderHook(() => useConnection(auth));

  await act(() => result.current.handlePresetChange('edm'));

  expect(result.current.activePreset).toBe('auto');
  expect(result.current.operationError).toBe('Could not change the response preset. Auto is still active.');
});
```

- [ ] **Step 2: Run the tests and verify missing cleanup/error behavior**

```powershell
npm run test:ui -- src/hooks/useConnection.test.tsx
```

Expected: FAIL on missing partial disconnect, missing `operationError`, and optimistic preset state.

- [ ] **Step 3: Implement partial-session cleanup**

Inside `handleConnect`, track whether `connect_with_code` succeeded:

```ts
let sessionConnected = false;
let captureStartAttempted = false;
try {
  await invoke('connect_with_code', {
    code: formattedCode,
    djName: djName.trim(),
    serverHost: connHost,
    serverPort: connPort,
    blockPalette: auth.user?.dj_profile?.block_palette ?? null,
    djSessionId,
  });
  sessionConnected = true;
  if (!selectedSource) throw new Error('No audio source selected');
  captureStartAttempted = true;
  await invoke('start_capture', { sourceId: selectedSource });
  setStatus((previous) => ({ ...previous, connected: true }));
} catch (error) {
  if (sessionConnected) {
    try {
      await invoke('disconnect');
    } catch (cleanupError) {
      console.error('Failed to clean up partial connection:', cleanupError);
    }
  }
  setStatus((previous) => ({
    ...previous,
    connected: false,
    error: captureStartAttempted
      ? 'Audio capture could not start. Check the selected input and try again.'
      : classifyConnectionError(error),
  }));
}
```

Extract the existing code-resolution error mapping into this focused local helper:

```ts
function classifyConnectionError(error: unknown): string {
  if (error instanceof api.ApiError) {
    if (error.status === 404) return 'Connect code not found. Check the code and try again.';
    if (error.status === 409) return 'Show is full — maximum DJ limit reached.';
    if (error.status === 503) return 'Server is currently offline. Try again later.';
    return error.message;
  }

  const value = String(error).toLowerCase();
  if (value.includes('timeout') || value.includes('timed out') || value.includes('connection refused')) {
    return "Can't reach server. Check that the VJ server is running.";
  }
  if (value.includes('auth') || value.includes('invalid') || value.includes('unauthorized')) {
    return 'Authentication failed. Ask your VJ operator for a new code.';
  }
  return String(error);
}
```

- [ ] **Step 4: Confirm preset, voice, and disconnect state only after success**

Expose `voiceStatus` instead of `_voiceStatus`. Add `operationError` and `clearOperationError`. For each user operation, clear the old error, call Tauri, then update local state. Return `true` on success and `false` on failure.

Preset failure uses:

```ts
function formatPresetName(name: string): string {
  if (name === 'edm') return 'EDM';
  return name[0].toUpperCase() + name.slice(1);
}

setOperationError(`Could not change the response preset. ${formatPresetName(activePreset)} is still active.`);
```

Voice failure uses **Could not change voice streaming. Your previous voice setting is still active.** Disconnect failure uses **Could not disconnect from the show. Check the connection and try again.** and must leave connected state visible.

- [ ] **Step 5: Render operation errors in the connected view**

Add one dismissible `InlineNotice` above the control region with `tone="error"`, the current `operationError`, and a **Dismiss** button calling `clearOperationError`. Do not add one notice per operation.

- [ ] **Step 6: Verify connection and regression tests**

```powershell
npm run test:ui -- src/hooks/useConnection.test.tsx src/hooks/useAudioSources.test.tsx
npm run build
```

Expected: PASS.

- [ ] **Step 7: Commit resilient operations**

```powershell
git add dj_client/src/hooks/useConnection.ts dj_client/src/hooks/useConnection.test.tsx dj_client/src/components/ConnectedView.tsx
git commit -m "fix(dj-client): confirm live control operations"
```

### Task 7: Control Dock and Guarded Disconnect

**Files:**
- Create: `dj_client/src/hooks/useDisconnectGuard.ts`
- Create: `dj_client/src/hooks/useDisconnectGuard.test.tsx`
- Create: `dj_client/src/components/ControlDock.tsx`
- Create: `dj_client/src/components/ControlDock.test.tsx`
- Modify: `dj_client/src/App.tsx`
- Modify: `dj_client/src/components/ConnectedView.tsx`
- Modify: `dj_client/src/styles/app.css`

**Interfaces:**
- Produces: `useDisconnectGuard({ connected, onDisconnect, timeoutMs? }): UseDisconnectGuardReturn`
- Produces: `ControlDock` with source, voice, capture, refresh, and guarded-disconnect props.
- Consumes: boolean-returning `handleDisconnect` from Task 6 and Task 2 audio-source state.

- [ ] **Step 1: Write failing disconnect-guard tests**

Create `src/hooks/useDisconnectGuard.test.tsx`:

```tsx
import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useDisconnectGuard } from './useDisconnectGuard';

afterEach(() => vi.useRealTimers());

it('arms first, confirms second, and expires after three seconds', async () => {
  vi.useFakeTimers();
  const onDisconnect = vi.fn(async () => true);
  const { result } = renderHook(() => useDisconnectGuard({
    connected: true,
    onDisconnect,
    timeoutMs: 3000,
  }));

  await act(() => result.current.requestDisconnect());
  expect(result.current.armed).toBe(true);
  expect(onDisconnect).not.toHaveBeenCalled();

  act(() => vi.advanceTimersByTime(3000));
  expect(result.current.armed).toBe(false);

  await act(() => result.current.requestDisconnect());
  await act(() => result.current.requestDisconnect());
  expect(onDisconnect).toHaveBeenCalledTimes(1);
  expect(result.current.armed).toBe(false);
});

it('cancels explicitly and when no longer connected', async () => {
  const onDisconnect = vi.fn(async () => true);
  const { result, rerender } = renderHook(
    ({ connected }) => useDisconnectGuard({ connected, onDisconnect }),
    { initialProps: { connected: true } },
  );
  await act(() => result.current.requestDisconnect());
  act(() => result.current.cancelDisconnect());
  expect(result.current.armed).toBe(false);
  await act(() => result.current.requestDisconnect());
  rerender({ connected: false });
  expect(result.current.armed).toBe(false);
});
```

- [ ] **Step 2: Run the guard tests and verify missing module failure**

```powershell
npm run test:ui -- src/hooks/useDisconnectGuard.test.tsx
```

Expected: FAIL because `useDisconnectGuard.ts` does not exist.

- [ ] **Step 3: Implement the isolated disconnect state machine**

Create `useDisconnectGuard.ts` with this public contract:

```ts
export interface UseDisconnectGuardOptions {
  connected: boolean;
  onDisconnect: () => Promise<boolean>;
  timeoutMs?: number;
}

export interface UseDisconnectGuardReturn {
  armed: boolean;
  requestDisconnect: () => Promise<void>;
  cancelDisconnect: () => void;
}
```

Store the timer ID in a ref. The first request sets `armed` and starts the timer. The second clears the timer, awaits `onDisconnect`, and clears `armed` regardless of success. `cancelDisconnect` clears both. An effect calls `cancelDisconnect` when `connected` becomes false and on unmount.

- [ ] **Step 4: Write failing control-dock tests**

Create `src/components/ControlDock.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import ControlDock from './ControlDock';

it('labels source and voice state and exposes guarded disconnect copy', async () => {
  const user = userEvent.setup();
  const onDisconnectRequest = vi.fn();
  render(
    <ControlDock
      sources={[{ id: 'spotify', name: 'Spotify', source_type: 'application' }]}
      selectedSource="spotify"
      onSourceChange={vi.fn()}
      onRefreshSources={vi.fn()}
      isLoadingSources={false}
      captureMode={{ mode: 'process_loopback', name: 'Spotify' }}
      voiceAvailable
      voiceEnabled
      onToggleVoice={vi.fn()}
      disconnectArmed={false}
      onDisconnectRequest={onDisconnectRequest}
    />,
  );

  expect(screen.getByLabelText('Audio source')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Mute voice streaming' })).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Disconnect from show' }));
  expect(onDisconnectRequest).toHaveBeenCalledOnce();
});

it('shows confirm copy when armed and unavailable copy without voice support', () => {
  render(
    <ControlDock
      sources={[]}
      selectedSource={null}
      onSourceChange={vi.fn()}
      onRefreshSources={vi.fn()}
      isLoadingSources={false}
      captureMode={null}
      voiceAvailable={false}
      voiceEnabled={false}
      onToggleVoice={vi.fn()}
      disconnectArmed
      onDisconnectRequest={vi.fn()}
    />,
  );
  expect(screen.getByRole('button', { name: 'Confirm disconnect' })).toBeTruthy();
  expect(screen.getByText('Voice unavailable')).toBeTruthy();
});
```

- [ ] **Step 5: Implement and integrate ControlDock**

Create a `<footer className="control-dock" aria-label="Performance controls">`. Reuse `AudioSourceSelect` with label **Audio source**. Show capture-mode context as text. Voice copy is **Voice unavailable**, **Start voice streaming**, or **Mute voice streaming**. The disconnect button uses **Disconnect from show** or **Confirm disconnect** and a danger treatment only when armed.

In `App.tsx`, create the guard with `connection.status.connected` and `connection.handleDisconnect`. Pass it to `ConnectedView`. Change Ctrl/Cmd+D to call `requestDisconnect`; Escape cancels an armed disconnect before handling other overlays/test capture. In `ConnectedView`, replace the old `.bottom-bar` markup with `ControlDock` and pass connected source changes through `audioSources.handleSourceChange(sourceId, true)`.

- [ ] **Step 6: Add dock responsive and focus styles**

Keep the dock one row at 860 pixels and above, two rows below 720 pixels, and inside normal document flow at short heights. Maintain 36-pixel controls, visible focus, and explicit danger separation. Do not make the dock obscure scrollable content.

- [ ] **Step 7: Verify guard, dock, connection tests, and build**

```powershell
npm run test:ui -- src/hooks/useDisconnectGuard.test.tsx src/components/ControlDock.test.tsx src/hooks/useConnection.test.tsx
npm run build
```

Expected: PASS.

- [ ] **Step 8: Commit the performance controls**

```powershell
git add dj_client/src/hooks/useDisconnectGuard.ts dj_client/src/hooks/useDisconnectGuard.test.tsx dj_client/src/components/ControlDock.tsx dj_client/src/components/ControlDock.test.tsx dj_client/src/App.tsx dj_client/src/components/ConnectedView.tsx dj_client/src/styles/app.css
git commit -m "feat(dj-client): add guarded performance controls"
```

### Task 8: Brand System, Window Contract, Preview Fixtures, and Full Verification

**Files:**
- Create: `dj_client/src/dev/DjAppPreview.tsx`
- Create: `dj_client/tests/ui-contract.test.mjs`
- Modify: `dj_client/src/main.tsx`
- Modify: `dj_client/index.html`
- Modify: `dj_client/src-tauri/tauri.conf.json`
- Modify: `dj_client/src/styles/app.css`
- Modify: `dj_client/package.json`

**Interfaces:**
- Produces: development-only `?ui-preview=preflight|live|standby|degraded` states.
- Production invariant: `DjAppPreview` is selected only when `import.meta.env.DEV` is true.
- Verification invariant: automated frontend, containment, Rust, responsive, keyboard, reduced-motion, and console checks are all recorded.

- [ ] **Step 1: Write failing static UI contract tests**

Create `tests/ui-contract.test.mjs`:

```js
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

test('loads all canonical font roles', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
  assert.match(html, /family=Inter/);
  assert.match(html, /family=JetBrains\+Mono/);
  assert.match(html, /family=Space\+Grotesk/);
});

test('uses the approved desktop window bounds', async () => {
  const raw = await readFile(new URL('../src-tauri/tauri.conf.json', import.meta.url), 'utf8');
  const config = JSON.parse(raw);
  const window = config.app.windows[0];
  assert.deepEqual(
    { width: window.width, height: window.height, minWidth: window.minWidth, minHeight: window.minHeight },
    { width: 960, height: 640, minWidth: 640, minHeight: 480 },
  );
});

test('retains responsive and reduced-motion contracts', async () => {
  const css = await readFile(new URL('../src/styles/app.css', import.meta.url), 'utf8');
  assert.match(css, /@media \(max-width: 859px\)/);
  assert.match(css, /@media \(max-width: 719px\)/);
  assert.match(css, /prefers-reduced-motion: reduce/);
  assert.match(css, /\.signal-bus/);
  assert.match(css, /\.cockpit-main/);
});
```

Add scripts:

```json
"test:ui-contract": "node --test tests/ui-contract.test.mjs",
"test:frontend": "npm run test:ui && npm run test:ui-contract"
```

- [ ] **Step 2: Run the contract tests and verify current font/window failures**

```powershell
npm run test:ui-contract
```

Expected: FAIL because Inter is not loaded and the window remains 700 by 520 with a 600-by-400 minimum.

- [ ] **Step 3: Apply the canonical font and window contract**

Update the Google Fonts URL to request `Inter:wght@400;500;600;700`, `JetBrains Mono:wght@500;700`, and `Space Grotesk:wght@400;500;600;700`. Update the Tauri window fields to:

```json
"width": 960,
"height": 640,
"minWidth": 640,
"minHeight": 480
```

Keep `resizable`, `center`, and `decorations` unchanged.

- [ ] **Step 4: Consolidate the active visual system**

Ensure active preflight/cockpit selectors derive from shared tokens. Use cyan-to-indigo for primary actions, amber only for beat/warm state, green only for successful routing/live availability, and red only for errors/destructive confirmation. Add `.sr-only`, explicit focus-visible, 859/719 breakpoints, short-height scrolling, and complete reduced-motion overrides for signal/beat/glow transitions. Retain auth/profile styles and unrelated legacy selectors.

- [ ] **Step 5: Add deterministic development-only visual fixtures**

Create `src/dev/DjAppPreview.tsx` with exact fixture values:

```tsx
const previewAudio = {
  current: {
    bands: [0.82, 0.61, 0.46, 0.33, 0.21],
    isBeat: true,
    bpm: 128,
    beatIntensity: 0.88,
  },
};

const previewStatus = {
  connected: true,
  is_active: true,
  latency_ms: 38,
  route_mode: 'relay',
  mc_connected: true,
  queue_position: 1,
  total_djs: 3,
  active_dj_name: 'DJ Nova',
  error: null,
};

const previewRoster = {
  djs: [
    { dj_id: 'nova', dj_name: 'DJ Nova', is_active: true, avatar_url: null, queue_position: 1 },
    { dj_id: 'spark', dj_name: 'DJ Spark', is_active: false, avatar_url: null, queue_position: 2 },
    { dj_id: 'echo', dj_name: 'Echo Phase', is_active: false, avatar_url: null, queue_position: 3 },
  ],
  active_dj_id: 'nova',
  your_position: 1,
  rotation_interval_sec: 900,
};
```

Render the same `TopBar`, `AudioStage`, `SystemRail`, and `ControlDock` components used by `ConnectedView`, with no-op callbacks. `standby` changes `is_active` to false and `active_dj_name` to **DJ Spark**. `degraded` sets latency to 224 and `mc_connected` to false. `preflight` renders `DisconnectedView` with onboarding visible and deterministic audio-source fixtures.

In `main.tsx`, keep production behavior explicit:

```tsx
const previewMode = new URLSearchParams(window.location.search).get('ui-preview');
const root = import.meta.env.DEV && previewMode
  ? <DjAppPreview mode={previewMode} />
  : <App />;

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>{root}</React.StrictMode>,
);
```

`DjAppPreview` validates the mode and falls back to `live` for unknown values; production never selects it.

- [ ] **Step 6: Run all automated frontend and containment checks**

From `dj_client`:

```powershell
npm run test:frontend
npm run test:containment
npm run build
```

Expected: all commands PASS.

- [ ] **Step 7: Run Rust tests**

```powershell
cargo test --manifest-path src-tauri/Cargo.toml
```

Expected: PASS. If a platform dependency fails, preserve exact command output and diagnose whether the failure is introduced or environmental before continuing.

- [ ] **Step 8: Perform responsive visual verification**

Start the local app:

```powershell
npm run dev -- --host 127.0.0.1 --port 4173
```

Inspect these URLs with the browser-control workflow at 960 by 640, 700 by 520, and 640 by 480:

- `http://127.0.0.1:4173/?ui-preview=preflight`
- `http://127.0.0.1:4173/?ui-preview=live`
- `http://127.0.0.1:4173/?ui-preview=standby`
- `http://127.0.0.1:4173/?ui-preview=degraded`

At every size verify no horizontal overflow, no clipped primary action, one audio stage, readable live/standby text, readable latency/route state, a reachable dock, and correct stacked order. Check console logs after each mode and fix any new error before continuing.

- [ ] **Step 9: Verify keyboard, focus, and reduced motion**

In preflight, tab through identity, show, source, test, advanced, connect, and account controls in visual order. In performance, tab through presets, source, refresh, voice, and disconnect. Verify Ctrl/Cmd+D arms rather than immediately disconnecting and Escape cancels the armed state. Emulate `prefers-reduced-motion: reduce` and verify beat/signal travel stops while state colors and labels remain.

- [ ] **Step 10: Inspect final diff and commit the visual/verification contract**

```powershell
git status --short
git diff --check
git diff -- dj_client
git add dj_client/index.html dj_client/package.json dj_client/src/main.tsx dj_client/src/dev/DjAppPreview.tsx dj_client/src/styles/app.css dj_client/src-tauri/tauri.conf.json dj_client/tests/ui-contract.test.mjs
git commit -m "feat(dj-client): finish stage-ready visual system"
```

- [ ] **Step 11: Run the completion audit against the specification**

Map every acceptance criterion in `docs/superpowers/specs/2026-08-20-dj-app-redesign-design.md` to current evidence: source file, automated test, build output, Rust test output, or inspected preview state. Any criterion without direct evidence remains incomplete and must be implemented or verified before claiming completion.
