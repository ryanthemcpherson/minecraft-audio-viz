/**
 * Single source of truth for what the site may claim about downloads.
 *
 * The self-installing Paper release (1.2.0) is designed and documented on the
 * site ahead of shipping. Until `isReleased` flips to true every download
 * button renders as "coming soon" and the Phase 0 notice stays visible.
 * Flipping the release on is a one-line change here.
 */

import { LINKS } from "./links";

export const RELEASE = {
  /** Unified product version the site describes. */
  version: "1.2.0",
  /** True once the signed 1.2.0 artifacts are published on GitHub Releases. */
  isReleased: false,
  /** Supported Minecraft / Java baseline for the release. */
  minecraft: "1.21.11",
  java: "21",
  /** Default public port the VJ runtime binds. */
  defaultPort: 8080,
  /** Where the artifacts live (or will live). */
  releasesUrl: LINKS.releases,
  /** Artifact file names as listed in the release design. */
  artifacts: {
    plugin: "AudioViz-1.2.0.jar",
    checksums: "SHA256SUMS.txt",
    runtimeManifest: "mcav-runtime-manifest-v1.json",
  },
} as const;

/** Short human label for the release chip shown in the hero and footer. */
export function releaseLabel(): string {
  return RELEASE.isReleased
    ? `Release ${RELEASE.version}`
    : `Release ${RELEASE.version} in progress`;
}
