package com.audioviz.render;

/**
 * Tick-owned view of one zone's newest state and accumulated transient events.
 *
 * <p>Consumers must close a non-empty drain after applying it. Closing returns
 * its snapshot slot to the bounded mailbox and clears retained event guards.</p>
 */
public final class ZoneRenderDrain implements AutoCloseable {

    private static final ZoneRenderDrain EMPTY = new ZoneRenderDrain();

    private final ZoneSnapshotMailbox mailbox;
    private final DrainedRenderEvents events;
    private ZoneRenderSnapshot snapshot;
    private boolean open;

    private ZoneRenderDrain() {
        mailbox = null;
        events = new DrainedRenderEvents(1);
    }

    ZoneRenderDrain(ZoneSnapshotMailbox mailbox, int eventCapacity) {
        this.mailbox = mailbox;
        events = new DrainedRenderEvents(eventCapacity);
    }

    static ZoneRenderDrain empty() {
        return EMPTY;
    }

    synchronized DrainedRenderEvents mutableEvents() {
        requireReusable();
        return events;
    }

    synchronized ZoneRenderDrain open(ZoneRenderSnapshot nextSnapshot) {
        requireReusable();
        snapshot = nextSnapshot;
        open = true;
        return this;
    }

    public synchronized ZoneRenderSnapshot snapshot() {
        return snapshot;
    }

    public DrainedRenderEvents events() {
        return events;
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        ZoneRenderSnapshot completedSnapshot = snapshot;
        snapshot = null;
        open = false;
        try {
            if (completedSnapshot != null) {
                mailbox.releaseAfterRead(completedSnapshot);
            }
        } finally {
            events.reset();
        }
    }

    private void requireReusable() {
        if (open) {
            throw new IllegalStateException("previous zone render drain was not closed");
        }
    }
}
