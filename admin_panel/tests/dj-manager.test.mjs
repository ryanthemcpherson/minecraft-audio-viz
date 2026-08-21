import assert from 'node:assert/strict';
import test from 'node:test';
import { DJManager } from '../js/modules/DJManager.js';

test('command bar summary follows authoritative active DJ and sync health state', () => {
  const state = {
    activeDJ: null,
    djRoster: [],
  };
  const elements = {
    djQueue: null,
    activeDjSummary: { dataset: {} },
    activeDjName: { textContent: '' },
    activeDjHealth: { textContent: '' },
  };
  const manager = new DJManager({ state, elements, ws: {} });

  manager.renderDJQueue();
  assert.equal(elements.activeDjName.textContent, 'No active DJ');
  assert.equal(elements.activeDjHealth.textContent, 'Sync idle');
  assert.equal(elements.activeDjSummary.dataset.health, 'idle');

  state.activeDJ = 'dj-1';
  state.djRoster = [{
    dj_id: 'dj-1',
    dj_name: 'Astra',
    clock_sync_age_s: null,
    clock_drift_rate: 0,
    jitter_ms: 0,
  }];
  manager.renderDJQueue();
  assert.equal(elements.activeDjHealth.textContent, 'Sync data pending');
  assert.equal(elements.activeDjSummary.dataset.health, 'pending');

  state.djRoster = [{
    dj_id: 'dj-1',
    dj_name: 'Astra',
    clock_sync_age_s: 12,
    clock_drift_rate: 1.2,
    jitter_ms: 2.4,
  }];
  manager.renderDJQueue();
  assert.equal(elements.activeDjName.textContent, 'Astra');
  assert.equal(elements.activeDjHealth.textContent, 'Sync locked · 2.4ms jitter');
  assert.equal(elements.activeDjSummary.dataset.health, 'locked');

  state.djRoster[0].clock_sync_age_s = 88;
  state.djRoster[0].jitter_ms = 14.2;
  manager.renderDJQueue();
  assert.equal(elements.activeDjHealth.textContent, 'Sync degraded · clock 88s · 14.2ms jitter');
  assert.equal(elements.activeDjSummary.dataset.health, 'degraded');
});
