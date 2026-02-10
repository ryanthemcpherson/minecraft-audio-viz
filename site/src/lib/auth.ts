/**
 * Client-side auth utilities for the MCAV coordinator API.
 *
 * Access token is stored in memory (via AuthProvider context).
 * Refresh token is stored in localStorage.
 */

const COORDINATOR_URL =
  process.env.NEXT_PUBLIC_COORDINATOR_URL || "http://localhost:8090";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface User {
  id: string;
  display_name: string;
  email: string | null;
  discord_username: string | null;
  avatar_url: string | null;
}

export interface OrgSummary {
  id: string;
  name: string;
  slug: string;
  role: string;
}

export interface UserProfile extends User {
  organizations: OrgSummary[];
}

export interface AuthResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  user: User;
}

// ---------------------------------------------------------------------------
// API helpers
// ---------------------------------------------------------------------------

async function api<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const res = await fetch(`${COORDINATOR_URL}${path}`, {
    headers: { "Content-Type": "application/json", ...options.headers },
    ...options,
  });

  if (!res.ok) {
    const body = await res.json().catch(() => ({ detail: res.statusText }));
    throw new Error(body.detail || `Request failed: ${res.status}`);
  }

  // 204 No Content
  if (res.status === 204) return undefined as unknown as T;

  return res.json();
}

// ---------------------------------------------------------------------------
// Auth endpoints
// ---------------------------------------------------------------------------

export async function register(
  email: string,
  password: string,
  displayName: string
): Promise<AuthResponse> {
  return api<AuthResponse>("/api/v1/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, password, display_name: displayName }),
  });
}

export async function loginWithEmail(
  email: string,
  password: string
): Promise<AuthResponse> {
  return api<AuthResponse>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
}

export async function getDiscordAuthUrl(): Promise<string> {
  const data = await api<{ authorize_url: string }>(
    "/api/v1/auth/discord"
  );
  // Store the state param for CSRF validation on callback
  try {
    const url = new URL(data.authorize_url);
    const state = url.searchParams.get("state");
    if (state) storeOAuthState(state);
  } catch {
    // If URL parsing fails, proceed without state storage
  }
  return data.authorize_url;
}

export async function exchangeDiscordCode(
  code: string,
  state: string
): Promise<AuthResponse> {
  const params = new URLSearchParams({ code, state });
  return api<AuthResponse>(`/api/v1/auth/discord/callback?${params}`);
}

export async function refreshToken(
  refreshTokenValue: string
): Promise<AuthResponse> {
  return api<AuthResponse>("/api/v1/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refresh_token: refreshTokenValue }),
  });
}

export async function fetchMe(accessToken: string): Promise<UserProfile> {
  return api<UserProfile>("/api/v1/auth/me", {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}

export async function logout(refreshTokenValue: string): Promise<void> {
  await api<void>("/api/v1/auth/logout", {
    method: "POST",
    body: JSON.stringify({ refresh_token: refreshTokenValue }),
  });
}

// ---------------------------------------------------------------------------
// Local storage helpers for refresh token
// ---------------------------------------------------------------------------

const REFRESH_TOKEN_KEY = "mcav_refresh_token";
const OAUTH_STATE_KEY = "mcav_oauth_state";

export function storeOAuthState(state: string): void {
  if (typeof window === "undefined") return;
  sessionStorage.setItem(OAUTH_STATE_KEY, state);
}

export function getStoredOAuthState(): string | null {
  if (typeof window === "undefined") return null;
  return sessionStorage.getItem(OAUTH_STATE_KEY);
}

export function clearStoredOAuthState(): void {
  if (typeof window === "undefined") return;
  sessionStorage.removeItem(OAUTH_STATE_KEY);
}

export function getStoredRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function storeRefreshToken(token: string): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(REFRESH_TOKEN_KEY, token);
}

export function clearStoredRefreshToken(): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}
