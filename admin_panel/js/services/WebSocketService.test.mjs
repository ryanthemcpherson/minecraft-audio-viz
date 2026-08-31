import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { WebSocketService } from './WebSocketService.js';

const adminAppSource = await readFile(new URL('../admin-app.js', import.meta.url), 'utf8');

test('admin app consumes the server-provided legacy port when present', () => {
    assert.match(adminAppSource, /__MCAV_LEGACY_WS_PORT__/);
});

test('sends an exact username and password authentication message', () => {
    const sentMessages = [];
    const service = new WebSocketService({
        username: 'operator',
        password: 'secret-value',
        vjPassword: 'secret-value',
    });
    service.ws = {
        send(message) {
            sentMessages.push(JSON.parse(message));
        },
    };

    service._onOpen();

    assert.deepEqual(sentMessages, [{
        type: 'vj_auth',
        username: 'operator',
        password: 'secret-value',
    }]);
});

test('uses the same-origin admin route when served over HTTPS', () => {
    const originalWebSocket = globalThis.WebSocket;
    let requestedUrl = '';

    class FakeWebSocket {
        static OPEN = 1;

        constructor(url) {
            requestedUrl = url;
            this.readyState = 0;
        }
    }

    globalThis.WebSocket = FakeWebSocket;
    try {
        const service = new WebSocketService({
            host: 'panel.example.test',
            pageHost: 'panel.example.test:8443',
            pageProtocol: 'https:',
            username: 'operator',
            password: 'secret-value',
        });

        service.connect();

        assert.equal(requestedUrl, 'wss://panel.example.test:8443/ws/admin');
    } finally {
        globalThis.WebSocket = originalWebSocket;
    }
});

test('preserves the explicit legacy WebSocket port override', () => {
    const originalWebSocket = globalThis.WebSocket;
    let requestedUrl = '';

    class FakeWebSocket {
        static OPEN = 1;

        constructor(url) {
            requestedUrl = url;
            this.readyState = 0;
        }
    }

    globalThis.WebSocket = FakeWebSocket;
    try {
        const service = new WebSocketService({
            host: 'panel.example.test',
            port: 8766,
            pageProtocol: 'https:',
        });

        service.connect();

        assert.equal(requestedUrl, 'wss://panel.example.test:8766');
    } finally {
        globalThis.WebSocket = originalWebSocket;
    }
});
