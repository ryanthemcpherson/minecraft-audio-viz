"use client";

import React, { useState, useEffect, useRef, useMemo, useCallback } from "react";
import { listPatterns } from "@/lib/patterns";
import CarouselCard from "./CarouselCard";

const CATEGORIES = ["All", "Original", "Epic", "Cosmic", "Organic", "Spectrum"];
const AUTO_CYCLE_MS = 12_000;

/**
 * Error boundary around the entire pattern carousel.
 * Catches errors from Fengari/Lua VM initialization, pattern loading,
 * or any rendering failure that escapes the per-card CanvasErrorBoundary.
 */
class CarouselErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { hasError: boolean; error: Error | null }
> {
  state = { hasError: false, error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error };
  }

  render() {
    if (this.state.hasError) {
      return (
        <section id="patterns" className="mx-auto max-w-7xl px-6 py-24">
          <div className="text-center">
            <h2 className="text-3xl font-bold tracking-tight text-text-primary sm:text-4xl">
              Visualization Patterns
            </h2>
            <p className="mt-6 text-text-secondary">
              Pattern previews could not be loaded. Please try refreshing the page.
            </p>
            {this.state.error && (
              <p className="mt-2 text-sm text-red-400/70">
                {this.state.error.message}
              </p>
            )}
          </div>
        </section>
      );
    }
    return this.props.children;
  }
}

function PatternCarouselInner() {
  const allPatterns = useMemo(() => listPatterns(), []);
  const [activeCategory, setActiveCategory] = useState("All");
  const [startIndex, setStartIndex] = useState(0);
  const [cardsPerView, setCardsPerView] = useState(3);
  const [paused, setPaused] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const filtered = useMemo(() => {
    if (activeCategory === "All") return allPatterns;
    return allPatterns.filter((p) => p.category === activeCategory);
  }, [allPatterns, activeCategory]);

  const selectCategory = useCallback((cat: string) => {
    setActiveCategory(cat);
    setStartIndex(0);
  }, []);

  // Responsive cardsPerView
  useEffect(() => {
    const mql = window.matchMedia("(min-width: 768px)");
    const update = () => setCardsPerView(mql.matches ? 3 : 1);
    update();
    mql.addEventListener("change", update);
    return () => mql.removeEventListener("change", update);
  }, []);

  const advance = useCallback(
    (dir: 1 | -1) => {
      if (filtered.length === 0) return;
      setStartIndex((prev) => {
        const next = prev + dir * cardsPerView;
        return ((next % filtered.length) + filtered.length) % filtered.length;
      });
    },
    [filtered.length, cardsPerView],
  );

  // Auto-cycle
  useEffect(() => {
    if (paused || filtered.length <= cardsPerView) {
      if (timerRef.current) clearInterval(timerRef.current);
      timerRef.current = null;
      return;
    }
    timerRef.current = setInterval(() => advance(1), AUTO_CYCLE_MS);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [paused, filtered.length, cardsPerView, advance]);

  // Build visible cards with modular wrap
  const visibleCards = useMemo(() => {
    if (filtered.length === 0) return [];
    const cards = [];
    for (let i = 0; i < Math.min(cardsPerView, filtered.length); i++) {
      const idx = (startIndex + i) % filtered.length;
      cards.push({ slotIndex: i, pattern: filtered[idx] });
    }
    return cards;
  }, [filtered, startIndex, cardsPerView]);

  return (
    <section id="patterns" className="mx-auto max-w-7xl px-6 py-24">
      {/* Header */}
      <div className="mb-10 text-center">
        <h2 className="text-3xl font-bold tracking-tight text-text-primary sm:text-4xl">
          Visualization Patterns
        </h2>
        <p className="mt-3 text-text-secondary">
          {allPatterns.length} Lua patterns that react to your music in real time
        </p>
      </div>

      {/* Category pills */}
      <div className="mb-8 flex flex-wrap items-center justify-center gap-2">
        {CATEGORIES.map((cat) => {
          const isActive = activeCategory === cat;
          const count =
            cat === "All"
              ? allPatterns.length
              : allPatterns.filter((p) => p.category === cat).length;

          return (
            <button
              key={cat}
              onClick={() => selectCategory(cat)}
              className={`rounded-full border px-4 py-1.5 text-sm font-medium transition-all ${
                isActive
                  ? "border-disc-cyan/50 bg-disc-cyan/10 text-white"
                  : "border-white/10 bg-white/3 text-text-secondary hover:border-white/20 hover:text-white"
              }`}
            >
              {cat}
              <span
                className={`ml-1.5 text-xs ${isActive ? "text-disc-cyan" : "text-text-secondary"}`}
              >
                {count}
              </span>
            </button>
          );
        })}
      </div>

      {/* Carousel */}
      <div
        className="relative"
        onMouseEnter={() => setPaused(true)}
        onMouseLeave={() => setPaused(false)}
      >
        {/* Prev button */}
        {filtered.length > cardsPerView && (
          <button
            onClick={() => advance(-1)}
            className="absolute -left-4 top-1/2 z-10 flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full border border-white/10 bg-black/60 text-white/70 backdrop-blur-sm transition-colors hover:border-white/20 hover:text-white"
            aria-label="Previous patterns"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="15 18 9 12 15 6" />
            </svg>
          </button>
        )}

        {/* Cards grid */}
        <div
          className={`grid gap-5 ${
            cardsPerView === 3 ? "grid-cols-1 md:grid-cols-3" : "grid-cols-1"
          }`}
        >
          {visibleCards.map(({ slotIndex, pattern }) => (
            <CarouselCard
              key={`${startIndex}-${slotIndex}`}
              meta={pattern}
              index={startIndex + slotIndex}
            />
          ))}
        </div>

        {/* Next button */}
        {filtered.length > cardsPerView && (
          <button
            onClick={() => advance(1)}
            className="absolute -right-4 top-1/2 z-10 flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full border border-white/10 bg-black/60 text-white/70 backdrop-blur-sm transition-colors hover:border-white/20 hover:text-white"
            aria-label="Next patterns"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="9 18 15 12 9 6" />
            </svg>
          </button>
        )}
      </div>

      {/* Dots indicator */}
      {filtered.length > cardsPerView && (
        <div className="mt-6 flex items-center justify-center gap-1.5">
          {Array.from({ length: Math.ceil(filtered.length / cardsPerView) }).map((_, i) => {
            const isActive = Math.floor(startIndex / cardsPerView) === i;
            return (
              <button
                key={i}
                onClick={() => setStartIndex(i * cardsPerView)}
                className={`h-1.5 rounded-full transition-all ${
                  isActive ? "w-6 bg-disc-cyan" : "w-1.5 bg-white/20 hover:bg-white/40"
                }`}
                aria-label={`Go to page ${i + 1}`}
              />
            );
          })}
        </div>
      )}
    </section>
  );
}

export default function PatternCarousel() {
  return (
    <CarouselErrorBoundary>
      <PatternCarouselInner />
    </CarouselErrorBoundary>
  );
}
