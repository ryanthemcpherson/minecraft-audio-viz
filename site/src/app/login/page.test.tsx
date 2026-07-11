import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { getStoredOAuthState, storeOAuthState } from "@/lib/auth";
import LoginPage from "./page";

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
  .IS_REACT_ACT_ENVIRONMENT = true;

const router = vi.hoisted(() => ({
  push: vi.fn(),
  replace: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => router,
}));

vi.mock("@/components/AuthProvider", () => ({
  useAuth: () => ({
    user: null,
    loading: false,
    setAuth: vi.fn(),
  }),
}));

describe("LoginPage OAuth callback handling", () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    document.cookie = "mcav_oauth_error=; path=/; max-age=0";
    document.cookie = "mcav_oauth_code=; path=/; max-age=0";
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
  });

  async function renderLoginPage(): Promise<void> {
    await act(async () => {
      root.render(<LoginPage />);
    });
  }

  it("consumes stored state when the provider returns an OAuth error", async () => {
    storeOAuthState("single-use-state");
    document.cookie = "mcav_oauth_error=Access%20denied; path=/";

    await renderLoginPage();

    expect(getStoredOAuthState()).toBeNull();
    expect(container.textContent).toContain("Access denied");
  });

  it.each(["missing-separator", "code%3A"])(
    "consumes stored state when the callback cookie is malformed (%s)",
    async (cookieValue) => {
    storeOAuthState("single-use-state");
      document.cookie = `mcav_oauth_code=${cookieValue}; path=/`;

    await renderLoginPage();

    expect(getStoredOAuthState()).toBeNull();
    expect(container.textContent).toContain("Invalid OAuth response");
    },
  );

  it("consumes stored state when the provider error cookie is empty", async () => {
    storeOAuthState("single-use-state");
    document.cookie = "mcav_oauth_error=; path=/";

    await renderLoginPage();

    expect(getStoredOAuthState()).toBeNull();
    expect(container.textContent).toContain("OAuth sign-in failed");
  });

  it("consumes stored state when the callback code cookie is empty", async () => {
    storeOAuthState("single-use-state");
    document.cookie = "mcav_oauth_code=; path=/";

    await renderLoginPage();

    expect(getStoredOAuthState()).toBeNull();
    expect(container.textContent).toContain("Invalid OAuth response");
  });

  it.each(["mcav_oauth_error", "mcav_oauth_code"])(
    "consumes stored state when %s has malformed percent-encoding",
    async (cookieName) => {
      storeOAuthState("single-use-state");
      document.cookie = `${cookieName}=%E0%A4%A; path=/`;

      await renderLoginPage();

      expect(getStoredOAuthState()).toBeNull();
      expect(container.textContent).toContain("Invalid OAuth response");
    },
  );

  it("preserves an in-flight OAuth state when there is no callback cookie", async () => {
    storeOAuthState("in-flight-state");

    await renderLoginPage();

    expect(getStoredOAuthState()).toBe("in-flight-state");
    expect(container.textContent).not.toContain("Invalid OAuth response");
  });
});
