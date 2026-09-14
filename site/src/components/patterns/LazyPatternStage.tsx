"use client";

import dynamic from "next/dynamic";

/**
 * The stage pulls in the Lua bundle and Three.js, so it is loaded on the
 * client only and after the initial paint.
 */
const PatternStage = dynamic(() => import("./PatternStage"), {
  ssr: false,
  loading: () => (
    <div>
      <div className="aspect-[16/11] animate-pulse rounded-md border border-white/10 bg-white/[0.03]" />
      <div className="mt-3 flex gap-1.5">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="h-8 w-20 animate-pulse rounded-md bg-white/[0.04]" />
        ))}
      </div>
    </div>
  ),
});

export default PatternStage;
