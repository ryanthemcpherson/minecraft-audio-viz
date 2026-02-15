"use client";

import { useState, useMemo, useCallback, useEffect, useRef, Suspense } from "react";
import dynamic from "next/dynamic";
import Link from "next/link";
import { listPatterns } from "@/lib/patterns";
import type { PatternMeta } from "@/lib/patterns";

const PreviewCanvas = dynamic(() => import("./PreviewCanvas"), { ssr: false });

const AUDIO_PRESETS = [
  { id: "edm", label: "EDM", description: "Punchy beats, heavy bass" },
  { id: "chill", label: "Chill", description: "Smooth, balanced response" },
  { id: "rock", label: "Rock", description: "Guitar and drums focus" },
  { id: "hiphop", label: "Hip-Hop", description: "Strong 808 bass" },
  { id: "classical", label: "Classical", description: "Smooth mids and highs" },
] as const;

type PresetId = (typeof AUDIO_PRESETS)[number]["id"];

const AUTO_CYCLE_INTERVAL = 20_000; // 20 seconds

export default function PreviewClient() {
  const patterns = useMemo(() => listPatterns(), []);
  const [selectedPatternId, setSelectedPatternId] = useState<string>(
    patterns[0]?.id ?? ""
  );
  const [selectedPreset, setSelectedPreset] = useState<PresetId>("edm");
  const [showControls, setShowControls] = useState(true);
  const [autoCycle, setAutoCycle] = useState(false);
  const [patternDropdownOpen, setPatternDropdownOpen] = useState(false);
  const [presetDropdownOpen, setPresetDropdownOpen] = useState(false);
  const patternDropdownRef = useRef<HTMLDivElement>(null);
  const presetDropdownRef = useRef<HTMLDivElement>(null);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const selectedPattern = useMemo(
    () => patterns.find((p) => p.id === selectedPatternId) ?? patterns[0],
    [patterns, selectedPatternId]
  );

  // Auto-cycle through patterns
  useEffect(() => {
    if (!autoCycle) return;
    const interval = setInterval(() => {
      setSelectedPatternId((current) => {
        const idx = patterns.findIndex((p) => p.id === current);
        const next = (idx + 1) % patterns.length;
        return patterns[next].id;
      });
    }, AUTO_CYCLE_INTERVAL);
    return () => clearInterval(interval);
  }, [autoCycle, patterns]);

  // Close dropdowns on outside click
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (
        patternDropdownRef.current &&
        !patternDropdownRef.current.contains(e.target as Node)
      ) {
        setPatternDropdownOpen(false);
      }
      if (
        presetDropdownRef.current &&
        !presetDropdownRef.current.contains(e.target as Node)
      ) {
        setPresetDropdownOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);

  // Auto-hide controls after inactivity
  useEffect(() => {
    function resetTimer() {
      setShowControls(true);
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
      hideTimerRef.current = setTimeout(() => setShowControls(false), 4000);
    }
    resetTimer();
    window.addEventListener("mousemove", resetTimer);
    window.addEventListener("touchstart", resetTimer);
    return () => {
      window.removeEventListener("mousemove", resetTimer);
      window.removeEventListener("touchstart", resetTimer);
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    };
  }, []);

  const selectPattern = useCallback(
    (p: PatternMeta) => {
      setSelectedPatternId(p.id);
      setPatternDropdownOpen(false);
      setAutoCycle(false);
    },
    []
  );

  // Group patterns by category for the dropdown
  const patternsByCategory = useMemo(() => {
    const map = new Map<string, PatternMeta[]>();
    for (const p of patterns) {
      const list = map.get(p.category) ?? [];
      list.push(p);
      map.set(p.category, list);
    }
    return map;
  }, [patterns]);

  return (
    <div className="fixed inset-0 z-40 bg-black">
      {/* Full-screen Canvas */}
      <Suspense
        fallback={
          <div className="flex h-full w-full items-center justify-center bg-black">
            <div className="flex flex-col items-center gap-4">
              <div className="flex gap-1.5">
                {[0, 1, 2, 3, 4].map((i) => (
                  <div
                    key={i}
                    className="eq-bar w-1.5 rounded-full bg-gradient-to-t from-electric-blue to-deep-purple"
                    style={{ height: "28px" }}
                  />
                ))}
              </div>
              <span className="text-sm text-text-secondary">
                Loading pattern engine...
              </span>
            </div>
          </div>
        }
      >
        {selectedPattern && (
          <PreviewCanvas
            key={selectedPatternId}
            pattern={selectedPattern}
            preset={selectedPreset}
          />
        )}
      </Suspense>

      {/* Controls overlay */}
      <div
        className={`pointer-events-none absolute inset-0 z-10 transition-opacity duration-500 ${
          showControls ? "opacity-100" : "opacity-0"
        }`}
      >
        {/* Top bar */}
        <div className="pointer-events-auto absolute inset-x-0 top-0 flex items-center justify-between bg-gradient-to-b from-black/70 via-black/30 to-transparent px-4 py-3 sm:px-6 sm:py-4">
          <Link
            href="/"
            className="flex items-center gap-2 text-sm text-white/80 transition-colors hover:text-white"
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M19 12H5M12 19l-7-7 7-7" />
            </svg>
            Back
          </Link>

          <div className="text-center">
            <h1 className="text-sm font-semibold text-white sm:text-base">
              {selectedPattern?.name ?? ""}
            </h1>
            <p className="hidden text-xs text-white/50 sm:block">
              {selectedPattern?.description ?? ""}
            </p>
          </div>

          <div className="w-12" />
        </div>

        {/* Bottom controls bar */}
        <div className="pointer-events-auto absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/80 via-black/40 to-transparent px-4 pb-4 pt-12 sm:px-6 sm:pb-6">
          <div className="mx-auto flex max-w-4xl flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
            {/* Left: Pattern and Preset selectors */}
            <div className="flex flex-wrap items-end gap-2 sm:gap-3">
              {/* Pattern selector */}
              <div ref={patternDropdownRef} className="relative">
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-white/40">
                  Pattern
                </label>
                <button
                  onClick={() => {
                    setPatternDropdownOpen(!patternDropdownOpen);
                    setPresetDropdownOpen(false);
                  }}
                  className="flex min-w-[140px] items-center justify-between gap-2 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white backdrop-blur-xl transition-all hover:border-white/20 hover:bg-white/10 sm:min-w-[180px]"
                >
                  <span className="truncate">{selectedPattern?.name}</span>
                  <svg
                    width="12"
                    height="12"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    className={`shrink-0 transition-transform ${
                      patternDropdownOpen ? "rotate-180" : ""
                    }`}
                  >
                    <path d="M6 9l6 6 6-6" />
                  </svg>
                </button>

                {patternDropdownOpen && (
                  <div className="absolute bottom-full left-0 mb-2 max-h-[50vh] w-64 overflow-y-auto rounded-xl border border-white/10 bg-[#111]/95 py-1 shadow-2xl backdrop-blur-xl sm:w-72">
                    {Array.from(patternsByCategory.entries()).map(
                      ([category, categoryPatterns]) => (
                        <div key={category}>
                          <div className="sticky top-0 bg-[#111]/95 px-3 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-white/30 backdrop-blur-xl">
                            {category}
                          </div>
                          {categoryPatterns.map((p) => (
                            <button
                              key={p.id}
                              onClick={() => selectPattern(p)}
                              className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-white/5 ${
                                p.id === selectedPatternId
                                  ? "text-electric-blue"
                                  : "text-white/80"
                              }`}
                            >
                              {p.id === selectedPatternId && (
                                <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-electric-blue" />
                              )}
                              <span className="truncate">{p.name}</span>
                            </button>
                          ))}
                        </div>
                      )
                    )}
                  </div>
                )}
              </div>

              {/* Preset selector */}
              <div ref={presetDropdownRef} className="relative">
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-white/40">
                  Audio Preset
                </label>
                <button
                  onClick={() => {
                    setPresetDropdownOpen(!presetDropdownOpen);
                    setPatternDropdownOpen(false);
                  }}
                  className="flex min-w-[110px] items-center justify-between gap-2 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white backdrop-blur-xl transition-all hover:border-white/20 hover:bg-white/10 sm:min-w-[140px]"
                >
                  <span>
                    {
                      AUDIO_PRESETS.find((p) => p.id === selectedPreset)?.label
                    }
                  </span>
                  <svg
                    width="12"
                    height="12"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    className={`shrink-0 transition-transform ${
                      presetDropdownOpen ? "rotate-180" : ""
                    }`}
                  >
                    <path d="M6 9l6 6 6-6" />
                  </svg>
                </button>

                {presetDropdownOpen && (
                  <div className="absolute bottom-full left-0 mb-2 w-56 rounded-xl border border-white/10 bg-[#111]/95 py-1 shadow-2xl backdrop-blur-xl">
                    {AUDIO_PRESETS.map((preset) => (
                      <button
                        key={preset.id}
                        onClick={() => {
                          setSelectedPreset(preset.id);
                          setPresetDropdownOpen(false);
                        }}
                        className={`flex w-full flex-col px-3 py-2 text-left transition-colors hover:bg-white/5 ${
                          preset.id === selectedPreset
                            ? "text-electric-blue"
                            : "text-white/80"
                        }`}
                      >
                        <span className="text-sm font-medium">
                          {preset.label}
                        </span>
                        <span className="text-xs text-white/40">
                          {preset.description}
                        </span>
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {/* Auto-cycle toggle */}
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-white/40">
                  &nbsp;
                </label>
                <button
                  onClick={() => setAutoCycle(!autoCycle)}
                  className={`flex items-center gap-2 rounded-lg border px-3 py-2 text-sm backdrop-blur-xl transition-all ${
                    autoCycle
                      ? "border-electric-blue/30 bg-electric-blue/10 text-electric-blue"
                      : "border-white/10 bg-white/5 text-white/60 hover:border-white/20 hover:text-white"
                  }`}
                  title="Auto-cycle patterns every 20s"
                >
                  <svg
                    width="14"
                    height="14"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <path d="M21 12a9 9 0 11-3-6.7" />
                    <path d="M21 3v6h-6" />
                  </svg>
                  <span className="hidden sm:inline">Auto</span>
                </button>
              </div>
            </div>

            {/* Right: CTA */}
            <Link
              href="/getting-started"
              className="inline-flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-electric-blue to-deep-purple px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-electric-blue/20 transition-all hover:shadow-xl hover:shadow-electric-blue/30 hover:brightness-110"
            >
              Get Started
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M5 12h14M12 5l7 7-7 7" />
              </svg>
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
