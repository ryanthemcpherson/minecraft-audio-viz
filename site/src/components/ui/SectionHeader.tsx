import type { ReactNode } from "react";

interface SectionHeaderProps {
  /** Short plain-language label above the title, e.g. "How it works". Optional; most sections do not need one. */
  eyebrow?: string;
  title: ReactNode;
  lede?: ReactNode;
  align?: "left" | "center";
  className?: string;
}

/**
 * Section heading: display title with an optional lede. Left-aligned by
 * default; the eyebrow is ordinary small text, not a machine label.
 */
export default function SectionHeader({
  eyebrow,
  title,
  lede,
  align = "left",
  className = "",
}: SectionHeaderProps) {
  const alignment = align === "center" ? "text-center mx-auto" : "text-left";
  return (
    <div className={`max-w-2xl ${alignment} ${className}`}>
      {eyebrow && <p className="mb-2 text-sm font-medium text-disc-cyan">{eyebrow}</p>}
      <h2 className="font-heading text-[clamp(1.75rem,1.2rem+2vw,2.75rem)] font-bold leading-[1.08] tracking-[-0.02em]">
        {title}
      </h2>
      {lede && <p className="mt-4 text-[17px] leading-relaxed text-text-secondary">{lede}</p>}
    </div>
  );
}
