"use client";

import Button from "@/components/ui/Button";

export default function Error({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
      <div className="grid-backdrop pointer-events-none absolute inset-0" aria-hidden="true" />
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-noteblock-amber/5 rounded-full blur-[120px]" />

      <div className="flat-card relative w-full max-w-md rounded-2xl p-10 text-center">
        <p className="mb-3 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-noteblock-amber">
          Error
        </p>
        <h1 className="mb-3 font-heading text-3xl font-bold text-text-primary sm:text-4xl">
          We hit a snag
        </h1>
        <p className="mb-8 text-sm leading-relaxed text-text-secondary">
          Something didn&apos;t load correctly. A retry usually fixes it.
        </p>
        <div className="flex flex-wrap items-center justify-center gap-3">
          <Button onClick={reset}>Try again</Button>
          <Button href="/" variant="ghost">
            Back to home
          </Button>
        </div>
      </div>
    </div>
  );
}
