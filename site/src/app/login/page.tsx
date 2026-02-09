"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import {
  register,
  loginWithEmail,
  getDiscordAuthUrl,
} from "@/lib/auth";

type Tab = "login" | "signup";

export default function LoginPage() {
  const router = useRouter();
  const { setAuth } = useAuth();

  const [tab, setTab] = useState<Tab>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

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
      router.push("/dashboard");
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

  return (
    <div className="flex min-h-screen items-center justify-center px-4 pt-20">
      <div className="w-full max-w-md rounded-2xl border border-white/5 bg-white/[0.02] p-8 backdrop-blur-xl">
        {/* Tabs */}
        <div className="mb-8 flex rounded-lg border border-white/5 bg-white/[0.02] p-1">
          <button
            onClick={() => { setTab("login"); setError(""); }}
            className={`flex-1 rounded-md px-4 py-2 text-sm font-medium transition-colors ${
              tab === "login"
                ? "bg-white/10 text-white"
                : "text-zinc-400 hover:text-zinc-200"
            }`}
          >
            Log in
          </button>
          <button
            onClick={() => { setTab("signup"); setError(""); }}
            className={`flex-1 rounded-md px-4 py-2 text-sm font-medium transition-colors ${
              tab === "signup"
                ? "bg-white/10 text-white"
                : "text-zinc-400 hover:text-zinc-200"
            }`}
          >
            Sign up
          </button>
        </div>

        {/* Discord */}
        <button
          onClick={handleDiscord}
          className="mb-6 flex w-full items-center justify-center gap-2 rounded-lg bg-[#5865F2] px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
            <path d="M20.317 4.369a19.791 19.791 0 00-4.885-1.515.074.074 0 00-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 00-5.487 0 12.64 12.64 0 00-.617-1.25.077.077 0 00-.079-.037A19.736 19.736 0 003.677 4.37a.07.07 0 00-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 00.031.057 19.9 19.9 0 005.993 3.03.078.078 0 00.084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 00-.041-.106 13.107 13.107 0 01-1.872-.892.077.077 0 01-.008-.128c.126-.094.252-.192.372-.291a.074.074 0 01.077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 01.078.009c.12.099.246.198.373.292a.077.077 0 01-.006.127 12.299 12.299 0 01-1.873.892.077.077 0 00-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 00.084.028 19.839 19.839 0 006.002-3.03.077.077 0 00.032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 00-.031-.03z" />
          </svg>
          Continue with Discord
        </button>

        <div className="mb-6 flex items-center gap-3">
          <div className="h-px flex-1 bg-white/10" />
          <span className="text-xs text-zinc-500">or</span>
          <div className="h-px flex-1 bg-white/10" />
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          {tab === "signup" && (
            <div>
              <label htmlFor="displayName" className="mb-1 block text-sm text-zinc-400">
                Display name
              </label>
              <input
                id="displayName"
                type="text"
                required
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-[#00D4FF]/50"
                placeholder="Your name"
              />
            </div>
          )}

          <div>
            <label htmlFor="email" className="mb-1 block text-sm text-zinc-400">
              Email
            </label>
            <input
              id="email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-[#00D4FF]/50"
              placeholder="you@example.com"
            />
          </div>

          <div>
            <label htmlFor="password" className="mb-1 block text-sm text-zinc-400">
              Password
            </label>
            <input
              id="password"
              type="password"
              required
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-[#00D4FF]/50"
              placeholder={tab === "signup" ? "Min 8 characters" : "Your password"}
            />
          </div>

          {error && (
            <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={loading}
            className="mt-2 rounded-lg bg-gradient-to-r from-[#00D4FF] to-[#7B2FFF] px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            {loading
              ? "..."
              : tab === "login"
                ? "Log in"
                : "Create account"}
          </button>
        </form>
      </div>
    </div>
  );
}
