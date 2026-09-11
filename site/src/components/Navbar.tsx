"use client";

import { useState, useEffect, useRef } from "react";
import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import { LINKS } from "@/lib/links";

const navLinks = [
  { label: "How it works", href: "/#how-it-works" },
  { label: "Patterns", href: "/patterns" },
  { label: "Getting started", href: "/getting-started" },
  { label: "Docs", href: LINKS.readme, external: true },
];

function GitHubIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z" />
    </svg>
  );
}

function DiscordIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515a.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0a12.64 12.64 0 0 0-.617-1.25a.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057a19.9 19.9 0 0 0 5.993 3.03a.078.078 0 0 0 .084-.028a14.09 14.09 0 0 0 1.226-1.994a.076.076 0 0 0-.041-.106a13.107 13.107 0 0 1-1.872-.892a.077.077 0 0 1-.008-.128a10.2 10.2 0 0 0 .372-.292a.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127a12.299 12.299 0 0 1-1.873.892a.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028a19.839 19.839 0 0 0 6.002-3.03a.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419c0-1.333.956-2.419 2.157-2.419c1.21 0 2.176 1.096 2.157 2.42c0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419c0-1.333.955-2.419 2.157-2.419c1.21 0 2.176 1.096 2.157 2.42c0 1.333-.946 2.418-2.157 2.418z" />
    </svg>
  );
}

export default function Navbar() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const { user, loading, logout } = useAuth();
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const userMenuRef = useRef<HTMLDivElement>(null);
  const pathname = usePathname();

  // Close user dropdown when clicking outside
  useEffect(() => {
    if (!userMenuOpen) return;
    function handleMouseDown(e: MouseEvent) {
      if (userMenuRef.current && !userMenuRef.current.contains(e.target as Node)) {
        setUserMenuOpen(false);
      }
    }
    document.addEventListener("mousedown", handleMouseDown);
    return () => document.removeEventListener("mousedown", handleMouseDown);
  }, [userMenuOpen]);

  // Close mobile menu or user dropdown on Escape key
  useEffect(() => {
    if (!mobileOpen && !userMenuOpen) return;
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        if (userMenuOpen) setUserMenuOpen(false);
        if (mobileOpen) setMobileOpen(false);
      }
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [mobileOpen, userMenuOpen]);

  const isActive = (href: string) =>
    !href.startsWith("http") && !href.includes("#") && pathname === href;

  const linkClass = (href: string) =>
    `text-sm transition-colors hover:text-white ${
      isActive(href) ? "text-white" : "text-text-secondary"
    }`;

  return (
    <nav className="fixed top-0 left-0 right-0 z-50 border-b border-white/5 bg-bg-primary/80 backdrop-blur-xl">
      <div className="mx-auto flex max-w-7xl items-center justify-between px-6 py-3.5">
        {/* Logo */}
        <Link href="/" className="flex items-center gap-2.5" aria-label="MCAV home">
          <Image
            src="/mcav.png"
            alt=""
            width={32}
            height={32}
            className="h-8 w-8"
            style={{ imageRendering: "pixelated" }}
            priority
          />
          <span className="font-heading text-lg font-bold tracking-tight">MCAV</span>
        </Link>

        {/* Desktop Links */}
        <div className="hidden items-center gap-7 md:flex">
          {navLinks.map((link) =>
            link.external ? (
              <a
                key={link.label}
                href={link.href}
                target="_blank"
                rel="noopener noreferrer"
                className={linkClass(link.href)}
              >
                {link.label}
              </a>
            ) : (
              <Link key={link.label} href={link.href} className={linkClass(link.href)}>
                {link.label}
              </Link>
            )
          )}

          <span className="h-5 w-px bg-white/10" aria-hidden="true" />

          <a
            href={LINKS.discord}
            target="_blank"
            rel="noopener noreferrer"
            className="text-text-secondary transition-colors hover:text-white"
            aria-label="Join the MCAV Discord"
          >
            <DiscordIcon />
          </a>
          <a
            href={LINKS.github}
            target="_blank"
            rel="noopener noreferrer"
            className="text-text-secondary transition-colors hover:text-white"
            aria-label="MCAV on GitHub"
          >
            <GitHubIcon />
          </a>

          {/* Auth section */}
          {!loading && (
            <>
              {user ? (
                <div ref={userMenuRef} className="relative">
                  <button
                    onClick={() => setUserMenuOpen(!userMenuOpen)}
                    className="flex items-center gap-2 rounded-lg px-3 py-1.5 transition-colors hover:bg-white/5"
                    aria-expanded={userMenuOpen}
                    aria-haspopup="true"
                  >
                    {user.avatar_url ? (
                      <Image
                        src={user.avatar_url}
                        alt={user.display_name}
                        width={28}
                        height={28}
                        className="h-7 w-7 rounded-full"
                        /* Avatar URLs come from Discord/Google OAuth or S3 uploads. Domains are dynamic. */
                        unoptimized
                      />
                    ) : (
                      <div className="flex h-7 w-7 items-center justify-center rounded-full bg-white/10 text-xs font-bold">
                        {user.display_name.charAt(0).toUpperCase()}
                      </div>
                    )}
                    <span className="text-sm text-text-primary">{user.display_name}</span>
                  </button>

                  {userMenuOpen && (
                    <div className="absolute right-0 mt-2 w-48 rounded-xl border border-white/10 bg-bg-secondary py-1 shadow-xl">
                      <Link
                        href="/dashboard"
                        onClick={() => setUserMenuOpen(false)}
                        className="block px-4 py-2 text-sm text-text-primary transition-colors hover:bg-white/5 hover:text-white"
                      >
                        Dashboard
                      </Link>
                      <Link
                        href="/settings"
                        onClick={() => setUserMenuOpen(false)}
                        className="block px-4 py-2 text-sm text-text-primary transition-colors hover:bg-white/5 hover:text-white"
                      >
                        Settings
                      </Link>
                      {user.is_admin && (
                        <Link
                          href="/admin"
                          onClick={() => setUserMenuOpen(false)}
                          className="flex items-center gap-2 px-4 py-2 text-sm text-noteblock-amber transition-colors hover:bg-white/5"
                        >
                          <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                            <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                          </svg>
                          Site Admin
                        </Link>
                      )}
                      <button
                        onClick={() => {
                          setUserMenuOpen(false);
                          logout();
                        }}
                        className="block w-full px-4 py-2 text-left text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                      >
                        Log out
                      </button>
                    </div>
                  )}
                </div>
              ) : (
                <Link
                  href="/login"
                  className="rounded-lg border border-white/10 bg-white/5 px-4 py-1.5 text-sm font-medium text-text-primary transition-colors hover:border-white/20 hover:bg-white/10 hover:text-white"
                >
                  Log in
                </Link>
              )}
            </>
          )}
        </div>

        {/* Mobile Menu Button */}
        <button
          onClick={() => setMobileOpen(!mobileOpen)}
          className="flex h-10 w-10 items-center justify-center rounded-lg text-text-secondary transition-colors hover:bg-white/5 hover:text-white md:hidden"
          aria-label="Toggle menu"
          aria-expanded={mobileOpen}
          aria-controls="mobile-menu"
        >
          {mobileOpen ? (
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          ) : (
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <line x1="4" y1="6" x2="20" y2="6" />
              <line x1="4" y1="12" x2="20" y2="12" />
              <line x1="4" y1="18" x2="20" y2="18" />
            </svg>
          )}
        </button>
      </div>

      {/* Mobile Menu */}
      {mobileOpen && (
        <div id="mobile-menu" className="border-t border-white/5 bg-bg-primary/95 backdrop-blur-xl md:hidden">
          <div className="flex flex-col gap-1 px-6 py-4">
            {navLinks.map((link) =>
              link.external ? (
                <a
                  key={link.label}
                  href={link.href}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={() => setMobileOpen(false)}
                  className="rounded-lg px-4 py-3 text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                >
                  {link.label}
                </a>
              ) : (
                <Link
                  key={link.label}
                  href={link.href}
                  onClick={() => setMobileOpen(false)}
                  className="rounded-lg px-4 py-3 text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                >
                  {link.label}
                </Link>
              )
            )}

            <div className="mt-1 flex items-center gap-2 px-4 py-2">
              <a
                href={LINKS.discord}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-2 rounded-lg border border-white/10 px-3 py-2 text-sm text-text-secondary hover:text-white"
              >
                <DiscordIcon /> Discord
              </a>
              <a
                href={LINKS.github}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-2 rounded-lg border border-white/10 px-3 py-2 text-sm text-text-secondary hover:text-white"
              >
                <GitHubIcon /> GitHub
              </a>
            </div>

            {/* Mobile auth */}
            {!loading && (
              <div className="mt-2 border-t border-white/5 pt-3">
                {user ? (
                  <>
                    <Link
                      href="/dashboard"
                      onClick={() => setMobileOpen(false)}
                      className="block rounded-lg px-4 py-3 text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                    >
                      Dashboard
                    </Link>
                    <Link
                      href="/settings"
                      onClick={() => setMobileOpen(false)}
                      className="block rounded-lg px-4 py-3 text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                    >
                      Settings
                    </Link>
                    {user.is_admin && (
                      <Link
                        href="/admin"
                        onClick={() => setMobileOpen(false)}
                        className="block rounded-lg px-4 py-3 text-sm text-noteblock-amber transition-colors hover:bg-white/5"
                      >
                        Site Admin
                      </Link>
                    )}
                    <button
                      onClick={() => {
                        setMobileOpen(false);
                        logout();
                      }}
                      className="block w-full rounded-lg px-4 py-3 text-left text-sm text-text-secondary transition-colors hover:bg-white/5 hover:text-white"
                    >
                      Log out
                    </button>
                  </>
                ) : (
                  <Link
                    href="/login"
                    onClick={() => setMobileOpen(false)}
                    className="block rounded-lg px-4 py-3 text-sm font-medium text-disc-cyan transition-colors hover:bg-white/5"
                  >
                    Log in
                  </Link>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </nav>
  );
}
