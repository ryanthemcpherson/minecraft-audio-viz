"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import {
  exchangeDiscordCode,
  getStoredOAuthState,
  clearStoredOAuthState,
} from "@/lib/auth";
import Link from "next/link";

function CallbackHandler() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { setAuth } = useAuth();

  const [error, setError] = useState<string | null>(null);
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) return;

    const errorParam = searchParams.get("error");
    if (errorParam) {
      const desc = searchParams.get("error_description") || "Permission denied";
      // Clean the URL before showing error
      window.history.replaceState({}, "", "/login");
      queueMicrotask(() => setError(desc));
      return;
    }

    const code = searchParams.get("code");
    const state = searchParams.get("state");

    if (!code || !state) {
      queueMicrotask(() => setError("Missing authorization code. Please try signing in again."));
      return;
    }

    // Mark as handled so we don't re-process on re-render
    handled.current = true;

    // Strip OAuth params from the URL immediately so password managers
    // (1Password, etc.) save a clean "https://mcav.live/login" entry
    // instead of the full callback URL with code/state params.
    window.history.replaceState({}, "", "/login");

    // Validate state against stored value (CSRF protection)
    const storedState = getStoredOAuthState();
    clearStoredOAuthState();

    if (storedState && storedState !== state) {
      queueMicrotask(() => setError("Security validation failed. Please try signing in again."));
      return;
    }

    exchangeDiscordCode(code, state)
      .then((res) => {
        setAuth(res.access_token, res.refresh_token, res.user);
        router.replace(res.user.onboarding_completed ? "/dashboard" : "/onboarding");
      })
      .catch((err) => {
        setError(
          err instanceof Error ? err.message : "Failed to complete sign-in"
        );
      });
  }, [searchParams, setAuth, router]);

  if (error) {
    return (
      <div className="relative w-full max-w-md glass-card rounded-2xl p-8 text-center">
        <div className="mb-4 text-4xl">!</div>
        <h1 className="mb-2 text-xl font-semibold text-white">
          Sign-in failed
        </h1>
        <p className="mb-6 text-sm text-text-secondary">{error}</p>
        <Link
          href="/login"
          className="inline-block rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-6 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90"
        >
          Back to login
        </Link>
      </div>
    );
  }

  return (
    <div className="relative w-full max-w-md glass-card rounded-2xl p-8 text-center">
      <div className="mb-4 flex justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-disc-cyan" />
      </div>
      <p className="text-sm text-text-secondary">
        Completing sign-in...
      </p>
    </div>
  );
}

export default function AuthCallbackPage() {
  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-disc-cyan/5 rounded-full blur-[120px]" />
      <Suspense
        fallback={
          <div className="relative w-full max-w-md glass-card rounded-2xl p-8 text-center">
            <div className="mb-4 flex justify-center">
              <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-disc-cyan" />
            </div>
            <p className="text-sm text-text-secondary">Loading...</p>
          </div>
        }
      >
        <CallbackHandler />
      </Suspense>
    </div>
  );
}
