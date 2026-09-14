import { RELEASE } from "@/lib/releaseStatus";

/**
 * Release status note at the top of the setup guide. Renders nothing once the
 * release ships, so the guide reads as final documentation.
 */
export default function ReleaseBanner() {
  if (RELEASE.isReleased) return null;

  return (
    <p role="status" className="mt-6 max-w-2xl border-l-2 border-warning/70 pl-4 text-sm leading-relaxed text-text-secondary">
      <span className="font-semibold text-warning">Release {RELEASE.version} is in progress.</span>{" "}
      This guide describes the self-installing Paper release. Until its signed artifacts land on{" "}
      <a href={RELEASE.releasesUrl} target="_blank" rel="noopener noreferrer" className="text-disc-cyan hover:underline">
        GitHub Releases
      </a>
      , the Releases page carries the previous Paper and Fabric builds, prebuilt DJ client distribution
      stays paused under Phase 0 containment, and source builds are for development only.
    </p>
  );
}
