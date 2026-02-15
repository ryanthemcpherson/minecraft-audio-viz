import type { Metadata } from "next";
import PatternGallery from "@/components/PatternGallery";
import Footer from "@/components/Footer";

export const metadata: Metadata = {
  title: "Pattern Gallery | MCAV",
  description:
    "Explore 30 real-time visualization patterns — spirals, auroras, DNA helixes, spectrum analyzers, and more. Each preview runs the actual pattern engine.",
};

export default function PatternsPage() {
  return (
    <>
      {/* Hero */}
      <section className="relative overflow-hidden pt-32 pb-16">
        {/* Background glow */}
        <div className="pointer-events-none absolute inset-0 z-0">
          <div className="absolute top-1/4 left-1/2 h-[500px] w-[600px] -translate-x-1/2 rounded-full bg-disc-cyan/5 blur-[120px]" />
          <div className="absolute top-1/3 right-1/4 h-[400px] w-[400px] rounded-full bg-disc-blue/5 blur-[100px]" />
        </div>

        <div className="relative z-10 mx-auto max-w-7xl px-6 text-center">
          <h1 className="animate-slide-up text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl">
            <span className="text-gradient">Pattern Gallery</span>
          </h1>
          <p className="animate-slide-up-delay-1 mx-auto mt-4 max-w-2xl text-lg text-text-secondary">
            30 visualization patterns — spirals, auroras, DNA helixes, and
            more. Each preview runs the actual pattern engine with simulated
            audio.
          </p>
        </div>
      </section>

      <PatternGallery />
      <Footer />
    </>
  );
}
