"use client";

import { useRef, useState, useEffect, useMemo, useCallback, Suspense } from "react";
import { Canvas } from "@react-three/fiber";
import type { RootState } from "@react-three/fiber";
import * as THREE from "three";
import PatternScene from "./PatternScene";
import type { PatternInstance } from "@/lib/patterns";

// ── WebGL context slot manager ──────────────────────────────────
// Browsers limit active WebGL contexts (~8-16). We use 14 to cover a full
// 3-column grid viewport plus rootMargin pre-loading.
// NOTE: Module-level mutable state is intentional and SSR-safe because this
// module is marked "use client" and runs only in the browser.
const MAX_WEBGL_CONTEXTS = 14;
let activeSlots = 0;
const waiters = new Set<() => void>();

function acquireSlot(): boolean {
  if (activeSlots < MAX_WEBGL_CONTEXTS) {
    activeSlots++;
    return true;
  }
  return false;
}

function releaseSlot() {
  activeSlots--;
  // Notify one waiter that a slot opened up
  for (const fn of waiters) {
    waiters.delete(fn);
    fn();
    break;
  }
}

function onSlotFreed(fn: () => void): () => void {
  waiters.add(fn);
  return () => { waiters.delete(fn); };
}
// ────────────────────────────────────────────────────────────────

interface PatternCardProps {
  id: string;
  name: string;
  description: string;
  category: string;
  staticCamera: boolean;
  startBlocks: number | null;
  createPattern: () => PatternInstance;
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
  startBlocks,
  createPattern,
  index,
}: PatternCardProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [isVisible, setIsVisible] = useState(false);
  const [hasSlot, setHasSlot] = useState(false);
  const slotHeld = useRef(false);
  const glRef = useRef<THREE.WebGLRenderer | null>(null);

  // Only create the pattern instance when we have a rendering slot
  const canRender = isVisible && hasSlot;

  const pattern = useMemo(() => {
    if (!canRender) return null;
    return createPattern();
  }, [canRender, createPattern]);

  // Phase offset so each card's audio sim is unique
  const phaseOffset = useMemo(() => index * 1.7 + index * 0.3, [index]);

  // Capture WebGL renderer on canvas creation for proper cleanup
  const handleCreated = useCallback((state: RootState) => {
    glRef.current = state.gl;
    state.gl.toneMapping = THREE.ACESFilmicToneMapping;
    state.gl.toneMappingExposure = 1.4;
  }, []);

  // IntersectionObserver to track viewport visibility
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

  // Acquire / release a WebGL context slot
  useEffect(() => {
    if (isVisible) {
      // Try to grab a slot immediately
      if (acquireSlot()) {
        slotHeld.current = true;
        queueMicrotask(() => setHasSlot(true));
        return;
      }
      // Otherwise wait for one to free up
      const unsub = onSlotFreed(() => {
        if (slotHeld.current) return;
        if (acquireSlot()) {
          slotHeld.current = true;
          setHasSlot(true);
          unsub();
        }
      });
      return () => { unsub(); };
    }

    // Not visible — release slot & dispose WebGL context
    if (slotHeld.current) {
      const gl = glRef.current;
      if (gl) {
        gl.dispose();
        glRef.current = null;
      }
      slotHeld.current = false;
      queueMicrotask(() => setHasSlot(false));
      releaseSlot();
    }
  }, [isVisible]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (slotHeld.current) {
        const gl = glRef.current;
        if (gl) {
          gl.dispose();
        }
        slotHeld.current = false;
        releaseSlot();
      }
    };
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
        {canRender && pattern ? (
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
              dpr={typeof window !== 'undefined' ? Math.min(window.devicePixelRatio, 1.5) : 1}
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

        {/* Entity count badge */}
        {startBlocks && (
          <div className="absolute top-3 right-3 z-10">
            <span className="rounded-full border border-white/10 bg-black/40 px-2.5 py-0.5 text-[10px] font-semibold text-white/80 backdrop-blur-sm">
              {startBlocks} blocks
            </span>
          </div>
        )}
      </div>

      {/* Info */}
      <div className="px-5 py-4">
        <h3 className="text-base font-semibold text-text-primary">{name}</h3>
        <p className="mt-1 text-sm text-text-secondary line-clamp-2">
          {description}
        </p>
      </div>
    </div>
  );
}
