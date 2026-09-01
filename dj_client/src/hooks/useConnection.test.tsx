// @vitest-environment jsdom

import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  classifyConnectionError,
  formatConnectionFingerprint,
  normalizeConnectionFingerprint,
  normalizeDjServerUrl,
  parseDjInviteLink,
  requiresPinReplacementConfirmation,
  useConnection,
} from './useConnection';
import type { UseAuthReturn } from './useAuth';
import { ApiError } from '../lib/api';

const tauriMocks = vi.hoisted(() => ({
  invoke: vi.fn(),
  listen: vi.fn(() => Promise.resolve(() => {})),
  getCurrent: vi.fn(() => Promise.resolve(null)),
  onOpenUrl: vi.fn(() => Promise.resolve(() => {})),
  resolveConnectCode: vi.fn(),
}));

vi.mock('@tauri-apps/api/core', () => ({ invoke: tauriMocks.invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: tauriMocks.listen }));
vi.mock('@tauri-apps/plugin-deep-link', () => ({
  getCurrent: tauriMocks.getCurrent,
  onOpenUrl: tauriMocks.onOpenUrl,
}));
vi.mock('../lib/api', async importOriginal => {
  const actual = await importOriginal<typeof import('../lib/api')>();
  return {
    ...actual,
    resolveConnectCode: tauriMocks.resolveConnectCode,
  };
});

const NOW_SECONDS = 1_788_198_200;
const FINGERPRINT = 'AA'.repeat(32);

function signedOutAuth(): UseAuthReturn {
  return {
    isLoading: false,
    isSignedIn: false,
    user: null,
    error: null,
    login: vi.fn(),
    register: vi.fn(),
    signInWithDiscord: vi.fn(),
    signInWithGoogle: vi.fn(),
    resendVerification: vi.fn(),
    verificationMessage: null,
    signOut: vi.fn(),
    clearError: vi.fn(),
  };
}

function inviteLink(overrides: Record<string, unknown> = {}): string {
  const invite = {
    schema_version: 1,
    server_url: 'wss://mc.example:8443/ws/dj',
    connect_code: 'BEAT-7K3M',
    certificate_sha256: FINGERPRINT,
    expires_at: NOW_SECONDS + 900,
    ...overrides,
  };
  const payload = btoa(JSON.stringify(invite))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/u, '');
  return `mcav://connect?invite=${payload}`;
}

beforeEach(() => {
  localStorage.clear();
  tauriMocks.invoke.mockReset();
  tauriMocks.invoke.mockResolvedValue(null);
  tauriMocks.getCurrent.mockReset();
  tauriMocks.getCurrent.mockResolvedValue(null);
  tauriMocks.onOpenUrl.mockClear();
  tauriMocks.listen.mockClear();
  tauriMocks.resolveConnectCode.mockReset();
});

describe('DJ invite import', () => {
  it('parses a certificate-bound mcav invite', () => {
    expect(parseDjInviteLink(inviteLink(), NOW_SECONDS)).toEqual({
      serverUrl: 'wss://mc.example:8443/ws/dj',
      connectCode: 'BEAT7K3M',
      certificateSha256: FINGERPRINT,
      expiresAt: NOW_SECONDS + 900,
    });
  });

  it('rejects malformed, unknown, expired, and oversized invites', () => {
    expect(() => parseDjInviteLink('https://mc.example/', NOW_SECONDS)).toThrow(/invite/i);
    expect(() => parseDjInviteLink(inviteLink({ unexpected: true }), NOW_SECONDS)).toThrow(
      /unknown/i,
    );
    expect(() =>
      parseDjInviteLink(inviteLink({ expires_at: NOW_SECONDS - 1 }), NOW_SECONDS),
    ).toThrow(/expired/i);
    expect(() =>
      parseDjInviteLink(inviteLink({ expires_at: NOW_SECONDS + 86_701 }), NOW_SECONDS),
    ).toThrow(/expiration/i);
    expect(() =>
      parseDjInviteLink(
        `mcav://connect?invite=${'A'.repeat(5_500)}`,
        NOW_SECONDS,
      ),
    ).toThrow(/large/i);
  });

  it('requires the canonical endpoint and strips no attacker-controlled URL parts', () => {
    expect(normalizeDjServerUrl('wss://MC.EXAMPLE:443/ws/dj')).toBe(
      'wss://mc.example/ws/dj',
    );
    for (const invalid of [
      'https://mc.example/ws/dj',
      'wss://admin:secret@mc.example/ws/dj',
      'wss://mc.example/ws/admin',
      'wss://mc.example/ws/dj?code=secret',
      'wss://mc.example/ws/dj#secret',
    ]) {
      expect(() => normalizeDjServerUrl(invalid)).toThrow();
    }
  });
});

describe('certificate trust UX', () => {
  it('normalizes and groups manual fingerprints without weakening validation', () => {
    const grouped = Array.from({ length: 32 }, () => 'aa').join(':');
    expect(normalizeConnectionFingerprint(grouped)).toBe(FINGERPRINT);
    expect(formatConnectionFingerprint(FINGERPRINT)).toBe(
      Array.from({ length: 32 }, () => 'AA').join(' '),
    );
    expect(() => normalizeConnectionFingerprint('AA:BB')).toThrow(/64/i);
  });

  it('imports an invite into hook state without storing its connect code', async () => {
    const { result } = renderHook(() => useConnection(signedOutAuth()));
    const link = inviteLink({ expires_at: Math.floor(Date.now() / 1000) + 900 });

    act(() => result.current.setInviteLink(link));
    await waitFor(() => expect(result.current.inviteLink).toBe(link));
    act(() => result.current.importInvite());

    await waitFor(() => expect(result.current.serverUrl).toBe('wss://mc.example:8443/ws/dj'));
    expect(result.current.connectCode).toBe('BEAT7K3M');
    expect(result.current.certificateFingerprint).toBe(FINGERPRINT);
    expect(result.current.inviteLink).toBe('');
    expect(localStorage.getItem('mcav.connectCode')).toBeNull();
  });

  it('loads the profile-scoped saved pin and blocks an unconfirmed replacement', async () => {
    tauriMocks.invoke.mockImplementation((command: string) => {
      if (command === 'get_saved_server_profile') {
        return Promise.resolve({
          serverUrl: 'wss://mc.example/ws/dj',
          certificateSha256: FINGERPRINT,
        });
      }
      return Promise.resolve(null);
    });
    const { result } = renderHook(() => useConnection(signedOutAuth()));

    act(() => {
      result.current.setDirectConnect(true);
      result.current.setServerUrl('wss://mc.example/ws/dj');
    });

    await waitFor(() => expect(result.current.certificateFingerprint).toBe(FINGERPRINT));
    act(() => result.current.setCertificateFingerprint('BB'.repeat(32)));
    await waitFor(() => expect(result.current.pinReplacementRequired).toBe(true));
    expect(result.current.pinReplacementConfirmed).toBe(false);
    act(() => result.current.setPinReplacementConfirmed(true));
    expect(result.current.pinReplacementConfirmed).toBe(true);
  });

  it('requires confirmation only when an existing profile pin changes', () => {
    expect(
      requiresPinReplacementConfirmation(
        { serverUrl: 'wss://mc.example/ws/dj', certificateSha256: FINGERPRINT },
        { serverUrl: 'wss://mc.example/ws/dj', certificateSha256: 'BB'.repeat(32) },
      ),
    ).toBe(true);
    expect(
      requiresPinReplacementConfirmation(
        { serverUrl: 'wss://mc.example/ws/dj', certificateSha256: FINGERPRINT },
        { serverUrl: 'wss://other.example/ws/dj', certificateSha256: 'BB'.repeat(32) },
      ),
    ).toBe(false);
    expect(
      requiresPinReplacementConfirmation(
        { serverUrl: 'wss://mc.example/ws/dj', certificateSha256: FINGERPRINT },
        { serverUrl: 'wss://mc.example/ws/dj', certificateSha256: null },
      ),
    ).toBe(true);
  });

  it.each([
    ['CERTIFICATE_PIN_MISMATCH', 'certificate changed'],
    ['CERTIFICATE_UNTRUSTED', 'not trusted'],
    ['CERTIFICATE_EXPIRED', 'expired'],
    ['CERTIFICATE_HOSTNAME_MISMATCH', 'server name'],
    ['CONNECTION_TIMEOUT', "can't reach"],
    ['AUTHENTICATION_FAILED', 'new code'],
    ['RECONNECT_REQUIRES_NEW_CODE', 'fresh administrator invite'],
    ['SERVER_PROFILE_STORE_FAILED', 'storage access'],
  ])('maps %s to an actionable error', (backendError, expectedText) => {
    expect(classifyConnectionError(backendError)).toMatch(
      new RegExp(expectedText, 'i'),
    );
  });

  it('does not expose unclassified backend details', () => {
    const message = classifyConnectionError(
      'store failure at C:\\Users\\operator\\AppData\\server-profiles.json',
    );

    expect(message).toBe('Connection failed. Check the server details and try again.');
    expect(message).not.toMatch(/AppData|server-profiles/);
  });

  it('does not expose unexpected coordinator response details', async () => {
    tauriMocks.resolveConnectCode.mockRejectedValue(
      new ApiError(500, 'database host db.internal.example failed at /srv/coordinator'),
    );
    const { result } = renderHook(() => useConnection(signedOutAuth()));
    act(() => {
      result.current.setDjName('Test DJ');
      result.current.setConnectCode('BEAT7K3M');
    });

    await act(async () => {
      await result.current.handleConnect(null, false, async () => {});
    });

    expect(result.current.status.error).toBe(
      'The coordinator could not complete the connection request. Try again later.',
    );
    expect(result.current.status.error).not.toMatch(/db\.internal|\/srv\/coordinator/);
  });
});
