import Button from "@/components/ui/Button";

export default function NotFound() {
  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
      <div className="grid-backdrop pointer-events-none absolute inset-0" aria-hidden="true" />
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-disc-cyan/5 rounded-full blur-[120px]" />

      <div className="flat-card relative w-full max-w-md rounded-2xl p-10 text-center">
        <p className="mb-3 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
          404
        </p>
        <h1 className="mb-3 font-heading text-3xl font-bold text-text-primary sm:text-4xl">
          Page not found
        </h1>
        <p className="mb-8 text-sm leading-relaxed text-text-secondary">
          The page you&apos;re looking for doesn&apos;t exist or has been moved.
        </p>
        <Button href="/">Back to home</Button>
      </div>
    </div>
  );
}
