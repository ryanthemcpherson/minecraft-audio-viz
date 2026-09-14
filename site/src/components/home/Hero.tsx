import Button from "@/components/ui/Button";
import LazyPatternStage from "@/components/patterns/LazyPatternStage";
import { RELEASE } from "@/lib/releaseStatus";
import { PATTERN_COUNT } from "@/lib/patterns/meta";

/**
 * The product is the hero: copy on the left, the live pattern stage on the
 * right, running real Lua patterns with real block textures.
 */
export default function Hero() {
  return (
    <section className="px-6 pt-28 pb-16 sm:pt-32 lg:pb-24">
      <div className="mx-auto grid max-w-7xl items-center gap-12 lg:grid-cols-[5fr_7fr] lg:gap-16">
        <div className="max-w-xl">
          <h1 className="font-heading text-[clamp(2.5rem,1.6rem+3.6vw,4.75rem)] font-bold leading-[1.02] tracking-[-0.03em]">
            Your music, rendered live in Minecraft.
          </h1>
          <p className="mt-6 text-lg leading-relaxed text-text-secondary">
            MCAV listens to whatever is playing on your PC, analyzes it in real time, and drives
            audio-reactive block structures on a Paper server. Vanilla clients. No mods, no
            resource packs.
          </p>

          <div className="mt-8 flex flex-wrap items-center gap-3">
            <Button href="/getting-started" size="lg">
              Get started
            </Button>
            <Button href="/patterns" variant="secondary" size="lg">
              Browse all {PATTERN_COUNT} patterns
            </Button>
          </div>

          <p className="mt-6 text-sm text-text-secondary">
            {RELEASE.isReleased
              ? `Release ${RELEASE.version} for Paper ${RELEASE.minecraft}.`
              : `Release ${RELEASE.version} for Paper ${RELEASE.minecraft} is in progress. Server install ships first; the DJ client follows once its release gates pass.`}
          </p>
        </div>

        <div className="min-w-0">
          <LazyPatternStage variant="hero" />
        </div>
      </div>
    </section>
  );
}
