# Performance-First VJ Control Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a performance-first 1920×1080 VJ workspace that preserves every existing admin-panel capability while integrating the unfinished manager extraction.

**Architecture:** Keep the frontend as static HTML, CSS, and ES modules served by the VJ server. First establish executable DOM and composition contracts, then wire the extracted managers, add a five-workspace shell, reshape Live and secondary workspaces, and remove proven duplicate monolith code only after parity checks pass.

**Tech Stack:** Vanilla HTML5, CSS Grid, ES modules, Node.js built-in test runner, Vite 8, Three.js, Python `pytest` through WSL for VJ static-serving coverage.

**Spec:** `docs/superpowers/specs/2026-08-20-vj-control-panel-design.md`

## Global Constraints

- The target Live workspace is 1920×1080 with no vertical scrolling.
- Preserve every existing control, message type, and WebSocket behavior.
- Keep the panel static and framework-free; add no runtime dependency.
- Preserve the untracked work under `admin_panel/js/modules/`, `admin_panel/js/managers/`, and `admin_panel/js/ui/` and integrate it deliberately.
- Keep VJ-server state authoritative; local storage is presentation-only.
- Retain targeted DOM and animation-frame-throttled updates.
- Meet WCAG AA, keyboard, non-color state, and reduced-motion requirements.
- Remove monolith and CSS duplicates only after executable parity checks pass.
- Use `apply_patch` for source edits, WSL-native Python for Python tests, and atomic conventional commits.

## File Map

### Create

- `admin_panel/tests/helpers/panel-source.mjs` — reads panel sources and extracts DOM IDs and module references.
- `admin_panel/tests/panel-contract.test.mjs` — guards workspace structure, unique IDs, and JavaScript-to-DOM bindings.
- `admin_panel/tests/module-composition.test.mjs` — guards manager construction and removal of legacy duplicate methods.
- `admin_panel/tests/workspace-manager.test.mjs` — tests workspace activation, focus, and persistence with injected fakes.
- `admin_panel/tests/pattern-library.test.mjs` — tests search, favorite, and recency ordering.
- `admin_panel/tests/control-state.test.mjs` — tests connected, stale, and unavailable presentation state.
- `admin_panel/js/config/workspaces.js` — canonical workspace definitions and storage key.
- `admin_panel/js/modules/WorkspaceManager.js` — navigation and workspace lifecycle controller.
- `admin_panel/js/utils/pattern-library.js` — pure pattern filtering and ordering helpers.
- `admin_panel/js/utils/control-state.js` — pure connection-capability state derivation.
- `admin_panel/css/control-panel.css` — performance-shell and workspace-specific layout.
- `images/admin_panel_live_v2.png`, `images/admin_panel_visuals_v2.png`, `images/admin_panel_zones_v2.png`, `images/admin_panel_djs_v2.png`, `images/admin_panel_system_v2.png` — final visual evidence without overwriting existing images.

### Modify

- `package.json` — add the admin-panel test script.
- `admin_panel/index.html` — introduce the command bar, navigation rail, output dock, and five workspaces while preserving control IDs.
- `admin_panel/css/mcav-tokens.css` — add canonical spacing, sizing, typography, state, elevation, and motion tokens.
- `admin_panel/css/admin.css` — retain shared components and remove only verified obsolete layout selectors.
- `admin_panel/js/admin-app.js` — become the composition root and WebSocket lifecycle owner.
- `admin_panel/js/modules/ElementCache.js` — cache new workspace and launch-deck elements.
- `admin_panel/js/modules/EventWiring.js` — bind workspace, search, favorite, and global show actions once.
- `admin_panel/js/modules/MessageRouter.js` — route all incoming messages to domain managers.
- `admin_panel/js/modules/UIHelpers.js` — expose global state, stale-state, focus, and accessibility updates.
- `admin_panel/js/modules/PatternManager.js` — render searched, favorited, and recent patterns without changing server data.
- `admin_panel/js/managers/PreviewManager.js` — support large Live and compact Visuals/Zones presentation from one canvas.
- Other extracted manager files only where parity review identifies a concrete missing dependency or stale monolith call.

---

### Task 1: Add executable panel contracts and workspace definitions

**Files:**
- Create: `admin_panel/tests/helpers/panel-source.mjs`
- Create: `admin_panel/tests/panel-contract.test.mjs`
- Create: `admin_panel/js/config/workspaces.js`
- Modify: `package.json`

**Interfaces:**
- Produces: `WORKSPACES`, `DEFAULT_WORKSPACE`, `WORKSPACE_STORAGE_KEY`, and `isWorkspaceName(value)`.
- Produces: `readPanelFile(relativePath)`, `extractIds(html)`, and `extractLiteralElementIds(source)` test helpers.
- Consumes: no earlier task.

- [ ] **Step 1: Add the failing workspace-definition test and DOM contract helper**

```js
// admin_panel/tests/helpers/panel-source.mjs
import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const panelRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

export function readPanelFile(relativePath) {
  return readFile(resolve(panelRoot, relativePath), 'utf8');
}

export function extractIds(html) {
  return [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
}

export function extractLiteralElementIds(source) {
  return [...source.matchAll(/(?:getElementById|setupControl|setupZoneControl|setupToggle)\(\s*['"]([^'"]+)['"]/g)]
    .map((match) => match[1]);
}
```

```js
// admin_panel/tests/panel-contract.test.mjs
import assert from 'node:assert/strict';
import { readdir } from 'node:fs/promises';
import test from 'node:test';
import { DEFAULT_WORKSPACE, WORKSPACES, isWorkspaceName } from '../js/config/workspaces.js';
import { extractIds, extractLiteralElementIds, readPanelFile } from './helpers/panel-source.mjs';

test('defines the approved workspace order', () => {
  assert.equal(DEFAULT_WORKSPACE, 'live');
  assert.deepEqual(WORKSPACES.map(({ id }) => id), ['live', 'visuals', 'zones', 'djs', 'system']);
  assert.equal(isWorkspaceName('zones'), true);
  assert.equal(isWorkspaceName('legacy'), false);
});

test('panel IDs are unique and every literal getElementById binding exists', async () => {
  const html = await readPanelFile('index.html');
  const ids = extractIds(html);
  assert.equal(new Set(ids).size, ids.length, 'index.html contains duplicate IDs');

  const sourceFiles = await readdir(new URL('../js/', import.meta.url), { recursive: true });
  for (const filename of sourceFiles.filter((name) => name.endsWith('.js') && !name.includes('vendor'))) {
    const source = await readPanelFile(`js/${filename}`);
    for (const id of extractLiteralElementIds(source)) {
      assert.ok(ids.includes(id), `${filename} binds missing #${id}`);
    }
  }
});
```

- [ ] **Step 2: Add the admin test command and verify the missing config fails**

```json
"test:admin": "node --test admin_panel/tests/*.test.mjs"
```

Run: `npm run test:admin`

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `admin_panel/js/config/workspaces.js`.

- [ ] **Step 3: Implement the canonical workspace definitions**

```js
// admin_panel/js/config/workspaces.js
export const DEFAULT_WORKSPACE = 'live';
export const WORKSPACE_STORAGE_KEY = 'mcav-active-workspace';

export const WORKSPACES = Object.freeze([
  { id: 'live', label: 'Live', shortcut: 'Alt+1' },
  { id: 'visuals', label: 'Visuals', shortcut: 'Alt+2' },
  { id: 'zones', label: 'Zones', shortcut: 'Alt+3' },
  { id: 'djs', label: 'DJs', shortcut: 'Alt+4' },
  { id: 'system', label: 'System', shortcut: 'Alt+5' },
]);

const workspaceNames = new Set(WORKSPACES.map(({ id }) => id));

export function isWorkspaceName(value) {
  return typeof value === 'string' && workspaceNames.has(value);
}
```

- [ ] **Step 4: Run the contract tests and production build**

Run: `npm run test:admin`

Expected: PASS for workspace definitions, unique IDs, and current literal DOM bindings.

Run: `npm run build`

Expected: Vite exits 0 and emits the existing frontend bundle.

- [ ] **Step 5: Commit the test foundation**

```powershell
git add package.json admin_panel/tests admin_panel/js/config/workspaces.js
git commit -m "test(admin): establish control panel contracts"
```

### Task 2: Wire the existing extracted managers into `AdminApp`

**Files:**
- Create: `admin_panel/tests/module-composition.test.mjs`
- Create: `admin_panel/tests/message-router.test.mjs`
- Modify: `admin_panel/js/admin-app.js`
- Modify: `admin_panel/js/modules/MessageRouter.js`
- Modify: `admin_panel/js/modules/EventWiring.js`
- Modify: extracted managers only when required by a demonstrated composition mismatch
- Add to Git: `admin_panel/js/modules/*.js`, `admin_panel/js/managers/*.js`, `admin_panel/js/ui/*.js`

**Interfaces:**
- Consumes: `createInitialState()`, `cacheElements(elements)`, and all existing extracted manager classes.
- Produces: `AdminApp` properties `ui`, `audio`, `patterns`, `actions`, `particles`, `scenes`, `zones`, `connectCodes`, `voice`, `dj`, `banner`, `bitmap`, `preview`, and `router`.
- Produces: `MessageRouter.handleMessage(data: object): void` as the sole incoming-message dispatcher.

- [ ] **Step 1: Add a failing composition test**

```js
// admin_panel/tests/module-composition.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { readPanelFile } from './helpers/panel-source.mjs';

const managerNames = [
  'UIHelpers', 'AudioManager', 'PatternManager', 'ActionsManager',
  'ParticleEffectsManager', 'SceneManager', 'ZoneManager',
  'ConnectCodeManager', 'VoiceChatManager', 'DJManager', 'BannerManager',
  'BitmapManager', 'PreviewManager', 'MessageRouter',
];

test('AdminApp composes every extracted manager', async () => {
  const source = await readPanelFile('js/admin-app.js');
  for (const managerName of managerNames) {
    assert.match(source, new RegExp(`import \\{ ${managerName} \\}`));
    assert.match(source, new RegExp(`new ${managerName}\\(this\\)`));
  }
  assert.match(source, /this\.state = createInitialState\(\)/);
  assert.match(source, /this\.router\.handleMessage\(e\.detail\)/);
});
```

```js
// admin_panel/tests/message-router.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { MessageRouter } from '../js/modules/MessageRouter.js';

test('ignores malformed message envelopes at the router boundary', () => {
  const notices = [];
  const router = new MessageRouter({
    ui: { showToast: (message, type) => notices.push({ message, type }) },
  });
  assert.doesNotThrow(() => router.handleMessage(null));
  assert.doesNotThrow(() => router.handleMessage({}));
  assert.equal(notices.length, 2);
  assert.equal(notices.every(({ type }) => type === 'warning'), true);
});
```

- [ ] **Step 2: Run the composition test to verify it fails**

Run: `npm run test:admin`

Expected: FAIL because `admin-app.js` imports only `WebSocketService` and debounce utilities, and malformed messages currently dereference `data.type`.

- [ ] **Step 3: Replace inline construction with the extracted composition graph**

Add explicit imports and initialize in dependency order:

```js
import { createInitialState } from './modules/InitialState.js';
import { cacheElements } from './modules/ElementCache.js';
import { setupEventListeners } from './modules/EventWiring.js';
import { MessageRouter } from './modules/MessageRouter.js';
import { UIHelpers } from './modules/UIHelpers.js';
import { AudioManager } from './modules/AudioManager.js';
import { PatternManager } from './modules/PatternManager.js';
import { ActionsManager } from './modules/ActionsManager.js';
import { ParticleEffectsManager } from './modules/ParticleEffectsManager.js';
import { SceneManager } from './modules/SceneManager.js';
import { ZoneManager } from './modules/ZoneManager.js';
import { ConnectCodeManager } from './modules/ConnectCodeManager.js';
import { VoiceChatManager } from './modules/VoiceChatManager.js';
import { DJManager } from './modules/DJManager.js';
import { BannerManager } from './modules/BannerManager.js';
import { BitmapManager } from './managers/BitmapManager.js';
import { PreviewManager } from './managers/PreviewManager.js';
```

```js
this.state = createInitialState();
this.elements = {};
cacheElements(this.elements);

this.ui = new UIHelpers(this);
this.audio = new AudioManager(this);
this.patterns = new PatternManager(this);
this.actions = new ActionsManager(this);
this.particles = new ParticleEffectsManager(this);
this.scenes = new SceneManager(this);
this.connectCodes = new ConnectCodeManager(this);
this.dj = new DJManager(this);
this.voice = new VoiceChatManager(this);
this.banner = new BannerManager(this);
this.bitmap = new BitmapManager(this);
this.preview = new PreviewManager(this);
this.zones = new ZoneManager(this);
this.router = new MessageRouter(this);

setupEventListeners(this);
```

Retain compatibility wrappers temporarily where an extracted manager still calls a monolith method. Record each wrapper in a `// cleanup-task-7: delegate to Manager.method` comment with the exact target manager method; Task 7 must remove every such marker.

- [ ] **Step 4: Route incoming messages and connection lifecycle through managers**

```js
this.ws.addEventListener('message', (event) => {
  this.router.handleMessage(event.detail);
});
```

Add this guard before the router switch so incomplete payloads cannot enter a domain handler:

```js
if (!data || typeof data.type !== 'string') {
  console.warn('[Protocol] Ignored malformed message envelope', data);
  app.ui.showToast('Ignored malformed server message', 'warning', 2500);
  return;
}
```

Replace connection callback calls with their manager equivalents, including `this.ui.setConnectionStatus`, `this.ui.updateServiceIndicators`, `this.connectCodes.resetGenerateButton`, `this.bitmap.fetchBitmapData`, and `this.preview.initPreview`.

- [ ] **Step 5: Run composition, contract, and build checks**

Run: `npm run test:admin`

Expected: all Node tests PASS.

Run: `npm run build`

Expected: Vite resolves every extracted module and exits 0.

- [ ] **Step 6: Smoke-test the unchanged interface before layout work**

Run: `npm run dev:admin -- --host 127.0.0.1`

Open: `http://127.0.0.1:5173/admin_panel/index.html`

Expected: the disconnected panel renders, reconnect remains available, tabs switch, preview failure is isolated, and the console has no uncaught exception.

- [ ] **Step 7: Commit the extracted-manager integration**

```powershell
git add admin_panel/js/admin-app.js admin_panel/js/modules admin_panel/js/managers admin_panel/js/ui admin_panel/tests/module-composition.test.mjs
git commit -m "refactor(admin): wire extracted control managers"
```

### Task 3: Add the command bar, navigation rail, and workspace controller

**Files:**
- Create: `admin_panel/tests/workspace-manager.test.mjs`
- Create: `admin_panel/js/modules/WorkspaceManager.js`
- Create: `admin_panel/css/control-panel.css`
- Modify: `admin_panel/index.html`
- Modify: `admin_panel/css/mcav-tokens.css`
- Modify: `admin_panel/js/admin-app.js`
- Modify: `admin_panel/js/modules/ElementCache.js`
- Modify: `admin_panel/js/modules/EventWiring.js`

**Interfaces:**
- Consumes: `WORKSPACES`, `DEFAULT_WORKSPACE`, and `WORKSPACE_STORAGE_KEY` from Task 1.
- Produces: `new WorkspaceManager({ root, storage, onChange })`, `setup(): void`, `activate(name, options): boolean`, and `activeWorkspace: string`.
- Produces DOM hooks: `[data-workspace-nav]`, `[data-workspace-panel]`, and `document.documentElement.dataset.workspace`.

- [ ] **Step 1: Add a failing workspace-controller test with injected DOM fakes**

```js
// admin_panel/tests/workspace-manager.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { WorkspaceManager } from '../js/modules/WorkspaceManager.js';

function fakeNode(workspace) {
  const attributes = new Map();
  return {
    dataset: { workspace }, hidden: false, focused: false,
    classList: { toggle() {} },
    setAttribute(name, value) { attributes.set(name, String(value)); },
    getAttribute(name) { return attributes.get(name); },
    addEventListener() {},
    focus() { this.focused = true; },
  };
}

test('activates one workspace and persists only valid names', () => {
  const buttons = ['live', 'visuals', 'zones', 'djs', 'system'].map(fakeNode);
  const panels = ['live', 'visuals', 'zones', 'djs', 'system'].map(fakeNode);
  const values = new Map([['mcav-active-workspace', 'invalid']]);
  const root = {
    documentElement: { dataset: {} },
    querySelectorAll(selector) {
      return selector === '[data-workspace-nav]' ? buttons : panels;
    },
  };
  const storage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };

  const manager = new WorkspaceManager({ root, storage });
  manager.setup();
  assert.equal(manager.activeWorkspace, 'live');
  assert.equal(manager.activate('zones', { focus: true }), true);
  assert.equal(root.documentElement.dataset.workspace, 'zones');
  assert.equal(panels.find((panel) => panel.dataset.workspace === 'zones').hidden, false);
  assert.equal(values.get('mcav-active-workspace'), 'zones');
  assert.equal(manager.activate('legacy'), false);
});

test('workspace navigation survives unavailable local storage', () => {
  const buttons = ['live', 'visuals', 'zones', 'djs', 'system'].map(fakeNode);
  const panels = ['live', 'visuals', 'zones', 'djs', 'system'].map(fakeNode);
  const root = {
    documentElement: { dataset: {} },
    querySelectorAll: (selector) => selector === '[data-workspace-nav]' ? buttons : panels,
  };
  const storage = {
    getItem() { throw new Error('blocked'); },
    setItem() { throw new Error('blocked'); },
  };
  const manager = new WorkspaceManager({ root, storage });
  assert.doesNotThrow(() => manager.setup());
  assert.doesNotThrow(() => manager.activate('visuals'));
  assert.equal(manager.activeWorkspace, 'visuals');
});
```

- [ ] **Step 2: Run the test and confirm the controller is missing**

Run: `npm run test:admin -- --test-name-pattern="activates one workspace"`

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `WorkspaceManager.js`.

- [ ] **Step 3: Implement workspace activation and keyboard-safe persistence**

```js
// admin_panel/js/modules/WorkspaceManager.js
import {
  DEFAULT_WORKSPACE,
  WORKSPACE_STORAGE_KEY,
  isWorkspaceName,
} from '../config/workspaces.js';

export class WorkspaceManager {
  constructor({ root = document, storage = window.localStorage, onChange = () => {} } = {}) {
    this.root = root;
    this.storage = storage;
    this.onChange = onChange;
    this.buttons = [];
    this.panels = [];
    this.activeWorkspace = DEFAULT_WORKSPACE;
  }

  setup() {
    this.buttons = [...this.root.querySelectorAll('[data-workspace-nav]')];
    this.panels = [...this.root.querySelectorAll('[data-workspace-panel]')];
    for (const button of this.buttons) {
      button.addEventListener('click', () => this.activate(button.dataset.workspace, { focus: true }));
    }
    let saved = null;
    try {
      saved = this.storage.getItem(WORKSPACE_STORAGE_KEY);
    } catch (error) {
      console.warn('[Workspace] Preference read failed', error);
    }
    this.activate(isWorkspaceName(saved) ? saved : DEFAULT_WORKSPACE, { persist: false });
  }

  activate(name, { focus = false, persist = true } = {}) {
    if (!isWorkspaceName(name)) return false;
    this.activeWorkspace = name;
    this.root.documentElement.dataset.workspace = name;
    for (const button of this.buttons) {
      const active = button.dataset.workspace === name;
      button.setAttribute('aria-selected', active);
      button.setAttribute('tabindex', active ? '0' : '-1');
      if (active && focus) button.focus();
    }
    for (const panel of this.panels) {
      panel.hidden = panel.dataset.workspace !== name;
    }
    if (persist) {
      try {
        this.storage.setItem(WORKSPACE_STORAGE_KEY, name);
      } catch (error) {
        console.warn('[Workspace] Preference write failed', error);
      }
    }
    this.onChange(name);
    return true;
  }
}
```

- [ ] **Step 4: Replace the old page shell with semantic global structure**

Link tokens before component CSS and add the workspace stylesheet after it:

```html
<link rel="stylesheet" href="css/mcav-tokens.css">
<link rel="stylesheet" href="css/admin.css">
<link rel="stylesheet" href="css/control-panel.css">
```

Use this hierarchy and move existing controls rather than copying them:

```html
<div id="app" class="control-app">
  <header id="header" class="command-bar">
    <a class="skip-link" href="#workspace-live">Skip to live controls</a>
    <div class="brand-lockup" aria-label="MCAV VJ Control">MCAV <span>VJ CONTROL</span></div>
    <div class="command-context"><!-- existing stage/zone controls --></div>
    <div class="command-health"><!-- existing connection and service state --></div>
    <div class="command-emergency"><!-- existing blackout/freeze buttons --></div>
  </header>
  <div class="control-shell">
    <nav class="workspace-rail" aria-label="VJ workspaces">
      <button data-workspace-nav data-workspace="live" aria-controls="workspace-live">Live</button>
      <button data-workspace-nav data-workspace="visuals" aria-controls="workspace-visuals">Visuals</button>
      <button data-workspace-nav data-workspace="zones" aria-controls="workspace-zones">Zones</button>
      <button data-workspace-nav data-workspace="djs" aria-controls="workspace-djs">DJs</button>
      <button data-workspace-nav data-workspace="system" aria-controls="workspace-system">System</button>
    </nav>
    <main class="workspace-stage">
      <section id="workspace-live" data-workspace-panel data-workspace="live"></section>
      <section id="workspace-visuals" data-workspace-panel data-workspace="visuals" hidden></section>
      <section id="workspace-zones" data-workspace-panel data-workspace="zones" hidden></section>
      <section id="workspace-djs" data-workspace-panel data-workspace="djs" hidden></section>
      <section id="workspace-system" data-workspace-panel data-workspace="system" hidden></section>
    </main>
  </div>
</div>
```

Do not create a sixth legacy panel. Place every existing section into the destination listed in the spec's capability mapping and preserve its existing ID.

- [ ] **Step 5: Add canonical layout and motion tokens**

```css
:root {
  --mcav-space-1: 0.25rem;
  --mcav-space-2: 0.5rem;
  --mcav-space-3: 0.75rem;
  --mcav-space-4: 1rem;
  --mcav-space-5: 1.5rem;
  --mcav-command-height: 3.5rem;
  --mcav-rail-width: 5rem;
  --mcav-control-height: 2.75rem;
  --mcav-radius-sm: 0.375rem;
  --mcav-radius-md: 0.625rem;
  --mcav-font-body: "Inter", ui-sans-serif, system-ui, sans-serif;
  --mcav-font-heading: "Space Grotesk", ui-sans-serif, system-ui, sans-serif;
  --mcav-font-data: "JetBrains Mono", ui-monospace, monospace;
  --mcav-state-success: #2fe098;
  --mcav-state-warning: #ffd166;
  --mcav-state-danger: #ff6767;
  --mcav-ease-out: cubic-bezier(0.16, 1, 0.3, 1);
  --mcav-duration-fast: 120ms;
  --mcav-focus-ring: 0 0 0 3px rgba(0, 204, 255, 0.35);
}
```

- [ ] **Step 6: Add the fixed shell and initial workspace layout**

```css
.control-app { min-height: 100dvh; background: var(--mcav-bg-primary); color: var(--mcav-text-primary); }
.command-bar { position: fixed; inset: 0 0 auto; z-index: 50; height: var(--mcav-command-height); }
.control-shell { min-height: 100dvh; padding-top: var(--mcav-command-height); }
.workspace-rail { position: fixed; inset: var(--mcav-command-height) auto 0 0; width: var(--mcav-rail-width); }
.workspace-stage { min-height: calc(100dvh - var(--mcav-command-height)); margin-left: var(--mcav-rail-width); }
[data-workspace-panel][hidden] { display: none !important; }
.workspace-heading { font-family: var(--mcav-font-heading); }
.telemetry, output, [data-live-value] { font-family: var(--mcav-font-data); font-variant-numeric: tabular-nums; }
.control-surface { background: var(--mcav-bg-card); border: 1px solid var(--mcav-border-subtle); }
```

- [ ] **Step 7: Compose and initialize `WorkspaceManager`**

```js
this.workspaces = new WorkspaceManager({
  onChange: (workspace) => this.preview?.setPresentationMode?.(workspace),
});
this.workspaces.setup();
```

Cache `workspaceNav` and `workspacePanels` in `ElementCache`, and replace old tab binding in `EventWiring` with Alt+1 through Alt+5 workspace shortcuts that ignore input, textarea, select, and contenteditable targets.

- [ ] **Step 8: Extend the DOM contract and run tests**

Add assertions that the five `data-workspace-nav` and five `data-workspace-panel` values match `WORKSPACES`, that `btn-blackout` and `btn-freeze` occur before `.workspace-stage`, and that no legacy tab IDs remain.

Run: `npm run test:admin`

Expected: all contract and controller tests PASS.

Run: `npm run build`

Expected: Vite exits 0.

- [ ] **Step 9: Commit the workspace shell**

```powershell
git add admin_panel/index.html admin_panel/css admin_panel/js/admin-app.js admin_panel/js/modules/WorkspaceManager.js admin_panel/js/modules/ElementCache.js admin_panel/js/modules/EventWiring.js admin_panel/tests
git commit -m "feat(admin): add performance workspace shell"
```

### Task 4: Build the no-scroll Live workspace and launch library

**Files:**
- Create: `admin_panel/tests/pattern-library.test.mjs`
- Create: `admin_panel/js/utils/pattern-library.js`
- Modify: `admin_panel/index.html`
- Modify: `admin_panel/css/control-panel.css`
- Modify: `admin_panel/js/modules/ElementCache.js`
- Modify: `admin_panel/js/modules/EventWiring.js`
- Modify: `admin_panel/js/modules/PatternManager.js`
- Modify: `admin_panel/js/managers/PreviewManager.js`

**Interfaces:**
- Produces: `filterAndRankPatterns(patterns, options): Pattern[]` and `updateRecentIds(ids, selectedId, limit): string[]`.
- Produces DOM hooks: `#pattern-search`, `[data-pattern-favorite]`, `[data-live-region]`, and Live grid areas `output`, `show`, `launch`, `effects`, and `audio`.
- Consumes: the single existing `#preview-canvas`, existing PatternManager state, and workspace change callback.

- [ ] **Step 1: Add failing pure tests for pattern search, favorites, and recency**

```js
// admin_panel/tests/pattern-library.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { filterAndRankPatterns, updateRecentIds } from '../js/utils/pattern-library.js';

const patterns = [
  { id: 'spectrum', name: 'Spectrum Bars' },
  { id: 'helix', name: 'DNA Helix' },
  { id: 'aurora', name: 'Aurora' },
];

test('filters by id or name and ranks favorites before recent items', () => {
  assert.deepEqual(
    filterAndRankPatterns(patterns, {
      query: '', favoriteIds: ['aurora'], recentIds: ['helix'],
    }).map(({ id }) => id),
    ['aurora', 'helix', 'spectrum'],
  );
  assert.deepEqual(
    filterAndRankPatterns(patterns, { query: 'dna', favoriteIds: [], recentIds: [] })
      .map(({ id }) => id),
    ['helix'],
  );
});

test('recent pattern IDs are unique and capped', () => {
  assert.deepEqual(updateRecentIds(['helix', 'aurora'], 'aurora', 2), ['aurora', 'helix']);
  assert.deepEqual(updateRecentIds(['helix', 'aurora'], 'spectrum', 2), ['spectrum', 'helix']);
});
```

- [ ] **Step 2: Run the pattern tests and confirm the helper is missing**

Run: `npm run test:admin -- --test-name-pattern="patterns|recent pattern"`

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `pattern-library.js`.

- [ ] **Step 3: Implement deterministic pattern ordering**

```js
// admin_panel/js/utils/pattern-library.js
function normalize(value) { return String(value ?? '').trim().toLowerCase(); }

export function filterAndRankPatterns(patterns, { query = '', favoriteIds = [], recentIds = [] } = {}) {
  const needle = normalize(query);
  const favorites = new Map(favoriteIds.map((id, index) => [id, index]));
  const recents = new Map(recentIds.map((id, index) => [id, index]));
  return patterns
    .filter((pattern) => !needle || normalize(pattern.id).includes(needle) || normalize(pattern.name).includes(needle))
    .map((pattern, sourceIndex) => ({ pattern, sourceIndex }))
    .sort((left, right) => {
      const leftFavorite = favorites.has(left.pattern.id) ? favorites.get(left.pattern.id) : Number.MAX_SAFE_INTEGER;
      const rightFavorite = favorites.has(right.pattern.id) ? favorites.get(right.pattern.id) : Number.MAX_SAFE_INTEGER;
      if (leftFavorite !== rightFavorite) return leftFavorite - rightFavorite;
      const leftRecent = recents.has(left.pattern.id) ? recents.get(left.pattern.id) : Number.MAX_SAFE_INTEGER;
      const rightRecent = recents.has(right.pattern.id) ? recents.get(right.pattern.id) : Number.MAX_SAFE_INTEGER;
      if (leftRecent !== rightRecent) return leftRecent - rightRecent;
      return left.sourceIndex - right.sourceIndex;
    })
    .map(({ pattern }) => pattern);
}

export function updateRecentIds(ids, selectedId, limit = 8) {
  if (!selectedId) return ids.slice(0, limit);
  return [selectedId, ...ids.filter((id) => id !== selectedId)].slice(0, limit);
}
```

- [ ] **Step 4: Reshape Live into the approved five-region grid**

```html
<section id="workspace-live" class="live-workspace" data-workspace-panel data-workspace="live">
  <section class="live-output" data-live-region="output" data-preview-slot="live"><!-- existing preview strip/canvas --></section>
  <aside class="show-rack" data-live-region="show"><!-- current output, preset, transition, BPM, DJ/sync --></aside>
  <section class="launch-deck" data-live-region="launch"><!-- scenes, search, pattern grid --></section>
  <section class="effects-deck" data-live-region="effects"><!-- existing effect triggers and particle launchers --></section>
  <section class="audio-strip" data-live-region="audio"><!-- faders and common audio settings --></section>
</section>
```

Keep `preview-canvas`, meters, scene controls, pattern grid, presets, effects, faders, attack, release, AGC, beat sensitivity, and beat threshold IDs unchanged.

- [ ] **Step 5: Add the exact desktop grid and compact overflow behavior**

```css
.live-workspace {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  grid-template-rows: minmax(22rem, 1fr) minmax(15rem, 0.72fr) 6.75rem;
  grid-template-areas:
    "output output output output output output output output show show show show"
    "launch launch launch launch launch launch launch launch effects effects effects effects"
    "audio audio audio audio audio audio audio audio audio audio audio audio";
  gap: var(--mcav-space-3);
  height: calc(100dvh - var(--mcav-command-height));
  overflow: hidden;
  padding: var(--mcav-space-3);
}
.live-output { grid-area: output; min-height: 0; }
.show-rack { grid-area: show; min-height: 0; overflow: auto; }
.launch-deck { grid-area: launch; min-height: 0; overflow: hidden; }
.effects-deck { grid-area: effects; min-height: 0; overflow: auto; }
.audio-strip { grid-area: audio; min-width: 0; }
```

- [ ] **Step 6: Extend `PatternManager` without changing server state**

Store favorites under `mcav-pattern-favorites` and recents under `mcav-pattern-recents`. On render, call `filterAndRankPatterns`; on pattern activation, call `updateRecentIds`; on favorite action, stop propagation, update only local storage, and rerender. Search input changes call `patterns.setSearchQuery(value)` and do not send a WebSocket message.

Use guarded storage helpers so browser privacy settings cannot break show control:

```js
_readStoredIds(key) {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '[]');
    return Array.isArray(value) ? value.filter((id) => typeof id === 'string') : [];
  } catch (error) {
    console.warn(`[Patterns] Could not read ${key}`, error);
    return [];
  }
}

_writeStoredIds(key, ids) {
  try { localStorage.setItem(key, JSON.stringify(ids)); }
  catch (error) { console.warn(`[Patterns] Could not write ${key}`, error); }
}
```

- [ ] **Step 7: Keep one preview canvas and expose presentation mode**

```js
setPresentationMode(workspace) {
  const visible = workspace === 'live' || workspace === 'visuals' || workspace === 'zones';
  this._presentationMode = workspace === 'live' ? 'live' : (visible ? 'compact' : 'hidden');
  const slot = visible ? document.querySelector(`[data-preview-slot="${workspace}"]`) : null;
  if (slot && this.elements.previewStrip?.parentElement !== slot) {
    slot.append(this.elements.previewStrip);
  }
  if (visible) {
    this.startAnimation();
    requestAnimationFrame(() => this._onResize());
  } else {
    this.stopAnimation();
  }
}
```

Do not clone or recreate `#preview-canvas`. Pause nonessential animation only when the mode is `hidden`; resume when returning to Live, Visuals, or Zones.

Cache `#preview-strip` as `elements.previewStrip` in `ElementCache` so the preview manager moves the existing wrapper rather than querying or rebuilding it.

- [ ] **Step 8: Verify Live behavior and commit**

Run: `npm run test:admin`

Expected: all tests PASS, including pattern ordering and DOM uniqueness.

Run: `npm run build`

Expected: Vite exits 0.

At 1920×1080 in the browser console, run:

```js
({ viewport: innerHeight, page: document.documentElement.scrollHeight, live: document.querySelector('#workspace-live').scrollHeight })
```

Expected: `page <= viewport`; only explicitly scrollable internal racks may have `scrollHeight > clientHeight`.

```powershell
git add admin_panel/index.html admin_panel/css/control-panel.css admin_panel/js/modules admin_panel/js/managers/PreviewManager.js admin_panel/js/utils/pattern-library.js admin_panel/tests
git commit -m "feat(admin): build live performance workspace"
```

### Task 5: Complete Visuals, Zones, DJs, and System workspaces

**Files:**
- Modify: `admin_panel/index.html`
- Modify: `admin_panel/css/control-panel.css`
- Modify: `admin_panel/tests/panel-contract.test.mjs`
- Modify: `admin_panel/js/managers/PreviewManager.js`

**Interfaces:**
- Consumes: workspace DOM hooks and `PreviewManager.setPresentationMode(workspace)` from Tasks 3–4.
- Produces: focused, scrollable secondary panels with all approved capability groups.
- Preserves: every ID referenced by first-party JavaScript.

- [ ] **Step 1: Add failing placement assertions for each secondary workspace**

Extend the test helper:

```js
export function extractWorkspaceSlice(html, workspace) {
  const start = html.indexOf(`data-workspace="${workspace}"`, html.indexOf('data-workspace-panel'));
  if (start < 0) throw new Error(`Missing workspace ${workspace}`);
  const next = html.indexOf('data-workspace-panel', start + 1);
  return html.slice(start, next < 0 ? html.length : next);
}
```

Add exact destination checks:

```js
const expectedIds = {
  visuals: ['bitmap-pattern-grid', 'bitmap-palette-grid', 'ledwall-effects-section', 'ledwall-text-section', 'ledwall-layers-section', 'particle-global-intensity', 'dj-logo-section'],
  zones: ['stage-zone-list', 'mode-entities', 'mode-particles', 'mode-hybrid', 'zone-entity-count', 'zone-block-type', 'band-materials-section', 'zone-size-x', 'btn-cleanup-zone'],
  djs: ['dj-queue', 'dj-pending-section', 'btn-generate-code', 'active-codes', 'banner-dj-select', 'banner-mode-text', 'banner-mode-image', 'btn-save-banner-profile'],
  system: ['sync-mode', 'ctrl-visual-delay', 'btn-sync-test', 'parity-check-btn', 'voice-chat-section', 'sync-dashboard'],
};
for (const [workspace, ids] of Object.entries(expectedIds)) {
  const slice = extractWorkspaceSlice(html, workspace);
  for (const id of ids) assert.match(slice, new RegExp(`id="${id}"`));
}
```

- [ ] **Step 2: Run the placement test and verify it fails for incomplete destinations**

Run: `npm run test:admin -- --test-name-pattern="destination"`

Expected: FAIL naming the first control outside its approved workspace.

- [ ] **Step 3: Move, do not duplicate, every secondary control group**

Use these internal layouts:

```html
<section class="config-workspace">
  <header class="workspace-heading"><h1>Visuals</h1><p>Build and tune visual layers.</p></header>
  <div class="config-grid">
    <nav class="section-index" aria-label="Visuals sections"></nav>
    <div class="config-sections"><!-- existing sections with unchanged IDs --></div>
    <aside class="context-preview" data-preview-slot="visuals" aria-label="Output preview"></aside>
  </div>
</section>
```

Use `data-preview-slot="visuals"` in Visuals and `data-preview-slot="zones"` in Zones. `PreviewManager` moves the existing `#preview-strip` between these slots; it never clones or recreates the canvas. DJs and System hide the Three.js preview but keep global show state and emergency actions visible in the command bar.

- [ ] **Step 4: Add sticky context and focused responsive layout**

```css
.config-workspace { height: calc(100dvh - var(--mcav-command-height)); overflow: auto; padding: var(--mcav-space-4); }
.config-grid { display: grid; grid-template-columns: 12rem minmax(0, 1fr) minmax(18rem, 28rem); gap: var(--mcav-space-4); }
.section-index, .context-preview { position: sticky; top: 0; align-self: start; }
html[data-workspace="djs"] .context-preview,
html[data-workspace="system"] .context-preview { display: none; }
```

- [ ] **Step 5: Verify every binding and capability destination**

Run: `npm run test:admin`

Expected: all IDs remain unique, all literal DOM bindings exist, and every destination assertion PASSes.

Run: `npm run build`

Expected: Vite exits 0.

- [ ] **Step 6: Commit the secondary workspaces**

```powershell
git add admin_panel/index.html admin_panel/css/control-panel.css admin_panel/js/managers/PreviewManager.js admin_panel/tests/panel-contract.test.mjs
git commit -m "feat(admin): organize visual and system workspaces"
```

### Task 6: Make connection state, accessibility, and responsive behavior explicit

**Files:**
- Create: `admin_panel/tests/control-state.test.mjs`
- Create: `admin_panel/js/utils/control-state.js`
- Modify: `admin_panel/index.html`
- Modify: `admin_panel/css/control-panel.css`
- Modify: `admin_panel/js/modules/UIHelpers.js`
- Modify: `admin_panel/js/modules/EventWiring.js`

**Interfaces:**
- Produces: `deriveControlState({ connected, minecraftConnected }): { connectionState, disableNetworkControls, disableMinecraftControls }`.
- Produces: `UIHelpers.applyControlState(): void`, which updates `#app`, status text, and controls marked with `data-requires-connection` or `data-requires-minecraft`.
- Consumes: connection lifecycle state owned by `AdminApp` and existing `UIHelpers.setConnectionStatus` calls.

- [ ] **Step 1: Add failing tests for disconnected, connected, and Minecraft-unavailable state**

```js
// admin_panel/tests/control-state.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { deriveControlState } from '../js/utils/control-state.js';

test('derives stale state without erasing the last show state', () => {
  assert.deepEqual(
    deriveControlState({ connected: false, minecraftConnected: false }),
    { connectionState: 'stale', disableNetworkControls: true, disableMinecraftControls: true },
  );
});

test('keeps server controls active while disabling Minecraft-only controls', () => {
  assert.deepEqual(
    deriveControlState({ connected: true, minecraftConnected: false }),
    { connectionState: 'connected', disableNetworkControls: false, disableMinecraftControls: true },
  );
  assert.deepEqual(
    deriveControlState({ connected: true, minecraftConnected: true }),
    { connectionState: 'connected', disableNetworkControls: false, disableMinecraftControls: false },
  );
});
```

- [ ] **Step 2: Run the focused test and verify the utility is missing**

Run: `npm run test:admin -- --test-name-pattern="stale state|Minecraft-only"`

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `control-state.js`.

- [ ] **Step 3: Implement the pure state derivation**

```js
// admin_panel/js/utils/control-state.js
export function deriveControlState({ connected, minecraftConnected }) {
  return {
    connectionState: connected ? 'connected' : 'stale',
    disableNetworkControls: !connected,
    disableMinecraftControls: !connected || !minecraftConnected,
  };
}
```

- [ ] **Step 4: Apply state at the UI boundary**

```js
applyControlState() {
  const presentation = deriveControlState(this.state);
  const app = document.getElementById('app');
  app.dataset.connectionState = presentation.connectionState;

  document.querySelectorAll('[data-requires-connection]').forEach((element) => {
    element.disabled = presentation.disableNetworkControls;
    element.setAttribute('aria-disabled', presentation.disableNetworkControls);
  });
  document.querySelectorAll('[data-requires-minecraft]').forEach((element) => {
    element.disabled = presentation.disableMinecraftControls;
    element.setAttribute('aria-disabled', presentation.disableMinecraftControls);
  });
}
```

Call `applyControlState()` after connected, disconnected, Minecraft-status, and initial-state events. Do not clear current pattern, scene, BPM, or meters on disconnect; mark their container `data-stale="true"` and expose `Last known state` to assistive technology.

- [ ] **Step 5: Mark real capability boundaries in HTML**

Use `data-requires-connection` for WebSocket mutations such as pattern, scene, preset, audio, and sync controls. Use `data-requires-minecraft` for entity-pool, zone cleanup, bitmap initialization, banner application, and parity controls. Keep reconnect enabled while disconnected. Keep blackout and freeze visible; disable them when their command cannot be delivered and explain the disabled state with adjacent status copy.

Represent optional and asynchronous state explicitly:

```css
[data-ui-state="loading"] { cursor: progress; opacity: 0.7; }
[data-ui-state="unavailable"] { opacity: 0.55; }
[data-ui-state="error"] { border-color: var(--mcav-state-danger); }
[data-stale="true"] { filter: saturate(0.55); }
[data-stale="true"]::after { content: "Last known state"; }
```

Use `data-ui-state="unavailable"` with explanatory copy for missing voice-chat, bitmap, renderer, or Minecraft capabilities; do not silently remove their controls.

- [ ] **Step 6: Complete keyboard and semantic behavior**

Ensure:

```js
const editable = event.target.matches('input, textarea, select, [contenteditable="true"]');
if (editable) return;
```

- Workspace navigation is a labeled tablist or navigation landmark with one selected item.
- Pattern and scene launchers are buttons, not clickable divs.
- Icon-only controls have `aria-label`.
- Dynamic connection, transition, and effect results use existing polite live regions.
- Modal closure restores focus through `ModalDialog`.
- Blackout and freeze expose pressed state with `aria-pressed`.

- [ ] **Step 7: Implement the approved responsive degradation order**

```css
@media (max-width: 1599px) {
  .telemetry-secondary { display: none; }
  .workspace-rail { width: 4.25rem; }
  .workspace-stage { margin-left: 4.25rem; }
}
@media (max-width: 1279px) {
  .workspace-nav-label { position: absolute; inline-size: 1px; block-size: 1px; overflow: hidden; clip-path: inset(50%); }
  .live-workspace { grid-template-rows: minmax(18rem, 1fr) minmax(13rem, 0.75fr) 6.5rem; }
  .config-grid { grid-template-columns: 4rem minmax(0, 1fr); }
  .context-preview { grid-column: 2; }
}
@media (max-width: 899px) {
  .command-bar { position: sticky; }
  .control-shell { padding-top: 0; }
  .workspace-rail { position: sticky; inset: var(--mcav-command-height) 0 auto; width: auto; flex-direction: row; }
  .workspace-stage { margin-left: 0; }
  .live-workspace, .config-grid { display: flex; flex-direction: column; height: auto; overflow: visible; }
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { scroll-behavior: auto !important; transition-duration: 0.01ms !important; animation-duration: 0.01ms !important; animation-iteration-count: 1 !important; }
}
```

- [ ] **Step 8: Run tests, build, keyboard checks, and commit**

Run: `npm run test:admin`

Expected: all tests PASS.

Run: `npm run build`

Expected: Vite exits 0.

Browser checks: Tab from the address bar through the command bar, rail, Live rack, launch deck, effects, and audio strip; press B/F/T and Alt+1–5 outside editable controls; emulate reduced motion and confirm glow pulses stop.

```powershell
git add admin_panel/index.html admin_panel/css/control-panel.css admin_panel/js/modules/UIHelpers.js admin_panel/js/modules/EventWiring.js admin_panel/js/utils/control-state.js admin_panel/tests/control-state.test.mjs
git commit -m "feat(admin): harden live control states and accessibility"
```

### Task 7: Remove verified monolith and layout duplication

**Files:**
- Modify: `admin_panel/tests/module-composition.test.mjs`
- Modify: `admin_panel/js/admin-app.js`
- Modify: `admin_panel/css/admin.css`
- Modify: extracted managers where a final compatibility wrapper is still referenced

**Interfaces:**
- Consumes: the fully composed managers and workspace DOM from Tasks 2–6.
- Produces: an `AdminApp` limited to construction, shared coordination fields, WebSocket lifecycle, and startup.
- Removes: only duplicate methods and selectors authorized by the approved design.

- [ ] **Step 1: Add failing cleanup assertions**

```js
test('AdminApp no longer contains extracted domain implementations', async () => {
  const source = await readPanelFile('js/admin-app.js');
  const prohibitedMethods = [
    '_cacheElements', '_setupEventListeners', '_handleMessage',
    '_renderPatternGrid', '_renderDJQueue', '_handleAudioState',
    '_sendZoneConfig', '_renderStageZoneList', '_initPreview',
    '_initBitmapControls', '_setupBannerListeners', '_showToast',
  ];
  for (const method of prohibitedMethods) {
    assert.doesNotMatch(source, new RegExp(`\\n\\s+${method}\\(`), `${method} still lives in AdminApp`);
  }
  assert.doesNotMatch(source, /cleanup-task-7:/);
  assert.ok(source.split(/\r?\n/).length < 700, 'AdminApp is still a domain monolith');
});

test('legacy layout selectors are gone', async () => {
  const css = await readPanelFile('css/admin.css');
  for (const selector of ['#tab-bar', '#zone-overview', '#main-content', '#work-area']) {
    assert.equal(css.includes(selector), false, `${selector} remains in legacy CSS`);
  }
});
```

- [ ] **Step 2: Run cleanup tests and verify they fail**

Run: `npm run test:admin -- --test-name-pattern="no longer contains|legacy layout"`

Expected: FAIL listing legacy methods and selectors that remain.

- [ ] **Step 3: Trace every remaining monolith caller before removal**

Run:

```powershell
rg -n "this\._(cacheElements|setupEventListeners|handleMessage|renderPatternGrid|renderDJQueue|handleAudioState|sendZoneConfig|renderStageZoneList|initPreview|initBitmapControls|setupBannerListeners|showToast)" admin_panel/js
rg -n "cleanup-task-7:" admin_panel/js
```

Expected: each result has a named manager replacement. Change callers first; do not remove a method with an unresolved caller.

- [ ] **Step 4: Remove duplicate implementations in manager-sized batches**

Use `apply_patch` to remove only methods now owned by the extracted managers. Keep these composition-root responsibilities in `AdminApp`:

```js
constructor() { /* WebSocket, shared state, manager construction, startup */ }
_setupWebSocket() { /* connection lifecycle and router handoff only */ }
_requestInitialState() { /* initial read-only message batch */ }
```

If a manager lacks behavior present in the monolith, copy and adapt that behavior into the manager, rerun tests/build, and only then remove the duplicate.

- [ ] **Step 5: Remove superseded layout CSS while retaining shared controls**

Delete old sidebar, tab bar, tab panel, and preview-strip layout rules. Keep buttons, sliders, meters, modals, toasts, forms, effects, bitmap components, zone components, DJ cards, and reduced-motion/focus rules unless their replacement is already verified in `control-panel.css`.

- [ ] **Step 6: Run the complete preservation and build suite**

Run: `npm run test:admin`

Expected: all tests PASS, including manager composition, no missing literal IDs, destination mapping, and cleanup assertions.

Run:

```powershell
Get-ChildItem admin_panel/js -Recurse -Filter *.js | Where-Object { $_.FullName -notmatch '\\vendor\\' } | ForEach-Object { node --check $_.FullName }
```

Expected: every first-party JavaScript file exits cleanly.

Run: `npm run build`

Expected: Vite exits 0.

- [ ] **Step 7: Commit the authorized cleanup**

```powershell
git add admin_panel/js/admin-app.js admin_panel/js/modules admin_panel/js/managers admin_panel/css/admin.css admin_panel/tests/module-composition.test.mjs
git commit -m "refactor(admin): remove verified control panel duplication"
```

### Task 8: Run end-to-end verification and capture visual evidence

**Files:**
- Create: `images/admin_panel_live_v2.png`
- Create: `images/admin_panel_visuals_v2.png`
- Create: `images/admin_panel_zones_v2.png`
- Create: `images/admin_panel_djs_v2.png`
- Create: `images/admin_panel_system_v2.png`
- Test only: `vj_server/tests/test_static_http.py`

**Interfaces:**
- Consumes: the completed panel and existing VJ static HTTP server.
- Produces: build/test output, viewport measurements, accessibility evidence, and five non-destructive screenshots.

- [ ] **Step 1: Run all frontend verification from a clean command**

Run:

```powershell
npm run test:admin
npm run build
Get-ChildItem admin_panel/js -Recurse -Filter *.js | Where-Object { $_.FullName -notmatch '\\vendor\\' } | ForEach-Object { node --check $_.FullName }
```

Expected: all Node tests PASS, Vite exits 0, and every syntax check exits 0.

- [ ] **Step 2: Run static-serving tests through WSL-native Python**

Run:

```powershell
wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server && if [ ! -d .venv ]; then python3 -m venv .venv; fi && .venv/bin/python -m pip install -e ".[dev]" && .venv/bin/python -m pytest tests/test_static_http.py -q'
```

Expected: `test_static_http.py` PASSes. Do not use Windows-native Python or global `pip`.

- [ ] **Step 3: Start the admin development server and open the panel**

Run: `npm run dev:admin -- --host 127.0.0.1`

Open: `http://127.0.0.1:5173/admin_panel/index.html`

Expected: no uncaught console error, one `#preview-canvas`, five workspace buttons, and five workspace panels.

- [ ] **Step 4: Inject deterministic state through real manager/router paths**

Use the browser console or evaluation tool:

```js
window.adminApp.router.handleMessage({
  type: 'vj_state',
  patterns: [
    { id: 'spectrum', name: 'Spectrum Bars' },
    { id: 'helix', name: 'DNA Helix' },
    { id: 'aurora', name: 'Aurora' },
  ],
  current_pattern: 'spectrum',
  entity_count: 32,
  zone: 'main',
  zone_patterns: { main: 'spectrum' },
  minecraft_connected: true,
  minecraft_server_type: 'paper',
});
window.adminApp.router.handleMessage({
  type: 'audio',
  bands: [0.84, 0.62, 0.47, 0.31, 0.18],
  amplitude: 0.78,
  is_beat: true,
  beat_intensity: 0.9,
  frame: 12482,
  bpm: 128,
  ping_ms: 18,
  pipeline_latency_ms: 31,
  jitter_ms: 2.4,
  sync_confidence: 0.97,
  fps: 60,
});
window.adminApp.router.handleMessage({
  type: 'dj_roster',
  dj_roster: [{ dj_id: 'demo-dj', dj_name: 'Demo DJ', latency_ms: 18, fps: 60, bpm: 128 }],
  active_dj: 'demo-dj',
});
```

Expected: real render methods populate patterns, output state, meters, telemetry, and DJ state without test-only UI branches.

- [ ] **Step 5: Verify the viewport matrix and Live no-scroll invariant**

Inspect 1920×1080, 1440×900, 1280×800, and 800×900. At each size check navigation, clipping, focus visibility, internal scrolling, preview sizing, emergency control persistence, and command-bar health.

At 1920×1080 evaluate:

```js
const live = document.querySelector('#workspace-live');
({
  pageFits: document.documentElement.scrollHeight <= innerHeight,
  liveFits: live.scrollHeight <= live.clientHeight,
  duplicateIds: [...document.querySelectorAll('[id]')]
    .map((element) => element.id)
    .filter((id, index, ids) => ids.indexOf(id) !== index),
});
```

Expected: `pageFits: true`, `liveFits: true`, and `duplicateIds: []`.

- [ ] **Step 6: Verify interactions and accessibility**

- Activate all five workspaces by click and Alt+1–5.
- Search patterns, favorite one, launch it, reload, and confirm presentation preferences restore.
- Exercise B/F/T with focus outside and inside editable controls.
- Verify reconnect remains enabled while disconnected and stale data is labeled.
- Verify Minecraft-only actions disable independently from server-only controls.
- Tab through the entire Live workspace and open/close every modal.
- Emulate `prefers-reduced-motion: reduce` and confirm beat/glow animations stop.
- Inspect accessible names, roles, pressed/selected states, and live regions.

Expected: all checks match the design spec without console errors.

- [ ] **Step 7: Capture the five new screenshots**

Capture each workspace at 1920×1080 after deterministic state injection. Save to the five `*_v2.png` paths listed above. Do not overwrite or delete existing screenshots.

- [ ] **Step 8: Review final repository state and commit verification artifacts**

Run:

```powershell
git status --short
git diff --stat
git diff --check
```

Expected: only intended panel, test, plan, and new screenshot changes; no whitespace errors.

```powershell
git add images/admin_panel_live_v2.png images/admin_panel_visuals_v2.png images/admin_panel_zones_v2.png images/admin_panel_djs_v2.png images/admin_panel_system_v2.png
git commit -m "docs: capture redesigned VJ control workspaces"
```

## Completion Audit

Before claiming completion, record authoritative evidence for each requirement:

- Live fits 1920×1080 without vertical scroll: browser measurements from Task 8 Step 5.
- All capabilities preserved: DOM binding and destination contract tests plus browser interaction checklist.
- Five-workspace hierarchy: workspace contract and controller tests.
- Static/WebSocket compatibility: Vite build, router fixture, and `test_static_http.py`.
- Extracted modules integrated and monolith reduced: composition/cleanup tests and final source inspection.
- Accessibility and responsive behavior: browser checks at every specified viewport and reduced-motion/focus inspection.
- Visual result: five final screenshots rendered from the completed application.
- Repository integrity: final `git status`, `git diff --check`, and atomic commit history.
