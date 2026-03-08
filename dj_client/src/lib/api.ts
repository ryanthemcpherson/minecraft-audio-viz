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
export { initTokenStore, hasTokens, clearTokens, isTokenExpiringSoon };

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
  email_verified: boolean;
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
  email_verified: boolean;
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
  poll_token?: string;
}

export interface PollResponse {
  status: 'pending' | 'complete' | 'expired';
  exchange_code?: string;
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
    } catch (err) {
      console.error("Failed to parse API error response:", err);
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
    } catch (err) {
      console.error("Failed to pre-emptively refresh token:", err);
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
      } catch (err) {
        console.error("Failed to refresh token after 401:", err);
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
  token: string;
  show_name: string;
  dj_count: number;
  dj_session_id: string;
}

export async function resolveConnectCode(
  code: string,
  idempotencyKey?: string,
): Promise<ResolvedConnectCode> {
  // Send user auth if signed in so coordinator links the session to the user.
  // Fall back to unauthenticated fetch when no tokens are available.
  try {
    return await authedFetch<ResolvedConnectCode>(
      `/connect/${encodeURIComponent(code)}/join`,
      {
        method: 'POST',
        headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
      },
    );
  } catch (e) {
    // If the error is "Not signed in", fall back to unauthenticated fetch.
    // Any other error (ApiError with 4xx/5xx) should propagate as-is.
    if (e instanceof Error && e.message === 'Not signed in') {
      return apiFetch<ResolvedConnectCode>(
        `/connect/${encodeURIComponent(code)}/join`,
        {
          method: 'POST',
          headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
        },
      );
    }
    throw e;
  }
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
    } catch (err) {
      console.error("Failed to revoke refresh token on logout:", err);
    }
  }
  await clearTokens();
}

export async function getGoogleAuthorizeUrl(
  desktop = true,
): Promise<DiscordAuthorizeResponse> {
  return apiFetch<DiscordAuthorizeResponse>(
    `/auth/google?desktop=${desktop}`,
  );
}

export async function pollDesktopAuth(pollToken: string): Promise<PollResponse> {
  return apiFetch<PollResponse>(`/auth/desktop-poll/${pollToken}`);
}

export async function forgotPassword(
  email: string,
): Promise<{ message: string }> {
  return apiFetch<{ message: string }>('/auth/forgot-password', {
    method: 'POST',
    body: JSON.stringify({ email }),
  });
}

export async function resendVerification(): Promise<{ message: string }> {
  return authedFetch<{ message: string }>('/auth/resend-verification', {
    method: 'POST',
  });
}
