import Link from "next/link";
import SectionHeader from "@/components/ui/SectionHeader";
import { RELEASE } from "@/lib/releaseStatus";

const audiences = [
  {
    title: "DJs",
    surface: "DJ client on Windows",
    points: [
      "Pick the system mix or a single app as your source.",
      "Choose a genre preset and watch the meters lock to the beat.",
      "Paste a connect code from the operator and go live.",
    ],
    href: "/getting-started#dj-setup",
    linkLabel: "DJ setup",
  },
  {
    title: "Server operators",
    surface: "Paper plugin and a browser control center",
    points: [
      `Drop one JAR into plugins/ on Paper ${RELEASE.minecraft}.`,
      "Lay out zones and stages, switch patterns and effects live.",
      "Invite DJs with short-lived connect codes.",
    ],
    href: "/getting-started#server-setup",
    linkLabel: "Server setup",
  },
  {
    title: "Players",
    surface: "Any vanilla Java client",
    points: [
      "Join the server like any other.",
      "No mods, no resource packs, no launcher profiles.",
      "Watch the stage react to the DJ in real time.",
    ],
  },
];

export default function Audiences() {
  return (
    <section className="border-t border-white/[0.07] px-6 py-24 sm:py-28">
      <div className="mx-auto max-w-7xl">
        <SectionHeader
          title="Three roles, one show."
          lede="Each role gets its own surface. None of them has to touch the others' setup."
        />

        <div className="mt-14 grid gap-10 md:grid-cols-3 md:gap-0 md:divide-x md:divide-white/[0.08]">
          {audiences.map((aud, i) => (
            <div key={aud.title} className={i === 0 ? "md:pr-10" : i === 1 ? "md:px-10" : "md:pl-10"}>
              <h3 className="font-heading text-2xl font-bold">{aud.title}</h3>
              <p className="mt-1 text-sm text-text-secondary">{aud.surface}</p>
              <ul className="mt-6 space-y-3 text-[15px] leading-relaxed text-text-secondary">
                {aud.points.map((point) => (
                  <li key={point} className="flex gap-3">
                    <span className="mt-[11px] h-px w-4 shrink-0 bg-disc-cyan" aria-hidden="true" />
                    <span>{point}</span>
                  </li>
                ))}
              </ul>
              {aud.href && (
                <Link
                  href={aud.href}
                  className="mt-6 inline-block text-sm font-semibold text-disc-cyan hover:underline"
                >
                  {aud.linkLabel}
                </Link>
              )}
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
