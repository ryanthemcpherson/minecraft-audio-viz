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
