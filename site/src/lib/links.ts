/**
 * Canonical external links used across the site.
 * Keep every GitHub / Discord URL here so a rename is a one-line change.
 */

export const GITHUB_REPO = "https://github.com/ryanthemcpherson/minecraft-audio-viz";

export const LINKS = {
  github: GITHUB_REPO,
  releases: `${GITHUB_REPO}/releases`,
  discussions: `${GITHUB_REPO}/discussions`,
  issues: `${GITHUB_REPO}/issues`,
  readme: `${GITHUB_REPO}/blob/main/README.md`,
  docs: {
    architecture: `${GITHUB_REPO}/blob/main/docs/COORDINATOR_ARCHITECTURE.md`,
    audioProcessing: `${GITHUB_REPO}/blob/main/docs/AUDIO_PROCESSING.md`,
    patternGuide: `${GITHUB_REPO}/blob/main/docs/PATTERN_GUIDE.md`,
    connectivity: `${GITHUB_REPO}/blob/main/docs/CONNECTIVITY.md`,
    serverRunbook: `${GITHUB_REPO}/blob/main/docs/MINECRAFT_SERVER_RUNBOOK.md`,
    djClient: `${GITHUB_REPO}/blob/main/dj_client/README.md`,
  },
  discord: "https://discord.gg/my2k8P2Mru",
} as const;
