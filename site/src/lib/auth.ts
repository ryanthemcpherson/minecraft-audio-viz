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
  onboarding_completed: boolean;
}

export interface OrgSummary {
  id: string;
  name: string;
  slug: string;
  role: string;
}

export interface DJProfile {
  id: string;
  user_id: string;
  dj_name: string;
  bio: string | null;
  genres: string | null;
  avatar_url: string | null;
  is_public: boolean;
  created_at: string;
}

export interface UserProfile extends User {
  user_type: string | null;
  dj_profile: DJProfile | null;
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
// Dashboard types
// ---------------------------------------------------------------------------

export interface ServerOwnerChecklist {
  org_created: boolean;
  server_registered: boolean;
  invite_created: boolean;
  show_started: boolean;
}

export interface OrgDashboardSummary {
  id: string;
  name: string;
  slug: string;
  role: string;
  server_count: number;
  member_count: number;
  active_show_count: number;
}

export interface RecentShowSummary {
  id: string;
  name: string;
  server_name: string;
  connect_code: string | null;
  status: string;
  current_djs: number;
  created_at: string;
}

export interface ServerOwnerDashboard {
  user_type: "server_owner";
  checklist: ServerOwnerChecklist;
  organizations: OrgDashboardSummary[];
  recent_shows: RecentShowSummary[];
}

export interface TeamMemberDashboard {
  user_type: "team_member";
  organizations: OrgDashboardSummary[];
  active_shows: RecentShowSummary[];
}

export interface DJDashboardData {
  user_type: "dj";
  dj_name: string;
  bio: string | null;
  genres: string | null;
  session_count: number;
  recent_sessions: RecentShowSummary[];
}

export interface GenericDashboard {
  user_type: "generic";
  organizations: OrgDashboardSummary[];
}

export type DashboardSummary =
  | ServerOwnerDashboard
  | TeamMemberDashboard
  | DJDashboardData
  | GenericDashboard;

// ---------------------------------------------------------------------------
// API helpers
// ---------------------------------------------------------------------------

async function api<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const { headers: optHeaders, ...rest } = options;
  const res = await fetch(`${COORDINATOR_URL}${path}`, {
    ...rest,
    headers: { "Content-Type": "application/json", ...(optHeaders as Record<string, string>) },
  });

  if (!res.ok) {
    const body = await res.json().catch(() => ({ detail: res.statusText }));
    const detail = body.detail;
    const message =
      typeof detail === "string"
        ? detail
        : Array.isArray(detail)
          ? detail.map((e: { msg?: string }) => e.msg || String(e)).join("; ")
          : `Request failed: ${res.status}`;
    throw new Error(message);
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
// Onboarding & org endpoints
// ---------------------------------------------------------------------------

export async function completeOnboarding(
  accessToken: string,
  userType: string
): Promise<User> {
  return api<User>("/api/v1/onboarding/complete", {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify({ user_type: userType }),
  });
}

export async function skipOnboarding(accessToken: string): Promise<User> {
  return api<User>("/api/v1/onboarding/skip", {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}

export async function createOrg(
  accessToken: string,
  name: string,
  slug: string,
  description?: string
): Promise<{ id: string; name: string; slug: string }> {
  return api("/api/v1/orgs", {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify({ name, slug, description }),
  });
}

export async function joinOrg(
  accessToken: string,
  inviteCode: string
): Promise<{ org_id: string; org_name: string; org_slug: string; role: string }> {
  return api("/api/v1/orgs/join", {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify({ invite_code: inviteCode }),
  });
}

export async function createInvite(
  accessToken: string,
  orgId: string
): Promise<{ id: string; code: string }> {
  return api(`/api/v1/orgs/${orgId}/invites`, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify({}),
  });
}

export async function createDJProfile(
  accessToken: string,
  data: { dj_name: string; bio?: string; genres?: string }
): Promise<DJProfile> {
  return api<DJProfile>("/api/v1/dj/profile", {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify(data),
  });
}

export async function fetchDashboardSummary(
  accessToken: string
): Promise<DashboardSummary> {
  return api<DashboardSummary>("/api/v1/dashboard/summary", {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}

export async function resetOnboarding(accessToken: string): Promise<User> {
  return api<User>("/api/v1/onboarding/reset", {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
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
