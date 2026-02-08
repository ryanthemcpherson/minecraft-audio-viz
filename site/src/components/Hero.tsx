"use client";

import dynamic from "next/dynamic";

const VisualizerBackground = dynamic(
  () => import("@/components/VisualizerBackground"),
  { ssr: false }
);

export default function Hero() {
  return (
    <section className="relative flex min-h-screen items-center justify-center overflow-hidden px-6 pt-20">
      {/* Three.js Visualizer Background */}
      <div className="pointer-events-none absolute inset-0">
        <VisualizerBackground />

        {/* Dark gradient overlay for text readability */}
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,transparent_0%,rgba(10,10,10,0.4)_50%,rgba(10,10,10,0.8)_100%)]" />

        {/* Bottom fade to next section */}
        <div className="absolute inset-x-0 bottom-0 h-48 bg-gradient-to-t from-[#0a0a0a] to-transparent" />

        {/* Top fade for navbar blend */}
        <div className="absolute inset-x-0 top-0 h-24 bg-gradient-to-b from-[#0a0a0a]/60 to-transparent" />
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
          Turn Sound Into{" "}
          <span className="text-gradient">Worlds</span>
        </h1>

        {/* Subline */}
        <p className="animate-slide-up-delay-2 mx-auto mt-6 max-w-2xl text-lg text-text-secondary sm:text-xl md:text-2xl">
          Real-time audio visualization in Minecraft. No client mods. Pure server-side magic.
        </p>

        {/* CTA Buttons */}
        <div className="animate-slide-up-delay-3 mt-10 flex flex-col items-center gap-4 sm:flex-row sm:justify-center">
          <a
            href="https://github.com/ryanthemcpherson/minecraft-audio-viz#quick-start"
            target="_blank"
            rel="noopener noreferrer"
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
          </a>
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