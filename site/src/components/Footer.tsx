import Image from "next/image";
import Link from "next/link";
import { LINKS } from "@/lib/links";
import { RELEASE, releaseLabel } from "@/lib/releaseStatus";

const footerLinks = [
  {
    heading: "Product",
    links: [
      { label: "Pattern gallery", href: "/patterns" },
      { label: "Getting started", href: "/getting-started" },
      { label: "How it works", href: "/#how-it-works" },
      { label: "Releases", href: LINKS.releases, external: true },
    ],
  },
  {
    heading: "Docs",
    links: [
      { label: "README", href: LINKS.readme, external: true },
      { label: "Architecture", href: LINKS.docs.architecture, external: true },
      { label: "Audio processing", href: LINKS.docs.audioProcessing, external: true },
      { label: "Writing patterns", href: LINKS.docs.patternGuide, external: true },
    ],
  },
  {
    heading: "Community",
    links: [
      { label: "Discord", href: LINKS.discord, external: true },
      { label: "GitHub", href: LINKS.github, external: true },
      { label: "Discussions", href: LINKS.discussions, external: true },
      { label: "Issues", href: LINKS.issues, external: true },
    ],
  },
  {
    heading: "Legal",
    links: [
      { label: "Privacy policy", href: "/privacy" },
      { label: "Terms of service", href: "/terms" },
    ],
  },
];

export default function Footer() {
  return (
    <footer className="border-t border-white/5 px-6 py-16">
      <div className="mx-auto max-w-7xl">
        <div className="grid gap-12 sm:grid-cols-2 lg:grid-cols-[1.6fr_1fr_1fr_1fr_1fr]">
          {/* Brand column */}
          <div>
            <div className="flex items-center gap-2.5">
              <Image
                src="/mcav.png"
                alt=""
                width={32}
                height={32}
                className="h-8 w-8"
                style={{ imageRendering: "pixelated" }}
              />
              <span className="font-heading text-lg font-bold tracking-tight">MCAV</span>
            </div>
            <p className="mt-4 max-w-xs text-sm leading-relaxed text-text-secondary">
              Live audio-reactive visuals inside Minecraft. Open source, server-side, and
              built for people who play shows.
            </p>

            <dl className="mt-6 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 font-mono text-[11px] uppercase tracking-wider text-text-secondary/70">
              <dt>Status</dt>
              <dd className={RELEASE.isReleased ? "text-success" : "text-warning"}>
                {releaseLabel()}
              </dd>
              <dt>Server</dt>
              <dd>Paper {RELEASE.minecraft}</dd>
              <dt>Runtime</dt>
              <dd>Java {RELEASE.java}</dd>
            </dl>

            {/* Mini equalizer */}
            <div className="mt-6 flex items-end gap-0.5" aria-hidden="true">
              {[...Array(8)].map((_, i) => (
                <div
                  key={i}
                  className="eq-bar w-1 rounded-full"
                  style={{
                    height: "16px",
                    background: "linear-gradient(to top, #00CCFF, #5B6AFF)",
                    animationDelay: `${i * 0.12}s`,
                    animationDuration: `${1 + i * 0.07}s`,
                    opacity: 0.5,
                  }}
                />
              ))}
            </div>
          </div>

          {/* Link columns */}
          {footerLinks.map((group) => (
            <div key={group.heading}>
              <h3 className="mb-4 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-white/50">
                {group.heading}
              </h3>
              <ul className="flex flex-col gap-3">
                {group.links.map((link) => (
                  <li key={link.label}>
                    {link.external ? (
                      <a
                        href={link.href}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-sm text-text-secondary transition-colors hover:text-white"
                      >
                        {link.label}
                      </a>
                    ) : (
                      <Link
                        href={link.href}
                        className="text-sm text-text-secondary transition-colors hover:text-white"
                      >
                        {link.label}
                      </Link>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>

        {/* Bottom bar */}
        <div className="mt-16 flex flex-col items-center justify-between gap-4 border-t border-white/5 pt-8 sm:flex-row">
          <p className="text-xs text-text-secondary/60">
            &copy; {new Date().getFullYear()} MCAV. Open source under the MIT License.
          </p>
          <p className="text-xs text-text-secondary/60">
            Not affiliated with Mojang or Microsoft.
          </p>
        </div>
      </div>
    </footer>
  );
}
