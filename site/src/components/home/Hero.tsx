import HeroBackground from "@/components/HeroBackground";
import Button, { ArrowIcon } from "@/components/ui/Button";
import Badge from "@/components/ui/Badge";
import { RELEASE, releaseLabel } from "@/lib/releaseStatus";

const flow = [
  { label: "System audio", sub: "Spotify, Chrome, any app" },
  { label: "DJ client", sub: "Rust, 5-band FFT, beat detect" },
  { label: "VJ server", sub: "Lua patterns, multi-DJ" },
  { label: "Paper server", sub: "Display Entities, vanilla clients" },
];

function FlowArrow() {
  return (
    <svg
      width="28"
      height="10"
      viewBox="0 0 28 10"
      fill="none"
      aria-hidden="true"
      className="hidden shrink-0 text-white/25 md:block"
    >
      <path d="M0 5h22" stroke="currentColor" strokeWidth="1.5" className="flow-line" />
      <path d="M20 1l6 4-6 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export default function Hero() {
  return (
    <section className="relative flex min-h-[92vh] items-center justify-center overflow-hidden px-6 pt-28 pb-16">
      <HeroBackground />

      <div className="relative z-10 mx-auto flex max-w-5xl flex-col items-center text-center">
        <div className="animate-slide-up">
          <Badge tone={RELEASE.isReleased ? "success" : "warning"} dot>
            {releaseLabel()} &middot; Paper {RELEASE.minecraft}
          </Badge>
        </div>

        <h1 className="animate-slide-up mt-6 font-heading text-5xl font-bold leading-[1.02] sm:text-6xl md:text-7xl lg:text-[5.5rem]">
          Your music,
          <br />
          <span className="text-gradient">rendered live</span> in Minecraft.
        </h1>

        <p className="animate-slide-up-delay-1 mx-auto mt-6 max-w-2xl text-lg leading-relaxed text-text-secondary sm:text-xl">
          MCAV listens to whatever is playing on your PC, analyzes it in real time, and drives
          audio-reactive 3D structures on a Paper server. Vanilla clients. No mods, no resource
          packs.
        </p>

        <div className="animate-slide-up-delay-2 mt-10 flex flex-col items-center gap-3 sm:flex-row">
          <Button href="/getting-started" size="lg" className="group">
            Get started
            <ArrowIcon className="transition-transform group-hover:translate-x-1" />
          </Button>
          <Button href="/patterns" variant="secondary" size="lg">
            Browse patterns
          </Button>
        </div>

        {/* Signal flow strip */}
        <div className="animate-slide-up-delay-3 mt-16 w-full">
          <div className="mx-auto grid max-w-4xl grid-cols-2 items-center gap-3 md:flex md:justify-center md:gap-2">
            {flow.map((node, i) => (
              <div key={node.label} className="contents">
                <div className="flat-card rounded-xl px-4 py-3 text-left">
                  <p className="font-mono text-[11px] font-semibold uppercase tracking-wider text-white">
                    {node.label}
                  </p>
                  <p className="mt-0.5 text-xs text-text-secondary">{node.sub}</p>
                </div>
                {i < flow.length - 1 && <FlowArrow />}
              </div>
            ))}
          </div>
          <p className="mt-4 font-mono text-[11px] uppercase tracking-[0.2em] text-text-secondary/70">
            ~21 ms analysis window &middot; 60 fps preview &middot; 20 fps in-game render
          </p>
        </div>
      </div>
    </section>
  );
}
