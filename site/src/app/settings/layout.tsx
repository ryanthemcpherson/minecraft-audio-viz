"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const settingsNav = [
  { label: "DJ Profile", href: "/settings/profile" },
  { label: "Account", href: "/settings/account" },
];

export default function SettingsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const pathname = usePathname();

  return (
    <div className="relative mx-auto max-w-4xl px-6 pt-28 pb-20">
      {/* Background glows */}
      <div className="pointer-events-none absolute inset-0 z-0">
        <div className="absolute top-0 left-1/2 h-[400px] w-[500px] -translate-x-1/2 rounded-full bg-disc-cyan/5 blur-[120px]" />
        <div className="absolute top-1/4 right-1/4 h-[300px] w-[300px] rounded-full bg-disc-blue/5 blur-[100px]" />
      </div>

      <div className="relative z-10">
        <h1 className="mb-6 text-2xl font-bold">
          <span className="text-gradient">Settings</span>
        </h1>

        <div className="flex gap-8">
          {/* Sidebar — hidden on mobile, shown as tab row instead */}
          <nav className="hidden w-48 shrink-0 flex-col gap-1 md:flex">
            {settingsNav.map((item) => {
              const active = pathname === item.href;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`rounded-lg px-3 py-2 text-sm transition-colors ${
                    active
                      ? "bg-white/5 font-medium text-white"
                      : "text-text-secondary hover:bg-white/[0.03] hover:text-white"
                  }`}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>

          {/* Mobile tab row */}
          <div className="mb-6 flex gap-1 overflow-x-auto border-b border-white/5 pb-2 md:hidden">
            {settingsNav.map((item) => {
              const active = pathname === item.href;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`whitespace-nowrap rounded-lg px-3 py-2 text-sm transition-colors ${
                    active
                      ? "bg-white/5 font-medium text-white"
                      : "text-text-secondary hover:text-white"
                  }`}
                >
                  {item.label}
                </Link>
              );
            })}
          </div>

          {/* Content */}
          <div className="min-w-0 flex-1">{children}</div>
        </div>
      </div>
    </div>
  );
}
