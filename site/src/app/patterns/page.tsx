import type { Metadata } from "next";
import SectionHeader from "@/components/ui/SectionHeader";
import Button from "@/components/ui/Button";
import Footer from "@/components/Footer";
import LazyPatternGallery from "@/components/patterns/LazyPatternGallery";
import { PATTERN_COUNT } from "@/lib/patterns/meta";
import { LINKS } from "@/lib/links";

export const metadata: Metadata = {
  title: "Pattern gallery",
  description: `Browse all ${PATTERN_COUNT} MCAV visualization patterns. Every preview runs the real Lua pattern in your browser against a synthetic track.`,
  alternates: { canonical: "https://mcav.live/patterns" },
};

export default function PatternsPage() {
  return (
    <>
      <section className="px-6 pt-32 pb-12">
        <div className="mx-auto max-w-7xl">
          <div className="flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
            <SectionHeader
              eyebrow="Pattern gallery"
              title={
                <>
                  {PATTERN_COUNT} patterns, <span className="text-gradient">all live.</span>
                </>
              }
              lede="Each card runs the same Lua the VJ server executes, fed by a synthetic 128 BPM track. Scroll to warm up previews; a few render at a time to keep your GPU happy."
            />
            <Button href={LINKS.docs.patternGuide} external variant="secondary">
              Write your own pattern
            </Button>
          </div>
        </div>
      </section>

      <section className="px-6 pb-28">
        <div className="mx-auto max-w-7xl">
          <LazyPatternGallery />
        </div>
      </section>

      <Footer />
    </>
  );
}
