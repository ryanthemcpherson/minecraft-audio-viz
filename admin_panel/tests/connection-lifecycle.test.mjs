import assert from 'node:assert/strict';
import test from 'node:test';
import { setupConnectionLifecycle } from '../js/modules/ConnectionLifecycle.js';

class FakeWebSocket {
  constructor() {
    this.listeners = new Map();
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  emit(type, detail) {
    this.listeners.get(type)?.({ detail });
  }
}

test('delegates representative WebSocket lifecycle events to managers', () => {
  const calls = [];
  const app = {
    ws: new FakeWebSocket(),
    state: { connected: false, minecraftConnected: true, bitmap: { dataFetched: true } },
    ui: {
      setConnectionStatus: (...args) => calls.push(['status', ...args]),
      updateServiceIndicators: () => calls.push(['indicators']),
    },
    connectCodes: { resetGenerateButton: () => calls.push(['reset-code']) },
    router: { handleMessage: (message) => calls.push(['message', message]) },
  };

  setupConnectionLifecycle(app);
  app.ws.emit('connecting', { attempt: 2, maxAttempts: 10 });
  app.ws.emit('disconnected');
  app.ws.emit('message', { type: 'patterns' });

  assert.equal(app.state.connected, false);
  assert.equal(app.state.minecraftConnected, false);
  assert.equal(app.state.bitmap.dataFetched, false);
  assert.deepEqual(calls, [
    ['status', 'connecting', 2, 10],
    ['status', 'disconnected'],
    ['indicators'],
    ['reset-code'],
    ['message', { type: 'patterns' }],
  ]);
});
