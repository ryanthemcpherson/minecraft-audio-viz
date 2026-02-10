"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import {
  exchangeDiscordCode,
  getStoredOAuthState,
  clearStoredOAuthState,
} from "@/lib/auth";
import Link from "next/link";

export default function AuthCallbackPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { setAuth } = useAuth();

  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const errorParam = searchParams.get("error");
    if (errorParam) {
      const desc = searchParams.get("error_description") || "Permission denied";
      setError(desc);
      return;
    }

    const code = searchParams.get("code");
    const state = searchParams.get("state");

    if (!code || !state) {
      setError("Missing authorization code. Please try signing in again.");
      return;
    }

    // Validate state against stored value (CSRF protection)
    const storedState = getStoredOAuthState();
    clearStoredOAuthState();

    if (storedState && storedState !== state) {
      setError("Security validation failed. Please try signing in again.");
      return;
    }

    exchangeDiscordCode(code, state)
      .then((res) => {
        setAuth(res.access_token, res.refresh_token, res.user);
        router.replace("/dashboard");
      })
      .catch((err) => {
        setError(
          err instanceof Error ? err.message : "Failed to complete sign-in"
        );
      });
  }, [searchParams, setAuth, router]);

  if (error) {
    return (
      <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
        <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-red-500/5 rounded-full blur-[120px]" />

        <div className="relative w-full max-w-md glass-card rounded-2xl p-8 text-center">
          <div className="mb-4 text-4xl">!</div>
          <h1 className="mb-2 text-xl font-semibold text-white">
            Sign-in failed
          </h1>
          <p className="mb-6 text-sm text-text-secondary">{error}</p>
          <Link
            href="/login"
            className="inline-block rounded-lg bg-gradient-to-r from-electric-blue to-deep-purple px-6 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90"
          >
            Back to login
          </Link>
        </div>
      </div>
    );
  }

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
