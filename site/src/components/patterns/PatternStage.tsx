"use client";

import { useEffect, useMemo, useRef, useState, useCallback } from "react";
import Link from "next/link";
import { listPatterns, type PatternMeta } from "@/lib/patterns";
import { useReducedMotion } from "@/lib/useReducedMotion";
import Badge, { categoryTone } from "@/components/ui/Badge";
import SectionHeader from "@/components/ui/SectionHeader";
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
  "spectrum",
];

const AUTO_ADVANCE_MS = 9_000;

/**
 * Homepage pattern showcase: one large live preview plus a selectable list.
 * Only a single WebGL context runs, no matter how many patterns are listed.
 */
export default function PatternStage() {
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

  return (
    <section id="patterns" className="relative px-6 py-28 sm:py-32">
      <div className="mx-auto max-w-7xl">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
          <SectionHeader
            eyebrow="Patterns"
            title="Pick a look. Or write one."
            lede={`${all.length} Lua patterns ship in the box, each reacting to bands, beats, and tempo. This preview runs the real Lua in your browser against a synthetic 128 BPM track.`}
          />
          <Link
            href="/patterns"
            className="inline-flex shrink-0 items-center gap-1.5 text-sm font-semibold text-disc-cyan hover:underline"
          >
            See all {all.length} patterns
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M5 12h14M12 5l7 7-7 7" />
            </svg>
          </Link>
        </div>

        <div className="mt-10">
          <AudioSourcePicker />
        </div>

        <div
          ref={stageRef}
          className="mt-5 grid gap-4 lg:grid-cols-[1.7fr_1fr]"
          onMouseEnter={() => setPaused(true)}
          onMouseLeave={() => setPaused(false)}
        >
          {/* Stage */}
          <div className="flat-card relative overflow-hidden rounded-2xl">
            <PatternPreview
              key={active.id}
              meta={active}
              live={visible && !reducedMotion}
              quality="high"
              phaseOffset={activeIndex * 1.3}
              className="aspect-[16/10] lg:aspect-auto lg:h-full lg:min-h-[420px]"
            />
            <div className="pointer-events-none absolute inset-x-0 bottom-0 flex items-end justify-between gap-4 bg-gradient-to-t from-black/80 via-black/30 to-transparent p-5">
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="font-heading text-xl font-bold text-white">{active.name}</h3>
                  <Badge tone={categoryTone(active.category)}>{active.category}</Badge>
                </div>
                <p className="mt-1 max-w-lg text-sm text-white/70">{active.description}</p>
              </div>
              <span className="hidden shrink-0 font-mono text-[10px] uppercase tracking-wider text-white/50 sm:block">
                {active.startBlocks ? `${active.startBlocks} blocks` : ""} &middot; {active.id}.lua
              </span>
            </div>
          </div>

          {/* Selector */}
          <div className="flat-card rounded-2xl p-2" role="tablist" aria-label="Featured patterns">
            {featured.map((p, i) => {
              const isActive = i === activeIndex;
              return (
                <button
                  key={p.id}
                  type="button"
                  role="tab"
                  aria-selected={isActive}
                  onClick={() => select(i)}
                  className={`flex w-full items-center justify-between gap-3 rounded-xl px-4 py-3 text-left transition-colors ${
                    isActive
                      ? "bg-white/[0.07] text-white"
                      : "text-text-secondary hover:bg-white/[0.04] hover:text-white"
                  }`}
                >
                  <span className="flex items-center gap-3">
                    <span
                      className={`h-1.5 w-1.5 rounded-full transition-colors ${
                        isActive ? "bg-disc-cyan" : "bg-white/15"
                      }`}
                      aria-hidden="true"
                    />
                    <span className="text-sm font-medium">{p.name}</span>
                  </span>
                  <Badge tone={isActive ? categoryTone(p.category) : "neutral"}>{p.category}</Badge>
                </button>
              );
            })}
            <p className="px-4 pb-2 pt-3 font-mono text-[10px] uppercase tracking-wider text-text-secondary/50">
              {paused ? "Paused" : "Auto-cycling"} &middot; hover to pause
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
