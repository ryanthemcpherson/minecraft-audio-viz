import assert from 'node:assert/strict';
import test from 'node:test';

import { resolveBrowserWebSocketUrl } from './browser-endpoint.js';

const httpsLocation = {
    protocol: 'https:',
    hostname: '203.0.113.9',
    host: '203.0.113.9:8080',
};

test('unified runtime config selects same-origin WSS path', () => {
    const url = resolveBrowserWebSocketUrl(
        httpsLocation,
        new URLSearchParams('port=18766'),
        { browserWebSocketMode: 'same-origin', browserWebSocketPath: '/ws' },
    );

    assert.equal(url, 'wss://203.0.113.9:8080/ws');
});

test('same-origin mode preserves an IPv6 host and valid path', () => {
    const url = resolveBrowserWebSocketUrl(
        { protocol: 'https:', hostname: '[2001:db8::1]', host: '[2001:db8::1]:8443' },
        new URLSearchParams(),
        { browserWebSocketMode: 'same-origin', browserWebSocketPath: '/ws/v1' },
    );

    assert.equal(url, 'wss://[2001:db8::1]:8443/ws/v1');
});

test('legacy mode preserves the explicit port override', () => {
    const url = resolveBrowserWebSocketUrl(
        { protocol: 'http:', hostname: 'localhost', host: 'localhost:8080' },
        new URLSearchParams('port=18766'),
        { browserWebSocketMode: 'legacy', browserWebSocketPort: 8766 },
    );

    assert.equal(url, 'ws://localhost:18766/');
});

for (const invalidPath of ['/ws?port=1', '/ws#fragment', '/ws\\socket', 'wss://example.test/ws', 'ws']) {
    test(`invalid same-origin path ${JSON.stringify(invalidPath)} falls back to legacy config`, () => {
        const url = resolveBrowserWebSocketUrl(
            { protocol: 'http:', hostname: 'localhost', host: 'localhost:8080' },
            new URLSearchParams(),
            { browserWebSocketMode: 'same-origin', browserWebSocketPath: invalidPath, browserWebSocketPort: 9321 },
        );

        assert.equal(url, 'ws://localhost:9321/');
    });
}

test('invalid runtime config falls back to the checked-in legacy port', () => {
    const url = resolveBrowserWebSocketUrl(
        { protocol: 'http:', hostname: 'localhost', host: 'localhost:8080' },
        new URLSearchParams('port=invalid'),
        { browserWebSocketMode: 'same-origin', browserWebSocketPath: '/ws?invalid', browserWebSocketPort: 'not-a-port' },
    );

    assert.equal(url, 'ws://localhost:8766/');
});
