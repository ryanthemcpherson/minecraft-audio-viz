"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/components/AuthProvider";
import Alert from "@/components/ui/Alert";
import Spinner from "@/components/ui/Spinner";
import { Input, Label } from "@/components/ui/Field";
import {
  register,
  loginWithEmail,
  getDiscordAuthUrl,
  getGoogleAuthUrl,
  exchangeDiscordCode,
  exchangeGoogleCode,
  exchangeOAuthCodeWithValidatedState,
  getOAuthProvider,
  clearStoredOAuthState,
} from "@/lib/auth";

type Tab = "login" | "signup";

/** Primary submit styling, mirroring Button's primary variant (which has no disabled prop). */
const submitButtonClassName =
  "mt-2 inline-flex items-center justify-center gap-2 rounded-xl bg-disc-cyan px-6 py-3 text-sm font-semibold whitespace-nowrap text-white transition-all duration-200 select-none active:brightness-95 disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:shadow-lg disabled:hover:brightness-100";
type ConsumedCookie =
  | { found: false }
  | { found: true; value: string | null };

/** Read and delete a cookie by name. */
function consumeCookie(name: string): ConsumedCookie {
  if (typeof document === "undefined") return { found: false };
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  if (!match) return { found: false };
  // Delete it immediately
  document.cookie = `${name}=; path=/; max-age=0`;
  try {
    return { found: true, value: decodeURIComponent(match[1]) };
  } catch {
    return { found: true, value: null };
  }
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

    const oauthErrorCookie = consumeCookie("mcav_oauth_error");
    if (oauthErrorCookie.found) {
      oauthHandled.current = true;
      clearStoredOAuthState();
      setError(
        oauthErrorCookie.value === null
          ? "Invalid OAuth response. Please try signing in again."
          : oauthErrorCookie.value || "OAuth sign-in failed. Please try again."
      );
      return;
    }

    const oauthCodeCookie = consumeCookie("mcav_oauth_code");
    if (!oauthCodeCookie.found) return;

    oauthHandled.current = true;
    const oauthCode = oauthCodeCookie.value;
    if (oauthCode === null) {
      clearStoredOAuthState();
      setError("Invalid OAuth response. Please try signing in again.");
      return;
    }
    const separatorIdx = oauthCode.indexOf(":");
    if (separatorIdx <= 0 || separatorIdx === oauthCode.length - 1) {
      clearStoredOAuthState();
      setError("Invalid OAuth response. Please try signing in again.");
      return;
    }

    const code = oauthCode.slice(0, separatorIdx);
    const state = oauthCode.slice(separatorIdx + 1);

    setOauthLoading(true);
    const provider = getOAuthProvider(state);
    const exchangeFn = provider === "google" ? exchangeGoogleCode : exchangeDiscordCode;
    exchangeOAuthCodeWithValidatedState(code, state, exchangeFn)
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
      setError(err instanceof Error ? err.message : "Unable to complete sign-in. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  async function handleDiscord() {
    try {
      const url = await getDiscordAuthUrl();
      window.location.href = url;
    } catch (err) {
      console.error("Failed to get Discord auth URL:", err);
      setError("Could not start Discord sign-in");
    }
  }

  async function handleGoogle() {
    try {
      const url = await getGoogleAuthUrl();
      window.location.href = url;
    } catch (err) {
      console.error("Failed to get Google auth URL:", err);
      setError("Could not start Google sign-in");
    }
  }

  // Show spinner while exchanging Discord OAuth code
  if (oauthLoading) {
    return (
      <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
<div className="relative w-full max-w-md glass-card rounded-2xl p-8 text-center">
          <div className="mb-4 flex justify-center">
            <Spinner label="Completing sign-in" />
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
<div className="relative w-full max-w-md glass-card rounded-2xl p-8">
        <p className="mb-2 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
          Account
        </p>
        <h1 className="mb-6 font-heading text-2xl font-bold leading-tight">
          {tab === "login" ? "Welcome back" : "Create your account"}
        </h1>

        {/* Tabs */}
        <div className="mb-8 flex rounded-xl border border-white/5 bg-white/[0.02] p-1">
          <button
            onClick={() => { setTab("login"); setError(""); setEmail(""); setPassword(""); setDisplayName(""); }}
            className={`flex-1 rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
              tab === "login"
                ? "bg-white/10 text-white"
                : "text-text-secondary hover:text-text-primary"
            }`}
          >
            Log in
          </button>
          <button
            onClick={() => { setTab("signup"); setError(""); setEmail(""); setPassword(""); setDisplayName(""); }}
            className={`flex-1 rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
              tab === "signup"
                ? "bg-white/10 text-white"
                : "text-text-secondary hover:text-text-primary"
            }`}
          >
            Sign up
          </button>
        </div>

        {/* OAuth buttons */}
        <div className="mb-6 flex flex-col gap-3">
          <button
            onClick={handleDiscord}
            className="flex w-full items-center justify-center gap-2.5 rounded-xl bg-[#5865F2] px-4 py-3 text-sm font-semibold text-white transition-colors hover:bg-[#4752C4]"
          >
            <svg width="24" height="24" viewBox="0 -28.5 256 256" fill="currentColor" aria-hidden="true">
              <path d="M216.856 16.597A208.502 208.502 0 00164.042 0c-2.275 4.113-4.933 9.645-6.766 14.046-19.692-2.961-39.203-2.961-58.533 0-1.832-4.4-4.55-9.933-6.846-14.046a207.809 207.809 0 00-52.855 16.638C5.618 67.147-3.443 116.4 1.087 164.956c22.169 16.555 43.653 26.612 64.775 33.193a161.094 161.094 0 0013.89-22.985 136.664 136.664 0 01-21.846-10.632 108.636 108.636 0 005.356-4.237c42.122 19.702 87.89 19.702 129.51 0a131.66 131.66 0 005.355 4.237 136.07 136.07 0 01-21.886 10.653c4.006 8.02 8.638 15.67 13.89 22.985 21.142-6.58 42.646-16.637 64.815-33.213 5.316-56.288-9.08-105.09-38.056-148.36zM85.474 135.095c-12.645 0-23.015-11.805-23.015-26.18s10.149-26.2 23.015-26.2c12.867 0 23.236 11.804 23.015 26.2.02 14.375-10.148 26.18-23.015 26.18zm85.051 0c-12.645 0-23.014-11.805-23.014-26.18s10.148-26.2 23.014-26.2c12.867 0 23.236 11.804 23.015 26.2 0 14.375-10.148 26.18-23.015 26.18z" />
            </svg>
            Continue with Discord
          </button>

          <button
            onClick={handleGoogle}
            className="flex w-full items-center justify-center gap-2.5 rounded-xl border border-white/10 bg-white/5 px-4 py-3 text-sm font-semibold text-white backdrop-blur-sm transition-colors hover:border-white/20 hover:bg-white/10"
          >
            <svg width="20" height="20" viewBox="0 0 48 48" aria-hidden="true">
              <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
              <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
              <path fill="#FBBC05" d="M10.53 28.59a14.5 14.5 0 010-9.18l-7.98-6.19a24.0 24.0 0 000 21.56l7.98-6.19z" />
              <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
            </svg>
            Continue with Google
          </button>
        </div>

        <div className="mb-6 flex items-center gap-3">
          <div className="h-px flex-1 bg-white/10" />
          <span className="font-mono text-[11px] uppercase tracking-wider text-text-secondary">or</span>
          <div className="h-px flex-1 bg-white/10" />
        </div>

        {error && (
          <Alert tone="danger" className="mb-4">
            {error}
          </Alert>
        )}

        {/* Login form */}
        <form
          onSubmit={handleSubmit}
          className={`flex flex-col gap-4 ${tab !== "login" ? "hidden" : ""}`}
        >
          <div>
            <Label htmlFor="login-email">
              Email
            </Label>
            <Input
              id="login-email"
              name="email"
              type="email"
              required={tab === "login"}
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
            />
          </div>

          <div>
            <Label htmlFor="login-password">
              Password
            </Label>
            <Input
              id="login-password"
              name="password"
              type="password"
              required={tab === "login"}
              autoComplete="current-password"
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Your password"
            />
          </div>

          <div className="text-right">
            <Link href="/forgot-password" className="text-xs text-text-secondary transition-colors hover:text-disc-cyan">
              Forgot password?
            </Link>
          </div>

          <button
            type="submit"
            disabled={loading}
            className={submitButtonClassName}
          >
            {loading ? "Logging in\u2026" : "Log in"}
          </button>
        </form>

        {/* Signup form */}
        <form
          onSubmit={handleSubmit}
          className={`flex flex-col gap-4 ${tab !== "signup" ? "hidden" : ""}`}
        >
          <div>
            <Label htmlFor="signup-name">
              Display name
            </Label>
            <Input
              id="signup-name"
              name="name"
              type="text"
              required={tab === "signup"}
              autoComplete="name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="Your name"
            />
          </div>

          <div>
            <Label htmlFor="signup-email">
              Email
            </Label>
            <Input
              id="signup-email"
              name="email"
              type="email"
              required={tab === "signup"}
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
            />
          </div>

          <div>
            <Label htmlFor="signup-password">
              Password
            </Label>
            <Input
              id="signup-password"
              name="new-password"
              type="password"
              required={tab === "signup"}
              autoComplete="new-password"
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Min 8 characters"
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className={submitButtonClassName}
          >
            {loading ? "Creating account\u2026" : "Create account"}
          </button>
        </form>

        <p className="mt-6 text-center text-xs text-text-secondary/60">
          By signing in, you agree to our{" "}
          <a href="/terms" className="text-text-secondary underline transition-colors hover:text-white">Terms of Service</a>
          {" "}and{" "}
          <a href="/privacy" className="text-text-secondary underline transition-colors hover:text-white">Privacy Policy</a>.
        </p>
      </div>
    </div>
  );
}
