import type { Metadata } from "next";
import SectionHeader from "@/components/ui/SectionHeader";
import Footer from "@/components/Footer";
import LazyPatternGallery from "@/components/patterns/LazyPatternGallery";
import { PATTERN_COUNT } from "@/lib/patterns/meta";
import { LINKS } from "@/lib/links";

export const metadata: Metadata = {
  title: "Pattern gallery",
  description: `Browse all ${PATTERN_COUNT} MCAV visualization patterns. Every preview runs the real Lua pattern in your browser with real block textures.`,
  alternates: { canonical: "https://mcav.live/patterns" },
};

export default function PatternsPage() {
  return (
    <>
      <section className="px-6 pt-28 pb-10 sm:pt-32">
        <div className="mx-auto max-w-7xl">
          <SectionHeader
            title={`${PATTERN_COUNT} patterns, all live.`}
            lede={
              <>
                Each preview runs the same Lua the VJ server executes, with the same block
                materials the game renders. A few previews run at a time as you scroll.{" "}
                <a
                  href={LINKS.docs.patternGuide}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-disc-cyan hover:underline"
                >
                  Write your own
                </a>
                .
              </>
            }
          />
        </div>
      </section>

      <section className="px-6 pb-24">
        <div className="mx-auto max-w-7xl">
          <LazyPatternGallery />
        </div>
      </section>

      <Footer />
    </>
  );
}
