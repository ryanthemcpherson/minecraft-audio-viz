import type { ReactNode } from "react";

interface PageHeaderProps {
  /** Small mono label above the title, e.g. "Dashboard" or "Settings". */
  eyebrow?: string;
  title: ReactNode;
  description?: ReactNode;
  /** Right-aligned actions (buttons, badges). */
  actions?: ReactNode;
  className?: string;
}

/**
 * Header for app pages (dashboard, settings, admin). Smaller than the
 * marketing SectionHeader and supports an actions slot.
 */
export default function PageHeader({
  eyebrow,
  title,
  description,
  actions,
  className = "",
}: PageHeaderProps) {
  return (
    <div className={`flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between ${className}`}>
      <div className="min-w-0">
        {eyebrow && (
          <p className="mb-2 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
            {eyebrow}
          </p>
        )}
        <h1 className="font-heading text-2xl font-bold leading-tight sm:text-3xl">{title}</h1>
        {description && (
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-text-secondary sm:text-base">
            {description}
          </p>
        )}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
