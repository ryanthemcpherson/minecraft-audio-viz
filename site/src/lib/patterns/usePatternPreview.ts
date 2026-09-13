"use client";

import { useEffect, useState, type RefObject } from "react";
import { getSharedSlotManager } from "./livePreviewSlots";

/**
 * Returns true while this preview holds one of the page's live WebGL slots.
 * A slot is requested when the element enters the viewport (with a small
 * margin so previews warm up just before they scroll in) and released when it
 * leaves or unmounts.
 */
export function usePatternPreview(
  ref: RefObject<HTMLElement | null>,
  id: string,
  enabled: boolean = true,
): boolean {
  const [live, setLive] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el || !enabled) return;

    const manager = getSharedSlotManager();
    let requested = false;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && !requested) {
          requested = true;
          // Reset before asking so a stale "live" from a previous run never lingers.
          setLive(false);
          // Prefer previews nearer the top of the page when slots are scarce.
          const priority = entry.boundingClientRect.top + window.scrollY;
          manager.request(id, setLive, priority);
        } else if (!entry.isIntersecting && requested) {
          requested = false;
          manager.release(id);
          setLive(false);
        }
      },
      { rootMargin: "120px 0px", threshold: 0 },
    );
    observer.observe(el);

    return () => {
      observer.disconnect();
      if (requested) manager.release(id);
    };
  }, [ref, id, enabled]);

  return enabled && live;
}
