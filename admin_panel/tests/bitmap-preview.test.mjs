import assert from 'node:assert/strict';
import test from 'node:test';
import {
  createZoneGroup,
  loadBitmapPreview,
} from './helpers/bitmap-preview-harness.mjs';

test('keeps a pattern selected before activation and renders the zone visibly', async () => {
  const { BitmapPreview } = await loadBitmapPreview();
  const preview = new BitmapPreview();

  preview.setPattern('main', 'bmp_waveform');
  preview.activate('main', 4, 2, 'bmp_plasma', createZoneGroup());
  preview.setZoneVisible('main', true);

  assert.equal(preview.zones.main.pattern, 'bmp_waveform');
  assert.equal(preview.zones.main.mesh.visible, true);
});

test('renders exact pixel_array frames as ARGB pixels while they are fresh', async () => {
  const clock = { now: 1_000 };
  const { BitmapPreview } = await loadBitmapPreview(clock);
  const preview = new BitmapPreview();
  preview.activate('main', 2, 1, 'bmp_plasma', createZoneGroup());
  preview.setZoneVisible('main', true);

  assert.equal(preview.ingestFrame({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xff112233, 0x80445566],
  }), true);
  preview.update(0.016, { bands: [1, 0, 0, 0, 0] });

  assert.deepEqual(
    [...preview.zones.main.ctx.lastImageData.data],
    [17, 34, 51, 255, 68, 85, 102, 128],
  );
  assert.equal(preview.zones.main.ctx.putCalls, 1);

  clock.now = 1_200;
  preview.update(0.016, { bands: [0, 0, 0, 0, 0] });
  assert.equal(preview.zones.main.ctx.putCalls, 1, 'fresh plugin frame remains authoritative');
});

test('decodes base64 little-endian ARGB and falls back after the frame is stale', async () => {
  const clock = { now: 2_000 };
  const { BitmapPreview } = await loadBitmapPreview(clock);
  const preview = new BitmapPreview();
  preview.activate('main', 2, 1, 'bmp_plasma', createZoneGroup());
  preview.setZoneVisible('main', true);

  assert.equal(preview.ingestFrame({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixels: '/wAA/wD/AIA=',
  }), true);
  preview.update(0.016, { bands: [0, 0, 0, 0, 0] });
  assert.deepEqual(
    [...preview.zones.main.ctx.lastImageData.data],
    [0, 0, 255, 255, 0, 255, 0, 128],
  );

  clock.now = 5_000;
  preview.update(0.016, { bands: [0, 0, 0, 0, 0] });
  assert.equal(preview.zones.main.ctx.putCalls, 2, 'stale exact frame yields to simulated pattern');
});

test('isolates malformed exact frames and continues simulated rendering', async () => {
  const { BitmapPreview } = await loadBitmapPreview();
  const preview = new BitmapPreview();
  preview.activate('main', 2, 1, 'bmp_plasma', createZoneGroup());
  preview.setZoneVisible('main', true);

  const malformedFrames = [
    { type: 'bitmap_frame', zone: 'main', width: 0, height: 1, pixel_array: [] },
    { type: 'bitmap_frame', zone: 'main', width: 2, height: 1, pixel_array: [0xff000000] },
    { type: 'bitmap_frame', zone: 'main', width: 2, height: 1, pixels: 'AAAA' },
    { type: 'bitmap_frame', zone: 'main', width: 2, height: 1, pixels: 'not base64!' },
  ];

  for (const frame of malformedFrames) {
    assert.doesNotThrow(() => assert.equal(preview.ingestFrame(frame), false));
  }
  assert.doesNotThrow(() => preview.update(0.016, { bands: [0, 0, 0, 0, 0] }));
  assert.equal(preview.zones.main.ctx.putCalls, 1);
});

test('reuses per-zone frame storage across pixel_array and base64 updates', async () => {
  const { BitmapPreview } = await loadBitmapPreview();
  const preview = new BitmapPreview();

  assert.equal(preview.ingestFrame({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xff112233, 0xff445566],
  }), true);
  const firstFrame = preview.exactFrames.main;
  const firstStorage = preview.frameBuffers.main;
  const firstRgba = firstFrame.rgba;
  const firstScratch = firstStorage.scratchRgba;
  const firstDecodeBytes = firstStorage.decodeBytes;

  assert.equal(preview.ingestFrame({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixels: '/wAA/wD/AIA=',
  }), true);

  assert.equal(preview.exactFrames.main, firstFrame);
  assert.equal(preview.frameBuffers.main, firstStorage);
  assert.equal(preview.exactFrames.main.rgba, firstRgba);
  assert.equal(preview.frameBuffers.main.scratchRgba, firstScratch);
  assert.equal(preview.frameBuffers.main.decodeBytes, firstDecodeBytes);
  assert.deepEqual([...firstRgba], [0, 0, 255, 255, 0, 255, 0, 128]);
});

test('rejects invalid input atomically without changing retained frame storage', async () => {
  const { BitmapPreview } = await loadBitmapPreview();
  const preview = new BitmapPreview();
  preview.ingestFrame({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xff112233, 0xff445566],
  });

  const frame = preview.exactFrames.main;
  const storage = preview.frameBuffers.main;
  const before = [...frame.rgba];

  assert.equal(preview.ingestFrame({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xff000000, 'invalid'],
  }), false);

  assert.equal(preview.exactFrames.main, frame);
  assert.equal(preview.frameBuffers.main, storage);
  assert.deepEqual([...frame.rgba], before);
});

test('reallocates frame storage once when dimensions change', async () => {
  const { BitmapPreview } = await loadBitmapPreview();
  const preview = new BitmapPreview();
  preview.activate('main', 2, 1, 'bmp_plasma', createZoneGroup());
  preview.setZoneVisible('main', true);
  preview.ingestFrame({
    type: 'bitmap_frame',
    zone: 'main',
    width: 2,
    height: 1,
    pixel_array: [0xff000000, 0xff000000],
  });
  const originalStorage = preview.frameBuffers.main;

  preview.ingestFrame({
    type: 'bitmap_frame',
    zone: 'main',
    width: 1,
    height: 2,
    pixel_array: [0xffff0000, 0xff00ff00],
  });
  const resizedStorage = preview.frameBuffers.main;
  preview.update(0.016, { bands: [0, 0, 0, 0, 0] });

  assert.notEqual(resizedStorage, originalStorage);
  assert.deepEqual(
    [...preview.zones.main.ctx.lastImageData.data],
    [255, 0, 0, 255, 0, 255, 0, 255],
  );

  preview.ingestFrame({
    type: 'bitmap_frame',
    zone: 'main',
    width: 1,
    height: 2,
    pixels: 'AAD//wD/AP8=',
  });
  assert.equal(preview.frameBuffers.main, resizedStorage);
});
