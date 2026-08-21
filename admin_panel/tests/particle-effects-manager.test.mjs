import assert from 'node:assert/strict';
import test from 'node:test';

import { ParticleEffectsManager } from '../js/modules/ParticleEffectsManager.js';

test('manager boundary blocks disconnected particle label and config mutations', () => {
  const sent = [];
  const app = {
    state: {
      connected: false,
      enabledParticleEffects: new Set(),
      zone: { name: 'main' },
    },
    elements: {},
    ws: { send: (message) => sent.push(message) },
  };
  const manager = new ParticleEffectsManager(app);

  assert.equal(manager._toggleParticleEffect('beat_burst'), false);
  assert.equal(manager.sendParticleConfig({ global_intensity: 1.5 }), false);
  assert.deepEqual(sent, []);

  app.state.connected = true;
  assert.equal(manager._toggleParticleEffect('beat_burst'), true);
  assert.equal(manager.sendParticleConfig({ global_intensity: 1.5 }), true);
  assert.deepEqual(sent.map(({ type }) => type), ['set_particle_effect', 'set_particle_config']);
});
