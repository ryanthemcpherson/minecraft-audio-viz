import Link from "next/link";

export default function NotFound() {
  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 pt-20">
      <div className="pointer-events-none absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-disc-cyan/5 rounded-full blur-[120px]" />

      <div className="relative w-full max-w-md glass-card rounded-2xl p-8 text-center">
        <p className="mb-2 text-6xl font-bold text-gradient">404</p>
        <h1 className="mb-2 text-xl font-semibold text-white">
          Page not found
        </h1>
        <p className="mb-6 text-sm text-text-secondary">
          The page you&apos;re looking for doesn&apos;t exist or has been moved.
        </p>
        <Link
          href="/"
          className="inline-block rounded-lg bg-gradient-to-r from-disc-cyan to-disc-blue px-6 py-3 text-sm font-semibold text-white transition-opacity hover:opacity-90"
        >
          Back to home
        </Link>
      </div>
    </div>
  );
}
