"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import {
  register,
  loginWithEmail,
  getDiscordAuthUrl,
  exchangeDiscordCode,
  getStoredOAuthState,
  clearStoredOAuthState,
} from "@/lib/auth";

type Tab = "login" | "signup";

/** Read and delete a cookie by name. */
function consumeCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  if (!match) return null;
  // Delete it immediately
  document.cookie = `${name}=; path=/; max-age=0`;
  return decodeURIComponent(match[1]);
}

export default function LoginPage() {
  const router = useRouter();
  const { user, loading: authLoading, setAuth } = useAuth();

  const [tab, setTab] = useState<Tab>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [oauthLoading, setOauthLoading] = useState(false);
  const oauthHandled = useRef(false);

  // Redirect already-logged-in users to dashboard
  useEffect(() => {
    if (!authLoading && user) {
      router.replace(user.onboarding_completed ? "/dashboard" : "/onboarding");
    }
  }, [authLoading, user, router]);

  // Check for OAuth callback cookie (set by middleware redirect)
  useEffect(() => {
    if (oauthHandled.current) return;

    const oauthError = consumeCookie("mcav_oauth_error");
    if (oauthError) {
      oauthHandled.current = true;
      setError(oauthError);
      return;
    }

    const oauthCode = consumeCookie("mcav_oauth_code");
    if (!oauthCode) return;

    oauthHandled.current = true;
    const separatorIdx = oauthCode.indexOf(":");
    if (separatorIdx === -1) {
      setError("Invalid OAuth response. Please try signing in again.");
      return;
    }

    const code = oauthCode.slice(0, separatorIdx);
    const state = oauthCode.slice(separatorIdx + 1);

    // Validate CSRF state
    const storedState = getStoredOAuthState();
    clearStoredOAuthState();
    if (storedState && storedState !== state) {
      setError("Security validation failed. Please try signing in again.");
      return;
    }

    setOauthLoading(true);
    exchangeDiscordCode(code, state)
      .then((res) => {
        setAuth(res.access_token, res.refresh_token, res.user);
        router.replace(
          res.user.onboarding_completed ? "/dashboard" : "/onboarding"
        );
      })
      .catch((err) => {
        setError(
          err instanceof Error ? err.message : "Failed to complete sign-in"
        );
        setOauthLoading(false);
      });
  }, [setAuth, router]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      const res =
        tab === "login"
          ? await loginWithEmail(email, password)
          : await register(email, password, displayName);

      setAuth(res.access_token, res.refresh_token, res.user);
      router.push(res.user.onboarding_completed ? "/dashboard" : "/onboarding");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setLoading(false);
    }
  }

  async function handleDiscord() {
    try {
      const url = await getDiscordAuthUrl();
      window.location.href = url;
    } catch {
      setError("Could not start Discord sign-in");
    }
  }

  // Show spinner while exchanging Discord OAuth code
  if (oauthLoading) {
    return (
      <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
        <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-electric-blue/5 rounded-full blur-[120px]" />
        <div className="relative w-full max-w-md glass-card rounded-2xl p-8 text-center">
          <div className="mb-4 flex justify-center">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-electric-blue" />
          </div>
          <p className="text-sm text-text-secondary">
            Completing sign-in...
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
      {/* Background glow */}
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-electric-blue/5 rounded-full blur-[120px]" />

      <div className="relative w-full max-w-md glass-card rounded-2xl p-8">
        {/* Tabs */}
        <div className="mb-8 flex rounded-lg border border-white/5 bg-white/[0.02] p-1">
          <button
            onClick={() => { setTab("login"); setError(""); setEmail(""); setPassword(""); setDisplayName(""); }}
            className={`flex-1 rounded-md px-4 py-2 text-sm font-medium transition-colors ${
              tab === "login"
                ? "bg-white/10 text-white"
                : "text-text-secondary hover:text-text-primary"
            }`}
          >
            Log in
          </button>
          <button
            onClick={() => { setTab("signup"); setError(""); setEmail(""); setPassword(""); setDisplayName(""); }}
            className={`flex-1 rounded-md px-4 py-2 text-sm font-medium transition-colors ${
              tab === "signup"
                ? "bg-white/10 text-white"
                : "text-text-secondary hover:text-text-primary"
            }`}
          >
            Sign up
          </button>
        </div>

        {/* Discord */}
        <button
          onClick={handleDiscord}
          className="mb-6 flex w-full items-center justify-center gap-2.5 rounded-full bg-[#5865F2] px-4 py-3 text-base font-medium text-white transition-colors hover:bg-[#4752C4]"
        >
          <svg width="24" height="24" viewBox="0 -28.5 256 256" fill="currentColor">
            <path d="M216.856 16.597A208.502 208.502 0 00164.042 0c-2.275 4.113-4.933 9.645-6.766 14.046-19.692-2.961-39.203-2.961-58.533 0-1.832-4.4-4.55-9.933-6.846-14.046a207.809 207.809 0 00-52.855 16.638C5.618 67.147-3.443 116.4 1.087 164.956c22.169 16.555 43.653 26.612 64.775 33.193a161.094 161.094 0 0013.89-22.985 136.664 136.664 0 01-21.846-10.632 108.636 108.636 0 005.356-4.237c42.122 19.702 87.89 19.702 129.51 0a131.66 131.66 0 005.355 4.237 136.07 136.07 0 01-21.886 10.653c4.006 8.02 8.638 15.67 13.89 22.985 21.142-6.58 42.646-16.637 64.815-33.213 5.316-56.288-9.08-105.09-38.056-148.36zM85.474 135.095c-12.645 0-23.015-11.805-23.015-26.18s10.149-26.2 23.015-26.2c12.867 0 23.236 11.804 23.015 26.2.02 14.375-10.148 26.18-23.015 26.18zm85.051 0c-12.645 0-23.014-11.805-23.014-26.18s10.148-26.2 23.014-26.2c12.867 0 23.236 11.804 23.015 26.2 0 14.375-10.148 26.18-23.015 26.18z" />
          </svg>
          Continue with Discord
        </button>

        <div className="mb-6 flex items-center gap-3">
          <div className="h-px flex-1 bg-white/10" />
          <span className="text-xs text-text-secondary">or</span>
          <div className="h-px flex-1 bg-white/10" />
        </div>

        {error && (
          <p className="mb-4 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
            {error}
          </p>
        )}

        {/* Login form */}
        <form
          onSubmit={handleSubmit}
          className={`flex flex-col gap-4 ${tab !== "login" ? "hidden" : ""}`}
        >
          <div>
            <label htmlFor="login-email" className="mb-1 block text-sm text-text-secondary">
              Email
            </label>
            <input
              id="login-email"
              name="email"
              type="email"
              required={tab === "login"}
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              placeholder="you@example.com"
            />
          </div>

          <div>
            <label htmlFor="login-password" className="mb-1 block text-sm text-text-secondary">
              Password
            </label>
            <input
              id="login-password"
              name="password"
              type="password"
              required={tab === "login"}
              autoComplete="current-password"
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              placeholder="Your password"
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="mt-2 rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            {loading ? "..." : "Log in"}
          </button>
        </form>

        {/* Signup form */}
        <form
          onSubmit={handleSubmit}
          className={`flex flex-col gap-4 ${tab !== "signup" ? "hidden" : ""}`}
        >
          <div>
            <label htmlFor="signup-name" className="mb-1 block text-sm text-text-secondary">
              Display name
            </label>
            <input
              id="signup-name"
              name="name"
              type="text"
              required={tab === "signup"}
              autoComplete="name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              placeholder="Your name"
            />
          </div>

          <div>
            <label htmlFor="signup-email" className="mb-1 block text-sm text-text-secondary">
              Email
            </label>
            <input
              id="signup-email"
              name="email"
              type="email"
              required={tab === "signup"}
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              placeholder="you@example.com"
            />
          </div>

          <div>
            <label htmlFor="signup-password" className="mb-1 block text-sm text-text-secondary">
              Password
            </label>
            <input
              id="signup-password"
              name="new-password"
              type="password"
              required={tab === "signup"}
              autoComplete="new-password"
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-electric-blue/50"
              placeholder="Min 8 characters"
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="mt-2 rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            {loading ? "..." : "Create account"}
          </button>
        </form>
      </div>
    </div>
  );
}
