import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const connectFormSource = readFileSync(
  new URL('../src/components/ConnectForm.tsx', import.meta.url),
  'utf8',
);
const disconnectedViewSource = readFileSync(
  new URL('../src/components/DisconnectedView.tsx', import.meta.url),
  'utf8',
);
const connectionHookSource = readFileSync(
  new URL('../src/hooks/useConnection.ts', import.meta.url),
  'utf8',
);

test('DJ connection profile wires the fingerprint helpers to both Tauri connection commands', () => {
  assert.match(connectFormSource, /tlsFingerprint: string/);
  assert.match(disconnectedViewSource, /tlsFingerprint=/);
  assert.match(connectionHookSource, /useTlsFingerprintProfile\(\)/);
  assert.match(connectionHookSource, /invoke\('connect_with_code', buildCodeConnectionArgs\(/);
  assert.match(connectionHookSource, /invoke\('connect_direct', buildDirectConnectionArgs\(/);
  assert.match(connectionHookSource, /sanitizeConnectionStatus\(event\.payload\)/);
});

test('connection profile labels the fingerprint as SHA-256 and non-secret', () => {
  assert.match(connectFormSource, /Server certificate SHA-256 fingerprint/i);
  assert.match(connectFormSource, /Safe to save; never share the server password/i);
  assert.match(connectFormSource, /64 hexadecimal/i);
  assert.match(connectFormSource, /wss:\/\/IP:25808/i);
});

test('fingerprint validation is exposed to keyboard and assistive technology', () => {
  assert.match(connectFormSource, /htmlFor="tls-fingerprint"/);
  assert.match(connectFormSource, /id="tls-fingerprint"/);
  assert.match(connectFormSource, /aria-describedby=\{[\s\S]*tlsFingerprintFieldState\.describedBy/);
  assert.match(connectFormSource, /aria-invalid=\{tlsFingerprintFieldState\.ariaInvalid\}/);
  assert.match(connectFormSource, /role="alert"/);
  assert.match(connectFormSource, /disabled=\{[\s\S]*!isTlsFingerprintValid/);
});

test('connection and validation errors render through alert live regions without certificate values', () => {
  assert.match(connectFormSource, /tls-fingerprint-error[^>]*[\s\S]*role="alert"/);
  assert.match(connectFormSource, /error-message" role="alert"/);
  assert.doesNotMatch(connectFormSource, /expected\s+[0-9a-f:]{64}/i);
});

test('connection profile never persists passwords or private keys', () => {
  assert.doesNotMatch(connectionHookSource, /localStorage\.setItem\([^)]*(password|privateKey|djKey)/i);
});
