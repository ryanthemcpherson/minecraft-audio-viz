import assert from 'node:assert/strict';
import test from 'node:test';

import {
    PreviewAuthSession,
    buildAuthMessage,
    websocketScheme,
} from './PreviewAuthSession.js';

test('preview selects secure WebSockets and binds exact operator identity', () => {
    assert.equal(websocketScheme('https:'), 'wss');
    assert.equal(websocketScheme('http:'), 'ws');
    assert.deepEqual(buildAuthMessage('operator', 'secret'), {
        type: 'vj_auth',
        username: 'operator',
        password: 'secret',
    });
});

test('no-auth negotiation opens the preview without dummy credentials', () => {
    const calls = [];
    const session = new PreviewAuthSession();

    session.onOpen((message) => calls.push(['send', message]));
    const handled = session.handleProtocolMessage(
        { type: 'auth_success' },
        { onAuthenticated: () => calls.push(['authenticated']) },
    );

    assert.equal(handled, true);
    assert.equal(session.canSendControls(), true);
    assert.deepEqual(calls, [['authenticated']]);
});

test('auth-required negotiation leaves the preview gated without credentials', () => {
    const calls = [];
    const session = new PreviewAuthSession();

    const handled = session.handleProtocolMessage(
        { type: 'auth_required' },
        { onAuthRequired: () => calls.push('auth-required') },
    );

    assert.equal(handled, true);
    assert.equal(session.canSendControls(), false);
    assert.equal(session.shouldReconnect(), false);
    assert.deepEqual(calls, ['auth-required']);
});

test('authenticated startup sends credentials once across negotiation messages', () => {
    const sent = [];
    const session = new PreviewAuthSession();
    session.setCredentials('operator', 'secret');

    session.onOpen((message) => sent.push(message));
    session.handleProtocolMessage(
        { type: 'auth_required' },
        { send: (message) => sent.push(message) },
    );

    assert.deepEqual(sent, [{
        type: 'vj_auth',
        username: 'operator',
        password: 'secret',
    }]);
});

test('auth failure clears pending preview controls and suppresses reconnect', () => {
    const calls = [];
    const session = new PreviewAuthSession({
        clearPending: () => calls.push('clear-pending'),
    });
    session.setCredentials('operator', 'wrong');
    session.onOpen(() => {});

    session.handleProtocolMessage(
        { type: 'auth_error', error: 'Invalid username or password' },
        { onAuthFailed: () => calls.push('auth-failed') },
    );

    assert.equal(session.canSendControls(), false);
    assert.equal(session.shouldReconnect(), false);
    assert.deepEqual(calls, ['clear-pending', 'clear-pending', 'auth-failed']);
});

test('logout ends the preview session and clears pending controls', () => {
    let clearCount = 0;
    const session = new PreviewAuthSession({ clearPending: () => { clearCount += 1; } });
    session.handleProtocolMessage({ type: 'auth_success' });

    session.logout();

    assert.equal(session.canSendControls(), false);
    assert.equal(session.shouldReconnect(), false);
    assert.equal(clearCount, 1);
});
