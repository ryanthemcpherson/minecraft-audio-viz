"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { verifyEmail } from "@/lib/auth";

function VerifyEmailContent() {
  const searchParams = useSearchParams();
  const token = searchParams.get("token");

  const [status, setStatus] = useState<"loading" | "success" | "error" | "no-token">(
    token ? "loading" : "no-token"
  );
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (!token) return;

    verifyEmail(token)
      .then((res) => {
        setMessage(res.message || "Email verified!");
        setStatus("success");
      })
      .catch((err) => {
        setMessage(err instanceof Error ? err.message : "Verification failed");
        setStatus("error");
      });
  }, [token]);

  return (
    <div className="relative flex min-h-screen items-center justify-center px-6 pt-20 pb-20">
      {/* Background glow */}
      <div className="pointer-events-none absolute inset-0 z-0">
        <div className="absolute top-1/3 left-1/2 h-[400px] w-[500px] -translate-x-1/2 rounded-full bg-disc-cyan/5 blur-[120px]" />
      </div>

      <div className="relative z-10 glass-card w-full max-w-md rounded-xl p-8 text-center">
        {status === "loading" && (
          <>
            <div className="mx-auto mb-4 h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-disc-cyan" />
            <h1 className="text-xl font-bold">Verifying your email...</h1>
            <p className="mt-2 text-sm text-text-secondary">Please wait a moment.</p>
          </>
        )}

        {status === "success" && (
          <>
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-green-500/10">
              <svg className="h-6 w-6 text-green-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h1 className="text-xl font-bold">Email Verified!</h1>
            <p className="mt-2 text-sm text-text-secondary">{message}</p>
            <Link
              href="/dashboard"
              className="mt-6 inline-block rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-6 py-2.5 text-sm font-semibold text-white transition-opacity hover:opacity-90"
            >
              Go to Dashboard
            </Link>
          </>
        )}

        {status === "error" && (
          <>
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-red-500/10">
              <svg className="h-6 w-6 text-red-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
            <h1 className="text-xl font-bold">Verification Failed</h1>
            <p className="mt-2 text-sm text-red-400">{message}</p>
            <Link
              href="/dashboard"
              className="mt-6 inline-block rounded-lg border border-white/10 px-6 py-2.5 text-sm font-medium text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
            >
              Go to Dashboard
            </Link>
          </>
        )}

        {status === "no-token" && (
          <>
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-amber-500/10">
              <svg className="h-6 w-6 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4.5c-.77-.833-2.694-.833-3.464 0L3.34 16.5c-.77.833.192 2.5 1.732 2.5z" />
              </svg>
            </div>
            <h1 className="text-xl font-bold">Invalid Verification Link</h1>
            <p className="mt-2 text-sm text-text-secondary">
              This link is missing a verification token. Please check your email for the correct link.
            </p>
            <Link
              href="/dashboard"
              className="mt-6 inline-block rounded-lg border border-white/10 px-6 py-2.5 text-sm font-medium text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
            >
              Go to Dashboard
            </Link>
          </>
        )}
      </div>
    </div>
  );
}

export default function VerifyEmailPage() {
  return (
    <Suspense
      fallback={
        <div className="flex min-h-screen items-center justify-center pt-20">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-disc-cyan" />
        </div>
      }
    >
      <VerifyEmailContent />
    </Suspense>
  );
}
