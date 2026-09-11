"use client";

import React, { useMemo, useState } from "react";
import { listPatterns } from "@/lib/patterns";
import { CATEGORY_ORDER } from "@/lib/patterns/meta";
import PatternCard from "./PatternCard";
import AudioSourcePicker from "./AudioSourcePicker";

class GalleryErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { hasError: boolean; error: Error | null }
> {
  state = { hasError: false, error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error) {
    console.error("[MCAV] Pattern gallery failed:", error);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flat-card rounded-2xl p-10 text-center">
          <p className="text-text-secondary">
            Pattern previews could not be loaded. Please try refreshing the page.
          </p>
          {this.state.error && (
            <p className="mt-2 font-mono text-xs text-danger/80">{this.state.error.message}</p>
          )}
        </div>
      );
    }
    return this.props.children;
  }
}

function sortCategories(categories: string[]): string[] {
  const order = CATEGORY_ORDER as readonly string[];
  return [...categories].sort((a, b) => {
    const ia = order.indexOf(a);
    const ib = order.indexOf(b);
    if (ia === -1 && ib === -1) return a.localeCompare(b);
    if (ia === -1) return 1;
    if (ib === -1) return -1;
    return ia - ib;
  });
}

function GalleryInner() {
  const all = useMemo(() => listPatterns(), []);
  const [category, setCategory] = useState("All");
  const [query, setQuery] = useState("");

  const categories = useMemo(() => {
    const counts = new Map<string, number>();
    for (const p of all) counts.set(p.category, (counts.get(p.category) ?? 0) + 1);
    return [
      { name: "All", count: all.length },
      ...sortCategories([...counts.keys()]).map((name) => ({
        name,
        count: counts.get(name) ?? 0,
      })),
    ];
  }, [all]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return all.filter((p) => {
      if (category !== "All" && p.category !== category) return false;
      if (!q) return true;
      return (
        p.name.toLowerCase().includes(q) ||
        p.description.toLowerCase().includes(q) ||
        p.id.includes(q)
      );
    });
  }, [all, category, query]);

  return (
    <div>
      {/* Controls */}
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Filter by category">
          {categories.map((cat) => {
            const isActive = category === cat.name;
            return (
              <button
                key={cat.name}
                type="button"
                onClick={() => setCategory(cat.name)}
                aria-pressed={isActive}
                className={`rounded-full border px-3.5 py-1.5 text-sm font-medium transition-all ${
                  isActive
                    ? "border-disc-cyan/50 bg-disc-cyan/10 text-white"
                    : "border-white/10 bg-white/[0.03] text-text-secondary hover:border-white/20 hover:text-white"
                }`}
              >
                {cat.name}
                <span
                  className={`ml-1.5 font-mono text-xs ${isActive ? "text-disc-cyan" : "text-text-secondary/70"}`}
                >
                  {cat.count}
                </span>
              </button>
            );
          })}
        </div>

        <label className="relative block md:w-72">
          <span className="sr-only">Search patterns</span>
          <svg
            className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-text-secondary"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="11" cy="11" r="8" />
            <path d="M21 21l-4.35-4.35" />
          </svg>
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search patterns"
            className="w-full rounded-xl border border-white/10 bg-white/[0.03] py-2 pl-10 pr-4 text-sm text-white placeholder:text-text-secondary/60 focus:border-disc-cyan/50 focus:outline-none"
          />
        </label>
      </div>

      <div className="mt-6 flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <AudioSourcePicker compact />
        <p className="font-mono text-[11px] uppercase tracking-wider text-text-secondary/70" aria-live="polite">
          {filtered.length} of {all.length} patterns
        </p>
      </div>

      {/* Grid */}
      {filtered.length === 0 ? (
        <div className="flat-card mt-6 rounded-2xl p-12 text-center">
          <p className="text-text-secondary">No patterns match that search.</p>
          <button
            type="button"
            onClick={() => {
              setQuery("");
              setCategory("All");
            }}
            className="mt-4 text-sm font-semibold text-disc-cyan hover:underline"
          >
            Clear filters
          </button>
        </div>
      ) : (
        <div className="mt-6 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map((p, i) => (
            <PatternCard key={p.id} meta={p} index={i} />
          ))}
        </div>
      )}
    </div>
  );
}

export default function PatternGallery() {
  return (
    <GalleryErrorBoundary>
      <GalleryInner />
    </GalleryErrorBoundary>
  );
}
