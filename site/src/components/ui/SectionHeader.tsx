import type { ReactNode } from "react";

interface SectionHeaderProps {
  /** Small mono label above the title, e.g. "01 / Capture". */
  eyebrow?: string;
  title: ReactNode;
  lede?: ReactNode;
  align?: "left" | "center";
  accent?: "cyan" | "blue" | "amber";
  className?: string;
}

const accents = {
  cyan: "text-disc-cyan",
  blue: "text-disc-blue",
  amber: "text-noteblock-amber",
};

/**
 * Consistent section heading: mono eyebrow, display title, optional lede.
 */
export default function SectionHeader({
  eyebrow,
  title,
  lede,
  align = "left",
  accent = "cyan",
  className = "",
}: SectionHeaderProps) {
  const alignment = align === "center" ? "text-center mx-auto" : "text-left";
  return (
    <div className={`max-w-3xl ${alignment} ${className}`}>
      {eyebrow && (
        <p
          className={`mb-3 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] ${accents[accent]}`}
        >
          {eyebrow}
        </p>
      )}
      <h2 className="font-heading text-3xl font-bold leading-[1.1] sm:text-4xl md:text-5xl">
        {title}
      </h2>
      {lede && (
        <p className="mt-4 text-base leading-relaxed text-text-secondary sm:text-lg">
          {lede}
        </p>
      )}
    </div>
  );
}
