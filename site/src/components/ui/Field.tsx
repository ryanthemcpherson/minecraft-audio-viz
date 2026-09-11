import type { InputHTMLAttributes, LabelHTMLAttributes, TextareaHTMLAttributes, SelectHTMLAttributes } from "react";

/** Shared control styling so every text input on the site looks the same. */
export const inputClassName =
  "w-full rounded-lg border border-white/10 bg-white/[0.03] px-3.5 py-2.5 text-sm text-white placeholder:text-text-secondary/60 transition-colors focus:border-disc-cyan/50 focus:bg-white/[0.05] focus:outline-none disabled:cursor-not-allowed disabled:opacity-50";

export function Label({ className = "", children, ...rest }: LabelHTMLAttributes<HTMLLabelElement>) {
  return (
    <label
      className={`mb-1.5 block font-mono text-[11px] font-semibold uppercase tracking-wider text-text-secondary ${className}`}
      {...rest}
    >
      {children}
    </label>
  );
}

export function Input({ className = "", ...rest }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={`${inputClassName} ${className}`} {...rest} />;
}

export function TextArea({ className = "", ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={`${inputClassName} min-h-24 resize-y ${className}`} {...rest} />;
}

export function Select({ className = "", children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={`${inputClassName} appearance-none ${className}`} {...rest}>
      {children}
    </select>
  );
}

/** Small helper text under a control. */
export function Hint({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return <p className={`mt-1.5 text-xs text-text-secondary/80 ${className}`}>{children}</p>;
}
