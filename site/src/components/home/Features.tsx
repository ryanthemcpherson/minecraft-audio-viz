import Link from "next/link";
import SectionHeader from "@/components/ui/SectionHeader";
import LiveMeter from "./LiveMeter";
import { PATTERN_COUNT } from "@/lib/patterns/meta";

const items = [
  {
    title: `${PATTERN_COUNT} Lua patterns, hot-swappable`,
    body: "Galaxies, auroras, dragons, LED walls. Switch mid-set with instant transitions, or write your own in a few dozen lines of Lua. Patterns are discovered from a folder; nothing to register.",
    link: { href: "/patterns", label: "Open the gallery" },
  },
  {
    title: "Connect codes, not IP addresses",
    body: "Operators mint short-lived codes from the control center. DJs paste one into the client and join the queue. Several DJs can be connected at once while the operator drives transitions.",
  },
  {
    title: "Zones, decorators, timelines",
    body: "Build multi-zone stages with spotlights and DJ billboards, then pre-program a show with pattern, preset, and effect cues on a timeline.",
  },
  {
    title: "See it in a browser first",
    body: "A Three.js preview renders the same entity frames the plugin receives, so you can test a pattern or a whole set without launching Minecraft.",
  },
  {
    title: "Nothing for players to install",
    body: "Rendering uses Display Entities on Paper. Anyone with a vanilla Java client can join and watch the show.",
  },
];

/**
 * A plain list of what the tool does. Text carries the hierarchy; the one
 * live element (the meter) sits next to the claim it proves.
 */
export default function Features() {
  return (
    <section id="features" className="border-t border-white/[0.07] px-6 py-24 sm:py-28">
      <div className="mx-auto max-w-7xl">
        <SectionHeader
          title="Built like VJ software, not a novelty plugin."
          lede="The parts you would expect from a live-performance tool, tuned for a game world."
        />

        <div className="mt-14 grid gap-12 lg:grid-cols-[minmax(0,7fr)_minmax(0,5fr)] lg:gap-20">
          {/* Lead claim with the live meter */}
          <div className="grid gap-8 sm:grid-cols-[minmax(0,1fr)_260px] sm:items-start">
            <div>
              <h3 className="font-heading text-2xl font-bold leading-tight">
                Beat-locked, not beat-approximate.
              </h3>
              <p className="mt-3 text-[15px] leading-relaxed text-text-secondary">
                Five frequency bands, onset and kick detection, tempo tracking, and a dedicated bass
                lane, all computed in Rust on the DJ machine inside a 21 ms window. Six presets tune
                attack, release, and beat thresholds per genre: auto, EDM, chill, rock, hip-hop,
                classical.
              </p>
              <p className="mt-3 text-sm text-text-secondary/80">
                Bands: 40 to 250 Hz, 250 to 500 Hz, 500 Hz to 2 kHz, 2 to 6 kHz, 6 to 20 kHz.
              </p>
            </div>
            <LiveMeter className="w-full" />
          </div>

          <dl className="grid gap-8 sm:grid-cols-2 lg:grid-cols-1">
            {items.map((item) => (
              <div key={item.title}>
                <dt className="font-heading text-lg font-bold leading-snug">{item.title}</dt>
                <dd className="mt-2 text-[15px] leading-relaxed text-text-secondary">
                  {item.body}
                  {item.link && (
                    <>
                      {" "}
                      <Link href={item.link.href} className="text-disc-cyan hover:underline">
                        {item.link.label}
                      </Link>
                      .
                    </>
                  )}
                </dd>
              </div>
            ))}
          </dl>
        </div>
      </div>
    </section>
  );
}
