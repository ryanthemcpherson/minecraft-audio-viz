import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const appUrl = new URL('./app.js', import.meta.url);
const authModule = await import('./PreviewAuthSession.js');
let moduleSequence = 0;

class FakeClassList {
    constructor() {
        this.values = new Set();
    }

    add(value) {
        this.values.add(value);
    }

    remove(value) {
        this.values.delete(value);
    }

    contains(value) {
        return this.values.has(value);
    }
}

class FakeElement {
    constructor() {
        this.classList = new FakeClassList();
        this.listeners = new Map();
        this.style = {};
        this.hidden = true;
        this.disabled = false;
        this.textContent = '';
        this.value = '';
    }

    addEventListener(type, callback) {
        this.listeners.set(type, callback);
    }

    dispatch(type) {
        this.listeners.get(type)?.({ preventDefault() {} });
    }

    focus() {}

    setAttribute() {}

    removeAttribute() {}
}

async function createConnectionHarness() {
    const originalGlobals = {
        document: globalThis.document,
        setTimeout: globalThis.setTimeout,
        WebSocket: globalThis.WebSocket,
        window: globalThis.window,
    };
    const sockets = [];
    const scheduledTimeouts = [];

    class FakeWebSocket {
        static CONNECTING = 0;
        static OPEN = 1;
        static CLOSING = 2;
        static CLOSED = 3;

        constructor(url) {
            this.url = url;
            this.readyState = FakeWebSocket.CONNECTING;
            this.sent = [];
            this.closeCalls = [];
            sockets.push(this);
        }

        send(message) {
            this.sent.push(JSON.parse(message));
        }

        close(code, reason) {
            this.closeCalls.push({ code, reason });
            this.readyState = FakeWebSocket.CLOSED;
        }

        open() {
            this.readyState = FakeWebSocket.OPEN;
            this.onopen?.();
        }

        message(data) {
            this.onmessage?.({ data: JSON.stringify(data) });
        }

        error() {
            this.onerror?.(new Error('socket failure'));
        }

        closed() {
            this.readyState = FakeWebSocket.CLOSED;
            this.onclose?.({ code: 1006 });
        }
    }

    const statusText = new FakeElement();
    const status = new FakeElement();
    status.querySelector = (selector) => selector === '.status-text' ? statusText : null;

    const elements = new Map([
        ['connection-status', status],
        ['auth-gate', new FakeElement()],
        ['preview-app', new FakeElement()],
        ['auth-error', new FakeElement()],
        ['auth-submit', new FakeElement()],
        ['auth-username', new FakeElement()],
        ['auth-password', new FakeElement()],
        ['auth-form', new FakeElement()],
        ['btn-logout', new FakeElement()],
    ]);

    globalThis.WebSocket = FakeWebSocket;
    globalThis.window = {
        location: { hostname: 'localhost', protocol: 'http:', search: '' },
        addEventListener() {},
    };
    globalThis.document = {
        addEventListener() {},
        getElementById(id) {
            return elements.get(id) ?? null;
        },
    };
    globalThis.setTimeout = (callback, delay) => {
        scheduledTimeouts.push({ callback, delay });
        return scheduledTimeouts.length;
    };
    globalThis.__previewAuthModule = authModule;

    const source = await readFile(appUrl, 'utf8');
    const instrumentedSource = source.replace(
        "import { PreviewAuthSession, websocketScheme } from './PreviewAuthSession.js';",
        'const { PreviewAuthSession, websocketScheme } = globalThis.__previewAuthModule;',
    ) + `\nexport { connectWebSocket, setupPreviewLogin };\n// ${moduleSequence++}`;
    const moduleUrl = `data:text/javascript;base64,${Buffer.from(instrumentedSource).toString('base64')}`;
    const app = await import(moduleUrl);

    return {
        app,
        elements,
        scheduledTimeouts,
        sockets,
        status,
        statusText,
        cleanup() {
            globalThis.document = originalGlobals.document;
            globalThis.setTimeout = originalGlobals.setTimeout;
            globalThis.WebSocket = originalGlobals.WebSocket;
            globalThis.window = originalGlobals.window;
            delete globalThis.__previewAuthModule;
        },
    };
}

test('superseded socket cannot authenticate the replacement connection', async (t) => {
    const harness = await createConnectionHarness();
    t.after(harness.cleanup);

    harness.app.connectWebSocket();
    const firstSocket = harness.sockets[0];
    firstSocket.open();
    harness.app.connectWebSocket();
    const replacementSocket = harness.sockets[1];
    replacementSocket.open();

    firstSocket.message({ type: 'auth_success' });

    assert.equal(harness.statusText.textContent, 'Negotiating access…');
    assert.equal(harness.status.classList.contains('connected'), false);
    assert.deepEqual(replacementSocket.sent, []);
});

test('superseded socket close and error cannot disconnect the replacement', async (t) => {
    const harness = await createConnectionHarness();
    t.after(harness.cleanup);

    harness.app.connectWebSocket();
    const firstSocket = harness.sockets[0];
    firstSocket.open();
    harness.app.connectWebSocket();
    const replacementSocket = harness.sockets[1];
    replacementSocket.open();
    replacementSocket.message({ type: 'auth_success' });

    firstSocket.error();
    firstSocket.closed();

    assert.equal(harness.statusText.textContent, 'Connected');
    assert.equal(harness.status.classList.contains('connected'), true);
    assert.equal(harness.status.classList.contains('error'), false);
    assert.equal(harness.scheduledTimeouts.length, 0);
});

test('superseded auth-required message cannot close the replacement', async (t) => {
    const harness = await createConnectionHarness();
    t.after(harness.cleanup);

    harness.app.connectWebSocket();
    const firstSocket = harness.sockets[0];
    firstSocket.open();
    harness.app.connectWebSocket();
    const replacementSocket = harness.sockets[1];
    replacementSocket.open();

    firstSocket.message({ type: 'auth_required' });

    assert.deepEqual(replacementSocket.closeCalls, []);
    assert.equal(harness.elements.get('auth-gate').hidden, true);
});

test('logged-out socket cannot reopen the preview with a late auth success', async (t) => {
    const harness = await createConnectionHarness();
    t.after(harness.cleanup);
    harness.app.setupPreviewLogin();

    harness.app.connectWebSocket();
    const socket = harness.sockets[0];
    socket.open();
    socket.message({ type: 'auth_success' });
    socket.sent.length = 0;

    harness.elements.get('btn-logout').dispatch('click');
    socket.message({ type: 'auth_success' });

    assert.equal(harness.elements.get('auth-gate').hidden, false);
    assert.equal(harness.status.classList.contains('connected'), false);
    assert.deepEqual(socket.sent, []);
});
