import SectionHeader from "@/components/ui/SectionHeader";
import Badge from "@/components/ui/Badge";

const stages = [
  {
    number: "01",
    title: "Capture",
    runsOn: "DJ client",
    tags: ["WASAPI", "Per-app loopback"],
    description:
      "Grab the full system mix or a single application. Spotify, a browser tab, a DAW: anything that makes sound on the DJ's Windows PC.",
    accent: "cyan" as const,
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
        <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
        <line x1="12" y1="19" x2="12" y2="23" />
      </svg>
    ),
  },
  {
    number: "02",
    title: "Analyze",
    runsOn: "DJ client",
    tags: ["5-band FFT", "Beat + BPM"],
    description:
      "A Rust pipeline splits the signal into five bands, detects onsets and kicks, and tracks tempo inside a 21 ms window. Only compact frames leave the machine, never raw audio.",
    accent: "blue" as const,
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <rect x="1" y="6" width="4" height="14" rx="1" />
        <rect x="6" y="2" width="4" height="18" rx="1" />
        <rect x="11" y="8" width="4" height="12" rx="1" />
        <rect x="16" y="4" width="4" height="16" rx="1" />
      </svg>
    ),
  },
  {
    number: "03",
    title: "Compose",
    runsOn: "VJ server",
    tags: ["Lua patterns", "Multi-DJ queue"],
    description:
      "Frames feed a pattern engine that turns bands and beats into entity positions, colors, and scale. The operator switches patterns, stages, and effects live from a browser.",
    accent: "amber" as const,
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <circle cx="12" cy="12" r="3" />
        <path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1" />
      </svg>
    ),
  },
  {
    number: "04",
    title: "Render",
    runsOn: "Paper plugin",
    tags: ["Display Entities", "Batched per tick"],
    description:
      "The plugin pools Display Entities and applies every update in one scheduler call per tick, so 160 blocks move in sync without touching the client. A Three.js preview mirrors the same frames.",
    accent: "cyan" as const,
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
        <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
        <line x1="12" y1="22.08" x2="12" y2="12" />
      </svg>
    ),
  },
];

const accentText = {
  cyan: "text-disc-cyan",
  blue: "text-disc-blue",
  amber: "text-noteblock-amber",
};

const accentBg = {
  cyan: "bg-disc-cyan/10",
  blue: "bg-disc-blue/10",
  amber: "bg-noteblock-amber/10",
};

export default function HowItWorks() {
  return (
    <section id="how-it-works" className="relative px-6 py-28 sm:py-32">
      <div className="pointer-events-none absolute inset-0 grid-backdrop" aria-hidden="true" />
      <div className="relative mx-auto max-w-7xl">
        <SectionHeader
          eyebrow="How it works"
          title="From waveform to world in four hops."
          lede="Every stage runs where it belongs. Audio never leaves the DJ's machine, the pattern math stays off the Minecraft tick thread, and players see the result with a vanilla client."
        />

        <div className="relative mt-14 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {/* Connector line behind the cards (desktop only) */}
          <svg
            className="pointer-events-none absolute left-0 right-0 top-[52px] hidden h-2 w-full lg:block"
            viewBox="0 0 100 2"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <path d="M0 1h100" stroke="rgba(255,255,255,0.15)" strokeWidth="1" className="flow-line" />
          </svg>

          {stages.map((stage) => (
            <article
              key={stage.number}
              className="flat-card relative flex flex-col rounded-2xl p-6"
            >
              <div className="mb-5 flex items-center justify-between">
                <div
                  className={`flex h-11 w-11 items-center justify-center rounded-xl ${accentBg[stage.accent]} ${accentText[stage.accent]}`}
                >
                  {stage.icon}
                </div>
                <span className="font-mono text-2xl font-bold text-white/10">{stage.number}</span>
              </div>

              <h3 className="font-heading text-xl font-bold">{stage.title}</h3>
              <p className="mt-0.5 font-mono text-[11px] uppercase tracking-wider text-text-secondary/70">
                Runs on {stage.runsOn}
              </p>

              <p className="mt-4 flex-1 text-sm leading-relaxed text-text-secondary">
                {stage.description}
              </p>

              <div className="mt-5 flex flex-wrap gap-1.5">
                {stage.tags.map((tag) => (
                  <Badge key={tag} tone="neutral">
                    {tag}
                  </Badge>
                ))}
              </div>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
