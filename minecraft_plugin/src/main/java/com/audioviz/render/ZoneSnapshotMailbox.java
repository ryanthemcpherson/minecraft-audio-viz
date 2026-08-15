package com.audioviz.render;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fixed-size latest-wins mailbox for one zone's render snapshots.
 */
public final class ZoneSnapshotMailbox {

    private final ZoneRenderSnapshot[] slots;
    private final AtomicReference<ZoneRenderSnapshot> latest = new AtomicReference<>();
    private final Object publicationLock = new Object();
    private long highestPublishedOrdinal = Long.MIN_VALUE;

    public ZoneSnapshotMailbox(
            String zoneName,
            int maxEntities,
            int defaultMaterialId,
            int slotCount
    ) {
        Objects.requireNonNull(zoneName, "zoneName");
        if (slotCount < 1) {
            throw new IllegalArgumentException("slotCount must be positive");
        }
        slots = new ZoneRenderSnapshot[slotCount];
        for (int index = 0; index < slotCount; index++) {
            slots[index] = new ZoneRenderSnapshot(this, zoneName, maxEntities, defaultMaterialId);
        }
    }

    /**
     * Claims a free slot for exclusive mutation by the caller.
     *
     * @return the claimed slot, or {@code null} when every slot is owned
     */
    public ZoneRenderSnapshot tryClaim(long ingressOrdinal) {
        for (ZoneRenderSnapshot slot : slots) {
            if (slot.tryClaim(ingressOrdinal)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Completes a write and publishes it when its ingress ordinal is newest.
     */
    public boolean publish(ZoneRenderSnapshot snapshot) {
        requireOwned(snapshot);
        synchronized (publicationLock) {
            snapshot.markPublished();
            if (snapshot.ingressOrdinal() <= highestPublishedOrdinal) {
                snapshot.releasePublished();
                return false;
            }

            highestPublishedOrdinal = snapshot.ingressOrdinal();
            ZoneRenderSnapshot previous = latest.get();
            while (!latest.compareAndSet(previous, snapshot)) {
                previous = latest.get();
            }
            if (previous != null) {
                previous.releasePublished();
            }
            return true;
        }
    }

    /**
     * Atomically removes the currently published snapshot for tick-thread use.
     */
    public ZoneRenderSnapshot takeLatest() {
        ZoneRenderSnapshot snapshot = latest.getAndSet(null);
        if (snapshot != null) {
            snapshot.markReading();
        }
        return snapshot;
    }

    /**
     * Returns a consumed snapshot to the free slot pool.
     */
    public void releaseAfterRead(ZoneRenderSnapshot snapshot) {
        requireOwned(snapshot);
        snapshot.releaseReading();
    }

    /** Returns an abandoned decoder claim to the free slot pool. */
    void releaseAfterFailedWrite(ZoneRenderSnapshot snapshot) {
        requireOwned(snapshot);
        snapshot.releaseWriting();
    }

    private void requireOwned(ZoneRenderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.ownedBy(this)) {
            throw new IllegalArgumentException("snapshot belongs to another mailbox");
        }
    }
}
