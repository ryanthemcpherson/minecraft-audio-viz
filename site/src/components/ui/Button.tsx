import Link from "next/link";
import type { ReactNode } from "react";

type Variant = "primary" | "secondary" | "ghost" | "disabled";
type Size = "sm" | "md" | "lg";

interface ButtonProps {
  href?: string;
  external?: boolean;
  variant?: Variant;
  size?: Size;
  className?: string;
  children: ReactNode;
  onClick?: () => void;
  type?: "button" | "submit";
  /**
   * Native disabled state for <button> renders (submit while saving, etc.).
   * Keeps the chosen variant's look at reduced opacity, unlike the
   * "disabled" variant which is a static placeholder for unavailable links.
   */
  disabled?: boolean;
  /** Accessible label when the visible content is not descriptive enough. */
  ariaLabel?: string;
}

const base =
  "inline-flex items-center justify-center gap-2 rounded-xl font-semibold whitespace-nowrap transition-all duration-200 select-none";

const variants: Record<Variant, string> = {
  primary:
    "bg-gradient-to-r from-disc-cyan to-disc-blue text-white shadow-lg shadow-disc-cyan/20 hover:shadow-xl hover:shadow-disc-cyan/30 hover:brightness-110 active:brightness-95",
  secondary:
    "border border-white/10 bg-white/5 text-white backdrop-blur-sm hover:border-white/20 hover:bg-white/10",
  ghost: "text-text-secondary hover:text-white hover:bg-white/5",
  disabled:
    "cursor-not-allowed border border-dashed border-white/15 bg-white/[0.02] text-text-secondary",
};

const sizes: Record<Size, string> = {
  sm: "px-4 py-2 text-xs",
  md: "px-6 py-3 text-sm",
  lg: "px-8 py-4 text-sm sm:text-base",
};

/**
 * Shared call-to-action button. Renders a Next <Link> for internal hrefs,
 * a plain anchor for external hrefs, and a <button> when no href is given.
 */
export default function Button({
  href,
  external,
  variant = "primary",
  size = "md",
  className = "",
  children,
  onClick,
  type = "button",
  disabled = false,
  ariaLabel,
}: ButtonProps) {
  const classes = `${base} ${variants[variant]} ${sizes[size]} disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:brightness-100 disabled:hover:shadow-lg ${className}`;

  if (variant === "disabled") {
    return (
      <span className={classes} aria-disabled="true" aria-label={ariaLabel}>
        {children}
      </span>
    );
  }

  if (href && external) {
    return (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className={classes}
        aria-label={ariaLabel}
      >
        {children}
      </a>
    );
  }

  if (href) {
    return (
      <Link href={href} className={classes} aria-label={ariaLabel}>
        {children}
      </Link>
    );
  }

  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={classes}
      aria-label={ariaLabel}
    >
      {children}
    </button>
  );
}

/** Small right-pointing arrow used inside buttons. */
export function ArrowIcon({ className = "" }: { className?: string }) {
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
      className={className}
    >
      <path d="M5 12h14M12 5l7 7-7 7" />
    </svg>
  );
}
