import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { existsSync, readFileSync } from 'node:fs';
import test from 'node:test';

const helperUrl = new URL('../src/lib/connectionProfile.ts', import.meta.url);
const helperExists = existsSync(helperUrl);
let profile = null;

if (helperExists) {
  const projectRequire = createRequire(new URL('../package.json', import.meta.url));
  const typescript = projectRequire('typescript');
  const source = readFileSync(helperUrl, 'utf8');
  const compiled = typescript.transpileModule(source, {
    compilerOptions: {
      module: typescript.ModuleKind.ES2022,
      target: typescript.ScriptTarget.ES2021,
    },
  }).outputText;
  profile = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);
}

function createStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem(key) {
      return values.has(key) ? values.get(key) : null;
    },
    setItem(key, value) {
      values.set(key, value);
    },
    removeItem(key) {
      values.delete(key);
    },
    value(key) {
      return values.get(key);
    },
  };
}

test('connection profile helper is executable with the existing TypeScript toolchain', () => {
  assert.equal(helperExists, true, 'connectionProfile.ts must provide executable pure behavior');
});

test('normalizes lowercase, whitespace, and colon-separated fingerprints canonically', () => {
  assert.ok(profile);
  const input = `${'ab:'.repeat(31)}ab\n`;
  assert.equal(profile.normalizeTlsFingerprint(input), 'AB'.repeat(32));
});

test('migrates persisted fingerprints and preserves an empty legacy profile across reload', () => {
  assert.ok(profile);
  const legacyStorage = createStorage();
  assert.equal(profile.loadTlsFingerprint(legacyStorage), '');
  assert.equal(legacyStorage.value('mcav.tlsFingerprint'), undefined);

  const migratedStorage = createStorage({
    'mcav.tlsFingerprint': `${'cd:'.repeat(31)}cd`,
  });
  assert.equal(profile.loadTlsFingerprint(migratedStorage), 'CD'.repeat(32));
  assert.equal(migratedStorage.value('mcav.tlsFingerprint'), 'CD'.repeat(32));

  profile.saveTlsFingerprint(migratedStorage, `${'ef '.repeat(31)}ef`);
  assert.equal(profile.loadTlsFingerprint(migratedStorage), 'EF'.repeat(32));
});

test('both Tauri connection payload builders send null for an unpinned profile', () => {
  assert.ok(profile);
  const direct = profile.buildDirectConnectionArgs({
    djName: 'Direct DJ',
    serverHost: '127.0.0.1',
    serverPort: 9000,
    tlsFingerprint: '',
  });
  const code = profile.buildCodeConnectionArgs({
    code: 'ABCD-EF12',
    djName: 'Code DJ',
    serverHost: '203.0.113.10',
    serverPort: 25808,
    tlsFingerprint: '  ',
    blockPalette: null,
    djSessionId: 'session-id',
  });

  assert.equal(direct.tlsFingerprint, null);
  assert.equal(code.tlsFingerprint, null);
});

test('malformed fingerprints disable connection and expose accessible validation state', () => {
  assert.ok(profile);
  const empty = profile.getTlsFingerprintFieldState('');
  assert.equal(empty.isValid, true);
  assert.equal(empty.ariaInvalid, false);
  assert.equal(empty.describedBy, 'tls-fingerprint-help');
  assert.equal(empty.validationMessage, null);

  const malformed = profile.getTlsFingerprintFieldState('AA:GG');
  assert.equal(malformed.normalizedValue, 'AAGG');
  assert.equal(malformed.isValid, false);
  assert.equal(malformed.ariaInvalid, true);
  assert.equal(
    malformed.describedBy,
    'tls-fingerprint-help tls-fingerprint-error',
  );
  assert.match(malformed.validationMessage, /64 hexadecimal/i);
});

test('initial and reconnect TLS errors map distinctly without rendering sentinel digests', () => {
  assert.ok(profile);
  const expected = 'EXPECTED_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
  const observed = 'OBSERVED_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB';
  const initial = profile.formatConnectionError(
    `TLS certificate fingerprint mismatch (expected ${expected}, observed ${observed})`,
  );
  const reconnect = profile.sanitizeConnectionStatus({
    connected: false,
    error_code: 'tls_fingerprint_mismatch',
    error: `unsafe ${expected} ${observed}`,
  });

  for (const rendered of [initial, reconnect.error]) {
    assert.match(rendered, /certificate changed/i);
    assert.match(rendered, /update the configured fingerprint/i);
    assert.doesNotMatch(rendered, /EXPECTED_|OBSERVED_/);
  }

  const messages = [
    profile.formatConnectionError('', 'invalid_tls_fingerprint'),
    profile.formatConnectionError('', 'missing_peer_certificate'),
    profile.formatConnectionError('', 'tls_fingerprint_mismatch'),
    profile.formatConnectionError('', 'tls_certificate_host_mismatch'),
    profile.formatConnectionError('', 'tls_handshake'),
  ];
  assert.equal(new Set(messages).size, 5);
});

test('server-controlled authentication event text is replaced by fixed safe UI guidance', () => {
  assert.ok(profile);
  const sentinel = 'SERVER_PAYLOAD_password=DO_NOT_RENDER';
  const status = profile.sanitizeConnectionStatus({
    connected: false,
    error_code: 'authentication_failed',
    error: sentinel,
  });

  assert.match(status.error, /Authentication failed/i);
  assert.match(status.error, /VJ operator/i);
  assert.doesNotMatch(status.error, /SERVER_PAYLOAD|DO_NOT_RENDER/);
});
