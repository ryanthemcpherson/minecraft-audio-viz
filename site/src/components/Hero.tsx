"use client";

import dynamic from "next/dynamic";
import Link from "next/link";

const VisualizerBackground = dynamic(
  () => import("@/components/VisualizerBackground"),
  { ssr: false }
);

export default function Hero() {
  return (
    <section className="relative flex min-h-screen items-center justify-center overflow-hidden px-6 pt-20">
      {/* Three.js Visualizer Background */}
      <div className="pointer-events-none absolute inset-0 dither-overlay">
        <VisualizerBackground />

        {/* Dark gradient overlay for text readability – smoother multi-stop */}
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,transparent_0%,rgba(10,10,10,0.02)_15%,rgba(10,10,10,0.08)_25%,rgba(10,10,10,0.18)_35%,rgba(10,10,10,0.32)_45%,rgba(10,10,10,0.5)_55%,rgba(10,10,10,0.65)_65%,rgba(10,10,10,0.78)_75%,rgba(10,10,10,0.88)_85%,rgba(10,10,10,0.95)_95%,#0a0a0a_100%)]" />

        {/* Bottom fade to next section – extra stops to avoid banding */}
        <div className="absolute inset-x-0 bottom-0 h-80 bg-[linear-gradient(to_top,#0a0a0a_0%,#0a0a0a_5%,rgba(10,10,10,0.97)_12%,rgba(10,10,10,0.9)_20%,rgba(10,10,10,0.75)_35%,rgba(10,10,10,0.55)_50%,rgba(10,10,10,0.3)_65%,rgba(10,10,10,0.12)_80%,rgba(10,10,10,0.04)_90%,transparent_100%)]" />

        {/* Top fade for navbar blend */}
        <div className="absolute inset-x-0 top-0 h-28 bg-[linear-gradient(to_bottom,rgba(10,10,10,0.55)_0%,rgba(10,10,10,0.25)_50%,transparent_100%)]" />
      </div>

      {/* Content */}
      <div className="relative z-10 mx-auto max-w-4xl text-center">
        {/* Badge */}
        <div className="animate-slide-up mb-8 inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm text-text-secondary backdrop-blur-sm">
          <span className="inline-block h-2 w-2 rounded-full bg-electric-blue animate-pulse" />
          Now with 27+ visualization patterns
        </div>

        {/* Headline */}
        <h1 className="animate-slide-up-delay-1 text-5xl font-bold leading-tight tracking-tight sm:text-6xl md:text-7xl lg:text-8xl">
          Minecraft Audio{" "}
          <span className="text-gradient">Visualizer</span>
        </h1>

        {/* Subline */}
        <p className="animate-slide-up-delay-2 mx-auto mt-6 max-w-2xl text-lg text-text-secondary sm:text-xl md:text-2xl">
          Real-time audio visualization in Minecraft. No client mods.
        </p>

        {/* CTA Buttons */}
        <div className="animate-slide-up-delay-3 mt-10 flex flex-col items-center gap-4 sm:flex-row sm:justify-center">
          <Link
            href="/getting-started"
            className="group relative inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-electric-blue to-deep-purple px-8 py-4 text-sm font-semibold text-white shadow-lg shadow-electric-blue/20 transition-all hover:shadow-xl hover:shadow-electric-blue/30 hover:brightness-110"
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
