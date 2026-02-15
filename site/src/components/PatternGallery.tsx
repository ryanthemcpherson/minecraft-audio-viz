"use client";

import { useState, useMemo, useEffect } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import PatternCard from "./PatternCard";
import { listPatterns } from "@/lib/patterns";

const CATEGORIES = ["All", "Original", "Epic", "Cosmic", "Organic", "Spectrum"];

export default function PatternGallery() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const [searchQuery, setSearchQuery] = useState("");
  const [activeCategory, setActiveCategory] = useState(
    searchParams.get("category") || "All"
  );

  const allPatterns = useMemo(() => listPatterns(), []);

  // Update URL when category changes
  useEffect(() => {
    const params = new URLSearchParams(searchParams.toString());
    if (activeCategory !== "All") {
      params.set("category", activeCategory);
    } else {
      params.delete("category");
    }
    const newUrl = params.toString() ? `?${params.toString()}` : window.location.pathname;
    router.replace(newUrl, { scroll: false });
  }, [activeCategory, router, searchParams]);

  const filtered = useMemo(() => {
    let result = allPatterns;

    // Filter by category
    if (activeCategory !== "All") {
      result = result.filter((p) => p.category === activeCategory);
    }

    // Filter by search query
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase();
      result = result.filter(
        (p) =>
          p.name.toLowerCase().includes(query) ||
          p.description.toLowerCase().includes(query)
      );
    }

    return result;
  }, [allPatterns, activeCategory, searchQuery]);

  return (
    <section className="mx-auto max-w-7xl px-6 pb-24">
      {/* Search and Filters */}
      <div className="mb-10 space-y-4">
        {/* Search Input */}
        <div className="relative">
          <input
            type="text"
            placeholder="Search patterns by name or description..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full rounded-full border border-white/10 bg-white/3 px-5 py-2.5 text-sm text-white placeholder-text-secondary/60 transition-all focus:border-disc-cyan/50 focus:bg-white/5 focus:outline-none focus:ring-2 focus:ring-disc-cyan/20"
          />
          <svg
            className="pointer-events-none absolute right-4 top-1/2 h-4 w-4 -translate-y-1/2 text-text-secondary"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
            />
          </svg>
        </div>

        {/* Category Filters */}
        <div className="flex flex-wrap items-center gap-2">
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
                    ? "border-disc-cyan/50 bg-disc-cyan/10 text-white"
                    : "border-white/10 bg-white/3 text-text-secondary hover:border-white/20 hover:text-white"
                }`}
              >
                {cat}
                <span
                  className={`ml-1.5 text-xs ${
                    isActive ? "text-disc-cyan" : "text-text-secondary"
                  }`}
                >
                  {count}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* Pattern Grid */}
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {filtered.map((pattern, i) => (
          <div
            key={pattern.id}
            className="animate-fade-in"
            style={{ animationDelay: `${i * 0.04}s` }}
          >
            <PatternCard
              id={pattern.id}
              name={pattern.name}
              description={pattern.description}
              category={pattern.category}
              staticCamera={pattern.staticCamera}
              startBlocks={pattern.startBlocks}
              createPattern={pattern.createPattern}
              index={i}
            />
          </div>
        ))}
      </div>

      {filtered.length === 0 && (
        <div className="py-20 text-center text-text-secondary">
          {searchQuery.trim()
            ? `No patterns found matching "${searchQuery}"`
            : `No patterns found in ${activeCategory}.`}
        </div>
      )}
    </section>
  );
}
