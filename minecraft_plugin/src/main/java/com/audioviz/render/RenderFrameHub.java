package com.audioviz.render;

import com.audioviz.protocol.MessageQueue;
import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Bounded registry and ingress-ordering boundary for normalized render frames. */
public final class RenderFrameHub {

    private static final int MAX_ZONE_NAME_LENGTH = 64;
    private static final Pattern ZONE_NAME = Pattern.compile("[a-zA-Z0-9_-]+");

    private final RenderProtocolLimits limits;
    private final ConcurrentHashMap<String, ZoneEntry> zones = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();
    private final AtomicLong ingressSequence = new AtomicLong();
    private final JsonRenderFrameDecoder decoder;
    private boolean valid = true;

    public RenderFrameHub(RenderProtocolLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        decoder = new JsonRenderFrameDecoder(this, limits);
    }

    /** Registers fixed storage for a main-thread-created zone and entity pool. */
    public boolean registerZone(String zoneName, int entityCapacity, int defaultMaterialId) {
        requireValidZoneName(zoneName);
        if (entityCapacity < 1 || entityCapacity > limits.maxEntitiesPerZone()) {
            throw new IllegalArgumentException("entityCapacity exceeds configured render limit");
        }
        if (defaultMaterialId < 0) {
            throw new IllegalArgumentException("defaultMaterialId must not be negative");
        }

        synchronized (registryLock) {
            if (!valid || zones.containsKey(zoneName) || zones.size() >= limits.maxZones()) {
                return false;
            }
            zones.put(zoneName, new ZoneEntry(zoneName, entityCapacity, defaultMaterialId, limits));
            return true;
        }
    }

    /**
     * Validates and publishes JSON while the exact connection-generation guard is held.
     */
    public RenderDecodeResult publishJson(
            JsonObject message,
            MessageQueue.MessageGuard guard,
            long receivedNanos
    ) {
        Objects.requireNonNull(guard, "guard");
        long ingressOrdinal = ingressSequence.incrementAndGet();
        if (message == null) {
            return RenderDecodeResult.rejected("message is null");
        }

        AtomicReference<RenderDecodeResult> result = new AtomicReference<>();
        boolean guardAccepted = guard.runIfValid(() -> result.set(
            decoder.decodeAndPublish(message, guard, ingressOrdinal, receivedNanos)));
        if (!guardAccepted) {
            return RenderDecodeResult.rejected("connection generation is no longer valid");
        }
        return Objects.requireNonNullElseGet(
            result.get(),
            () -> RenderDecodeResult.rejected("connection guard did not run publication"));
    }

    /** Takes the latest snapshot and all durable events for one tick-thread consumer. */
    public ZoneRenderDrain take(String zoneName) {
        if (zoneName == null) {
            return ZoneRenderDrain.empty();
        }
        ZoneEntry entry = zones.get(zoneName);
        return entry == null ? ZoneRenderDrain.empty() : entry.take();
    }

    /** Invalidates and removes one zone before its pool/world lifecycle cleanup. */
    public void removeZone(String zoneName) {
        if (zoneName == null) {
            return;
        }
        synchronized (registryLock) {
            ZoneEntry entry = zones.get(zoneName);
            if (entry != null) {
                entry.invalidate();
                zones.remove(zoneName, entry);
            }
        }
    }

    /** Permanently closes this hub and invalidates all registered fixed storage. */
    public void invalidateAll() {
        synchronized (registryLock) {
            valid = false;
            zones.forEach((ignored, entry) -> entry.invalidate());
            zones.clear();
        }
    }

    public int zoneCount() {
        return zones.size();
    }

    ZoneEntry findZone(String zoneName) {
        return zones.get(zoneName);
    }

    RenderProtocolLimits limits() {
        return limits;
    }

    private static void requireValidZoneName(String zoneName) {
        Objects.requireNonNull(zoneName, "zoneName");
        if (zoneName.isEmpty()
                || zoneName.length() > MAX_ZONE_NAME_LENGTH
                || !ZONE_NAME.matcher(zoneName).matches()) {
            throw new IllegalArgumentException("invalid zone name");
        }
    }

    static final class ZoneEntry {
        private final ZoneSnapshotMailbox mailbox;
        private final RenderEventLatch eventLatch;
        private final ZoneRenderDrain drain;
        private final DrainedRenderEvents discardEvents;
        private boolean valid = true;

        private ZoneEntry(
                String zoneName,
                int entityCapacity,
                int defaultMaterialId,
                RenderProtocolLimits limits
        ) {
            mailbox = new ZoneSnapshotMailbox(
                zoneName,
                entityCapacity,
                defaultMaterialId,
                limits.snapshotSlotCount());
            eventLatch = new RenderEventLatch(limits.maxParticlesPerTick());
            drain = new ZoneRenderDrain(mailbox, limits.maxParticlesPerTick());
            discardEvents = new DrainedRenderEvents(limits.maxParticlesPerTick());
        }

        ZoneRenderSnapshot tryClaim(long ingressOrdinal) {
            synchronized (this) {
                return valid ? mailbox.tryClaim(ingressOrdinal) : null;
            }
        }

        synchronized RenderDecodeResult commit(
                ZoneRenderSnapshot snapshot,
                JsonObject message,
                long eventSequence,
                MessageQueue.MessageGuard guard
        ) {
            if (!valid) {
                mailbox.releaseAfterFailedWrite(snapshot);
                return RenderDecodeResult.rejected("zone was removed during decode");
            }

            snapshot.connectionGuard(guard);
            JsonRenderFrameDecoder.latchValidatedEvents(
                eventLatch,
                message,
                eventSequence,
                guard);
            return mailbox.publish(snapshot)
                ? RenderDecodeResult.accepted(snapshot)
                : RenderDecodeResult.superseded();
        }

        void releaseAfterFailedWrite(ZoneRenderSnapshot snapshot) {
            mailbox.releaseAfterFailedWrite(snapshot);
        }

        synchronized ZoneRenderDrain take() {
            if (!valid) {
                return ZoneRenderDrain.empty();
            }
            DrainedRenderEvents events = drain.mutableEvents();
            ZoneRenderSnapshot snapshot = mailbox.takeLatest();
            eventLatch.drainInto(events);
            if (snapshot == null && events.beatCount() == 0 && events.particleCount() == 0) {
                return ZoneRenderDrain.empty();
            }
            return drain.open(snapshot);
        }

        synchronized void invalidate() {
            if (!valid) {
                return;
            }
            valid = false;
            ZoneRenderSnapshot pending = mailbox.takeLatest();
            if (pending != null) {
                mailbox.releaseAfterRead(pending);
            }
            eventLatch.drainInto(discardEvents);
            discardEvents.reset();
        }
    }
}
