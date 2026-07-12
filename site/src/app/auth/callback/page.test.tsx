import { act, StrictMode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  getStoredOAuthState,
  storeOAuthState,
  type AuthResponse,
} from "@/lib/auth";
import AuthCallbackPage from "./page";

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
  .IS_REACT_ACT_ENVIRONMENT = true;

const navigation = vi.hoisted(() => ({
  router: {
    replace: vi.fn(),
  },
  searchParams: null as URLSearchParams | null,
}));

const authContext = vi.hoisted(() => ({
  setAuth: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => navigation.router,
  useSearchParams: () => navigation.searchParams,
}));

vi.mock("@/components/AuthProvider", () => ({
  useAuth: () => ({
    setAuth: authContext.setAuth,
  }),
}));

const AUTH_RESPONSE: AuthResponse = {
  access_token: "access-token",
  refresh_token: "refresh-token",
  token_type: "bearer",
  expires_in: 3600,
  user: {
    id: "user-1",
    display_name: "Test DJ",
    email: "dj@example.com",
    discord_username: "test-dj",
    avatar_url: null,
    onboarding_completed: false,
    email_verified: true,
    is_admin: false,
  },
};

function makeState(
  provider: "discord" | "google" = "discord",
  desktop = false
): string {
  const payload = btoa(JSON.stringify({ provider, desktop }))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return `header.${payload}.signature`;
}

function successfulAuthFetch() {
  return vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: () => Promise.resolve(AUTH_RESPONSE),
    statusText: "OK",
  });
}

describe("AuthCallbackPage direct OAuth callbacks", () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    navigation.searchParams = new URLSearchParams();
    window.history.replaceState({}, "", "/auth/callback");
    vi.stubGlobal("fetch", vi.fn());

    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  async function renderCallback(strict = false): Promise<void> {
    await act(async () => {
      root.render(
        strict ? (
          <StrictMode>
            <AuthCallbackPage />
          </StrictMode>
        ) : (
          <AuthCallbackPage />
        )
      );
    });
  }

  async function remountCallback(): Promise<void> {
    await act(async () => root.unmount());
    root = createRoot(container);
  }

  it("consumes provider-error state and handles it only once in Strict Mode", async () => {
    const state = makeState();
    storeOAuthState(state);
    navigation.searchParams = new URLSearchParams({
      error: "access_denied",
      error_description: "Permission denied",
      state,
    });
    const replaceState = vi.spyOn(window.history, "replaceState");

    await renderCallback(true);
    navigation.searchParams = new URLSearchParams(navigation.searchParams);
    await renderCallback(true);

    expect(getStoredOAuthState()).toBeNull();
    expect(container.textContent).toContain("Permission denied");
    expect(replaceState).toHaveBeenCalledOnce();
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each([
    ["authorization code", new URLSearchParams({ state: makeState() })],
    ["state", new URLSearchParams({ code: "authorization-code" })],
  ])("consumes stored state when callback is missing %s", async (_, params) => {
    storeOAuthState("single-use-state");
    navigation.searchParams = params;

    await renderCallback();

    expect(getStoredOAuthState()).toBeNull();
    expect(container.textContent).toContain("Missing authorization code");
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each([
    [
      "orphaned error description",
      new URLSearchParams({ error_description: "orphaned" }),
    ],
    ["empty code and state", new URLSearchParams({ code: "", state: "" })],
  ])(
    "consumes stored state for a malformed callback with %s",
    async (_, params) => {
      storeOAuthState("single-use-state");
      navigation.searchParams = params;

      await renderCallback();

      expect(getStoredOAuthState()).toBeNull();
      expect(container.textContent).toContain("Missing authorization code");
      expect(fetch).not.toHaveBeenCalled();
    }
  );

  it("rejects replay after a terminal callback consumed the state", async () => {
    const state = makeState();
    storeOAuthState(state);
    navigation.searchParams = new URLSearchParams({
      error: "access_denied",
      state,
    });

    await renderCallback();
    await remountCallback();

    navigation.searchParams = new URLSearchParams({
      code: "replayed-code",
      state,
    });
    await renderCallback();

    expect(getStoredOAuthState()).toBeNull();
    expect(container.textContent).toContain("Security validation failed");
    expect(fetch).not.toHaveBeenCalled();
  });

  it("preserves in-flight state on a genuine no-callback visit", async () => {
    storeOAuthState("in-flight-state");
    const replaceState = vi.spyOn(window.history, "replaceState");

    await renderCallback(true);

    expect(getStoredOAuthState()).toBe("in-flight-state");
    expect(replaceState).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("exchanges a valid browser callback only once across Strict Mode and rerender", async () => {
    const state = makeState("google");
    storeOAuthState(state);
    navigation.searchParams = new URLSearchParams({
      code: "browser-code",
      state,
    });
    const fetchMock = successfulAuthFetch();
    vi.stubGlobal("fetch", fetchMock);

    await renderCallback(true);
    navigation.searchParams = new URLSearchParams(navigation.searchParams);
    await renderCallback(true);

    expect(getStoredOAuthState()).toBeNull();
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0][0]).toContain(
      "/api/v1/auth/google/callback?"
    );
    expect(authContext.setAuth).toHaveBeenCalledOnce();
    expect(authContext.setAuth).toHaveBeenCalledWith(
      "access-token",
      "refresh-token",
      AUTH_RESPONSE.user
    );
    expect(navigation.router.replace).toHaveBeenCalledOnce();
    expect(navigation.router.replace).toHaveBeenCalledWith("/onboarding");
  });

  it("forwards a desktop callback without consuming browser state", async () => {
    const desktopState = makeState("discord", true);
    storeOAuthState("unrelated-browser-state");
    navigation.searchParams = new URLSearchParams({
      code: "desktop-code",
      state: desktopState,
    });
    const realWindow = window;
    let redirectedTo: string | null = null;
    const windowProxy = new Proxy(realWindow, {
      get(target, property) {
        if (property === "location") {
          return {
            get href() {
              return target.location.href;
            },
            set href(value: string) {
              redirectedTo = value;
            },
          };
        }
        return Reflect.get(target, property, target);
      },
    });
    vi.stubGlobal("window", windowProxy);

    await renderCallback();

    expect(redirectedTo).toBe(
      `/api/v1/auth/discord/callback?${new URLSearchParams({
        code: "desktop-code",
        state: desktopState,
      })}`
    );
    expect(getStoredOAuthState()).toBe("unrelated-browser-state");
    expect(fetch).not.toHaveBeenCalled();
  });
});
