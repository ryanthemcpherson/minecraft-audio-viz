import Button from "@/components/ui/Button";
import { RELEASE } from "@/lib/releaseStatus";

interface DownloadButtonProps {
  /** What the visitor is downloading, e.g. "AudioViz-1.2.0.jar". */
  label: string;
  /** Short qualifier shown after the label, e.g. "Paper plugin". */
  hint?: string;
  variant?: "primary" | "secondary";
}

function DownloadIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
    </svg>
  );
}

/**
 * Links to GitHub Releases when the release is out. Before that it renders a
 * clearly inert "coming soon" control so nobody hunts for a missing file.
 */
export default function DownloadButton({ label, hint, variant = "primary" }: DownloadButtonProps) {
  if (!RELEASE.isReleased) {
    return (
      <Button variant="disabled" ariaLabel={`${label} is not yet available`}>
        <DownloadIcon />
        {label}
        <span className="ml-1 font-mono text-[10px] uppercase tracking-wider text-warning">
          Coming in {RELEASE.version}
        </span>
      </Button>
    );
  }

  return (
    <Button href={RELEASE.releasesUrl} external variant={variant}>
      <DownloadIcon />
      {label}
      {hint && <span className="text-xs font-normal opacity-70">{hint}</span>}
    </Button>
  );
}
