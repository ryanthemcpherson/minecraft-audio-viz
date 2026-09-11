"use client";

import dynamic from "next/dynamic";

/**
 * The stage pulls in the Lua bundle and Three.js, so it is loaded on the
 * client only and after the initial paint.
 */
const PatternStage = dynamic(() => import("./PatternStage"), {
  ssr: false,
  loading: () => (
    <section id="patterns" className="px-6 py-28 sm:py-32">
      <div className="mx-auto max-w-7xl">
        <div className="h-8 w-48 animate-pulse rounded bg-white/5" />
        <div className="mt-12 grid gap-4 lg:grid-cols-[1.7fr_1fr]">
          <div className="aspect-[16/10] animate-pulse rounded-2xl bg-white/[0.03] lg:min-h-[420px]" />
          <div className="animate-pulse rounded-2xl bg-white/[0.03]" />
        </div>
      </div>
    </section>
  ),
});

export default PatternStage;
