interface SpinnerProps {
  size?: "sm" | "md" | "lg";
  className?: string;
  /** Screen-reader label. Defaults to "Loading". */
  label?: string;
}

const sizes = {
  sm: "h-4 w-4 border-2",
  md: "h-8 w-8 border-2",
  lg: "h-12 w-12 border-[3px]",
};

/** Cyan ring spinner used for pending states. */
export default function Spinner({ size = "md", className = "", label = "Loading" }: SpinnerProps) {
  return (
    <span role="status" className={`inline-flex ${className}`}>
      <span
        className={`animate-spin rounded-full border-white/15 border-t-disc-cyan ${sizes[size]}`}
        aria-hidden="true"
      />
      <span className="sr-only">{label}</span>
    </span>
  );
}
