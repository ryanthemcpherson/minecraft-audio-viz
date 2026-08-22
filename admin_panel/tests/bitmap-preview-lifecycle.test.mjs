import assert from 'node:assert/strict';
import test from 'node:test';
import { PreviewManager } from '../js/managers/PreviewManager.js';
import { createInitialState } from '../js/modules/InitialState.js';
import { MessageRouter } from '../js/modules/MessageRouter.js';
import { ZoneManager } from '../js/modules/ZoneManager.js';
import {
  createFakeDocument,
  createFakeThree,
  createScene,
  loadBitmapPreview,
} from './helpers/bitmap-preview-harness.mjs';

async function createHarness(zones) {
  const { BitmapPreview } = await loadBitmapPreview();
  globalThis.THREE = createFakeThree();
  globalThis.document = createFakeDocument();

  const state = createInitialState();
  state.allZones = zones;
  const app = {
    state,
    elements: {},
    ws: { isConnected: false, send: () => {} },
    ui: {
      formatStageName: (name) => name,
      formatZoneDisplayName: (name) => name,
      showToast: () => {},
      updateBlockCountDisplay: () => {},
      updateServiceIndicators: () => {},
      updateMCDependentControls: () => {},
    },
    patterns: {
      handlePatterns: () => {},
      updatePatternDisplay: () => {},
      updatePatternHighlightForZones: () => {},
    },
    dj: { handleDJRoster: () => {}, renderPendingDJs: () => {} },
    bitmap: {
      updateZoneSelector: () => {},
      updateStatus: () => {},
      fetchBitmapData: () => {},
      highlightPattern: () => {},
    },
    audio: {
      updateBandMaterialsSourceHint: () => {},
      updateVisualDelayDisplay: () => {},
      updateVisualDelayModeDisplay: () => {},
      syncBandMaterials: () => {},
    },
  };

  const preview = Object.create(PreviewManager.prototype);
  preview.app = app;
  preview.state = state;
  preview.ws = app.ws;
  preview.elements = {};
  preview._initialized = true;
  preview._scene = createScene();
  preview._zoneGroups = {};
  preview._bitmapPreview = new BitmapPreview();
  preview._blocks = [{ visible: true }];
  preview._bandColorMaterials = null;
  preview._blockIndicators = null;
  preview._stageGround = null;
  preview._stageBlocksScanned = false;
  preview._stageBounds = null;
  preview._stageCenter = { x: 0, y: 0, z: 0 };
  preview._disposeStageBlocks = () => {};
  app.preview = preview;
  app.zones = new ZoneManager(app);
  app.router = new MessageRouter(app);

  preview.rebuildZoneLayout();
  return { app, preview };
}

test('hydrates a single initialized bitmap zone after its layout already exists', async () => {
  const { app, preview } = await createHarness([
    { name: 'main', stage: 'show', origin: { x: 0, y: 0, z: 0 }, size: { x: 8, y: 6, z: 1 } },
  ]);

  app.router.handleMessage({
    type: 'vj_state',
    patterns: [],
    zone_patterns: { main: { pattern: 'bmp_spectrum', render_mode: 'bitmap' } },
    bitmap_zones: { main: { initialized: true, width: 4, height: 3 } },
  });

  assert.equal(preview.bitmapPreview.isActive('main'), true);
  assert.equal(preview.bitmapPreview.zones.main.mesh.visible, true);
  assert.equal(preview.bitmapPreview.zones.main.width, 4);
  assert.equal(preview.bitmapPreview.zones.main.height, 3);
  assert.equal(preview.bitmapPreview.zones.main.pattern, 'bmp_spectrum');
  assert.equal(preview._blocks.every(({ visible }) => !visible), true);

  app.state.entities = [{ x: 0.5, y: 0.5, z: 0.5, scale: 1 }];
  preview._updateSingleZone([1, 1, 1, 1, 1]);
  assert.equal(preview._blocks.every(({ visible }) => !visible), true);
});

test('hydrates independent multi-zone bitmap dimensions and patterns', async () => {
  const { app, preview } = await createHarness([
    { name: 'left', stage: 'show', origin: { x: -8, y: 0, z: 0 }, size: { x: 6, y: 4, z: 1 } },
    { name: 'right', stage: 'show', origin: { x: 8, y: 0, z: 0 }, size: { x: 6, y: 4, z: 1 } },
  ]);

  app.router.handleMessage({
    type: 'vj_state',
    patterns: [],
    zone_patterns: {
      left: { pattern: 'bmp_waveform', render_mode: 'bitmap' },
      right: { pattern: 'bmp_vumeter', render_mode: 'bitmap' },
    },
    bitmap_zones: {
      left: { initialized: true, width: 3, height: 2 },
      right: { initialized: true, width: 5, height: 4 },
    },
  });

  assert.deepEqual(
    Object.fromEntries(Object.entries(preview.bitmapPreview.zones).map(([name, zone]) => [
      name,
      { width: zone.width, height: zone.height, pattern: zone.pattern, visible: zone.mesh.visible },
    ])),
    {
      left: { width: 3, height: 2, pattern: 'bmp_waveform', visible: true },
      right: { width: 5, height: 4, pattern: 'bmp_vumeter', visible: true },
    },
  );
});

test('applies pattern changes both before and after bitmap plane activation', async () => {
  const { app, preview } = await createHarness([
    { name: 'main', stage: 'show', origin: { x: 0, y: 0, z: 0 }, size: { x: 8, y: 6, z: 1 } },
  ]);

  app.router.handleMessage({ type: 'bitmap_pattern_set', zone: 'main', pattern: 'bmp_waveform' });
  app.router.handleMessage({ type: 'bitmap_initialized', zone: 'main', width: 4, height: 3 });
  preview.rebuildZoneLayout();
  assert.equal(preview.bitmapPreview.zones.main.pattern, 'bmp_waveform');

  app.router.handleMessage({ type: 'bitmap_pattern_set', zone: 'main', pattern: 'bmp_vumeter' });
  assert.equal(preview.bitmapPreview.zones.main.pattern, 'bmp_vumeter');
});

test('routes exact frames without preventing particle updates in the preview loop', async () => {
  const { app, preview } = await createHarness([
    { name: 'main', stage: 'show', origin: { x: 0, y: 0, z: 0 }, size: { x: 8, y: 6, z: 1 } },
  ]);
  app.router.handleMessage({ type: 'bitmap_initialized', zone: 'main', width: 2, height: 1 });
  preview.rebuildZoneLayout();

  app.router.handleMessage({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xffff0000, 0xff00ff00],
  });

  let particleUpdates = 0;
  preview._particleSystem = { update: () => { particleUpdates += 1; } };
  preview._blocks = [];
  preview._renderer = { render: () => {} };
  preview._camera = {};
  preview._lastFrameTime = 0;
  preview._frameCount = 0;
  preview._lastFpsUpdate = 0;
  preview._animationId = null;
  globalThis.requestAnimationFrame = () => 1;
  globalThis.performance = { now: () => 16 };

  preview._animate();

  assert.equal(particleUpdates, 1);
  assert.deepEqual(
    [...preview.bitmapPreview.zones.main.ctx.lastImageData.data],
    [255, 0, 0, 255, 0, 255, 0, 255],
  );
});

test('hydrates state and an exact frame received before zone layout', async () => {
  const { app, preview } = await createHarness([]);
  app.router.handleMessage({
    type: 'vj_state',
    patterns: [],
    zone_patterns: { main: { pattern: 'bmp_plasma', render_mode: 'bitmap' } },
    bitmap_zones: { main: { initialized: true, width: 2, height: 1 } },
  });
  app.router.handleMessage({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xffff0000, 0xff00ff00],
  });

  assert.equal(preview.bitmapPreview.isActive('main'), false);
  const retainedStorage = preview.bitmapPreview.frameBuffers.main;

  app.router.handleMessage({
    type: 'zones',
    zones: [{
      name: 'main',
      stage: 'show',
      origin: { x: 0, y: 0, z: 0 },
      size: { x: 8, y: 6, z: 1 },
    }],
  });
  preview.bitmapPreview.update(0.016, { bands: [0, 0, 0, 0, 0] });

  assert.equal(preview.bitmapPreview.isActive('main'), true);
  assert.equal(preview.bitmapPreview.frameBuffers.main, retainedStorage);
  assert.deepEqual(
    [...preview.bitmapPreview.zones.main.ctx.lastImageData.data],
    [255, 0, 0, 255, 0, 255, 0, 255],
  );
});

test('preserves exact frame storage across a transient layout rebuild', async () => {
  const { app, preview } = await createHarness([
    { name: 'main', stage: 'show', origin: { x: 0, y: 0, z: 0 }, size: { x: 8, y: 6, z: 1 } },
  ]);
  app.router.handleMessage({
    type: 'vj_state',
    patterns: [],
    zone_patterns: { main: { pattern: 'bmp_plasma', render_mode: 'bitmap' } },
    bitmap_zones: { main: { initialized: true, width: 2, height: 1 } },
  });
  app.router.handleMessage({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xff112233, 0xff445566],
  });
  const frame = preview.bitmapPreview.exactFrames.main;
  const storage = preview.bitmapPreview.frameBuffers.main;

  preview.rebuildZoneLayout();
  preview.bitmapPreview.update(0.016, { bands: [0, 0, 0, 0, 0] });

  assert.equal(preview.bitmapPreview.exactFrames.main, frame);
  assert.equal(preview.bitmapPreview.frameBuffers.main, storage);
  assert.deepEqual(
    [...preview.bitmapPreview.zones.main.ctx.lastImageData.data],
    [17, 34, 51, 255, 68, 85, 102, 255],
  );
});

test('purges GPU and retained frame state when a zone leaves bitmap mode', async () => {
  const { app, preview } = await createHarness([
    { name: 'main', stage: 'show', origin: { x: 0, y: 0, z: 0 }, size: { x: 8, y: 6, z: 1 } },
  ]);
  app.router.handleMessage({
    type: 'vj_state',
    patterns: [],
    zone_patterns: { main: { pattern: 'bmp_plasma', render_mode: 'bitmap' } },
    bitmap_zones: { main: { initialized: true, width: 2, height: 1 } },
  });
  app.router.handleMessage({ type: 'bitmap_pattern_set', zone: 'main', pattern: 'bmp_waveform' });
  app.router.handleMessage({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xff112233, 0xff445566],
  });
  preview._pendingBitmapFrames = new Map([['main', { zone: 'main' }]]);
  const plane = preview.bitmapPreview.zones.main;

  app.state.zonePatterns = { main: { pattern: 'spectrum', render_mode: 'block' } };
  app.zones.syncBitmapStateFromZonePatterns();

  assert.equal(plane.mesh.geometry.disposed, true);
  assert.equal(plane.mesh.material.disposed, true);
  assert.equal(plane.texture.disposed, true);
  assert.equal(preview.bitmapPreview.zones.main, undefined);
  assert.equal(preview.bitmapPreview.exactFrames.main, undefined);
  assert.equal(preview.bitmapPreview.frameBuffers.main, undefined);
  assert.equal(preview.bitmapPreview.pendingPatterns.main, undefined);
  assert.equal(preview._pendingBitmapFrames.has('main'), false);
});

test('purges retained frame state when an initialized zone disappears', async () => {
  const { app, preview } = await createHarness([
    { name: 'main', stage: 'show', origin: { x: 0, y: 0, z: 0 }, size: { x: 8, y: 6, z: 1 } },
  ]);
  app.router.handleMessage({
    type: 'vj_state',
    patterns: [],
    zone_patterns: { main: { pattern: 'bmp_plasma', render_mode: 'bitmap' } },
    bitmap_zones: { main: { initialized: true, width: 2, height: 1 } },
  });
  app.router.handleMessage({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xff112233, 0xff445566],
  });
  preview._pendingBitmapFrames = new Map([['main', { zone: 'main' }]]);

  app.router.handleMessage({ type: 'zones', zones: [] });

  assert.equal(preview.bitmapPreview.exactFrames.main, undefined);
  assert.equal(preview.bitmapPreview.frameBuffers.main, undefined);
  assert.equal(preview._pendingBitmapFrames.has('main'), false);
});

test('purges a queued frame when bitmap mode exits before preview initialization', async () => {
  const { app, preview } = await createHarness([
    { name: 'main', stage: 'show', origin: { x: 0, y: 0, z: 0 }, size: { x: 8, y: 6, z: 1 } },
  ]);
  preview._bitmapPreview = null;
  preview._initialized = false;
  preview._pendingBitmapFrames = new Map([['main', { zone: 'main' }]]);
  app.state.bitmap.initializedZones.add('main');
  app.state.zonePatterns = { main: { pattern: 'spectrum', render_mode: 'block' } };

  app.zones.syncBitmapStateFromZonePatterns();

  assert.equal(preview._pendingBitmapFrames.has('main'), false);
});

test('purges a pre-layout exact frame after an authoritative empty zone inventory', async () => {
  const { app, preview } = await createHarness([]);
  app.router.handleMessage({
    type: 'vj_state',
    patterns: [],
    zone_patterns: { main: { pattern: 'bmp_plasma', render_mode: 'bitmap' } },
    bitmap_zones: { main: { initialized: true, width: 2, height: 1 } },
  });
  app.router.handleMessage({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xff112233, 0xff445566],
  });

  app.router.handleMessage({ type: 'zones', zones: [] });

  assert.equal(preview.bitmapPreview.exactFrames.main, undefined);
  assert.equal(preview.bitmapPreview.frameBuffers.main, undefined);
  assert.equal(app.state.bitmap.initializedZones.has('main'), false);
});
