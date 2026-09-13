import { describe, it, expect, vi } from "vitest";
import { LivePreviewSlotManager } from "../livePreviewSlots";

/** Synchronous scheduler so most tests can assert immediately. */
const sync = (run: () => void) => run();

/** Collects scheduled rebalances so a test can flush them by hand. */
function deferredScheduler() {
  const queue: Array<() => void> = [];
  return {
    schedule: (run: () => void) => {
      queue.push(run);
    },
    flush: () => {
      while (queue.length) queue.shift()!();
    },
    pending: () => queue.length,
  };
}

describe("LivePreviewSlotManager", () => {
  it("grants slots up to the maximum in request order", () => {
    const manager = new LivePreviewSlotManager(2, sync);
    const a = vi.fn();
    const b = vi.fn();
    const c = vi.fn();

    manager.request("a", a);
    manager.request("b", b);
    manager.request("c", c);

    expect(manager.isLive("a")).toBe(true);
    expect(manager.isLive("b")).toBe(true);
    expect(manager.isLive("c")).toBe(false);
    expect(a).toHaveBeenCalledWith(true);
    expect(b).toHaveBeenCalledWith(true);
    expect(c).not.toHaveBeenCalled();
    expect(manager.liveCount()).toBe(2);
  });

  it("promotes the next waiting preview when a live one is released", () => {
    const manager = new LivePreviewSlotManager(1, sync);
    const a = vi.fn();
    const b = vi.fn();

    manager.request("a", a);
    manager.request("b", b);
    expect(manager.isLive("b")).toBe(false);

    manager.release("a");

    expect(manager.isLive("a")).toBe(false);
    expect(manager.isLive("b")).toBe(true);
    expect(b).toHaveBeenCalledWith(true);
  });

  it("does not rebalance when a waiting (non-live) preview is released", () => {
    const scheduler = deferredScheduler();
    const manager = new LivePreviewSlotManager(1, scheduler.schedule);
    manager.request("a", vi.fn());
    manager.request("b", vi.fn());
    scheduler.flush();
    expect(manager.isLive("a")).toBe(true);

    manager.release("b");
    expect(scheduler.pending()).toBe(0);
    expect(manager.isLive("a")).toBe(true);
  });

  it("keeps arrival order when the same id re-registers", () => {
    const manager = new LivePreviewSlotManager(1, sync);
    const a1 = vi.fn();
    const a2 = vi.fn();
    const b = vi.fn();

    manager.request("a", a1);
    manager.request("b", b);
    manager.request("a", a2);

    expect(a2).toHaveBeenCalledWith(true);
    expect(manager.isLive("a")).toBe(true);
    expect(manager.isLive("b")).toBe(false);
  });

  it("promotes the best-ranked waiting preview when a slot frees", () => {
    const manager = new LivePreviewSlotManager(1, sync);
    manager.request("a", vi.fn(), 0);
    manager.request("far", vi.fn(), 900);
    manager.request("near", vi.fn(), 200);

    manager.release("a");

    expect(manager.isLive("near")).toBe(true);
    expect(manager.isLive("far")).toBe(false);
  });

  it("ranks a burst of requests by priority before granting", () => {
    const scheduler = deferredScheduler();
    const manager = new LivePreviewSlotManager(2, scheduler.schedule);
    const row3 = vi.fn();

    // Arrival order is scrambled, as IntersectionObserver delivery can be.
    manager.request("row3", row3, 1200);
    manager.request("row1a", vi.fn(), 400);
    manager.request("row2", vi.fn(), 800);
    manager.request("row1b", vi.fn(), 400);

    expect(manager.liveCount()).toBe(0);
    expect(scheduler.pending()).toBe(1); // coalesced
    scheduler.flush();

    expect(manager.isLive("row1a")).toBe(true);
    expect(manager.isLive("row1b")).toBe(true);
    expect(manager.isLive("row2")).toBe(false);
    expect(manager.isLive("row3")).toBe(false);
    expect(row3).not.toHaveBeenCalled();
  });

  it("preempts the worst live preview when a better-ranked one appears", () => {
    const manager = new LivePreviewSlotManager(2, sync);
    const low = vi.fn();
    manager.request("mid", vi.fn(), 500);
    manager.request("low", low, 900);
    expect(manager.isLive("low")).toBe(true);

    const top = vi.fn();
    manager.request("top", top, 100);

    expect(manager.isLive("top")).toBe(true);
    expect(manager.isLive("mid")).toBe(true);
    expect(manager.isLive("low")).toBe(false);
    expect(low).toHaveBeenLastCalledWith(false);
    expect(top).toHaveBeenCalledWith(true);
  });

  it("uses the default deferred scheduler when none is given", async () => {
    vi.useFakeTimers();
    try {
      const manager = new LivePreviewSlotManager(1);
      manager.request("a", vi.fn());
      expect(manager.liveCount()).toBe(0);
      vi.runAllTimers();
      expect(manager.liveCount()).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("ignores release of unknown ids", () => {
    const manager = new LivePreviewSlotManager(1, sync);
    expect(() => manager.release("nope")).not.toThrow();
    expect(manager.liveCount()).toBe(0);
  });

  it("rejects an invalid maximum", () => {
    expect(() => new LivePreviewSlotManager(-1)).toThrow();
    expect(() => new LivePreviewSlotManager(1.5)).toThrow();
  });

  it("grants nothing when the maximum is zero", () => {
    const manager = new LivePreviewSlotManager(0, sync);
    const a = vi.fn();
    manager.request("a", a);
    expect(manager.isLive("a")).toBe(false);
    expect(a).not.toHaveBeenCalled();
  });
});
