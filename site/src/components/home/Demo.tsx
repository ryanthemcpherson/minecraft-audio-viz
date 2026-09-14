"use client";

import { useState } from "react";
import SectionHeader from "@/components/ui/SectionHeader";

const DEMO_VIDEO_URL = "/demo.mp4";

/**
 * Demo video behind a plain poster. The 10 MB file is not fetched until the
 * visitor presses play.
 */
export default function Demo() {
  const [playing, setPlaying] = useState(false);

  return (
    <section id="demo" className="border-t border-white/[0.07] px-6 py-24 sm:py-28">
      <div className="mx-auto max-w-7xl">
        <div className="grid gap-10 lg:grid-cols-[minmax(0,4fr)_minmax(0,8fr)] lg:items-start">
          <SectionHeader
            title="A set, start to finish."
            lede="DJ client on the left, the stage reacting in-game on the right."
          />

          <div className="relative aspect-video w-full overflow-hidden rounded-md border border-white/10 bg-bg-secondary">
            {playing ? (
              <video className="h-full w-full" controls autoPlay playsInline preload="auto">
                <source src={DEMO_VIDEO_URL} type="video/mp4" />
                Your browser does not support the video tag.
              </video>
            ) : (
              <button
                type="button"
                onClick={() => setPlaying(true)}
                className="group flex h-full w-full items-center justify-center"
                aria-label="Play demo video"
              >
                <span className="flex items-center gap-3 rounded-md border border-white/15 bg-bg-primary/70 px-5 py-3 text-sm font-semibold text-text-primary transition-colors group-hover:border-white/30">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                    <polygon points="6,3 20,12 6,21" />
                  </svg>
                  Play demo
                  <span className="font-normal text-text-secondary">10 MB</span>
                </span>
              </button>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
