"use client";

import { useState } from "react";
import Link from "next/link";
import { requestPasswordReset } from "@/lib/auth";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      await requestPasswordReset(email);
      setSuccess(true);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-disc-cyan/5 rounded-full blur-[120px]" />

      <div className="relative w-full max-w-md glass-card rounded-2xl p-8">
        <h1 className="mb-2 text-xl font-semibold text-white">Reset your password</h1>
        <p className="mb-6 text-sm text-text-secondary">
          Enter your email address and we&apos;ll send you a link to reset your password.
        </p>

        {success ? (
          <div className="flex flex-col gap-4">
            <div className="rounded-lg bg-green-500/10 px-4 py-3 text-sm text-green-400">
              Check your email for a password reset link. It may take a minute to arrive.
            </div>
            <Link
              href="/login"
              className="text-center text-sm text-disc-cyan hover:underline"
            >
              Back to login
            </Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {error && (
              <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
                {error}
              </p>
            )}

            <div>
              <label htmlFor="reset-email" className="mb-1 block text-sm text-text-secondary">
                Email
              </label>
              <input
                id="reset-email"
                name="email"
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-disc-cyan/50"
                placeholder="you@example.com"
              />
            </div>

            <button
              type="submit"
              disabled={loading || !email}
              className="rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
            >
              {loading ? "Sending..." : "Send reset link"}
            </button>

            <Link
              href="/login"
              className="text-center text-sm text-text-secondary hover:text-white transition-colors"
            >
              Back to login
            </Link>
          </form>
        )}
      </div>
    </div>
  );
}
