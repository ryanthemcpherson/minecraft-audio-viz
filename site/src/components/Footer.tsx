import Link from "next/link";

const footerLinks = [
  {
    heading: "Project",
    links: [
      { label: "GitHub", href: "https://github.com/ryanthemcpherson/minecraft-audio-viz", external: true },
      { label: "Documentation", href: "https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/README.md", external: true },
      { label: "Releases", href: "https://github.com/ryanthemcpherson/minecraft-audio-viz/releases", external: true },
    ],
  },
  {
    heading: "Community",
    links: [
      { label: "Discussions", href: "https://github.com/ryanthemcpherson/minecraft-audio-viz/discussions", external: true },
      { label: "Issues", href: "https://github.com/ryanthemcpherson/minecraft-audio-viz/issues", external: true },
    ],
  },
  {
    heading: "Resources",
    links: [
      { label: "Getting Started", href: "/getting-started" },
      { label: "Architecture", href: "https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/docs/COORDINATOR_ARCHITECTURE.md", external: true },
      { label: "Audio Processing", href: "https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/docs/AUDIO_PROCESSING.md", external: true },
    ],
  },
];

export default function Footer() {
  return (
    <footer className="border-t border-white/5 px-6 py-16">
      <div className="mx-auto max-w-7xl">
        <div className="grid gap-12 sm:grid-cols-2 lg:grid-cols-4">
          {/* Brand column */}
          <div>
            <div className="flex items-center gap-2">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-electric-blue to-deep-purple">
                <svg
                  width="18"
                  height="18"
                  viewBox="0 0 24 24"
                  fill="none"
                  className="text-white"
                >
                  <rect x="3" y="14" width="4" height="7" rx="1" fill="currentColor" />
                  <rect x="10" y="8" width="4" height="13" rx="1" fill="currentColor" />
                  <rect x="17" y="3" width="4" height="18" rx="1" fill="currentColor" />
                </svg>
              </div>
              <span className="text-xl font-bold tracking-tight">MCAV</span>
            </div>
            <p className="mt-4 text-sm leading-relaxed text-text-secondary">
              Real-time audio visualization for Minecraft. Turn any audio source into
              reactive 3D architecture.
            </p>

            {/* Mini equalizer */}
            <div className="mt-6 flex items-end gap-0.5">
              {[...Array(8)].map((_, i) => (
                <div
                  key={i}
                  className="eq-bar w-1 rounded-full"
                  style={{
                    height: "16px",
                    background:
                      "linear-gradient(to top, #00D4FF, #8B5CF6)",
                    animationDelay: `${i * 0.12}s`,
                    animationDuration: `${1 + (i * 0.07)}s`,
                    opacity: 0.5,
                  }}
                />
              ))}
            </div>
          </div>

          {/* Link columns */}
          {footerLinks.map((group) => (
            <div key={group.heading}>
              <h3 className="mb-4 text-sm font-semibold uppercase tracking-wider text-white/60">
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
            &copy; 2026 MCAV. Open source under MIT License.
          </p>
          <p className="text-xs text-text-secondary/60">
            Built with &#9829; for the Minecraft community
          </p>
        </div>
      </div>
    </footer>
  );
}
