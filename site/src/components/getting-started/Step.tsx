import type { ReactNode } from "react";

interface StepProps {
  id: string;
  number: string;
  title: string;
  accent?: "cyan" | "blue" | "amber";
  children: ReactNode;
}

const accentGradient = {
  cyan: "from-disc-cyan to-disc-blue",
  blue: "from-disc-blue to-disc-cyan",
  amber: "from-noteblock-amber to-disc-blue",
};

/** Numbered step with a vertical rail, used throughout the setup guide. */
export default function Step({ id, number, title, accent = "cyan", children }: StepProps) {
  return (
    <div id={id} className="relative scroll-mt-28 pl-0 sm:pl-16">
      <div className="mb-5 flex items-center gap-4 sm:-ml-16">
        <div
          className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br ${accentGradient[accent]} font-mono text-sm font-bold text-white`}
        >
          {number}
        </div>
        <h3 className="font-heading text-xl font-bold sm:text-2xl">{title}</h3>
      </div>
      <div className="space-y-5 text-text-secondary">{children}</div>
    </div>
  );
}

/** Small bordered note used for tips and warnings inside a step. */
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
    <div className={tone === "tip" ? "callout-tip" : "callout-warning"}>
      {title && (
        <p
          className={`mb-1 font-mono text-[11px] font-semibold uppercase tracking-wider ${
            tone === "tip" ? "text-disc-cyan" : "text-warning"
          }`}
        >
          {title}
        </p>
      )}
      <div className="text-sm leading-relaxed text-text-secondary">{children}</div>
    </div>
  );
}
