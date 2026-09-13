import type { ReactNode } from "react";

export type BadgeTone =
  | "cyan"
  | "blue"
  | "amber"
  | "success"
  | "warning"
  | "danger"
  | "neutral";

const tones: Record<BadgeTone, string> = {
  cyan: "border-disc-cyan/30 bg-disc-cyan/10 text-disc-cyan",
  blue: "border-disc-blue/30 bg-disc-blue/10 text-disc-blue",
  amber: "border-noteblock-amber/30 bg-noteblock-amber/10 text-noteblock-amber",
  success: "border-success/30 bg-success/10 text-success",
  warning: "border-warning/30 bg-warning/10 text-warning",
  danger: "border-danger/30 bg-danger/10 text-danger",
  neutral: "border-white/10 bg-white/5 text-text-secondary",
};

interface BadgeProps {
  tone?: BadgeTone;
  children: ReactNode;
  className?: string;
  /** Render a small status dot before the label. */
  dot?: boolean;
}

/** Small pill label. Uses the mono font so it reads as a data tag. */
export default function Badge({ tone = "neutral", children, className = "", dot }: BadgeProps) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-wider ${tones[tone]} ${className}`}
    >
      {dot && <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />}
      {children}
    </span>
  );
}

/** Map a pattern category to a badge tone. Unknown categories fall back to neutral. */
export function categoryTone(category: string): BadgeTone {
  switch (category) {
    case "Original":
      return "cyan";
    case "Epic":
      return "amber";
    case "Cosmic":
      return "blue";
    case "Organic":
      return "success";
    case "Spectrum":
      return "warning";
    case "Mainstage":
      return "danger";
    default:
      return "neutral";
  }
}
