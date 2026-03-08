/**
 * Secure token storage using tauri-plugin-store.
 *
 * Tokens are cached in memory for synchronous access and persisted to an
 * encrypted store on disk. On first init, any legacy localStorage tokens are
 * migrated automatically.
 */

import { Store } from '@tauri-apps/plugin-store';

// ---------------------------------------------------------------------------
// In-memory cache (sync reads, async writes)
// ---------------------------------------------------------------------------

let store: Store | null = null;
let cachedAccessToken: string | null = null;
let cachedRefreshToken: string | null = null;
let cachedExpiresAt: number | null = null;
let initialized = false;

// Legacy localStorage keys (migrated on first run)
const LEGACY_KEYS = {
  accessToken: 'mcav.auth.accessToken',
  refreshToken: 'mcav.auth.refreshToken',
  expiresAt: 'mcav.auth.expiresAt',
} as const;

// ---------------------------------------------------------------------------
// Initialization
// ---------------------------------------------------------------------------

/**
 * Load tokens from the Tauri store (or migrate from localStorage).
 * Must be called once before any other token-store function.
 */
export async function initTokenStore(): Promise<void> {
  if (initialized) return;

  try {
    store = await Store.load('auth.json', {
      defaults: {},
      autoSave: true,
    });
    cachedAccessToken = (await store.get<string>('accessToken')) ?? null;
    cachedRefreshToken = (await store.get<string>('refreshToken')) ?? null;
    cachedExpiresAt = (await store.get<number>('expiresAt')) ?? null;
  } catch (err) {
    console.error("Failed to load token store:", err);
  }

  // Migrate from localStorage if the store is empty but legacy tokens exist
  if (!cachedAccessToken) {
    const legacyToken = localStorage.getItem(LEGACY_KEYS.accessToken);
    if (legacyToken) {
      cachedAccessToken = legacyToken;
      cachedRefreshToken = localStorage.getItem(LEGACY_KEYS.refreshToken);
      const expiresStr = localStorage.getItem(LEGACY_KEYS.expiresAt);
      cachedExpiresAt = expiresStr ? Number(expiresStr) : null;

      // Persist to secure store and remove legacy entries
      await persistTokens();
      localStorage.removeItem(LEGACY_KEYS.accessToken);
      localStorage.removeItem(LEGACY_KEYS.refreshToken);
      localStorage.removeItem(LEGACY_KEYS.expiresAt);
    }
  }

  initialized = true;
}

// ---------------------------------------------------------------------------
// Sync readers
// ---------------------------------------------------------------------------

export function getAccessToken(): string | null {
  return cachedAccessToken;
}

export function getRefreshToken(): string | null {
  return cachedRefreshToken;
}

export function hasTokens(): boolean {
  return cachedAccessToken !== null;
}

export function isTokenExpiringSoon(): boolean {
  if (!cachedExpiresAt) return true;
  // Refresh if less than 2 minutes remaining
  return Date.now() > cachedExpiresAt - 120_000;
}

// ---------------------------------------------------------------------------
// Async writers
// ---------------------------------------------------------------------------

async function persistTokens(): Promise<void> {
  if (!store) return;
  try {
    if (cachedAccessToken) {
      await store.set('accessToken', cachedAccessToken);
    }
    if (cachedRefreshToken) {
      await store.set('refreshToken', cachedRefreshToken);
    }
    if (cachedExpiresAt) {
      await store.set('expiresAt', cachedExpiresAt);
    }
  } catch (err) {
    console.error("Failed to persist tokens to store:", err);
  }
}

export async function storeTokens(
  accessToken: string,
  refreshToken: string,
  expiresIn: number,
): Promise<void> {
  cachedAccessToken = accessToken;
  cachedRefreshToken = refreshToken;
  cachedExpiresAt = Date.now() + expiresIn * 1000;
  await persistTokens();
}

export async function clearTokens(): Promise<void> {
  cachedAccessToken = null;
  cachedRefreshToken = null;
  cachedExpiresAt = null;
  if (store) {
    try {
      await store.delete('accessToken');
      await store.delete('refreshToken');
      await store.delete('expiresAt');
    } catch (err) {
      console.error("Failed to clear tokens from store:", err);
    }
  }
}
