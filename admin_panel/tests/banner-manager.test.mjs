import assert from 'node:assert/strict';
import test from 'node:test';
import { BannerManager } from '../js/modules/BannerManager.js';

class FakeControl extends EventTarget {
  constructor(value = '') {
    super();
    this.value = value;
    this.files = [];
    this.textContent = '';
    this.classList = { add: () => {}, remove: () => {}, toggle: () => {} };
  }
}

test('registers banner save handling once when setup is called repeatedly', () => {
  const originalDocument = globalThis.document;
  const saveButton = new FakeControl();
  const controls = new Map([
    ['btn-save-banner-profile', saveButton],
    ['banner-dj-select', new FakeControl('dj-1')],
    ['banner-text-style', new FakeControl('bold')],
    ['banner-text-color-mode', new FakeControl('frequency')],
    ['banner-text-fixed-color', new FakeControl('#ffffff')],
    ['banner-text-format', new FakeControl('%s')],
    ['banner-grid-width', new FakeControl('24')],
    ['banner-grid-height', new FakeControl('12')],
    ['banner-pulse-intensity', new FakeControl('50')],
  ]);
  globalThis.document = {
    getElementById: (id) => controls.get(id) || null,
    querySelectorAll: () => [],
    querySelector: () => null,
  };

  try {
    const sent = [];
    const manager = new BannerManager({ ws: { send: (message) => sent.push(message) } });
    manager.setupBannerListeners();
    manager.setupBannerListeners();
    saveButton.dispatchEvent(new Event('click'));

    assert.deepEqual(sent.map(({ type }) => type), ['set_banner_profile']);
  } finally {
    globalThis.document = originalDocument;
  }
});
