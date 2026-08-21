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

test('routes representative valid protocol messages to their domain manager', () => {
  const calls = [];
  const app = {
    ui: { showToast: () => {} },
    patterns: { handlePatterns: (data) => calls.push(['patterns', data.type]) },
    audio: { handleAudioState: (data) => calls.push(['audio', data.type]) },
    particles: { handleParticleEffects: (data) => calls.push(['particles', data.type]) },
  };
  const router = new MessageRouter(app);

  const routes = [
    { message: { type: 'patterns', patterns: [] }, expected: ['patterns', 'patterns'] },
    { message: { type: 'audio', bands: [] }, expected: ['audio', 'audio'] },
    { message: { type: 'particle_effects', effects: [] }, expected: ['particles', 'particle_effects'] },
  ];

  for (const { message, expected } of routes) {
    router.handleMessage(message);
  }

  assert.deepEqual(calls, routes.map(({ expected }) => expected));
});

test('authoritative VJ snapshots replace blackout and freeze presentation state', () => {
  const calls = [];
  const app = {
    state: { bitmap: {} },
    elements: {},
    ui: { showToast() {} },
    patterns: { handlePatterns() {} },
    dj: { handleDJRoster() {} },
    audio: { updateBandMaterialsSourceHint() {} },
    actions: {
      setBlackoutState: (enabled) => calls.push(['blackout', enabled]),
      setFreezeState: (enabled) => calls.push(['freeze', enabled]),
    },
  };

  new MessageRouter(app).handleMessage({
    type: 'vj_state',
    blackout: true,
    freeze: false,
  });

  assert.deepEqual(calls, [['blackout', true], ['freeze', false]]);
});
