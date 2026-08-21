import assert from 'node:assert/strict';
import test from 'node:test';

import {
  filterAndRankPatterns,
  updateRecentIds,
} from '../js/utils/pattern-library.js';
import { PatternManager } from '../js/modules/PatternManager.js';

const patterns = [
  { id: 'spectrum', name: 'Spectrum Bars' },
  { id: 'helix', name: 'DNA Helix' },
  { id: 'aurora', name: 'Aurora' },
];

test('filters by id or name without changing the source collection', () => {
  const original = structuredClone(patterns);

  assert.deepEqual(
    filterAndRankPatterns(patterns, { query: 'dna' }).map(({ id }) => id),
    ['helix'],
  );
  assert.deepEqual(
    filterAndRankPatterns(patterns, { query: 'SPECT' }).map(({ id }) => id),
    ['spectrum'],
  );
  assert.deepEqual(patterns, original);
});

test('ranks favorites before recent items and keeps source order within each tier', () => {
  assert.deepEqual(
    filterAndRankPatterns(patterns, {
      favoriteIds: ['aurora'],
      recentIds: ['helix'],
    }).map(({ id }) => id),
    ['aurora', 'helix', 'spectrum'],
  );
  assert.deepEqual(
    filterAndRankPatterns(patterns, {
      favoriteIds: ['aurora', 'spectrum'],
      recentIds: ['helix'],
    }).map(({ id }) => id),
    ['aurora', 'spectrum', 'helix'],
  );
});

test('recent pattern IDs are unique, newest-first, and capped', () => {
  assert.deepEqual(updateRecentIds(['helix', 'aurora'], 'aurora', 2), ['aurora', 'helix']);
  assert.deepEqual(updateRecentIds(['helix', 'aurora'], 'spectrum', 2), ['spectrum', 'helix']);
  assert.deepEqual(updateRecentIds(['helix', 'helix'], '', 2), ['helix']);
});

function fakeElement(tagName = 'div') {
  const listeners = new Map();
  const classes = new Set();
  return {
    tagName: tagName.toUpperCase(),
    children: [],
    dataset: {},
    attributes: new Map(),
    className: '',
    textContent: '',
    title: '',
    classList: {
      add(...names) { names.forEach((name) => classes.add(name)); },
      remove(...names) { names.forEach((name) => classes.delete(name)); },
      toggle(name, force) {
        const enabled = force ?? !classes.has(name);
        if (enabled) classes.add(name);
        else classes.delete(name);
        return enabled;
      },
      contains(name) { return classes.has(name); },
    },
    get firstChild() { return this.children[0] ?? null; },
    appendChild(child) { this.children.push(child); child.parentElement = this; return child; },
    removeChild(child) { this.children.splice(this.children.indexOf(child), 1); },
    setAttribute(name, value) { this.attributes.set(name, String(value)); },
    getAttribute(name) { return this.attributes.get(name); },
    removeAttribute(name) { this.attributes.delete(name); },
    addEventListener(type, listener) { listeners.set(type, listener); },
    dispatch(type, event = {}) {
      const enriched = {
        preventDefault() { this.defaultPrevented = true; },
        stopPropagation() { this.propagationStopped = true; },
        ...event,
      };
      listeners.get(type)?.(enriched);
      return enriched;
    },
  };
}

function descendants(node) {
  return node.children.flatMap((child) => [child, ...descendants(child)]);
}

function withPatternHarness(storage, callback) {
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document');
  const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const grid = fakeElement();
  const created = [];
  const sent = [];
  const fakeDocument = {
    createElement(tagName) { const element = fakeElement(tagName); created.push(element); return element; },
    querySelectorAll(selector) {
      if (selector === '.pattern-btn') return created.filter((node) => node.dataset.pattern);
      return [];
    },
  };
  Object.defineProperty(globalThis, 'document', { configurable: true, value: fakeDocument });
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage });

  const app = {
    state: {
      patterns: structuredClone(patterns),
      currentPattern: 'spectrum',
      currentPreset: 'auto',
      selectedZones: new Set(),
      zonePatterns: {},
    },
    elements: {
      patternGrid: grid,
      currentPattern: fakeElement(),
      currentPreset: fakeElement(),
      presetButtons: [],
    },
    ws: { send(message) { sent.push(message); } },
  };

  try {
    return callback({ manager: new PatternManager(app), grid, sent });
  } finally {
    if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument);
    else delete globalThis.document;
    if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage);
    else delete globalThis.localStorage;
  }
}

test('search reranks the rendered pattern library without sending show control', () => {
  const values = new Map([
    ['mcav-pattern-favorites', '["aurora"]'],
    ['mcav-pattern-recents', '["helix"]'],
  ]);
  withPatternHarness({
    getItem(key) { return values.get(key) ?? null; },
    setItem(key, value) { values.set(key, value); },
  }, ({ manager, grid, sent }) => {
    manager.renderPatternGrid();
    assert.deepEqual(
      descendants(grid).filter((node) => node.dataset.pattern).map((node) => node.dataset.pattern),
      ['aurora', 'helix', 'spectrum'],
    );

    manager.setSearchQuery('dna');
    assert.deepEqual(
      descendants(grid).filter((node) => node.dataset.pattern).map((node) => node.dataset.pattern),
      ['helix'],
    );
    assert.deepEqual(sent, []);
  });
});

test('favorite buttons are keyboard-operable and never launch a pattern', () => {
  const values = new Map();
  withPatternHarness({
    getItem(key) { return values.get(key) ?? null; },
    setItem(key, value) { values.set(key, value); },
  }, ({ manager, grid, sent }) => {
    manager.renderPatternGrid();
    const favorite = descendants(grid).find((node) => node.dataset.patternFavorite === 'aurora');
    assert.ok(favorite, 'Aurora exposes a favorite control');
    assert.equal(favorite.tagName, 'BUTTON');
    assert.equal(favorite.getAttribute('aria-pressed'), 'false');
    assert.match(favorite.getAttribute('aria-label'), /Aurora/i);

    const event = favorite.dispatch('click');
    assert.equal(event.propagationStopped, true);
    assert.deepEqual(sent, []);
    assert.equal(values.get('mcav-pattern-favorites'), '["aurora"]');

    const refreshedFavorite = descendants(grid)
      .find((node) => node.dataset.patternFavorite === 'aurora');
    assert.equal(refreshedFavorite.getAttribute('aria-pressed'), 'true');
    assert.equal(refreshedFavorite.dispatch('keydown', { key: 'Enter' }).defaultPrevented, undefined);
    assert.deepEqual(sent, []);
  });
});

test('launching a pattern sends one action and records recency', () => {
  const values = new Map();
  withPatternHarness({
    getItem(key) { return values.get(key) ?? null; },
    setItem(key, value) { values.set(key, value); },
  }, ({ manager, grid, sent }) => {
    manager.renderPatternGrid();
    const launch = descendants(grid).find((node) => node.dataset.pattern === 'helix');
    launch.dispatch('click');

    assert.deepEqual(sent, [{ type: 'set_pattern', pattern: 'helix' }]);
    assert.equal(values.get('mcav-pattern-recents'), '["helix"]');
  });
});

test('authoritative pattern highlighting replaces aria-current and can clear it', () => {
  const values = new Map();
  withPatternHarness({
    getItem(key) { return values.get(key) ?? null; },
    setItem(key, value) { values.set(key, value); },
  }, ({ manager, grid }) => {
    manager.renderPatternGrid();
    const launches = descendants(grid).filter((node) => node.dataset.pattern);

    manager.highlightCurrentPattern('aurora');
    assert.deepEqual(
      launches.filter((node) => node.getAttribute('aria-current') === 'true')
        .map((node) => node.dataset.pattern),
      ['aurora'],
    );

    manager.highlightCurrentPattern(null);
    assert.equal(
      launches.filter((node) => node.getAttribute('aria-current') === 'true').length,
      0,
      'no authoritative current pattern leaves no stale aria-current marker',
    );
  });
});

test('pattern controls survive throwing storage methods and a throwing storage getter', () => {
  assert.doesNotThrow(() => withPatternHarness({
    getItem() { throw new Error('blocked read'); },
    setItem() { throw new Error('blocked write'); },
  }, ({ manager }) => {
    manager.renderPatternGrid();
    manager.setSearchQuery('aurora');
    manager.toggleFavorite('aurora');
    manager.setPattern('helix');
  }));

  const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    get() { throw new Error('getter blocked'); },
  });
  try {
    const app = {
      state: { patterns: [], selectedZones: new Set() },
      elements: { patternGrid: fakeElement(), presetButtons: [] },
      ws: { send() {} },
    };
    assert.doesNotThrow(() => new PatternManager(app));
  } finally {
    if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage);
    else delete globalThis.localStorage;
  }
});
