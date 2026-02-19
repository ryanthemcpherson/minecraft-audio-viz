"use client";

import { useRef, useState, useEffect, useMemo, Suspense } from "react";
import { Canvas } from "@react-three/fiber";
import PatternScene from "./PatternScene";
import type { VisualizationPattern } from "@/lib/patterns/base";

interface PatternCardProps {
  id: string;
  name: string;
  description: string;
  category: string;
  staticCamera: boolean;
  createPattern: () => VisualizationPattern;
  index: number;
}

const CATEGORY_COLORS: Record<string, string> = {
  Original: "bg-electric-blue/15 text-electric-blue border-electric-blue/30",
  Epic: "bg-hot-pink/15 text-hot-pink border-hot-pink/30",
  Cosmic: "bg-deep-purple/15 text-deep-purple border-deep-purple/30",
  Organic: "bg-emerald-500/15 text-emerald-400 border-emerald-500/30",
  Spectrum: "bg-amber-500/15 text-amber-400 border-amber-500/30",
};

export default function PatternCard({
  name,
  description,
  category,
  staticCamera,
  createPattern,
  index,
}: PatternCardProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [isVisible, setIsVisible] = useState(false);

  // Only create the pattern instance when visible
  const pattern = useMemo(() => {
    if (!isVisible) return null;
    return createPattern();
  }, [isVisible, createPattern]);

  // Phase offset so each card's audio sim is unique
  const phaseOffset = useMemo(() => index * 1.7 + index * 0.3, [index]);

  // IntersectionObserver to only render when visible
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        setIsVisible(entry.isIntersecting);
      },
      { rootMargin: "100px" }
    );

    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const badgeClass = CATEGORY_COLORS[category] ?? CATEGORY_COLORS.Original;

  return (
    <div
      ref={containerRef}
      className="group glass-card overflow-hidden rounded-2xl transition-shadow duration-300 hover:ring-1 hover:ring-white/20 hover:shadow-lg hover:shadow-electric-blue/5"
      style={{
        animationDelay: `${index * 0.05}s`,
      }}
    >
      {/* 3D Canvas */}
      <div className="relative aspect-[16/10] w-full overflow-hidden bg-[#050505]">
        {isVisible && pattern ? (
          <Suspense
            fallback={
              <div className="flex h-full w-full items-center justify-center">
                <div className="flex gap-1">
                  {[0, 1, 2, 3, 4].map((i) => (
                    <div
                      key={i}
                      className="eq-bar w-1 rounded-full bg-gradient-to-t from-electric-blue to-deep-purple"
                      style={{ height: "20px" }}
                    />
                  ))}
                </div>
              </div>
            }
          >
            <Canvas
              dpr={1}
              gl={{
                antialias: false,
                alpha: true,
                powerPreference: "low-power",
              }}
              camera={{
                position: [0, 1.5, 4.5],
                fov: 50,
                near: 0.1,
                far: 30,
              }}
              style={{
                position: "absolute",
                top: 0,
                left: 0,
                width: "100%",
                height: "100%",
              }}
              frameloop="always"
            >
              <PatternScene pattern={pattern} phaseOffset={phaseOffset} staticCamera={staticCamera} />
            </Canvas>
          </Suspense>
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <div className="flex gap-1">
              {[0, 1, 2, 3, 4].map((i) => (
                <div
                  key={i}
                  className="eq-bar w-1 rounded-full bg-gradient-to-t from-electric-blue/40 to-deep-purple/40"
                  style={{ height: "20px" }}
                />
              ))}
            </div>
          </div>
        )}

        {/* Category badge */}
        <div className="absolute top-3 left-3 z-10">
          <span
            className={`rounded-full border px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider ${badgeClass}`}
          >
            {category}
          </span>
        </div>
      </div>

      {/* Info */}
      <div className="px-4 py-3">
        <h3 className="text-sm font-semibold text-text-primary">{name}</h3>
        <p className="mt-0.5 text-xs text-text-secondary line-clamp-2">
          {description}
        </p>
      </div>
    </div>
  );
}
