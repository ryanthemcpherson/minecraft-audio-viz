package com.audioviz.render;

import com.audioviz.protocol.MessageQueue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFrameHubTest {

    private static final MessageQueue.MessageGuard ALLOW_ALL = operation -> {
        operation.run();
        return true;
    };

    @Test
    void rejectedGuardCannotPublishStateOrTransientEvents() {
        RenderFrameHub hub = hubWithMainZone();
        AtomicInteger guardedOperations = new AtomicInteger();
        MessageQueue.MessageGuard rejected = operation -> {
            guardedOperations.incrementAndGet();
            return false;
        };

        RenderDecodeResult result = hub.publishJson(frame(1, true, 0.6), rejected, 100);

        assertEquals(RenderDecodeResult.Status.REJECTED, result.status());
        assertEquals(1, guardedOperations.get());
        try (ZoneRenderDrain drain = hub.take("main")) {
            assertNull(drain.snapshot());
            assertEquals(0, drain.events().beatCount());
        }
    }

    @Test
    void beatInSubsequentlySupersededFrameSurvivesWithItsExactGuard() {
        RenderFrameHub hub = hubWithMainZone();

        assertTrue(hub.publishJson(frame(1, true, 0.7), ALLOW_ALL, 100).accepted());
        assertTrue(hub.publishJson(frame(2, false, 0.0), ALLOW_ALL, 200).accepted());

        try (ZoneRenderDrain drain = hub.take("main")) {
            assertEquals(2, drain.snapshot().frameSequence());
            assertFalse(drain.snapshot().beat());
            assertSame(ALLOW_ALL, drain.snapshot().connectionGuard());
            assertEquals(1, drain.events().beatCount());
            assertEquals(1, drain.events().beatSequence(0));
            assertSame(ALLOW_ALL, drain.events().beatGuard(0));
        }
    }

    @Test
    void staleDecodeKeepsValidatedEventsButCannotReplaceNewerState() {
        RenderFrameHub hub = hubWithMainZone();
        JsonRenderFrameDecoder decoder = new JsonRenderFrameDecoder(hub);

        assertTrue(decoder.decode(frame(20, false, 0), 20, 200).accepted());
        RenderDecodeResult stale = decoder.decode(frame(19, true, 0.8), 19, 190);

        assertEquals(RenderDecodeResult.Status.SUPERSEDED, stale.status());
        try (ZoneRenderDrain drain = hub.take("main")) {
            assertEquals(20, drain.snapshot().frameSequence());
            assertEquals(1, drain.events().beatCount());
            assertEquals(19, drain.events().beatSequence(0));
        }
    }

    @Test
    void removeAndInvalidateAllDropPendingWorkAndBoundZoneRegistration() {
        RenderFrameHub hub = new RenderFrameHub(new RenderProtocolLimits(1, 2, 10, 1));
        assertTrue(hub.registerZone("main", 2, 0));
        assertFalse(hub.registerZone("other", 2, 0));
        assertTrue(hub.publishJson(frame(1, true, 0.5), ALLOW_ALL, 100).accepted());

        hub.removeZone("main");

        assertEquals(0, hub.zoneCount());
        assertEquals(RenderDecodeResult.Status.REJECTED,
            hub.publishJson(frame(2, true, 0.9), ALLOW_ALL, 200).status());
        assertNull(hub.take("main").snapshot());

        assertTrue(hub.registerZone("replacement", 2, 0));
        hub.invalidateAll();
        assertEquals(0, hub.zoneCount());
        assertFalse(hub.registerZone("late", 2, 0));
    }

    @Test
    void ingressOrdinalAdvancesOncePerPublishAttempt() {
        RenderFrameHub hub = hubWithMainZone();

        hub.publishJson(frame(1, false, 0), operation -> false, 10);
        RenderDecodeResult accepted = hub.publishJson(frame(2, false, 0), ALLOW_ALL, 20);

        assertTrue(accepted.accepted());
        assertEquals(2, accepted.snapshot().ingressOrdinal());
        try (ZoneRenderDrain ignored = hub.take("main")) {
            // Return the accepted slot after inspection.
        }
    }

    @Test
    void repeatedRemoteFrameUsesCollisionFreeLocalParticleEventIds() {
        RenderFrameHub hub = hubWithMainZone();
        JsonObject first = particleFrame(7, "NOTE");
        JsonObject second = particleFrame(7, "FLAME");

        assertTrue(hub.publishJson(first, ALLOW_ALL, 10).accepted());
        assertTrue(hub.publishJson(second, ALLOW_ALL, 20).accepted());

        try (ZoneRenderDrain drain = hub.take("main")) {
            assertEquals(2, drain.events().particleCount());
            assertEquals(11, drain.events().particle(0).eventId());
            assertEquals(22, drain.events().particle(1).eventId());
            assertEquals("NOTE", drain.events().particle(0).particleName());
            assertEquals("FLAME", drain.events().particle(1).particleName());
        }
    }

    private static RenderFrameHub hubWithMainZone() {
        RenderFrameHub hub = new RenderFrameHub(new RenderProtocolLimits(2, 4, 10, 1));
        assertTrue(hub.registerZone("main", 4, 0));
        return hub;
    }

    private static JsonObject frame(long sequence, boolean beat, double intensity) {
        return JsonParser.parseString("""
            {"type":"batch_update","zone":"main","frame":%d,
             "is_beat":%s,"beat_intensity":%s,
             "entities":[{"id":"block_0","x":0.5,"y":0.5,"z":0.5}]}
            """.formatted(sequence, beat, intensity)).getAsJsonObject();
    }

    private static JsonObject particleFrame(long sequence, String particleName) {
        return JsonParser.parseString("""
            {"type":"batch_update","zone":"main","frame":%d,"entities":[],
             "particles":[{"particle":"%s","count":1}]}
            """.formatted(sequence, particleName)).getAsJsonObject();
    }
}
