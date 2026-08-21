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

test('DJ connection profile carries the certificate fingerprint to both Tauri connection commands', () => {
  assert.match(connectFormSource, /tlsFingerprint: string/);
  assert.match(disconnectedViewSource, /tlsFingerprint=/);
  assert.match(connectionHookSource, /mcav\.tlsFingerprint/);
  assert.match(connectionHookSource, /invoke\('connect_with_code',[\s\S]*?tlsFingerprint:\s*normalizedTlsFingerprint\s*\|\|\s*null/);
  assert.match(connectionHookSource, /invoke\('connect_direct',[\s\S]*?tlsFingerprint:\s*normalizedTlsFingerprint\s*\|\|\s*null/);
});

test('connection profile labels the fingerprint as SHA-256 and non-secret', () => {
  assert.match(connectFormSource, /Server certificate SHA-256 fingerprint/i);
  assert.match(connectFormSource, /Safe to save; never share the server password/i);
  assert.match(connectFormSource, /64 hexadecimal/i);
  assert.match(connectFormSource, /wss:\/\/IP:25808/i);
});

test('fingerprint is canonicalized across editing, reload, and persistence while an old local profile remains unpinned', () => {
  assert.match(connectionHookSource, /normalizeTlsFingerprint/);
  assert.match(connectionHookSource, /replace\([^)]*[:\\s]/);
  assert.match(connectionHookSource, /toUpperCase\(\)/);
  assert.match(
    connectionHookSource,
    /normalizeTlsFingerprint\(localStorage\.getItem\('mcav\.tlsFingerprint'\)\s*\?\?\s*''\)/,
  );
  assert.match(
    connectionHookSource,
    /localStorage\.setItem\('mcav\.tlsFingerprint',\s*normalizedTlsFingerprint\)/,
  );
  assert.match(connectionHookSource, /normalizedTlsFingerprint\s*\|\|\s*null/);
});

test('fingerprint validation is exposed to keyboard and assistive technology', () => {
  assert.match(connectFormSource, /htmlFor="tls-fingerprint"/);
  assert.match(connectFormSource, /id="tls-fingerprint"/);
  assert.match(
    connectFormSource,
    /aria-describedby=\{[\s\S]*isTlsFingerprintValid[\s\S]*tls-fingerprint-help tls-fingerprint-error/,
  );
  assert.match(connectFormSource, /aria-invalid=\{[^}]*tlsFingerprint[^}]*\}/i);
  assert.match(connectFormSource, /role="alert"/);
  assert.match(connectFormSource, /disabled=\{[\s\S]*!isTlsFingerprintValid/);
});

test('TLS failures are mapped to actionable messages without rendering certificate values', () => {
  assert.match(connectionHookSource, /Invalid TLS certificate fingerprint/i);
  assert.match(connectionHookSource, /TLS peer did not provide a certificate/i);
  assert.match(connectionHookSource, /TLS certificate fingerprint mismatch/i);
  assert.match(connectionHookSource, /TLS certificate is not valid for the requested server host/i);
  assert.match(connectionHookSource, /TLS handshake failed/i);
  assert.match(
    connectionHookSource,
    /if \(normalizedError\.includes\('tls certificate fingerprint mismatch'\)\) \{\s*return 'The server certificate does not match the saved fingerprint\.[^']*';\s*\}/,
  );
  assert.doesNotMatch(connectionHookSource, /errorMessage\s*=\s*errStr[^;]*TlsFingerprintMismatch/);
  assert.doesNotMatch(connectFormSource, /expected\s+[0-9a-f:]{64}/i);
});

test('connection profile never persists passwords or private keys', () => {
  assert.doesNotMatch(connectionHookSource, /localStorage\.setItem\([^)]*(password|privateKey|djKey)/i);
});
