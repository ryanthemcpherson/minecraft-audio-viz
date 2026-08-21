import assert from 'node:assert/strict';
import test from 'node:test';

import { WebSocketService } from './WebSocketService.js';

globalThis.WebSocket ??= class WebSocket {
    static OPEN = 1;
};

function openSocket(sentMessages = []) {
    return {
        readyState: 1,
        closeCalls: [],
        send(message) {
            sentMessages.push(JSON.parse(message));
        },
        close(code, reason) {
            this.closeCalls.push({ code, reason });
        },
    };
}

function serviceWithoutTimers(options = {}) {
    const service = new WebSocketService(options);
    service._startPingInterval = () => {};
    service._stopPingInterval = () => {};
    return service;
}

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

test('connects using the supplied browser endpoint URL', () => {
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
            url: 'wss://panel.example.test/ws',
            username: 'operator',
            password: 'secret-value',
        });

        service.connect();

        assert.equal(requestedUrl, 'wss://panel.example.test/ws');
    } finally {
        globalThis.WebSocket = originalWebSocket;
    }
});

test('opens a no-auth session after explicit server negotiation', () => {
    const sentMessages = [];
    const events = [];
    const service = serviceWithoutTimers();
    service.ws = openSocket(sentMessages);
    service.addEventListener('connected', () => events.push('connected'));

    service._onOpen();
    assert.deepEqual(sentMessages, []);

    service._onMessage({ data: JSON.stringify({ type: 'auth_success' }) });

    assert.deepEqual(events, ['connected']);
    assert.equal(service.isAuthenticated, true);
    assert.deepEqual(sentMessages, [{ type: 'get_state' }]);
});

test('keeps an auth-required session gated until credentials are supplied', () => {
    const events = [];
    const service = serviceWithoutTimers();
    service.ws = openSocket();
    service.addEventListener('auth_required', () => events.push('auth-required'));

    service._onOpen();
    service._onMessage({ data: JSON.stringify({ type: 'auth_required' }) });

    assert.deepEqual(events, ['auth-required']);
    assert.equal(service.isAuthenticated, false);
    assert.equal(service.shouldReconnect, false);
    assert.equal(service.ws.closeCalls.length, 1);
});

test('drops pre-auth commands instead of carrying them into a later session', () => {
    const sentMessages = [];
    const service = serviceWithoutTimers();
    service.ws = openSocket(sentMessages);

    assert.equal(service.send({ type: 'blackout' }), false);
    assert.deepEqual(service.messageQueue, []);

    service._onMessage({ data: JSON.stringify({ type: 'auth_success' }) });
    assert.deepEqual(sentMessages, [{ type: 'get_state' }]);
});

test('auth failure clears pending commands and suppresses reconnect', () => {
    const service = serviceWithoutTimers({ username: 'operator', password: 'wrong' });
    service.ws = openSocket();
    service.messageQueue.push({ type: 'freeze' });
    service._sessionEstablished = true;
    service._awaitingAuth = true;

    service._onMessage({
        data: JSON.stringify({ type: 'auth_error', error: 'Invalid username or password' }),
    });

    assert.deepEqual(service.messageQueue, []);
    assert.equal(service.isAuthenticated, false);
    assert.equal(service._sessionEstablished, false);
    assert.equal(service.shouldReconnect, false);
});

test('logout clears credentials and queued controls', () => {
    const service = serviceWithoutTimers({ username: 'operator', password: 'secret' });
    service.ws = openSocket();
    service.isAuthenticated = true;
    service._sessionEstablished = true;
    service.messageQueue.push({ type: 'set_pattern', pattern: 'spectrum' });

    service.disconnect();

    assert.deepEqual(service.messageQueue, []);
    assert.equal(service.username, '');
    assert.equal(service.password, '');
    assert.equal(service.isAuthenticated, false);
    assert.equal(service._sessionEstablished, false);
});

test('new authentication never flushes controls from an ended session', () => {
    const sentMessages = [];
    const service = serviceWithoutTimers({ username: 'operator', password: 'secret' });
    service.ws = openSocket(sentMessages);
    service.messageQueue.push({ type: 'blackout' });
    service._sessionEstablished = false;
    service._awaitingAuth = true;

    service._onMessage({ data: JSON.stringify({ type: 'auth_success' }) });

    assert.deepEqual(sentMessages, [{ type: 'get_state' }]);
    assert.deepEqual(service.messageQueue, []);
});

test('same-session reconnect flushes only controls queued during that reconnect', () => {
    const sentMessages = [];
    const service = serviceWithoutTimers({ username: 'operator', password: 'secret' });
    service.ws = openSocket(sentMessages);
    service._sessionEstablished = true;
    service.messageQueue.push({ type: 'set_pattern', pattern: 'rings' });
    service._awaitingAuth = true;

    service._onMessage({ data: JSON.stringify({ type: 'auth_success' }) });

    assert.deepEqual(sentMessages, [
        { type: 'get_state' },
        { type: 'set_pattern', pattern: 'rings' },
    ]);
});

test('immediate safety sends never enter the reconnect queue', () => {
    const sentMessages = [];
    const service = serviceWithoutTimers();
    service.ws = openSocket(sentMessages);
    service._sessionEstablished = true;
    service.shouldReconnect = true;

    assert.equal(service.sendImmediate({ type: 'set_blackout', enabled: true }), false);
    assert.deepEqual(service.messageQueue, []);

    service.isAuthenticated = true;
    assert.equal(service.sendImmediate({ type: 'set_blackout', enabled: true }), true);
    assert.deepEqual(sentMessages, [{ type: 'set_blackout', enabled: true }]);
});

test('immediate safety send reports synchronous socket delivery failure', () => {
    const service = serviceWithoutTimers();
    service.isAuthenticated = true;
    service.ws = {
        readyState: WebSocket.OPEN,
        send() {
            throw new Error('socket closed during send');
        },
    };

    assert.equal(service.sendImmediate({ type: 'set_freeze', enabled: true }), false);
    assert.deepEqual(service.messageQueue, []);
});

test('superseded socket message close and error callbacks cannot mutate the new session', () => {
    const originalWebSocket = globalThis.WebSocket;
    const sockets = [];
    const events = [];
    let reconnectSchedules = 0;

    class FakeWebSocket {
        static OPEN = 1;

        constructor() {
            this.readyState = 0;
            this.sent = [];
            sockets.push(this);
        }

        send(message) {
            this.sent.push(JSON.parse(message));
        }

        close() {}
    }

    globalThis.WebSocket = FakeWebSocket;
    try {
        const service = serviceWithoutTimers();
        service._scheduleReconnect = () => { reconnectSchedules += 1; };
        service.addEventListener('connected', () => events.push('connected'));
        service.addEventListener('disconnected', () => events.push('disconnected'));
        service.addEventListener('error', () => events.push('error'));
        service.addEventListener('message', (event) => events.push(event.detail.type));

        service.connect();
        const superseded = sockets[0];
        superseded.readyState = 3;
        service.isConnecting = false;
        service.connect();
        const current = sockets[1];

        superseded.onmessage({ data: JSON.stringify({ type: 'auth_success' }) });
        superseded.onmessage({ data: JSON.stringify({ type: 'vj_state' }) });
        superseded.onerror(new Error('late error'));
        superseded.onclose({ code: 1006, reason: 'late close' });

        assert.deepEqual(events, []);
        assert.equal(reconnectSchedules, 0);
        assert.equal(service.ws, current);
        assert.equal(service.isConnecting, true);

        current.readyState = FakeWebSocket.OPEN;
        current.onopen();
        current.onmessage({ data: JSON.stringify({ type: 'auth_success' }) });
        assert.deepEqual(events, ['connected']);
        assert.equal(service.isAuthenticated, true);
    } finally {
        globalThis.WebSocket = originalWebSocket;
    }
});
