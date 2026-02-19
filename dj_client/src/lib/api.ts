/**
 * Coordinator API client for the DJ client.
 *
 * Auth tokens are stored securely via tauri-plugin-store (see token-store.ts).
 * The authedFetch wrapper automatically refreshes expired tokens and retries
 * once on 401 responses.
 */

import {
  initTokenStore,
  getAccessToken,
  getRefreshToken,
  hasTokens,
  isTokenExpiringSoon,
  storeTokens,
  clearTokens,
} from './token-store';

// Re-export for consumers
export { initTokenStore, hasTokens, clearTokens };

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface UserResponse {
  id: string;
  display_name: string;
  email: string | null;
  discord_username: string | null;
  avatar_url: string | null;
  onboarding_completed: boolean;
}

export interface DJProfileResponse {
  id: string;
  user_id: string;
  dj_name: string;
  bio: string | null;
  genres: string | null;
  avatar_url: string | null;
  banner_url: string | null;
  color_palette: string[] | null;
  slug: string | null;
  soundcloud_url: string | null;
  spotify_url: string | null;
  website_url: string | null;
  block_palette: (string | null)[] | null;
  is_public: boolean;
  created_at: string;
}

export interface OrgSummary {
  id: string;
  name: string;
  slug: string;
  role: string;
}

export interface UserProfileResponse {
  id: string;
  display_name: string;
  email: string | null;
  discord_username: string | null;
  avatar_url: string | null;
  onboarding_completed: boolean;
  user_type: string | null;
  dj_profile: DJProfileResponse | null;
  organizations: OrgSummary[];
}

export interface AuthResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  user: UserResponse;
}

export interface DiscordAuthorizeResponse {
  authorize_url: string;
  state: string;
}

// ---------------------------------------------------------------------------
// API error with status code
// ---------------------------------------------------------------------------

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function getBaseUrl(): string {
  return localStorage.getItem('mcav.coordinatorUrl') || 'https://api.mcav.live/api/v1';
}

// ---------------------------------------------------------------------------
// Core fetch wrapper
// ---------------------------------------------------------------------------

async function apiFetch<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const url = `${getBaseUrl()}${path}`;
  const res = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options.headers,
    },
  });

  if (!res.ok) {
    const body = await res.text();
    let detail: string;
    try {
      detail = JSON.parse(body).detail ?? body;
    } catch {
      detail = body;
    }
    throw new ApiError(res.status, detail);
  }

  if (res.status === 204) return undefined as unknown as T;
  return res.json() as Promise<T>;
}

async function authedFetch<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  // Pre-emptive refresh if token is expiring soon
  if (isTokenExpiringSoon()) {
    try {
      await refreshTokens();
    } catch {
      // If refresh fails, try the request anyway
    }
  }

  const token = getAccessToken();
  if (!token) throw new Error('Not signed in');

  const opts = {
    ...options,
    headers: {
      ...options.headers,
      Authorization: `Bearer ${token}`,
    },
  };

  try {
    return await apiFetch<T>(path, opts);
  } catch (e) {
    // Retry once on 401 with a refreshed token
    if (e instanceof ApiError && e.status === 401) {
      try {
        await refreshTokens();
      } catch {
        await clearTokens();
        throw e;
      }
      const newToken = getAccessToken();
      if (!newToken) {
        await clearTokens();
        throw e;
      }
      return await apiFetch<T>(path, {
        ...options,
        headers: {
          ...options.headers,
          Authorization: `Bearer ${newToken}`,
        },
      });
    }
    throw e;
  }
}

// ---------------------------------------------------------------------------
// Auth API
// ---------------------------------------------------------------------------

export async function login(
  email: string,
  password: string,
): Promise<AuthResponse> {
  const auth = await apiFetch<AuthResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
  await storeTokens(auth.access_token, auth.refresh_token, auth.expires_in);
  return auth;
}

export async function register(
  email: string,
  password: string,
  displayName: string,
): Promise<AuthResponse> {
  const auth = await apiFetch<AuthResponse>('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, display_name: displayName }),
  });
  await storeTokens(auth.access_token, auth.refresh_token, auth.expires_in);
  return auth;
}

export async function getDiscordAuthorizeUrl(
  desktop = true,
): Promise<DiscordAuthorizeResponse> {
  return apiFetch<DiscordAuthorizeResponse>(
    `/auth/discord?desktop=${desktop}`,
  );
}

export async function exchangeDesktopCode(
  code: string,
): Promise<AuthResponse> {
  const auth = await apiFetch<AuthResponse>('/auth/exchange', {
    method: 'POST',
    body: JSON.stringify({ exchange_code: code }),
  });
  await storeTokens(auth.access_token, auth.refresh_token, auth.expires_in);
  return auth;
}

export async function refreshTokens(): Promise<AuthResponse> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) throw new Error('No refresh token');

  const auth = await apiFetch<AuthResponse>('/auth/refresh', {
    method: 'POST',
    body: JSON.stringify({ refresh_token: refreshToken }),
  });
  await storeTokens(auth.access_token, auth.refresh_token, auth.expires_in);
  return auth;
}

// ---------------------------------------------------------------------------
// Connect code API
// ---------------------------------------------------------------------------

export interface ResolvedConnectCode {
  websocket_url: string;
  show_name: string;
}

export async function resolveConnectCode(
  code: string,
): Promise<ResolvedConnectCode> {
  return apiFetch<ResolvedConnectCode>(
    `/connect/${encodeURIComponent(code)}`,
  );
}

// ---------------------------------------------------------------------------
// Profile API
// ---------------------------------------------------------------------------

export async function getProfile(): Promise<UserProfileResponse> {
  return authedFetch<UserProfileResponse>('/auth/me');
}

export async function logout(): Promise<void> {
  const refreshToken = getRefreshToken();
  if (refreshToken) {
    try {
      await apiFetch('/auth/logout', {
        method: 'POST',
        body: JSON.stringify({ refresh_token: refreshToken }),
      });
    } catch {
      // Best-effort; clear tokens regardless
    }
  }
  await clearTokens();
}
