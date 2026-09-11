/**
 * Live preview slot manager.
 *
 * Every pattern preview wants its own WebGL context, but browsers cap the
 * number of contexts per page (typically 8 to 16) and each one costs a render
 * loop. This manager hands out a fixed number of "live" slots to the visible
 * previews with the best priority (lowest value, e.g. distance from the top of
 * the page). Previews without a slot render a static placeholder.
 *
 * Rebalancing is deferred and coalesced so that a burst of requests arriving
 * over a frame or two (every card in the first viewport, for example) is
 * ranked by priority rather than by whichever IntersectionObserver happened to
 * fire first. A better-ranked newcomer preempts the worst live preview, so the
 * live set always matches the top of the visible set.
 *
 * The manager is framework-agnostic so it can be unit tested; the React hook
 * in usePatternPreview.ts wires it to IntersectionObserver.
 */

export type SlotListener = (live: boolean) => void;

/** Runs a rebalance. Defaults to a short timer; tests can pass a synchronous scheduler. */
export type RebalanceScheduler = (run: () => void) => void;

const REBALANCE_DELAY_MS = 32;

const timerScheduler: RebalanceScheduler = (run) => {
  setTimeout(run, REBALANCE_DELAY_MS);
};

interface Registration {
  listener: SlotListener;
  priority: number;
  /** Monotonic arrival counter used to break priority ties. */
  order: number;
}

export class LivePreviewSlotManager {
  private readonly maxLive: number;
  private readonly schedule: RebalanceScheduler;
  private readonly registrations = new Map<string, Registration>();
  private readonly live = new Set<string>();
  private nextOrder = 0;
  private rebalanceQueued = false;

  constructor(maxLive: number, schedule: RebalanceScheduler = timerScheduler) {
    if (!Number.isInteger(maxLive) || maxLive < 0) {
      throw new Error(`maxLive must be a non-negative integer, got ${maxLive}`);
    }
    this.maxLive = maxLive;
    this.schedule = schedule;
  }

  /**
   * A preview became visible and would like to render live.
   * `priority` ranks previews; lower wins. Equal priorities keep arrival order.
   */
  request(id: string, listener: SlotListener, priority: number = 0): void {
    const existing = this.registrations.get(id);
    if (existing) {
      // Re-registering keeps the arrival order; priority may be refreshed.
      existing.listener = listener;
      existing.priority = priority;
      listener(this.live.has(id));
      this.queueRebalance();
      return;
    }
    this.registrations.set(id, { listener, priority, order: this.nextOrder++ });
    this.queueRebalance();
  }

  /** A preview left the viewport or unmounted. */
  release(id: string): void {
    if (!this.registrations.delete(id)) return;
    if (this.live.delete(id)) this.queueRebalance();
  }

  isLive(id: string): boolean {
    return this.live.has(id);
  }

  liveCount(): number {
    return this.live.size;
  }

  private queueRebalance(): void {
    if (this.rebalanceQueued) return;
    this.rebalanceQueued = true;
    this.schedule(() => {
      this.rebalanceQueued = false;
      this.rebalance();
    });
  }

  private rebalance(): void {
    const ranked = [...this.registrations.entries()]
      .sort(([, a], [, b]) => a.priority - b.priority || a.order - b.order)
      .map(([id]) => id);

    const desired = new Set(ranked.slice(0, this.maxLive));

    // Revoke first so freed slots are available to newcomers.
    for (const id of [...this.live]) {
      if (desired.has(id)) continue;
      this.live.delete(id);
      this.registrations.get(id)?.listener(false);
    }

    for (const id of desired) {
      if (this.live.has(id)) continue;
      this.live.add(id);
      this.registrations.get(id)?.listener(true);
    }
  }
}

/** How many previews may render with WebGL at the same time. */
export const MAX_LIVE_PREVIEWS = 4;

let shared: LivePreviewSlotManager | null = null;

/** Page-wide manager shared by every preview on the page. */
export function getSharedSlotManager(): LivePreviewSlotManager {
  if (!shared) shared = new LivePreviewSlotManager(MAX_LIVE_PREVIEWS);
  return shared;
}
