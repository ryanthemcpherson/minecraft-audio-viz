"use client";

import dynamic from "next/dynamic";

const PatternCarousel = dynamic(() => import("@/components/PatternCarousel"), {
  ssr: false,
});

export default function LazyPatternCarousel() {
  return <PatternCarousel />;
}
