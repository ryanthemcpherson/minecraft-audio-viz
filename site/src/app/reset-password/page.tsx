"use client";

import { useState, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { resetPassword } from "@/lib/auth";
import Alert from "@/components/ui/Alert";
import Button from "@/components/ui/Button";
import Spinner from "@/components/ui/Spinner";
import { Input, Label } from "@/components/ui/Field";

/** Primary submit styling, mirroring Button's primary variant (which has no disabled prop). */
const submitButtonClassName =
  "inline-flex items-center justify-center gap-2 rounded-xl bg-disc-cyan px-6 py-3 text-sm font-semibold whitespace-nowrap text-white transition-all duration-200 select-none active:brightness-95 disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:shadow-lg disabled:hover:brightness-100";

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
<div className="relative w-full max-w-md glass-card rounded-2xl p-8">
          <Alert tone="danger" className="mb-4">
            Invalid reset link. Please request a new password reset.
          </Alert>
          <Button href="/forgot-password" variant="secondary" className="w-full">
            Request new reset link
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
<div className="relative w-full max-w-md glass-card rounded-2xl p-8">
        <p className="mb-2 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
          Account
        </p>
        <h1 className="mb-2 font-heading text-2xl font-bold leading-tight">Set new password</h1>
        <p className="mb-6 text-sm leading-relaxed text-text-secondary">
          Enter your new password below.
        </p>

        {success ? (
          <div className="flex flex-col gap-4">
            <Alert tone="success">Your password has been reset successfully.</Alert>
            <Button href="/login" className="w-full">
              Back to login
            </Button>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {error && <Alert tone="danger">{error}</Alert>}

            <div>
              <Label htmlFor="new-password">New password</Label>
              <Input
                id="new-password"
                name="new-password"
                type="password"
                required
                autoComplete="new-password"
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Min 8 characters, 1 uppercase, 1 digit"
              />
            </div>

            <div>
              <Label htmlFor="confirm-password">Confirm password</Label>
              <Input
                id="confirm-password"
                name="confirm-password"
                type="password"
                required
                autoComplete="new-password"
                minLength={8}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="Confirm your password"
              />
            </div>

            <button
              type="submit"
              disabled={loading || !password || !confirmPassword}
              className={submitButtonClassName}
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
    <Suspense
      fallback={
        <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
          <Spinner />
        </div>
      }
    >
      <ResetPasswordForm />
    </Suspense>
  );
}
