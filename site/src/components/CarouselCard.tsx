"use client";

import React, { useRef, useMemo, useCallback, Suspense } from "react";
import { Canvas } from "@react-three/fiber";
import type { RootState } from "@react-three/fiber";
import * as THREE from "three";
import PatternScene from "./PatternScene";
import type { PatternMeta } from "@/lib/patterns";

class CanvasErrorBoundary extends React.Component<
  { children: React.ReactNode; fallback: React.ReactNode },
  { hasError: boolean }
> {
  state = { hasError: false };
  static getDerivedStateFromError() {
    return { hasError: true };
  }
  render() {
    if (this.state.hasError) return this.props.fallback;
    return this.props.children;
  }
}

const CATEGORY_COLORS: Record<string, string> = {
  Original: "bg-disc-cyan/15 text-disc-cyan border-disc-cyan/30",
  Epic: "bg-noteblock-amber/15 text-noteblock-amber border-noteblock-amber/30",
  Cosmic: "bg-disc-blue/15 text-disc-blue border-disc-blue/30",
  Organic: "bg-emerald-500/15 text-emerald-400 border-emerald-500/30",
  Spectrum: "bg-amber-500/15 text-amber-400 border-amber-500/30",
};

interface CarouselCardProps {
  meta: PatternMeta;
  index: number;
}

export default function CarouselCard({ meta, index }: CarouselCardProps) {
  const glRef = useRef<THREE.WebGLRenderer | null>(null);

  const pattern = useMemo(() => meta.createPattern(), [meta]);
  const phaseOffset = useMemo(() => index * 1.7 + index * 0.3, [index]);

  const handleCreated = useCallback((state: RootState) => {
    glRef.current = state.gl;
    state.gl.toneMapping = THREE.ACESFilmicToneMapping;
    state.gl.toneMappingExposure = 1.4;
  }, []);

  const badgeClass = CATEGORY_COLORS[meta.category] ?? CATEGORY_COLORS.Original;

  return (
    <div className="group glass-card overflow-hidden rounded-2xl transition-shadow duration-300 hover:ring-1 hover:ring-white/20 hover:shadow-lg hover:shadow-disc-cyan/5">
      {/* 3D Canvas */}
      <div className="relative aspect-[16/10] w-full overflow-hidden bg-[#050505]">
        <CanvasErrorBoundary
          fallback={
            <div className="flex h-full w-full items-center justify-center text-white/30">
              <span className="text-xs">Preview unavailable</span>
            </div>
          }
        >
          <Suspense
            fallback={
              <div className="flex h-full w-full items-center justify-center">
                <div className="flex gap-1">
                  {[0, 1, 2, 3, 4].map((i) => (
                    <div
                      key={i}
                      className="eq-bar w-1 rounded-full bg-gradient-to-t from-disc-cyan to-disc-blue"
                      style={{ height: "20px" }}
                    />
                  ))}
                </div>
              </div>
            }
          >
            <Canvas
              dpr={typeof window !== "undefined" ? Math.min(window.devicePixelRatio, 1.5) : 1}
              gl={{
                antialias: true,
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
              onCreated={handleCreated}
            >
              <PatternScene pattern={pattern} phaseOffset={phaseOffset} staticCamera={meta.staticCamera} />
            </Canvas>
          </Suspense>
        </CanvasErrorBoundary>

        {/* Category badge */}
        <div className="absolute top-3 left-3 z-10">
          <span
            className={`rounded-full border px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider ${badgeClass}`}
          >
            {meta.category}
          </span>
        </div>

        {/* Entity count badge */}
        {meta.startBlocks && (
          <div className="absolute top-3 right-3 z-10">
            <span className="rounded-full border border-white/10 bg-black/40 px-2.5 py-0.5 text-[10px] font-semibold text-white/80 backdrop-blur-sm">
              {meta.startBlocks} blocks
            </span>
          </div>
        )}
      </div>

      {/* Info */}
      <div className="px-5 py-4">
        <h3 className="text-base font-semibold text-text-primary">{meta.name}</h3>
        <p className="mt-1 text-sm text-text-secondary line-clamp-2">{meta.description}</p>
      </div>
    </div>
  );
}
