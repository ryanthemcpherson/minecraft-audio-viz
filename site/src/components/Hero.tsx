import Link from "next/link";
import HeroBackground from "./HeroBackground";

export default function Hero() {
  return (
    <section className="relative flex min-h-screen items-center justify-center overflow-hidden px-6 pt-20">
      {/* Three.js Visualizer Background */}
      <HeroBackground />

      {/* Content */}
      <div className="relative z-10 mx-auto max-w-4xl text-center">
        {/* Headline */}
        <h1 className="animate-slide-up text-5xl font-bold leading-tight tracking-tight sm:text-6xl md:text-7xl lg:text-8xl">
          Minecraft Audio{" "}
          <span className="text-gradient">Visualizer</span>
        </h1>

        {/* Subline */}
        <p className="animate-slide-up-delay-1 mx-auto mt-6 max-w-2xl text-lg text-text-secondary sm:text-xl md:text-2xl">
          Real-time audio visualization in Minecraft. No client mods.
        </p>

        {/* CTA Buttons */}
        <div className="animate-slide-up-delay-2 mt-10 flex flex-col items-center gap-4 sm:flex-row sm:justify-center">
          <Link
            href="/getting-started"
            className="group relative inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-disc-cyan to-disc-blue px-8 py-4 text-sm font-semibold text-white shadow-lg shadow-disc-cyan/20 transition-all hover:shadow-xl hover:shadow-disc-cyan/30 hover:brightness-110"
          >
            Get Started
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="transition-transform group-hover:translate-x-1"
            >
              <path d="M5 12h14M12 5l7 7-7 7" />
            </svg>
          </Link>
          <a
            href="#demo"
            className="inline-flex items-center gap-2 rounded-xl border border-white/10 bg-white/5 px-8 py-4 text-sm font-semibold text-white backdrop-blur-sm transition-all hover:border-white/20 hover:bg-white/10"
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="currentColor"
            >
              <polygon points="5,3 19,12 5,21" />
            </svg>
            Watch Demo
          </a>
        </div>
      </div>
    </section>
  );
}
