import assert from 'node:assert/strict';
import test from 'node:test';

import { setupAdminLogin } from '../js/modules/AdminLoginController.js';

function fakeElement() {
  const listeners = new Map();
  const attributes = new Map();
  return {
    hidden: false,
    value: '',
    textContent: '',
    disabled: false,
    focused: false,
    addEventListener(type, listener) { listeners.set(type, listener); },
    emit(type) { listeners.get(type)?.({ preventDefault() {} }); },
    setAttribute(name, value) { attributes.set(name, String(value)); },
    removeAttribute(name) { attributes.delete(name); },
    getAttribute(name) { return attributes.get(name); },
    focus() { this.focused = true; },
  };
}

test('starts negotiation immediately and opens only after auth success', () => {
  const elements = new Map([
    ['auth-gate', fakeElement()],
    ['auth-form', fakeElement()],
    ['auth-error', fakeElement()],
    ['auth-username', fakeElement()],
    ['auth-password', fakeElement()],
    ['auth-submit', fakeElement()],
    ['app', fakeElement()],
    ['btn-logout', fakeElement()],
  ]);
  const calls = [];
  let application = null;
  let callbacks = null;
  const websocket = {
    setCredentials: (...args) => calls.push(['credentials', ...args]),
    manualReconnect: () => calls.push(['reconnect']),
    disconnect: () => calls.push(['disconnect']),
  };

  setupAdminLogin({
    root: { getElementById: (id) => elements.get(id) },
    createApp(options) {
      callbacks = options;
      calls.push(['create', options.username, options.password]);
      return { ws: websocket };
    },
    getApp: () => application,
    setApp: (next) => { application = next; },
  });

  assert.deepEqual(calls, [['create', '', '']]);
  assert.equal(elements.get('auth-gate').hidden, false);
  assert.equal(elements.get('app').hidden, true);

  callbacks.onAuthenticated();
  assert.equal(elements.get('auth-gate').hidden, true);
  assert.equal(elements.get('app').hidden, false);

  callbacks.onAuthRequired();
  assert.equal(elements.get('auth-gate').hidden, false);
  assert.equal(elements.get('app').hidden, true);
});

test('credential submit starts a fresh session and logout clears it', () => {
  const elements = new Map([
    ['auth-gate', fakeElement()],
    ['auth-form', fakeElement()],
    ['auth-error', fakeElement()],
    ['auth-username', fakeElement()],
    ['auth-password', fakeElement()],
    ['auth-submit', fakeElement()],
    ['app', fakeElement()],
    ['btn-logout', fakeElement()],
  ]);
  const calls = [];
  const websocket = {
    setCredentials: (...args) => calls.push(['credentials', ...args]),
    manualReconnect: () => calls.push(['reconnect']),
    disconnect: () => calls.push(['disconnect']),
  };
  let application = null;

  setupAdminLogin({
    root: { getElementById: (id) => elements.get(id) },
    createApp: () => ({ ws: websocket }),
    getApp: () => application,
    setApp: (next) => { application = next; },
  });

  elements.get('auth-username').value = 'operator';
  elements.get('auth-password').value = 'secret';
  elements.get('auth-form').emit('submit');
  elements.get('btn-logout').emit('click');

  assert.deepEqual(calls, [
    ['credentials', 'operator', 'secret'],
    ['reconnect'],
    ['disconnect'],
  ]);
  assert.equal(elements.get('auth-username').value, '');
  assert.equal(elements.get('app').hidden, true);
});
