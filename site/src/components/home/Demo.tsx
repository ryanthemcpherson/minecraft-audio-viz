"use client";

import { useState } from "react";
import SectionHeader from "@/components/ui/SectionHeader";

const DEMO_VIDEO_URL = "/demo.mp4";

/**
 * Demo video with a lightweight poster. The 10 MB file is not fetched until
 * the visitor presses play.
 */
export default function Demo() {
  const [playing, setPlaying] = useState(false);

  return (
    <section id="demo" className="relative px-6 py-28 sm:py-32">
      <div className="mx-auto max-w-5xl">
        <SectionHeader
          eyebrow="Demo"
          title="MCAV in action."
          lede="A short capture of a live set: DJ client on the left, the stage reacting in-game on the right."
          align="center"
        />

        <div className="group relative mt-12 overflow-hidden rounded-2xl border border-white/10 bg-bg-secondary shadow-2xl">
          <div
            className="pointer-events-none absolute -inset-1 rounded-2xl bg-gradient-to-r from-disc-cyan/20 via-disc-blue/20 to-noteblock-amber/20 opacity-0 blur-xl transition-opacity duration-500 group-hover:opacity-100"
            aria-hidden="true"
          />

          <div className="relative aspect-video w-full overflow-hidden rounded-2xl bg-[#0a0b10]">
            {playing ? (
              <video className="h-full w-full" controls autoPlay playsInline preload="auto">
                <source src={DEMO_VIDEO_URL} type="video/mp4" />
                Your browser does not support the video tag.
              </video>
            ) : (
              <button
                type="button"
                onClick={() => setPlaying(true)}
                className="group/play flex h-full w-full items-center justify-center bg-[radial-gradient(ellipse_at_center,rgba(0,204,255,0.12)_0%,rgba(91,106,255,0.08)_35%,transparent_70%)]"
                aria-label="Play demo video"
              >
                {/* Decorative EQ backdrop */}
                <div className="pointer-events-none absolute inset-x-0 bottom-0 flex h-1/2 items-end justify-center gap-1 px-8 opacity-40" aria-hidden="true">
                  {Array.from({ length: 32 }).map((_, i) => (
                    <div
                      key={i}
                      className="eq-bar w-full max-w-3 rounded-t-sm bg-gradient-to-t from-disc-cyan to-disc-blue"
                      style={{
                        height: "30%",
                        animationDelay: `${(i % 8) * 0.13}s`,
                        animationDuration: `${1.1 + (i % 5) * 0.15}s`,
                      }}
                    />
                  ))}
                </div>
                <span className="relative flex h-20 w-20 items-center justify-center rounded-full border border-white/20 bg-white/10 text-white backdrop-blur-md transition-transform group-hover/play:scale-110">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                    <polygon points="6,3 20,12 6,21" />
                  </svg>
                </span>
              </button>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
