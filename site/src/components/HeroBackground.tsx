"use client";

import dynamic from "next/dynamic";

const VisualizerBackground = dynamic(
  () => import("@/components/VisualizerBackground"),
  { ssr: false }
);

export default function HeroBackground() {
  return (
    <div className="pointer-events-none absolute inset-0 dither-overlay">
      <VisualizerBackground />

      {/* Dark gradient overlay for text readability – smoother multi-stop */}
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,transparent_0%,rgba(8,9,13,0.02)_15%,rgba(8,9,13,0.08)_25%,rgba(8,9,13,0.18)_35%,rgba(8,9,13,0.32)_45%,rgba(8,9,13,0.5)_55%,rgba(8,9,13,0.65)_65%,rgba(8,9,13,0.78)_75%,rgba(8,9,13,0.88)_85%,rgba(8,9,13,0.95)_95%,#08090d_100%)]" />

      {/* Bottom fade to next section – extra stops to avoid banding */}
      <div className="absolute inset-x-0 bottom-0 h-80 bg-[linear-gradient(to_top,#08090d_0%,#08090d_5%,rgba(8,9,13,0.97)_12%,rgba(8,9,13,0.9)_20%,rgba(8,9,13,0.75)_35%,rgba(8,9,13,0.55)_50%,rgba(8,9,13,0.3)_65%,rgba(8,9,13,0.12)_80%,rgba(8,9,13,0.04)_90%,transparent_100%)]" />

      {/* Top fade for navbar blend */}
      <div className="absolute inset-x-0 top-0 h-28 bg-[linear-gradient(to_bottom,rgba(8,9,13,0.55)_0%,rgba(8,9,13,0.25)_50%,transparent_100%)]" />
    </div>
  );
}
