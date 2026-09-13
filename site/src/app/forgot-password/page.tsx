"use client";

import { useState } from "react";
import Link from "next/link";
import { requestPasswordReset } from "@/lib/auth";
import Alert from "@/components/ui/Alert";
import Button from "@/components/ui/Button";
import { Input, Label } from "@/components/ui/Field";

/** Primary submit styling, mirroring Button's primary variant (which has no disabled prop). */
const submitButtonClassName =
  "inline-flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-disc-cyan to-disc-blue px-6 py-3 text-sm font-semibold whitespace-nowrap text-white shadow-lg shadow-disc-cyan/20 transition-all duration-200 select-none hover:shadow-xl hover:shadow-disc-cyan/30 hover:brightness-110 active:brightness-95 disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:shadow-lg disabled:hover:brightness-100";

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
      setError(err instanceof Error ? err.message : "Unable to send reset link. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-disc-cyan/5 rounded-full blur-[120px]" />

      <div className="relative w-full max-w-md glass-card rounded-2xl p-8">
        <p className="mb-2 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
          Account
        </p>
        <h1 className="mb-2 font-heading text-2xl font-bold leading-tight">Reset your password</h1>
        <p className="mb-6 text-sm leading-relaxed text-text-secondary">
          Enter your email address and we&apos;ll send you a link to reset your password.
        </p>

        {success ? (
          <div className="flex flex-col gap-4">
            <Alert tone="success">
              Check your email for a password reset link. It may take a minute to arrive.
            </Alert>
            <Button href="/login" variant="secondary" className="w-full">
              Back to login
            </Button>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {error && <Alert tone="danger">{error}</Alert>}

            <div>
              <Label htmlFor="reset-email">Email</Label>
              <Input
                id="reset-email"
                name="email"
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
              />
            </div>

            <button
              type="submit"
              disabled={loading || !email}
              className={submitButtonClassName}
            >
              {loading ? "Sending..." : "Send reset link"}
            </button>

            <Link
              href="/login"
              className="text-center text-sm text-text-secondary transition-colors hover:text-white"
            >
              Back to login
            </Link>
          </form>
        )}
      </div>
    </div>
  );
}
