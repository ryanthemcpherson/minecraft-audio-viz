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

  send() {}

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

test('notifies the login gate when authenticated without storing credentials', () => {
  const originalDocument = globalThis.document;
  const originalLocalStorage = globalThis.localStorage;
  const originalPrompt = globalThis.prompt;
  const calls = [];
  const root = { classList: { add() {}, remove() {} } };
  globalThis.document = { getElementById: () => root };
  globalThis.localStorage = {
    getItem() { throw new Error('credential storage must not be read'); },
    setItem() { throw new Error('credential storage must not be written'); },
  };
  globalThis.prompt = () => { throw new Error('native credential prompt must not open'); };

  try {
    const app = {
      ws: new FakeWebSocket(),
      state: {
        connected: false,
        zone: { name: 'main' },
        bitmap: { dataFetched: false },
      },
      ui: {
        setConnectionStatus: (...args) => calls.push(['status', ...args]),
        showToast: () => {},
      },
      bitmap: { fetchBitmapData: () => {} },
      preview: {
        previewInitialized: true,
        previewFailed: false,
        previewStripCollapsed: false,
      },
      onAuthenticated: () => calls.push(['authenticated']),
    };

    setupConnectionLifecycle(app);
    app.ws.emit('connected');

    assert.equal(app.state.connected, true);
    assert.deepEqual(calls.slice(0, 2), [
      ['authenticated'],
      ['status', 'connected'],
    ]);
  } finally {
    globalThis.document = originalDocument;
    globalThis.localStorage = originalLocalStorage;
    globalThis.prompt = originalPrompt;
  }
});

test('returns authentication failures to the login gate without prompting or persistence', () => {
  const originalLocalStorage = globalThis.localStorage;
  const originalPrompt = globalThis.prompt;
  const calls = [];
  globalThis.localStorage = {
    getItem() { throw new Error('credential storage must not be read'); },
    setItem() { throw new Error('credential storage must not be written'); },
  };
  globalThis.prompt = () => { throw new Error('native credential prompt must not open'); };

  try {
    const app = {
      ws: new FakeWebSocket(),
      ui: { setConnectionStatus: (...args) => calls.push(['status', ...args]) },
      onAuthFailed: (message) => calls.push(['auth-failed', message]),
    };

    setupConnectionLifecycle(app);
    assert.doesNotThrow(() => app.ws.emit('auth_failed', {
      error: 'Invalid username or password',
    }));
    assert.deepEqual(calls, [
      ['status', 'disconnected'],
      ['auth-failed', 'Invalid username or password'],
    ]);
  } finally {
    globalThis.localStorage = originalLocalStorage;
    globalThis.prompt = originalPrompt;
  }
});

test('returns an authentication challenge to the login gate', () => {
  const calls = [];
  const app = {
    ws: new FakeWebSocket(),
    onAuthRequired: () => calls.push('auth-required'),
  };

  setupConnectionLifecycle(app);
  app.ws.emit('auth_required');

  assert.deepEqual(calls, ['auth-required']);
});
