"use client";

import React, { useRef, useMemo, useCallback, useEffect, Suspense } from "react";
import { Canvas } from "@react-three/fiber";
import type { RootState } from "@react-three/fiber";
import * as THREE from "three";
import PatternScene, { type PreviewQuality } from "@/components/PatternScene";
import type { PatternMeta } from "@/lib/patterns";
import { categoryTone } from "@/components/ui/Badge";

class CanvasErrorBoundary extends React.Component<
  { children: React.ReactNode; fallback: React.ReactNode },
  { hasError: boolean }
> {
  state = { hasError: false };
  static getDerivedStateFromError() {
    return { hasError: true };
  }
  componentDidCatch(error: Error) {
    console.error("[MCAV] Pattern preview failed to render:", error);
  }
  render() {
    if (this.state.hasError) return this.props.fallback;
    return this.props.children;
  }
}

const TONE_HEX: Record<string, string> = {
  cyan: "#00CCFF",
  blue: "#5B6AFF",
  amber: "#FFAA00",
  success: "#2fe098",
  warning: "#ffd166",
  danger: "#ff6767",
  neutral: "#a1a1aa",
};

/** Static stand-in shown when a preview does not hold a live WebGL slot. */
export function PreviewPlaceholder({
  category,
  label,
}: {
  category: string;
  label?: string;
}) {
  const hex = TONE_HEX[categoryTone(category)] ?? TONE_HEX.neutral;
  return (
    <div
      className="relative flex h-full w-full items-center justify-center overflow-hidden"
      style={{
        background: `radial-gradient(ellipse at 50% 60%, ${hex}22 0%, ${hex}08 40%, transparent 70%)`,
      }}
      aria-hidden="true"
    >
      <div
        className="absolute inset-0 opacity-40"
        style={{
          backgroundImage:
            "linear-gradient(rgba(255,255,255,0.05) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.05) 1px, transparent 1px)",
          backgroundSize: "24px 24px",
        }}
      />
      <div className="relative flex items-end gap-1.5">
        {[0.35, 0.7, 1, 0.55, 0.8].map((h, i) => (
          <div
            key={i}
            className="w-2 rounded-sm"
            style={{ height: `${h * 36}px`, background: hex, opacity: 0.55 }}
          />
        ))}
      </div>
      {label && (
        <span className="absolute bottom-3 font-mono text-[10px] uppercase tracking-wider text-white/40">
          {label}
        </span>
      )}
    </div>
  );
}

interface LiveCanvasProps {
  meta: PatternMeta;
  phaseOffset: number;
  quality: PreviewQuality;
}

function LiveCanvas({ meta, phaseOffset, quality }: LiveCanvasProps) {
  const glRef = useRef<THREE.WebGLRenderer | null>(null);
  const pattern = useMemo(() => meta.createPattern(), [meta]);

  const dpr = useMemo(() => {
    if (typeof window === "undefined") return 1;
    return Math.min(window.devicePixelRatio, 1.5);
  }, []);

  useEffect(() => {
    return () => {
      glRef.current?.dispose();
      glRef.current = null;
      pattern.dispose?.();
    };
  }, [pattern]);

  const handleCreated = useCallback((state: RootState) => {
    glRef.current = state.gl;
    state.gl.toneMapping = THREE.ACESFilmicToneMapping;
    state.gl.toneMappingExposure = 1.4;
    // Look slightly down at the stage so the floor reads as a floor.
    state.camera.lookAt(0, -0.35, 0);
  }, []);

  return (
    <Canvas
      dpr={dpr}
      gl={{ antialias: true, alpha: true, powerPreference: quality === "high" ? "high-performance" : "low-power" }}
      camera={{ position: [0, 2.4, 5.6], fov: 42, near: 0.1, far: 30 }}
      style={{ position: "absolute", top: 0, left: 0, width: "100%", height: "100%" }}
      frameloop="always"
      onCreated={handleCreated}
    >
      <PatternScene
        pattern={pattern}
        phaseOffset={phaseOffset}
        staticCamera={meta.staticCamera}
        quality={quality}
      />
    </Canvas>
  );
}

interface PatternPreviewProps {
  meta: PatternMeta;
  /** Whether this preview currently holds a live WebGL slot. */
  live: boolean;
  phaseOffset?: number;
  className?: string;
  /** "high" enables bloom; reserve for a single showcase canvas. */
  quality?: PreviewQuality;
}

/**
 * Renders a pattern with WebGL when `live`, otherwise a static placeholder.
 * Always keeps the same aspect box so the layout never shifts.
 */
export default function PatternPreview({
  meta,
  live,
  phaseOffset = 0,
  className = "",
  quality = "low",
}: PatternPreviewProps) {
  return (
    <div className={`relative w-full overflow-hidden bg-[#050505] ${className}`}>
      {live ? (
        <CanvasErrorBoundary
          fallback={<PreviewPlaceholder category={meta.category} label="Preview unavailable" />}
        >
          <Suspense fallback={<PreviewPlaceholder category={meta.category} />}>
            <LiveCanvas meta={meta} phaseOffset={phaseOffset} quality={quality} />
          </Suspense>
        </CanvasErrorBoundary>
      ) : (
        <PreviewPlaceholder category={meta.category} />
      )}
    </div>
  );
}
