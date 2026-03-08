import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { AuthResponse, UserProfile } from "../auth";

// Mock process.env before importing the module
vi.stubEnv("NEXT_PUBLIC_COORDINATOR_URL", "https://api.test.mcav.live");

// Dynamic import so env stub takes effect
const authModule = await import("../auth");
const {
  fetchMe,
  refreshToken,
  loginWithEmail,
  register,
  logout,
  getOAuthProvider,
  storeRefreshToken,
  getStoredRefreshToken,
  clearStoredRefreshToken,
  storeOAuthState,
  getStoredOAuthState,
  clearStoredOAuthState,
} = authModule;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const MOCK_USER: UserProfile = {
  id: "u_123",
  display_name: "TestUser",
  email: "test@example.com",
  discord_username: null,
  avatar_url: null,
  onboarding_completed: false,
  email_verified: true,
  is_admin: false,
  user_type: null,
  dj_profile: null,
  organizations: [],
};

const MOCK_AUTH_RESPONSE: AuthResponse = {
  access_token: "acc_new",
  refresh_token: "ref_new",
  token_type: "bearer",
  expires_in: 3600,
  user: {
    id: "u_123",
    display_name: "TestUser",
    email: "test@example.com",
    discord_username: null,
    avatar_url: null,
    onboarding_completed: false,
    email_verified: true,
  },
};

function mockFetchSuccess(body: unknown, status = 200) {
  return vi.fn().mockResolvedValue({
    ok: true,
    status,
    json: () => Promise.resolve(body),
    statusText: "OK",
  });
}

function mockFetchError(status: number, detail: string | unknown) {
  return vi.fn().mockResolvedValue({
    ok: false,
    status,
    json: () => Promise.resolve({ detail }),
    statusText: "Error",
  });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("auth API functions", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", mockFetchSuccess({}));
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("fetchMe", () => {
    it("calls /api/v1/auth/me with Bearer token", async () => {
      const fetchMock = mockFetchSuccess(MOCK_USER);
      vi.stubGlobal("fetch", fetchMock);

      const result = await fetchMe("my_token");

      expect(fetchMock).toHaveBeenCalledOnce();
      const [url, opts] = fetchMock.mock.calls[0];
      expect(url).toBe("https://api.test.mcav.live/api/v1/auth/me");
      expect(opts.headers.Authorization).toBe("Bearer my_token");
      expect(result.id).toBe("u_123");
    });

    it("throws on non-ok response with detail message", async () => {
      vi.stubGlobal("fetch", mockFetchError(403, "Not authorized"));

      await expect(fetchMe("bad_token")).rejects.toThrow("Not authorized");
    });

    it("throws with status code when no detail string", async () => {
      vi.stubGlobal("fetch", mockFetchError(500, { unexpected: true }));

      await expect(fetchMe("tok")).rejects.toThrow("Request failed: 500");
    });

    it("handles validation error arrays in detail", async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 422,
        json: () =>
          Promise.resolve({
            detail: [
              { msg: "field required", loc: ["body", "email"] },
              { msg: "invalid format", loc: ["body", "password"] },
            ],
          }),
        statusText: "Unprocessable Entity",
      });
      vi.stubGlobal("fetch", fetchMock);

      await expect(fetchMe("tok")).rejects.toThrow(
        "field required; invalid format"
      );
    });
  });

  describe("refreshToken", () => {
    it("POSTs to /api/v1/auth/refresh with refresh_token body", async () => {
      const fetchMock = mockFetchSuccess(MOCK_AUTH_RESPONSE);
      vi.stubGlobal("fetch", fetchMock);

      const result = await refreshToken("ref_abc");

      const [url, opts] = fetchMock.mock.calls[0];
      expect(url).toBe("https://api.test.mcav.live/api/v1/auth/refresh");
      expect(opts.method).toBe("POST");
      expect(JSON.parse(opts.body)).toEqual({ refresh_token: "ref_abc" });
      expect(result.access_token).toBe("acc_new");
    });
  });

  describe("loginWithEmail", () => {
    it("POSTs credentials to /api/v1/auth/login", async () => {
      const fetchMock = mockFetchSuccess(MOCK_AUTH_RESPONSE);
      vi.stubGlobal("fetch", fetchMock);

      const result = await loginWithEmail("user@test.com", "secret123");

      const [url, opts] = fetchMock.mock.calls[0];
      expect(url).toBe("https://api.test.mcav.live/api/v1/auth/login");
      expect(opts.method).toBe("POST");
      expect(JSON.parse(opts.body)).toEqual({
        email: "user@test.com",
        password: "secret123",
      });
      expect(result.access_token).toBe("acc_new");
    });
  });

  describe("register", () => {
    it("POSTs registration data to /api/v1/auth/register", async () => {
      const fetchMock = mockFetchSuccess(MOCK_AUTH_RESPONSE);
      vi.stubGlobal("fetch", fetchMock);

      const result = await register("new@test.com", "pass", "New User");

      const [url, opts] = fetchMock.mock.calls[0];
      expect(url).toBe("https://api.test.mcav.live/api/v1/auth/register");
      expect(JSON.parse(opts.body)).toEqual({
        email: "new@test.com",
        password: "pass",
        display_name: "New User",
      });
      expect(result.token_type).toBe("bearer");
    });
  });

  describe("logout", () => {
    it("POSTs to /api/v1/auth/logout with refresh token", async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 204,
        json: () => Promise.resolve(undefined),
        statusText: "No Content",
      });
      vi.stubGlobal("fetch", fetchMock);

      await logout("ref_tok");

      const [url, opts] = fetchMock.mock.calls[0];
      expect(url).toBe("https://api.test.mcav.live/api/v1/auth/logout");
      expect(opts.method).toBe("POST");
      expect(JSON.parse(opts.body)).toEqual({ refresh_token: "ref_tok" });
    });
  });

  describe("401 triggers token refresh retry", () => {
    it("retries with new token after refresh on 401", async () => {
      // Store a refresh token so tryRefreshAccessToken can use it
      storeRefreshToken("stored_refresh");

      let callCount = 0;
      const fetchMock = vi.fn().mockImplementation((url: string) => {
        callCount++;
        if (url.endsWith("/api/v1/auth/me") && callCount === 1) {
          // First call: 401
          return Promise.resolve({
            ok: false,
            status: 401,
            json: () => Promise.resolve({ detail: "Token expired" }),
            statusText: "Unauthorized",
          });
        }
        if (url.endsWith("/api/v1/auth/refresh")) {
          // Refresh call
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                ...MOCK_AUTH_RESPONSE,
                access_token: "refreshed_token",
              }),
            statusText: "OK",
          });
        }
        // Retry call with new token
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve(MOCK_USER),
          statusText: "OK",
        });
      });
      vi.stubGlobal("fetch", fetchMock);

      const result = await fetchMe("expired_token");

      expect(result.id).toBe("u_123");
      // Should have made 3 calls: original, refresh, retry
      expect(fetchMock).toHaveBeenCalledTimes(3);
    });
  });
});

describe("getOAuthProvider", () => {
  it("returns 'discord' for a Discord state JWT", () => {
    // Create a fake JWT with provider: discord
    const payload = btoa(JSON.stringify({ provider: "discord" }));
    const fakeJwt = `header.${payload}.signature`;
    expect(getOAuthProvider(fakeJwt)).toBe("discord");
  });

  it("returns 'google' for a Google state JWT", () => {
    const payload = btoa(JSON.stringify({ provider: "google" }));
    const fakeJwt = `header.${payload}.signature`;
    expect(getOAuthProvider(fakeJwt)).toBe("google");
  });

  it("returns 'discord' as default for malformed state", () => {
    expect(getOAuthProvider("not-a-jwt")).toBe("discord");
  });

  it("returns 'discord' for empty string", () => {
    expect(getOAuthProvider("")).toBe("discord");
  });

  it("handles base64url encoding (- and _)", () => {
    // provider: google with base64url chars
    const payload = btoa(JSON.stringify({ provider: "google" }))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
    const fakeJwt = `header.${payload}.signature`;
    expect(getOAuthProvider(fakeJwt)).toBe("google");
  });
});

describe("localStorage helpers", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  describe("refresh token", () => {
    it("stores and retrieves a refresh token", () => {
      expect(getStoredRefreshToken()).toBeNull();
      storeRefreshToken("my_refresh_token");
      expect(getStoredRefreshToken()).toBe("my_refresh_token");
    });

    it("clears the stored refresh token", () => {
      storeRefreshToken("tok");
      clearStoredRefreshToken();
      expect(getStoredRefreshToken()).toBeNull();
    });
  });

  describe("OAuth state", () => {
    it("stores and retrieves OAuth state", () => {
      expect(getStoredOAuthState()).toBeNull();
      storeOAuthState("state_abc");
      expect(getStoredOAuthState()).toBe("state_abc");
    });

    it("clears stored OAuth state", () => {
      storeOAuthState("state_xyz");
      clearStoredOAuthState();
      expect(getStoredOAuthState()).toBeNull();
    });
  });
});
