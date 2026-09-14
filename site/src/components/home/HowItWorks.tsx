import SectionHeader from "@/components/ui/SectionHeader";

const stages = [
  {
    title: "Capture",
    where: "DJ client, Windows",
    body: "The full system mix or a single application. Spotify, a browser tab, a DAW: anything making sound on the DJ's PC.",
  },
  {
    title: "Analyze",
    where: "DJ client, Rust",
    body: "Five bands, onset and kick detection, tempo tracking, inside a 21 ms window. Only compact frames leave the machine, never raw audio.",
  },
  {
    title: "Compose",
    where: "VJ server, Lua",
    body: "Frames feed a pattern engine that turns bands and beats into block positions, colors, and scale. The operator switches patterns and effects live.",
  },
  {
    title: "Render",
    where: "Paper plugin, Java",
    body: "Display Entities are pooled and updated in one scheduler call per tick, so 160 blocks move in sync without touching the client.",
  },
];

/**
 * One signal-flow line, four stops. No cards: the rail is the diagram.
 */
export default function HowItWorks() {
  return (
    <section id="how-it-works" className="border-t border-white/[0.07] px-6 py-24 sm:py-28">
      <div className="mx-auto max-w-7xl">
        <div className="grid gap-12 lg:grid-cols-[minmax(0,4fr)_minmax(0,8fr)]">
          <SectionHeader
            title="From waveform to world in four hops."
            lede="Every stage runs where it belongs. Audio never leaves the DJ's machine, pattern math stays off the Minecraft tick thread, and players see the result with a vanilla client."
          />

          <ol className="relative grid gap-10 sm:grid-cols-2 lg:grid-cols-4 lg:gap-8">
            {/* Rail (desktop) */}
            <div
              className="pointer-events-none absolute left-0 right-0 top-[7px] hidden h-px bg-white/15 lg:block"
              aria-hidden="true"
            />
            {stages.map((stage, i) => (
              <li key={stage.title} className="relative lg:pt-8">
                <span
                  className="absolute left-0 top-0 hidden h-[15px] w-[15px] rounded-full border-2 border-disc-cyan bg-bg-primary lg:block"
                  aria-hidden="true"
                />
                <p className="text-sm text-text-secondary">
                  <span className="font-mono text-xs text-text-secondary/70">0{i + 1}</span>
                  <span className="mx-2 text-white/20">/</span>
                  {stage.where}
                </p>
                <h3 className="mt-2 font-heading text-xl font-bold">{stage.title}</h3>
                <p className="mt-2 text-[15px] leading-relaxed text-text-secondary">{stage.body}</p>
              </li>
            ))}
          </ol>
        </div>
      </div>
    </section>
  );
}
