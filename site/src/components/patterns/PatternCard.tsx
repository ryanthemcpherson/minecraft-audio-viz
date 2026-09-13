"use client";

import { useRef } from "react";
import type { PatternMeta } from "@/lib/patterns";
import { usePatternPreview } from "@/lib/patterns/usePatternPreview";
import { useReducedMotion } from "@/lib/useReducedMotion";
import Badge, { categoryTone } from "@/components/ui/Badge";
import PatternPreview from "./PatternPreview";

interface PatternCardProps {
  meta: PatternMeta;
  index: number;
}

/**
 * Gallery card. Requests a live WebGL slot while on screen; otherwise shows a
 * static placeholder. Visitors who prefer reduced motion always get the
 * placeholder.
 */
export default function PatternCard({ meta, index }: PatternCardProps) {
  const ref = useRef<HTMLElement>(null);
  const reducedMotion = useReducedMotion();
  const live = usePatternPreview(ref, meta.id, !reducedMotion);
  const phaseOffset = index * 1.7 + index * 0.3;

  return (
    <article
      ref={ref}
      id={`pattern-${meta.id}`}
      className="group flat-card overflow-hidden rounded-2xl transition-shadow duration-300 hover:shadow-lg hover:shadow-disc-cyan/5"
    >
      <PatternPreview meta={meta} live={live} phaseOffset={phaseOffset} className="aspect-[16/10]" />

      <div className="px-5 py-4">
        <div className="flex items-center justify-between gap-3">
          <h3 className="font-heading text-base font-semibold text-text-primary">{meta.name}</h3>
          <Badge tone={categoryTone(meta.category)}>{meta.category}</Badge>
        </div>
        <p className="mt-1.5 line-clamp-2 text-sm text-text-secondary">{meta.description}</p>
        <div className="mt-3 flex items-center gap-3 font-mono text-[10px] uppercase tracking-wider text-text-secondary/60">
          {meta.startBlocks && <span>{meta.startBlocks} blocks</span>}
          <span>{meta.id}.lua</span>
          {live && (
            <span className="ml-auto flex items-center gap-1 text-success">
              <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
              live
            </span>
          )}
        </div>
      </div>
    </article>
  );
}
