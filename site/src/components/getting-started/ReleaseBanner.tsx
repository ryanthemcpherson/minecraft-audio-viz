import { RELEASE } from "@/lib/releaseStatus";

/**
 * Explains release status at the top of the setup guide. Renders nothing once
 * the release ships, so the guide reads as final documentation.
 */
export default function ReleaseBanner() {
  if (RELEASE.isReleased) return null;

  return (
    <div
      role="status"
      className="mx-auto mt-8 max-w-3xl rounded-2xl border border-warning/25 bg-warning/[0.06] px-5 py-4 text-left"
    >
      <div className="flex gap-3">
        <svg
          className="mt-0.5 h-5 w-5 shrink-0 text-warning"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <circle cx="12" cy="12" r="10" />
          <path d="M12 8v4M12 16h.01" />
        </svg>
        <div className="text-sm leading-relaxed text-text-secondary">
          <p className="font-semibold text-warning">
            Release {RELEASE.version} is in progress.
          </p>
          <p className="mt-1">
            This guide describes the self-installing Paper release. Until its signed artifacts
            land on{" "}
            <a
              href={RELEASE.releasesUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="text-disc-cyan hover:underline"
            >
              GitHub Releases
            </a>
            , the Releases page carries the previous Paper and Fabric builds, prebuilt DJ client
            distribution stays paused under Phase 0 containment, and source builds are for
            development only.
          </p>
        </div>
      </div>
    </div>
  );
}
