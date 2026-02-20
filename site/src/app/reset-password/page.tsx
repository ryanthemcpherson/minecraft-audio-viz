"use client";

import { useState, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { resetPassword } from "@/lib/auth";

function ResetPasswordForm() {
  const searchParams = useSearchParams();
  const token = searchParams.get("token") ?? "";

  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState(false);

  function validatePassword(pw: string): string | null {
    if (pw.length < 8) return "Password must be at least 8 characters";
    if (!/[A-Z]/.test(pw)) return "Password must contain at least one uppercase letter";
    if (!/[0-9]/.test(pw)) return "Password must contain at least one digit";
    return null;
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");

    if (password !== confirmPassword) {
      setError("Passwords do not match");
      return;
    }

    const strengthError = validatePassword(password);
    if (strengthError) {
      setError(strengthError);
      return;
    }

    if (!token) {
      setError("Missing reset token. Please use the link from your email.");
      return;
    }

    setLoading(true);
    try {
      await resetPassword(token, password);
      setSuccess(true);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setLoading(false);
    }
  }

  if (!token) {
    return (
      <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
        <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-disc-cyan/5 rounded-full blur-[120px]" />
        <div className="relative w-full max-w-md glass-card rounded-2xl p-8">
          <p className="mb-4 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
            Invalid reset link. Please request a new password reset.
          </p>
          <Link href="/forgot-password" className="text-sm text-disc-cyan hover:underline">
            Request new reset link
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-disc-cyan/5 rounded-full blur-[120px]" />

      <div className="relative w-full max-w-md glass-card rounded-2xl p-8">
        <h1 className="mb-2 text-xl font-semibold text-white">Set new password</h1>
        <p className="mb-6 text-sm text-text-secondary">
          Enter your new password below.
        </p>

        {success ? (
          <div className="flex flex-col gap-4">
            <div className="rounded-lg bg-green-500/10 px-4 py-3 text-sm text-green-400">
              Your password has been reset successfully.
            </div>
            <Link
              href="/login"
              className="rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-4 py-3 text-center text-sm font-semibold text-white transition-opacity hover:opacity-90"
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
              <label htmlFor="new-password" className="mb-1 block text-sm text-text-secondary">
                New password
              </label>
              <input
                id="new-password"
                name="new-password"
                type="password"
                required
                autoComplete="new-password"
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-disc-cyan/50"
                placeholder="Min 8 characters, 1 uppercase, 1 digit"
              />
            </div>

            <div>
              <label htmlFor="confirm-password" className="mb-1 block text-sm text-text-secondary">
                Confirm password
              </label>
              <input
                id="confirm-password"
                name="confirm-password"
                type="password"
                required
                autoComplete="new-password"
                minLength={8}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="w-full rounded-lg border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm text-white outline-none transition-colors focus:border-disc-cyan/50"
                placeholder="Confirm your password"
              />
            </div>

            <button
              type="submit"
              disabled={loading || !password || !confirmPassword}
              className="rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-4 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
            >
              {loading ? "Resetting..." : "Reset password"}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={
      <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-disc-cyan" />
      </div>
    }>
      <ResetPasswordForm />
    </Suspense>
  );
}
