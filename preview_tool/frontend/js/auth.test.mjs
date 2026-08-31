import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('./app.js', import.meta.url), 'utf8');

test('preview never reads VJ credentials from URLs or browser storage', () => {
    assert.doesNotMatch(source, /vj_password|mcav_vj_password|localStorage\.setItem/);
});

test('preview authenticates with an in-memory username and password', () => {
    assert.match(source, /type:\s*['"]vj_auth['"][\s\S]*username[\s\S]*password/);
});

test('preview selects secure WebSockets on HTTPS pages', () => {
    assert.match(source, /location\.protocol\s*===\s*['"]https:['"][\s\S]*['"]wss['"]/);
});

test('preview uses the same-origin preview route by default', () => {
    assert.match(source, /location\.host[\s\S]*\/ws\/preview/);
    assert.doesNotMatch(source, /wsPort:[^\n]*\|\|\s*8766/);
});

test('preview consumes the server-provided legacy port when present', () => {
    assert.match(source, /__MCAV_LEGACY_WS_PORT__/);
});

test('preview responds to server heartbeats', () => {
    assert.match(source, /data\.type\s*===\s*['"]ping['"][\s\S]*type:\s*['"]pong['"]/);
});
