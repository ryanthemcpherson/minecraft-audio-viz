import assert from 'node:assert/strict';
import test from 'node:test';

import { WebSocketService } from './WebSocketService.js';

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

test('uses wss when the control panel is served over HTTPS', () => {
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
            username: 'operator',
            password: 'secret-value',
        });

        service.connect();

        assert.equal(requestedUrl, 'wss://panel.example.test:8766');
    } finally {
        globalThis.WebSocket = originalWebSocket;
    }
});
