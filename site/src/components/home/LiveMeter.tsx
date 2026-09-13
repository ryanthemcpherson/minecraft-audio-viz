"use client";

import { useEffect, useRef, useState } from "react";
import { sampleAudio } from "@/lib/audio/audioSource";
import { useReducedMotion } from "@/lib/useReducedMotion";

const BANDS = ["Bass", "Low-mid", "Mid", "Hi-mid", "High"];
const BAND_COLORS = ["#00CCFF", "#2fa9ff", "#5B6AFF", "#b07cff", "#FFAA00"];
const STATIC_LEVELS = [0.82, 0.55, 0.62, 0.4, 0.3];

/**
 * A five-band level meter driven by the same synthetic audio model the
 * pattern previews use. Pure DOM transforms, no WebGL, so it is cheap enough
 * to live inside a feature card.
 */
export default function LiveMeter({ className = "" }: { className?: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const barRefs = useRef<(HTMLDivElement | null)[]>([]);
  const beatRef = useRef<HTMLDivElement>(null);
  const bpmRef = useRef<HTMLSpanElement>(null);
  const [visible, setVisible] = useState(true);
  const reducedMotion = useReducedMotion();

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), {
      threshold: 0,
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (reducedMotion) {
      barRefs.current.forEach((bar, i) => {
        if (bar) bar.style.transform = `scaleY(${STATIC_LEVELS[i]})`;
      });
      if (beatRef.current) beatRef.current.style.opacity = "0.6";
      return;
    }
    if (!visible) return;

    const smoothed = new Float32Array(BANDS.length);
    let beatGlow = 0;
    let raf = 0;
    const start = performance.now();

    const tick = (now: number) => {
      const t = (now - start) / 1000;
      const audio = sampleAudio(t, 0.4);
      if (bpmRef.current) bpmRef.current.textContent = audio.bpm > 0 ? String(Math.round(audio.bpm)) : "--";
      for (let i = 0; i < BANDS.length; i++) {
        const target = audio.bands[i];
        // Fast attack, slower release: reads like a real meter
        const rate = target > smoothed[i] ? 0.45 : 0.18;
        smoothed[i] += (target - smoothed[i]) * rate;
        const bar = barRefs.current[i];
        if (bar) bar.style.transform = `scaleY(${0.06 + smoothed[i] * 0.94})`;
      }
      beatGlow = audio.isBeat ? 1 : beatGlow * 0.88;
      if (beatRef.current) beatRef.current.style.opacity = String(0.25 + beatGlow * 0.75);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [visible, reducedMotion]);

  return (
    <div ref={containerRef} className={`select-none ${className}`} aria-hidden="true">
      <div className="flex items-end justify-between gap-3">
        <div className="flex h-32 flex-1 items-end gap-2 sm:h-40">
          {BANDS.map((band, i) => (
            <div key={band} className="flex h-full flex-1 flex-col items-center justify-end gap-2">
              <div className="relative h-full w-full overflow-hidden rounded-md bg-white/[0.04]">
                <div
                  ref={(node) => {
                    barRefs.current[i] = node;
                  }}
                  className="absolute inset-x-0 bottom-0 h-full origin-bottom rounded-md will-change-transform"
                  style={{
                    background: `linear-gradient(to top, ${BAND_COLORS[i]}, ${BAND_COLORS[i]}66)`,
                    transform: "scaleY(0.06)",
                    boxShadow: `0 0 18px ${BAND_COLORS[i]}44`,
                  }}
                />
              </div>
              <span className="font-mono text-[9px] uppercase tracking-wider text-text-secondary/70 sm:text-[10px]">
                {band}
              </span>
            </div>
          ))}
        </div>

        <div className="flex flex-col items-center gap-2 pb-5">
          <div
            ref={beatRef}
            className="h-4 w-4 rounded-full bg-noteblock-amber"
            style={{ opacity: 0.25, boxShadow: "0 0 16px #FFAA00" }}
          />
          <span className="font-mono text-[10px] uppercase tracking-wider text-text-secondary/70">
            Beat
          </span>
          <span ref={bpmRef} className="mt-2 font-mono text-sm font-semibold text-white">128</span>
          <span className="font-mono text-[10px] uppercase tracking-wider text-text-secondary/70">
            BPM
          </span>
        </div>
      </div>
    </div>
  );
}
