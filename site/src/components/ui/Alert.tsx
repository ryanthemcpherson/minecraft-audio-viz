import type { ReactNode } from "react";

type Tone = "danger" | "success" | "warning" | "info";

const tones: Record<Tone, string> = {
  danger: "border-danger/25 bg-danger/10 text-danger",
  success: "border-success/25 bg-success/10 text-success",
  warning: "border-warning/25 bg-warning/10 text-warning",
  info: "border-disc-cyan/25 bg-disc-cyan/10 text-disc-cyan",
};

interface AlertProps {
  tone?: Tone;
  children: ReactNode;
  className?: string;
  /** Optional bold lead-in. */
  title?: string;
}

/**
 * Inline status message for forms and panels. Danger alerts announce
 * assertively; everything else is polite.
 */
export default function Alert({ tone = "info", children, className = "", title }: AlertProps) {
  return (
    <div
      role={tone === "danger" ? "alert" : "status"}
      className={`rounded-lg border px-3.5 py-2.5 text-sm leading-relaxed ${tones[tone]} ${className}`}
    >
      {title && <span className="font-semibold">{title} </span>}
      {children}
    </div>
  );
}
