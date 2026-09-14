"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { verifyEmail } from "@/lib/auth";
import Alert from "@/components/ui/Alert";
import Button from "@/components/ui/Button";
import Spinner from "@/components/ui/Spinner";

function VerifyEmailContent() {
  const searchParams = useSearchParams();
  const token = searchParams.get("token");

  const [status, setStatus] = useState<"loading" | "success" | "error" | "no-token">(
    token ? "loading" : "no-token"
  );
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (!token) return;

    const controller = new AbortController();

    verifyEmail(token)
      .then((res) => {
        if (controller.signal.aborted) return;
        setMessage(res.message || "Email verified!");
        setStatus("success");
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setMessage(err instanceof Error ? err.message : "Verification failed");
        setStatus("error");
      });

    return () => controller.abort();
  }, [token]);

  return (
    <div className="relative flex min-h-screen items-center justify-center px-6 pt-20 pb-20">
      {/* Background glow */}
      <div className="pointer-events-none absolute inset-0 z-0">
</div>

      <div className="relative z-10 glass-card w-full max-w-md rounded-2xl p-8 text-center">
        <p className="mb-2 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
          Account
        </p>

        {status === "loading" && (
          <>
            <div className="mb-4 flex justify-center">
              <Spinner label="Verifying your email" />
            </div>
            <h1 className="font-heading text-2xl font-bold leading-tight">Verifying your email...</h1>
            <p className="mt-2 text-sm text-text-secondary">Please wait a moment.</p>
          </>
        )}

        {status === "success" && (
          <>
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-success/10">
              <svg className="h-6 w-6 text-success" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h1 className="font-heading text-2xl font-bold leading-tight">Email verified!</h1>
            <p className="mt-2 text-sm text-text-secondary">{message}</p>
            <Button href="/dashboard" className="mt-6">
              Go to dashboard
            </Button>
          </>
        )}

        {status === "error" && (
          <>
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-danger/10">
              <svg className="h-6 w-6 text-danger" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
            <h1 className="font-heading text-2xl font-bold leading-tight">Verification failed</h1>
            <Alert tone="danger" className="mt-3 text-left">
              {message}
            </Alert>
            <Button href="/dashboard" variant="secondary" className="mt-6">
              Go to dashboard
            </Button>
          </>
        )}

        {status === "no-token" && (
          <>
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-warning/10">
              <svg className="h-6 w-6 text-warning" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4.5c-.77-.833-2.694-.833-3.464 0L3.34 16.5c-.77.833.192 2.5 1.732 2.5z" />
              </svg>
            </div>
            <h1 className="font-heading text-2xl font-bold leading-tight">Invalid verification link</h1>
            <p className="mt-2 text-sm text-text-secondary">
              This link is missing a verification token. Please check your email for the correct link.
            </p>
            <Button href="/dashboard" variant="secondary" className="mt-6">
              Go to dashboard
            </Button>
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
          <Spinner />
        </div>
      }
    >
      <VerifyEmailContent />
    </Suspense>
  );
}
