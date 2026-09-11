"use client";

import dynamic from "next/dynamic";

const PatternGallery = dynamic(() => import("./PatternGallery"), {
  ssr: false,
  loading: () => (
    <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="aspect-[16/12] animate-pulse rounded-2xl bg-white/[0.03]" />
      ))}
    </div>
  ),
});

export default PatternGallery;
