import assert from 'node:assert/strict';
import test from 'node:test';

import { AudioManager } from '../js/modules/AudioManager.js';
import { PreviewManager } from '../js/managers/PreviewManager.js';
import { readPanelFile } from './helpers/panel-source.mjs';

function meterElement() {
  const attributes = new Map();
  return {
    style: {},
    parentElement: null,
    setAttribute(name, value) { attributes.set(name, String(value)); },
    getAttribute(name) { return attributes.get(name) ?? null; },
  };
}

test('stage selector, six faders, and eleven meters have programmatic names and meter ranges', async () => {
  const html = await readPanelFile('index.html');
  const stageTag = html.match(/<select\b[^>]*\bid="stage-select"[^>]*>/)?.[0];
  assert.match(stageTag, /aria-label="Stage"/);

  const faders = [...html.matchAll(/<input\b[^>]*class="fader-input"[^>]*>/g)].map(([tag]) => tag);
  assert.equal(faders.length, 6);
  assert.deepEqual(
    faders.map((tag) => tag.match(/aria-label="([^"]+)"/)?.[1]),
    ['Bass sensitivity', 'Low sensitivity', 'Mid sensitivity', 'High sensitivity', 'Air sensitivity', 'Master sensitivity'],
  );

  const meters = [...html.matchAll(/<(?:div)\b[^>]*\brole="meter"[^>]*>/g)].map(([tag]) => tag);
  assert.equal(meters.length, 11);
  for (const meter of meters) {
    assert.match(meter, /aria-label="[^"]+"/);
    assert.match(meter, /aria-valuemin="0"/);
    assert.match(meter, /aria-valuemax="100"/);
    assert.match(meter, /aria-valuenow="0"/);
  }
});

test('audio and preview meter values update in their existing render paths', () => {
  const audioContainers = Array.from({ length: 6 }, meterElement);
  const audioFills = audioContainers.map((container) => {
    const fill = meterElement();
    fill.closest = () => container;
    return fill;
  });
  const audio = Object.create(AudioManager.prototype);
  audio.state = { bands: [-0.1, 0.25, 0.5, 0.75, 1.2], amplitude: 1.4 };
  audio.elements = {
    meters: audioFills.slice(0, 5),
    meterValues: Array.from({ length: 5 }, () => ({ textContent: '' })),
    meterMaster: audioFills[5],
    meterMasterValue: { textContent: '' },
  };
  audio._updateMeters();
  assert.deepEqual(audioContainers.map((meter) => meter.getAttribute('aria-valuenow')), ['0', '25', '50', '75', '100', '100']);

  const originalDocument = globalThis.document;
  const previewMeters = Array.from({ length: 5 }, meterElement);
  const previewFills = previewMeters.map((container) => {
    const fill = meterElement();
    fill.parentElement = container;
    return fill;
  });
  globalThis.document = {
    getElementById(id) {
      const index = Number.parseInt(id.replace('strip-band-', ''), 10);
      return previewFills[index] ?? null;
    },
  };
  const preview = Object.create(PreviewManager.prototype);
  preview.state = { bands: [0, 0.2, 0.4, 0.6, 0.8] };
  try {
    preview._updateMeters();
    assert.deepEqual(previewMeters.map((meter) => meter.getAttribute('aria-valuenow')), ['0', '20', '40', '60', '80']);
  } finally {
    globalThis.document = originalDocument;
  }
});
