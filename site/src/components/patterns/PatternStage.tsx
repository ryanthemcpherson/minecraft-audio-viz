"use client";

import { useEffect, useMemo, useRef, useState, useCallback } from "react";
import Link from "next/link";
import { listPatterns, type PatternMeta } from "@/lib/patterns";
import { useReducedMotion } from "@/lib/useReducedMotion";
import PatternPreview from "./PatternPreview";
import AudioSourcePicker from "./AudioSourcePicker";

/** Curated set shown on the homepage, in display order. Missing ids are skipped. */
const FEATURED_IDS = [
  "galaxy",
  "aurora",
  "dragon",
  "tesseract",
  "ledwall",
  "vortex",
  "mandala",
  "phoenix",
];

const AUTO_ADVANCE_MS = 9_000;

interface PatternStageProps {
  /** "hero" is the compact homepage layout: stage, pattern chips, source picker. */
  variant?: "hero" | "section";
}

/**
 * Live showcase: one large preview running the real Lua pattern with real
 * block textures, plus a strip of patterns to switch between. A single WebGL
 * context no matter how many patterns are listed.
 */
export default function PatternStage({ variant = "section" }: PatternStageProps) {
  const all = useMemo(() => listPatterns(), []);
  const featured = useMemo<PatternMeta[]>(() => {
    const byId = new Map(all.map((p) => [p.id, p]));
    const picked = FEATURED_IDS.map((id) => byId.get(id)).filter(
      (p): p is PatternMeta => Boolean(p),
    );
    return picked.length > 0 ? picked : all.slice(0, 8);
  }, [all]);

  const [activeIndex, setActiveIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const [visible, setVisible] = useState(false);
  const stageRef = useRef<HTMLDivElement>(null);
  const reducedMotion = useReducedMotion();

  useEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), {
      rootMargin: "120px 0px",
      threshold: 0,
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (paused || !visible || reducedMotion || featured.length < 2) return;
    const timer = setInterval(() => {
      setActiveIndex((i) => (i + 1) % featured.length);
    }, AUTO_ADVANCE_MS);
    return () => clearInterval(timer);
  }, [paused, visible, reducedMotion, featured.length]);

  const select = useCallback((index: number) => {
    setActiveIndex(index);
    setPaused(true);
  }, []);

  const active = featured[activeIndex];
  if (!active) return null;

  const isHero = variant === "hero";

  return (
    <div
      ref={stageRef}
      className={isHero ? "" : "mx-auto max-w-7xl"}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
    >
      <div className="relative overflow-hidden rounded-md border border-white/10 bg-[#050505]">
        <PatternPreview
          key={active.id}
          meta={active}
          live={visible && !reducedMotion}
          quality="high"
          phaseOffset={activeIndex * 1.3}
          className={isHero ? "aspect-[16/11]" : "aspect-[16/9]"}
        />
        {/* Caption: name, category, and the file the server actually runs */}
        <div className="pointer-events-none absolute inset-x-0 bottom-0 flex items-end justify-between gap-4 bg-gradient-to-t from-black/85 via-black/40 to-transparent px-4 pb-3 pt-10">
          <div className="min-w-0">
            <p className="font-heading text-lg font-bold leading-tight text-white">{active.name}</p>
            <p className="mt-0.5 truncate text-sm text-white/70">{active.description}</p>
          </div>
          <p className="shrink-0 font-mono text-[11px] text-white/60">
            {active.startBlocks ? `${active.startBlocks} blocks` : active.category}
          </p>
        </div>
      </div>

      {/* Pattern strip */}
      <div
        role="tablist"
        aria-label="Featured patterns"
        className="mt-3 flex flex-wrap items-center gap-1.5"
      >
        {featured.map((p, i) => {
          const isActive = i === activeIndex;
          return (
            <button
              key={p.id}
              type="button"
              role="tab"
              aria-selected={isActive}
              onClick={() => select(i)}
              className={`rounded-md border px-3 py-1.5 text-sm transition-colors ${
                isActive
                  ? "border-disc-cyan/60 bg-disc-cyan/10 text-text-primary"
                  : "border-white/10 text-text-secondary hover:border-white/25 hover:text-text-primary"
              }`}
            >
              {p.name}
            </button>
          );
        })}
        {isHero && (
          <Link
            href="/patterns"
            className="px-2 text-sm text-disc-cyan hover:underline"
          >
            All {all.length} patterns
          </Link>
        )}
      </div>

      <div className="mt-4">
        <AudioSourcePicker compact={isHero} />
      </div>
    </div>
  );
}
