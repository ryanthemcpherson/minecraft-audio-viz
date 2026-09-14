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
  "inline-flex items-center justify-center gap-2 rounded-md font-semibold whitespace-nowrap transition-colors duration-150 select-none";

/**
 * One solid accent fill for the primary action, a hairline for secondary,
 * plain text for tertiary. No gradients, no colored shadows: hierarchy comes
 * from fill versus outline, not from glow.
 */
const variants: Record<Variant, string> = {
  primary: "bg-disc-cyan text-bg-primary hover:bg-[#33d6ff] active:bg-[#00b8e6]",
  secondary: "border border-white/15 text-text-primary hover:border-white/30 hover:bg-white/[0.04]",
  ghost: "text-text-secondary hover:text-text-primary",
  disabled: "cursor-not-allowed border border-dashed border-white/15 text-text-secondary",
};

const sizes: Record<Size, string> = {
  sm: "px-3.5 py-2 text-sm",
  md: "px-5 py-2.5 text-sm",
  lg: "px-6 py-3 text-base",
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
  const classes = `${base} ${variants[variant]} ${sizes[size]} disabled:cursor-not-allowed disabled:opacity-50 ${className}`;

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
