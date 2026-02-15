"use client";

import { useState, useEffect, useRef } from "react";
import Image from "next/image";
import Link from "next/link";
import { useAuth } from "@/components/AuthProvider";

const navLinks = [
  { label: "Live Preview", href: "/preview" },
  { label: "Getting Started", href: "/getting-started" },
  { label: "Patterns", href: "/patterns" },
  { label: "Features", href: "/#features" },
  { label: "Demo", href: "/#demo" },
  {
    label: "Docs",
    href: "https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/README.md",
    external: true,
  },
  {
    label: "GitHub",
    href: "https://github.com/ryanthemcpherson/minecraft-audio-viz",
    external: true,
  },
];

export default function Navbar() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const { user, loading, logout } = useAuth();
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const userMenuRef = useRef<HTMLDivElement>(null);

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

  return (
    <nav className="fixed top-0 left-0 right-0 z-50 border-b border-white/5 bg-[#08090d]/80 backdrop-blur-xl">
      <div className="mx-auto flex max-w-7xl items-center justify-between px-6 py-4">
        {/* Logo */}
        <Link href="/" className="flex items-center gap-2">
          <Image
            src="/mcav.png"
            alt="MCAV"
            width={32}
            height={32}
            className="h-8 w-8"
            style={{ imageRendering: "pixelated" }}
            priority
          />
          <span className="text-xl font-bold tracking-tight">MCAV</span>
        </Link>

        {/* Desktop Links */}
        <div className="hidden items-center gap-8 md:flex">
          {navLinks.map((link) =>
            link.external ? (
              <a
                key={link.label}
                href={link.href}
                target="_blank"
                rel="noopener noreferrer"
                className="text-sm text-text-secondary transition-colors hover:text-white"
              >
                {link.label}
              </a>
            ) : (
              <Link
                key={link.label}
                href={link.href}
                className="text-sm text-text-secondary transition-colors hover:text-white"
              >
                {link.label}
              </Link>
            )
          )}

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
                        unoptimized
                      />
                    ) : (
                      <div className="flex h-7 w-7 items-center justify-center rounded-full bg-white/10 text-xs font-bold">
                        {user.display_name.charAt(0).toUpperCase()}
                      </div>
                    )}
                    <span className="text-sm text-text-primary">
                      {user.display_name}
                    </span>
                  </button>

                  {userMenuOpen && (
                    <div className="absolute right-0 mt-2 w-48 rounded-xl border border-white/10 bg-[#111] py-1 shadow-xl">
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
                  className="rounded-lg bg-white/5 px-4 py-2 text-sm font-medium text-text-primary transition-colors hover:bg-white/10 hover:text-white"
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
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          ) : (
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="4" y1="6" x2="20" y2="6" />
              <line x1="4" y1="12" x2="20" y2="12" />
              <line x1="4" y1="18" x2="20" y2="18" />
            </svg>
          )}
        </button>
      </div>

      {/* Mobile Menu */}
      {mobileOpen && (
        <div id="mobile-menu" className="border-t border-white/5 bg-[#08090d]/95 backdrop-blur-xl md:hidden">
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
