import Link from "next/link";
import SectionHeader from "@/components/ui/SectionHeader";
import { RELEASE } from "@/lib/releaseStatus";

const audiences = [
  {
    title: "DJs",
    surface: "DJ client on Windows",
    accent: "text-disc-cyan",
    border: "hover:border-disc-cyan/30",
    points: [
      "Pick the system mix or a single app as your source",
      "Choose a genre preset, watch the meters lock to the beat",
      "Paste a connect code from the operator and go live",
    ],
    href: "/getting-started#dj-setup",
    linkLabel: "DJ setup",
  },
  {
    title: "Server operators",
    surface: "Paper plugin + browser control center",
    accent: "text-disc-blue",
    border: "hover:border-disc-blue/30",
    points: [
      `Drop one JAR into plugins/ on Paper ${RELEASE.minecraft}`,
      "Lay out zones and stages, switch patterns and effects live",
      "Invite DJs with short-lived connect codes",
    ],
    href: "/getting-started#server-setup",
    linkLabel: "Server setup",
  },
  {
    title: "Players",
    surface: "Any vanilla Java client",
    accent: "text-noteblock-amber",
    border: "hover:border-noteblock-amber/30",
    points: [
      "Join the server like any other",
      "No mods, no resource packs, no launcher profiles",
      "Watch the stage react to the DJ in real time",
    ],
  },
];

export default function Audiences() {
  return (
    <section className="relative px-6 py-28 sm:py-32">
      <div className="mx-auto max-w-7xl">
        <SectionHeader
          eyebrow="Who it is for"
          title="Three roles, one show."
          lede="Each role gets its own surface. None of them has to touch the others' setup."
        />

        <div className="mt-14 grid gap-5 md:grid-cols-3">
          {audiences.map((aud) => (
            <article
              key={aud.title}
              className={`flat-card flex flex-col rounded-2xl p-7 ${aud.border}`}
            >
              <h3 className={`font-heading text-2xl font-bold ${aud.accent}`}>{aud.title}</h3>
              <p className="mt-1 font-mono text-[11px] uppercase tracking-wider text-text-secondary/70">
                {aud.surface}
              </p>
              <ul className="mt-6 flex flex-1 flex-col gap-3">
                {aud.points.map((point) => (
                  <li key={point} className="flex items-start gap-3 text-sm leading-relaxed text-text-secondary">
                    <svg
                      className={`mt-1 h-3.5 w-3.5 shrink-0 ${aud.accent}`}
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2.5"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden="true"
                    >
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                    <span>{point}</span>
                  </li>
                ))}
              </ul>
              {aud.href && (
                <Link
                  href={aud.href}
                  className={`mt-7 inline-flex items-center gap-1 text-sm font-semibold ${aud.accent} hover:underline`}
                >
                  {aud.linkLabel}
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M5 12h14M12 5l7 7-7 7" />
                  </svg>
                </Link>
              )}
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
