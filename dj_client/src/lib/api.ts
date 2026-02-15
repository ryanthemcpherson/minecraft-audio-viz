/**
 * Coordinator API client for the DJ client.
 * All auth state is stored in localStorage.
 */

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
// Storage keys
// ---------------------------------------------------------------------------

const KEYS = {
  accessToken: 'mcav.auth.accessToken',
  refreshToken: 'mcav.auth.refreshToken',
  expiresAt: 'mcav.auth.expiresAt',
} as const;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function getBaseUrl(): string {
  return localStorage.getItem('mcav.coordinatorUrl') || 'https://api.mcav.live';
}

function storeTokens(auth: AuthResponse): void {
  localStorage.setItem(KEYS.accessToken, auth.access_token);
  localStorage.setItem(KEYS.refreshToken, auth.refresh_token);
  localStorage.setItem(
    KEYS.expiresAt,
    String(Date.now() + auth.expires_in * 1000),
  );
}

export function clearTokens(): void {
  localStorage.removeItem(KEYS.accessToken);
  localStorage.removeItem(KEYS.refreshToken);
  localStorage.removeItem(KEYS.expiresAt);
}

export function getStoredAccessToken(): string | null {
  return localStorage.getItem(KEYS.accessToken);
}

export function hasStoredTokens(): boolean {
  return !!localStorage.getItem(KEYS.accessToken);
}

function isTokenExpiringSoon(): boolean {
  const expiresAt = localStorage.getItem(KEYS.expiresAt);
  if (!expiresAt) return true;
  // Refresh if less than 2 minutes remaining
  return Date.now() > Number(expiresAt) - 120_000;
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
    throw new Error(detail);
  }

  if (res.status === 204) return undefined as unknown as T;
  return res.json() as Promise<T>;
}

async function authedFetch<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  // Auto-refresh if token is expiring soon
  if (isTokenExpiringSoon()) {
    try {
      await refreshTokens();
    } catch {
      // If refresh fails, try the request anyway — it might still work
    }
  }

  const token = localStorage.getItem(KEYS.accessToken);
  if (!token) throw new Error('Not signed in');

  return apiFetch<T>(path, {
    ...options,
    headers: {
      ...options.headers,
      Authorization: `Bearer ${token}`,
    },
  });
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
  storeTokens(auth);
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
  storeTokens(auth);
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
  storeTokens(auth);
  return auth;
}

export async function refreshTokens(): Promise<AuthResponse> {
  const refreshToken = localStorage.getItem(KEYS.refreshToken);
  if (!refreshToken) throw new Error('No refresh token');

  const auth = await apiFetch<AuthResponse>('/auth/refresh', {
    method: 'POST',
    body: JSON.stringify({ refresh_token: refreshToken }),
  });
  storeTokens(auth);
  return auth;
}

export async function getProfile(): Promise<UserProfileResponse> {
  return authedFetch<UserProfileResponse>('/auth/me');
}

export async function logout(): Promise<void> {
  const refreshToken = localStorage.getItem(KEYS.refreshToken);
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
  clearTokens();
}
