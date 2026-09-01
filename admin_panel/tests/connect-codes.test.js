// @vitest-environment jsdom

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  buildInvite,
  copyInviteLink,
  decodeInviteLink,
  encodeInviteLink,
  normalizeDjServerUrl,
  renderInvite,
} from '../js/managers/connect-codes.js';

const NOW_SECONDS = 1_788_198_200;
const EXPIRES_AT = 1_788_200_000;
const FINGERPRINT = 'AA'.repeat(32);

function generatedRuntime(overrides = {}) {
  return {
    public_url: 'https://mc.example:8080/',
    certificate_sha256: FINGERPRINT,
    ...overrides,
  };
}

function generatedCode(overrides = {}) {
  return {
    code: 'BEAT-7K3M',
    expires_at: EXPIRES_AT,
    ttl_minutes: 30,
    ...overrides,
  };
}

beforeEach(() => {
  document.body.textContent = '';
});

describe('certificate-bound DJ invites', () => {
  it('builds an exact pinned invite for generated TLS', () => {
    expect(
      buildInvite(generatedRuntime(), generatedCode(), { nowSeconds: NOW_SECONDS }),
    ).toEqual({
      schema_version: 1,
      server_url: 'wss://mc.example:8080/ws/dj',
      connect_code: 'BEAT-7K3M',
      certificate_sha256: FINGERPRINT,
      expires_at: EXPIRES_AT,
    });
  });

  it('omits the certificate pin for a trusted certificate', () => {
    expect(
      buildInvite(
        generatedRuntime({ certificate_sha256: null }),
        generatedCode(),
        { nowSeconds: NOW_SECONDS },
      ),
    ).toEqual({
      schema_version: 1,
      server_url: 'wss://mc.example:8080/ws/dj',
      connect_code: 'BEAT-7K3M',
      expires_at: EXPIRES_AT,
    });
  });

  it('requires a canonical public URL and normalizes only its origin', () => {
    expect(normalizeDjServerUrl('https://mc.example:8443/')).toBe(
      'wss://mc.example:8443/ws/dj',
    );
    expect(() => buildInvite({}, generatedCode(), { nowSeconds: NOW_SECONDS })).toThrow(
      /public URL/i,
    );
    for (const invalidUrl of [
      'http://mc.example:8080/',
      'https://admin:secret@mc.example/',
      'https://mc.example/control',
      'https://mc.example/?token=secret',
      'https://mc.example/#secret',
    ]) {
      expect(() => normalizeDjServerUrl(invalidUrl)).toThrow();
    }
  });

  it('enforces code, fingerprint, and expiration bounds', () => {
    expect(() =>
      buildInvite(
        generatedRuntime({ certificate_sha256: 'aa'.repeat(32) }),
        generatedCode(),
        { nowSeconds: NOW_SECONDS },
      ),
    ).toThrow(/fingerprint/i);
    expect(() =>
      buildInvite(generatedRuntime(), generatedCode({ code: '<img>' }), {
        nowSeconds: NOW_SECONDS,
      }),
    ).toThrow(/connect code/i);
    expect(() =>
      buildInvite(generatedRuntime(), generatedCode({ expires_at: NOW_SECONDS - 301 }), {
        nowSeconds: NOW_SECONDS,
      }),
    ).toThrow(/expiration/i);
    expect(() =>
      buildInvite(
        generatedRuntime(),
        generatedCode({ expires_at: NOW_SECONDS + 30 * 60 + 301 }),
        { nowSeconds: NOW_SECONDS },
      ),
    ).toThrow(/expiration/i);
    expect(() =>
      buildInvite(generatedRuntime(), generatedCode({ ttl_minutes: 1441 }), {
        nowSeconds: NOW_SECONDS,
      }),
    ).toThrow(/lifetime/i);
  });

  it('accepts a server-bounded 24-hour invite across small clock skew', () => {
    expect(() =>
      buildInvite(
        generatedRuntime(),
        generatedCode({
          expires_at: NOW_SECONDS + 86_401,
          ttl_minutes: 1440,
        }),
        { nowSeconds: NOW_SECONDS },
      ),
    ).not.toThrow();
  });

  it('round trips compact base64url JSON without padding', () => {
    const invite = buildInvite(generatedRuntime(), generatedCode(), {
      nowSeconds: NOW_SECONDS,
    });
    const link = encodeInviteLink(invite);

    expect(link).toMatch(/^mcav:\/\/connect\?invite=[A-Za-z0-9_-]+$/);
    expect(link.split('invite=')[1]).not.toContain('=');
    expect(decodeInviteLink(link)).toEqual(invite);
  });
});

describe('DJ invite UI boundaries', () => {
  it('reveals and selects a readonly manual fallback only after clipboard failure', async () => {
    const invite = buildInvite(generatedRuntime(), generatedCode(), {
      nowSeconds: NOW_SECONDS,
    });
    const fallbackField = document.createElement('input');
    fallbackField.hidden = true;
    const focus = vi.spyOn(fallbackField, 'focus');
    const select = vi.spyOn(fallbackField, 'select');
    const clipboard = {
      writeText: vi.fn(async () => {
        throw new DOMException('denied', 'NotAllowedError');
      }),
    };

    expect(fallbackField.hidden).toBe(true);
    expect(select).not.toHaveBeenCalled();
    const result = await copyInviteLink(invite, { clipboard, fallbackField });

    expect(result.copied).toBe(false);
    expect(fallbackField.hidden).toBe(false);
    expect(fallbackField.readOnly).toBe(true);
    expect(fallbackField.value).toBe(result.link);
    expect(focus).toHaveBeenCalledOnce();
    expect(select).toHaveBeenCalledOnce();
  });

  it('renders peer-controlled values with textContent', () => {
    document.body.innerHTML = `
      <section id="invite" hidden>
        <span id="url"></span>
        <span id="expires"></span>
        <div id="fingerprint-row"><span id="fingerprint"></span></div>
      </section>
    `;
    const elements = {
      container: document.querySelector('#invite'),
      serverUrl: document.querySelector('#url'),
      expiresAt: document.querySelector('#expires'),
      fingerprintRow: document.querySelector('#fingerprint-row'),
      fingerprint: document.querySelector('#fingerprint'),
    };
    const malicious = '<img src=x onerror="globalThis.pwned=true">';

    renderInvite(
      {
        server_url: malicious,
        expires_at: EXPIRES_AT,
        certificate_sha256: FINGERPRINT,
      },
      elements,
    );

    expect(elements.serverUrl.textContent).toBe(malicious);
    expect(elements.serverUrl.querySelector('img')).toBeNull();
    expect(elements.fingerprint.textContent).toContain('AA AA AA AA');
    expect(elements.container.hidden).toBe(false);
  });

  it('publishes a strict optional-pin schema', () => {
    const schema = JSON.parse(
      readFileSync(resolve('protocol/schemas/types/dj-invite.schema.json'), 'utf8'),
    );
    const index = JSON.parse(
      readFileSync(resolve('protocol/schemas/index.json'), 'utf8'),
    );

    expect(schema.additionalProperties).toBe(false);
    expect(schema.required).toEqual([
      'schema_version',
      'server_url',
      'connect_code',
      'expires_at',
    ]);
    expect(schema.properties.certificate_sha256.pattern).toBe('^[0-9A-F]{64}$');
    expect(index.types.dj_invite).toBe('types/dj-invite.schema.json');
  });

  it('ships an accessible explicit-click invite surface', () => {
    const html = readFileSync(resolve('admin_panel/index.html'), 'utf8');
    const parsed = new DOMParser().parseFromString(html, 'text/html');
    const manualField = parsed.querySelector('#invite-manual-copy');

    expect(parsed.querySelector('#btn-copy-invite[type="button"]')).not.toBeNull();
    expect(parsed.querySelector('#invite-copy-status[aria-live="polite"]')).not.toBeNull();
    expect(manualField.readOnly).toBe(true);
    expect(manualField.hidden).toBe(true);
    expect(manualField.getAttribute('aria-label')).toMatch(/manual copy/i);
  });
});
