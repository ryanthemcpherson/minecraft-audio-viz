import assert from 'node:assert/strict';
import test from 'node:test';

import { PreviewManager } from '../js/managers/PreviewManager.js';
import { extractIds, readPanelFile } from './helpers/panel-source.mjs';

function regionStart(html, region) {
  return html.indexOf(`data-live-region="${region}"`);
}

test('Live exposes the five-region performance workspace around one preview canvas', async () => {
  const html = await readPanelFile('index.html');
  const regions = [...html.matchAll(/data-live-region="([^"]+)"/g)].map((match) => match[1]);

  assert.deepEqual(regions, ['output', 'show', 'launch', 'effects', 'audio']);
  assert.equal(extractIds(html).filter((id) => id === 'preview-canvas').length, 1);
  assert.match(html, /data-live-region="output"[^>]*data-preview-slot="live"/);
  assert.ok(regionStart(html, 'output') < html.indexOf('id="preview-strip"'));
  assert.ok(regionStart(html, 'launch') < html.indexOf('id="pattern-search"'));
  assert.ok(regionStart(html, 'launch') < html.indexOf('id="pattern-grid"'));
  assert.ok(regionStart(html, 'effects') < html.indexOf('id="effect-triggers"'));
  assert.ok(regionStart(html, 'audio') < html.indexOf('id="band-faders"'));
});

test('presentation modes move the existing preview wrapper and pause only hidden workspaces', () => {
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document');
  const originalRaf = Object.getOwnPropertyDescriptor(globalThis, 'requestAnimationFrame');
  const strip = { parentElement: null };
  const liveSlot = {
    append(node) { node.parentElement = this; },
  };
  const zoneSlot = {
    append(node) { node.parentElement = this; },
  };
  const destinations = new Map([['live', liveSlot], ['zones', zoneSlot]]);
  let created = 0;
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: {
      querySelector(selector) {
        const workspace = selector.match(/data-preview-slot="([^"]+)"/)?.[1];
        return destinations.get(workspace) ?? null;
      },
      createElement() { created += 1; },
    },
  });
  Object.defineProperty(globalThis, 'requestAnimationFrame', {
    configurable: true,
    value(callback) { callback(); return 1; },
  });

  try {
    const manager = Object.create(PreviewManager.prototype);
    manager.elements = { previewStrip: strip };
    manager.startAnimation = () => { manager.started = (manager.started ?? 0) + 1; };
    manager.stopAnimation = () => { manager.stopped = (manager.stopped ?? 0) + 1; };
    manager._onResize = () => { manager.resized = (manager.resized ?? 0) + 1; };

    manager.setPresentationMode('live');
    assert.equal(manager._presentationMode, 'live');
    assert.equal(strip.parentElement, liveSlot);
    assert.equal(manager.started, 1);

    manager.setPresentationMode('visuals');
    assert.equal(manager._presentationMode, 'hidden');
    assert.equal(strip.parentElement, liveSlot, 'Task 5 may add the compact destination later');
    assert.equal(manager.stopped, 1, 'a renderer hidden inside Live must not keep running');

    manager.setPresentationMode('zones');
    assert.equal(manager._presentationMode, 'compact');
    assert.equal(strip.parentElement, zoneSlot);
    assert.equal(manager.started, 2);

    manager.setPresentationMode('djs');
    assert.equal(manager._presentationMode, 'hidden');
    assert.equal(manager.stopped, 2);
    assert.equal(created, 0, 'presentation changes never clone or recreate the canvas');
  } finally {
    if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument);
    else delete globalThis.document;
    if (originalRaf) Object.defineProperty(globalThis, 'requestAnimationFrame', originalRaf);
    else delete globalThis.requestAnimationFrame;
  }
});

test('late preview initialization cannot animate without a visible presentation slot', () => {
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document');
  const originalRaf = Object.getOwnPropertyDescriptor(globalThis, 'requestAnimationFrame');
  const strip = { parentElement: null };
  const destinations = new Map();
  let animationStarts = 0;
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: {
      querySelector(selector) {
        const workspace = selector.match(/data-preview-slot="([^"]+)"/)?.[1];
        return destinations.get(workspace) ?? null;
      },
    },
  });
  Object.defineProperty(globalThis, 'requestAnimationFrame', {
    configurable: true,
    value() { return 1; },
  });

  try {
    const manager = Object.create(PreviewManager.prototype);
    Object.assign(manager, {
      elements: { previewStrip: strip },
      _initialized: false,
      _failed: false,
      _stripCollapsed: false,
      _animationId: null,
      _animate() { animationStarts += 1; },
      _onResize() {},
    });

    manager.setPresentationMode('visuals');
    manager._initialized = true;
    manager.startAnimation();
    assert.equal(animationStarts, 0, 'async init completion must respect the hidden mode');

    const zoneSlot = { append(node) { node.parentElement = this; } };
    destinations.set('zones', zoneSlot);
    manager.setPresentationMode('zones');
    assert.equal(animationStarts, 1, 'a later valid compact slot resumes the preserved renderer');
    assert.equal(strip.parentElement, zoneSlot);
  } finally {
    if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument);
    else delete globalThis.document;
    if (originalRaf) Object.defineProperty(globalThis, 'requestAnimationFrame', originalRaf);
    else delete globalThis.requestAnimationFrame;
  }
});

test('collapsed preview cannot animate across presentation changes and resumes only when visible', () => {
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document');
  const originalRaf = Object.getOwnPropertyDescriptor(globalThis, 'requestAnimationFrame');
  const liveSlot = { append(node) { node.parentElement = this; } };
  let animationStarts = 0;
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: {
      querySelector(selector) {
        return selector === '[data-preview-slot="live"]' ? liveSlot : null;
      },
    },
  });
  Object.defineProperty(globalThis, 'requestAnimationFrame', {
    configurable: true,
    value() { return 1; },
  });

  try {
    const manager = Object.create(PreviewManager.prototype);
    Object.assign(manager, {
      elements: { previewStrip: { parentElement: null } },
      _initialized: true,
      _failed: false,
      _stripCollapsed: true,
      _animationId: null,
      _animate() { animationStarts += 1; },
      _onResize() {},
      stopAnimation() { this._animationId = null; },
    });

    manager.setPresentationMode('live');
    assert.equal(animationStarts, 0, 'a collapsed Live preview stays paused');

    manager._stripCollapsed = false;
    manager.setPresentationMode('visuals');
    manager.startAnimation();
    assert.equal(animationStarts, 0, 'expansion while hidden cannot start the renderer');

    manager.setPresentationMode('live');
    assert.equal(animationStarts, 1, 'an expanded preview resumes in its visible Live slot');
  } finally {
    if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument);
    else delete globalThis.document;
    if (originalRaf) Object.defineProperty(globalThis, 'requestAnimationFrame', originalRaf);
    else delete globalThis.requestAnimationFrame;
  }
});

test('Live CSS keeps the collapsed preview body at zero height', async () => {
  const css = await readPanelFile('css/control-panel.css');

  assert.match(
    css,
    /\.live-output\s+\.preview-strip\.collapsed\s+\.preview-strip-body\s*\{[^}]*height:\s*0/s,
  );
});

test('expanded Scene collections are explicitly bounded to an internal scroller', async () => {
  const html = await readPanelFile('index.html');
  const css = await readPanelFile('css/control-panel.css');

  assert.match(html, /class="[^"]*live-scene-bank[^"]*"[^>]*data-live-destination="launch"/);
  assert.match(
    css,
    /\.launch-deck\s+>\s+\.live-scene-bank:not\(\.collapsed\)\s*\{[^}]*max-height:/s,
  );
  assert.match(
    css,
    /\.live-scene-bank:not\(\.collapsed\)\s+\.scenes-grid\s*\{[^}]*overflow-y:\s*auto/s,
  );
});
