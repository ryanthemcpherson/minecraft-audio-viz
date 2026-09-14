import Image from "next/image";
import Link from "next/link";
import { LINKS } from "@/lib/links";
import { RELEASE, releaseLabel } from "@/lib/releaseStatus";

const groups = [
  {
    heading: "Product",
    links: [
      { label: "Patterns", href: "/patterns" },
      { label: "Getting started", href: "/getting-started" },
      { label: "Releases", href: LINKS.releases, external: true },
    ],
  },
  {
    heading: "Docs",
    links: [
      { label: "README", href: LINKS.readme, external: true },
      { label: "Architecture", href: LINKS.docs.architecture, external: true },
      { label: "Writing patterns", href: LINKS.docs.patternGuide, external: true },
    ],
  },
  {
    heading: "Community",
    links: [
      { label: "Discord", href: LINKS.discord, external: true },
      { label: "GitHub", href: LINKS.github, external: true },
      { label: "Issues", href: LINKS.issues, external: true },
    ],
  },
];

export default function Footer() {
  return (
    <footer className="border-t border-white/[0.07] px-6 py-14">
      <div className="mx-auto max-w-7xl">
        <div className="grid gap-10 md:grid-cols-[minmax(0,5fr)_repeat(3,minmax(0,2fr))]">
          <div>
            <div className="flex items-center gap-2.5">
              <Image
                src="/mcav.png"
                alt=""
                width={24}
                height={24}
                className="h-6 w-6"
                style={{ imageRendering: "pixelated" }}
              />
              <span className="font-heading text-base font-bold">MCAV</span>
            </div>
            <p className="mt-3 max-w-xs text-sm leading-relaxed text-text-secondary">
              Live audio-reactive visuals inside Minecraft. Open source, server-side, built for
              people who play shows.
            </p>
            <p className="mt-4 text-sm text-text-secondary">
              {releaseLabel()}. Paper {RELEASE.minecraft}, Java {RELEASE.java}.
            </p>
          </div>

          {groups.map((group) => (
            <div key={group.heading}>
              <h3 className="text-sm font-semibold text-text-primary">{group.heading}</h3>
              <ul className="mt-3 space-y-2">
                {group.links.map((link) =>
                  link.external ? (
                    <li key={link.label}>
                      <a
                        href={link.href}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-sm text-text-secondary hover:text-text-primary"
                      >
                        {link.label}
                      </a>
                    </li>
                  ) : (
                    <li key={link.label}>
                      <Link href={link.href} className="text-sm text-text-secondary hover:text-text-primary">
                        {link.label}
                      </Link>
                    </li>
                  ),
                )}
              </ul>
            </div>
          ))}
        </div>

        <div className="mt-12 flex flex-col gap-2 border-t border-white/[0.07] pt-6 text-xs text-text-secondary/70 sm:flex-row sm:justify-between">
          <p>&copy; {new Date().getFullYear()} MCAV. MIT License.</p>
          <p>
            <Link href="/privacy" className="hover:text-text-primary">
              Privacy
            </Link>
            <span className="mx-2">&middot;</span>
            <Link href="/terms" className="hover:text-text-primary">
              Terms
            </Link>
            <span className="mx-2">&middot;</span>
            Not affiliated with Mojang or Microsoft.
          </p>
        </div>
      </div>
    </footer>
  );
}
