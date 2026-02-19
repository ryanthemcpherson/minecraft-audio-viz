"use client";

export default function Error({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-noteblock-amber/5 rounded-full blur-[120px]" />

      <div className="relative w-full max-w-md glass-card rounded-2xl p-8 text-center">
        <p className="mb-4 text-4xl">!</p>
        <h1 className="mb-2 text-xl font-semibold text-white">
          Something went wrong
        </h1>
        <p className="mb-6 text-sm text-text-secondary">
          An unexpected error occurred. Please try again.
        </p>
        <button
          onClick={reset}
          className="inline-block rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-6 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90"
        >
          Try again
        </button>
      </div>
    </div>
  );
}
