import Link from "next/link";
import SectionHeader from "@/components/ui/SectionHeader";
import Badge from "@/components/ui/Badge";
import LiveMeter from "./LiveMeter";
import { PATTERN_COUNT } from "@/lib/patterns/meta";

const tiles = [
  {
    eyebrow: "Patterns",
    title: `${PATTERN_COUNT} Lua patterns, hot-swappable`,
    body: "Galaxies, auroras, dragons, LED walls. Switch mid-set with instant transitions, or write your own in a few dozen lines of Lua. Patterns are auto-discovered from a folder.",
    href: "/patterns",
    linkLabel: "Open the gallery",
    chips: ["Galaxy", "Aurora", "Dragon", "Tesseract", "LED wall"],
  },
  {
    eyebrow: "Multi-DJ",
    title: "Connect codes, not IP addresses",
    body: "Operators mint short-lived codes from the control center. DJs paste one into the client and join the queue. Several DJs can be connected at once while the operator drives transitions.",
  },
  {
    eyebrow: "Stages",
    title: "Zones, decorators, timelines",
    body: "Build multi-zone stages with spotlights and DJ billboards, then pre-program a show with pattern, preset, and effect cues on a timeline.",
  },
  {
    eyebrow: "Preview",
    title: "See it in a browser first",
    body: "A Three.js preview renders the same entity frames the plugin receives, so you can test a pattern or a whole set without launching Minecraft.",
  },
  {
    eyebrow: "Server-side",
    title: "Nothing for players to install",
    body: "Rendering uses Display Entities on Paper. Anyone with a vanilla Java client can join and watch the show.",
  },
];

export default function Features() {
  return (
    <section id="features" className="relative px-6 py-28 sm:py-32">
      <div className="mx-auto max-w-7xl">
        <SectionHeader
          eyebrow="What you get"
          title="Built like VJ software, not a novelty plugin."
          lede="The parts you would expect from a live-performance tool, tuned for a game world."
        />

        <div className="mt-14 grid gap-5 md:grid-cols-2 lg:grid-cols-3">
          {/* Hero tile: live meter */}
          <article className="flat-card relative overflow-hidden rounded-2xl p-7 md:col-span-2">
            <div className="pointer-events-none absolute -right-24 -top-24 h-64 w-64 rounded-full bg-disc-cyan/10 blur-3xl" aria-hidden="true" />
            <div className="relative grid gap-8 lg:grid-cols-[1.1fr_1fr] lg:items-center">
              <div>
                <p className="mb-3 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-disc-cyan">
                  Analysis
                </p>
                <h3 className="font-heading text-2xl font-bold leading-tight sm:text-3xl">
                  Beat-locked, not beat-approximate.
                </h3>
                <p className="mt-4 text-sm leading-relaxed text-text-secondary sm:text-base">
                  Five frequency bands, onset and kick detection, tempo tracking, and a dedicated
                  bass lane, all computed in Rust on the DJ machine inside a 21 ms window. Six
                  presets (auto, EDM, chill, rock, hip-hop, classical) tune attack, release, and
                  beat thresholds per genre.
                </p>
                <div className="mt-5 flex flex-wrap gap-1.5">
                  {["40-250 Hz", "250-500 Hz", "500-2k Hz", "2-6k Hz", "6-20k Hz"].map((b) => (
                    <Badge key={b} tone="neutral">
                      {b}
                    </Badge>
                  ))}
                </div>
              </div>
              <LiveMeter />
            </div>
          </article>

          {tiles.map((tile) => (
            <article key={tile.eyebrow} className="flat-card flex flex-col rounded-2xl p-7">
              <p className="mb-3 font-mono text-[11px] font-semibold uppercase tracking-[0.2em] text-text-secondary/70">
                {tile.eyebrow}
              </p>
              <h3 className="font-heading text-xl font-bold leading-snug">{tile.title}</h3>
              <p className="mt-3 flex-1 text-sm leading-relaxed text-text-secondary">{tile.body}</p>
              {tile.chips && (
                <div className="mt-4 flex flex-wrap gap-1.5">
                  {tile.chips.map((chip) => (
                    <Badge key={chip} tone="cyan">
                      {chip}
                    </Badge>
                  ))}
                </div>
              )}
              {tile.href && (
                <Link
                  href={tile.href}
                  className="mt-5 inline-flex items-center gap-1 text-sm font-semibold text-disc-cyan hover:underline"
                >
                  {tile.linkLabel}
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
