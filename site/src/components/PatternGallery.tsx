"use client";

import { useState, useMemo } from "react";
import PatternCard from "./PatternCard";
import { listPatterns } from "@/lib/patterns";

const CATEGORIES = ["All", "Original", "Epic", "Cosmic", "Organic", "Spectrum"];

export default function PatternGallery() {
  const [activeCategory, setActiveCategory] = useState("All");

  const allPatterns = useMemo(() => listPatterns(), []);

  const filtered = useMemo(() => {
    if (activeCategory === "All") return allPatterns;
    return allPatterns.filter((p) => p.category === activeCategory);
  }, [allPatterns, activeCategory]);

  return (
    <section className="mx-auto max-w-7xl px-6 pb-24">
      {/* Category Filters */}
      <div className="mb-10 flex flex-wrap items-center gap-2">
        {CATEGORIES.map((cat) => {
          const isActive = activeCategory === cat;
          const count =
            cat === "All"
              ? allPatterns.length
              : allPatterns.filter((p) => p.category === cat).length;

          return (
            <button
              key={cat}
              onClick={() => setActiveCategory(cat)}
              className={`rounded-full border px-4 py-1.5 text-sm font-medium transition-all ${
                isActive
                  ? "border-electric-blue/50 bg-electric-blue/10 text-white"
                  : "border-white/10 bg-white/3 text-text-secondary hover:border-white/20 hover:text-white"
              }`}
            >
              {cat}
              <span
                className={`ml-1.5 text-xs ${
                  isActive ? "text-electric-blue" : "text-text-secondary"
                }`}
              >
                {count}
              </span>
            </button>
          );
        })}
      </div>

      {/* Pattern Grid */}
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {filtered.map((pattern, i) => (
          <div key={pattern.id} className="animate-slide-up" style={{ animationDelay: `${i * 0.04}s`, opacity: 0 }}>
            <PatternCard
              id={pattern.id}
              name={pattern.name}
              description={pattern.description}
              category={pattern.category}
              createPattern={pattern.createPattern}
              index={i}
            />
          </div>
        ))}
      </div>

      {filtered.length === 0 && (
        <div className="py-20 text-center text-text-secondary">
          No patterns found in this category.
        </div>
      )}
    </section>
  );
}
