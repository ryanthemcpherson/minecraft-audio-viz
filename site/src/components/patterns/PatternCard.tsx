"use client";

import { useRef } from "react";
import type { PatternMeta } from "@/lib/patterns";
import { usePatternPreview } from "@/lib/patterns/usePatternPreview";
import { useReducedMotion } from "@/lib/useReducedMotion";
import PatternPreview from "./PatternPreview";

interface PatternCardProps {
  meta: PatternMeta;
  index: number;
}

/**
 * Gallery entry. Requests a live WebGL slot while on screen; otherwise shows a
 * static placeholder. Visitors who prefer reduced motion always get the
 * placeholder.
 */
export default function PatternCard({ meta, index }: PatternCardProps) {
  const ref = useRef<HTMLElement>(null);
  const reducedMotion = useReducedMotion();
  const live = usePatternPreview(ref, meta.id, !reducedMotion);
  const phaseOffset = index * 1.7 + index * 0.3;

  return (
    <article ref={ref} id={`pattern-${meta.id}`} className="group">
      <PatternPreview
        meta={meta}
        live={live}
        phaseOffset={phaseOffset}
        className="aspect-[16/10] rounded-md border border-white/10 transition-colors group-hover:border-white/25"
      />

      <div className="mt-3 flex items-baseline justify-between gap-3">
        <h3 className="font-heading text-base font-bold">{meta.name}</h3>
        <p className="shrink-0 font-mono text-[11px] text-text-secondary/70">
          {meta.startBlocks ? `${meta.startBlocks} blocks` : ""}
          {live && <span className="ml-2 text-success">live</span>}
        </p>
      </div>
      <p className="mt-1 line-clamp-2 text-sm leading-relaxed text-text-secondary">{meta.description}</p>
      <p className="mt-1.5 text-xs text-text-secondary/70">
        {meta.category} <span className="mx-1 text-white/20">/</span>
        <span className="font-mono">{meta.id}.lua</span>
      </p>
    </article>
  );
}
