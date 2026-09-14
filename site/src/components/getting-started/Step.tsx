import type { ReactNode } from "react";

interface StepProps {
  id: string;
  number: string;
  title: string;
  children: ReactNode;
}

/** Numbered step on a left rail: plain numeral, hairline, no chrome. */
export default function Step({ id, number, title, children }: StepProps) {
  return (
    <div id={id} className="relative grid scroll-mt-28 gap-4 sm:grid-cols-[64px_minmax(0,1fr)] sm:gap-8">
      <div className="flex items-start gap-3 sm:block">
        <span className="font-mono text-sm text-disc-cyan">{number}</span>
        <h3 className="font-heading text-xl font-bold sm:hidden">{title}</h3>
      </div>
      <div className="min-w-0 border-t border-white/[0.08] pt-4 sm:border-t-0 sm:pt-0">
        <h3 className="hidden font-heading text-xl font-bold sm:block">{title}</h3>
        <div className="mt-3 space-y-5 text-[15px] leading-relaxed text-text-secondary">{children}</div>
      </div>
    </div>
  );
}

/** Small note used for tips and warnings inside a step. Text, not a box. */
export function Note({
  tone = "tip",
  title,
  children,
}: {
  tone?: "tip" | "warning";
  title?: string;
  children: ReactNode;
}) {
  return (
    <div className={`border-l-2 pl-4 ${tone === "tip" ? "border-disc-cyan/60" : "border-warning/70"}`}>
      {title && (
        <p className={`text-sm font-semibold ${tone === "tip" ? "text-text-primary" : "text-warning"}`}>
          {title}
        </p>
      )}
      <div className="mt-1 text-sm leading-relaxed text-text-secondary">{children}</div>
    </div>
  );
}
