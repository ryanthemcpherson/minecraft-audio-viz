package com.audioviz.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRenderFrameDecoderTest {

    private RenderFrameHub hub;
    private JsonRenderFrameDecoder decoder;

    @BeforeEach
    void setUp() {
        hub = new RenderFrameHub(new RenderProtocolLimits(2, 2, 10, 1));
        assertTrue(hub.registerZone("main", 2, 0));
        decoder = new JsonRenderFrameDecoder(hub);
    }

    @Test
    void completeAudioAndDenseEntitiesNormalizeWithoutWorldAccess() {
        JsonObject message = json("""
            {"type":"batch_update","zone":"main","frame":42,
             "source_time_ns":700,"generated_time_ns":800,
             "bands":[0.1,0.2,0.3,0.4,0.5],"amplitude":0.7,
             "is_beat":true,"is_kick":true,"beat_intensity":0.8,"bpm":128.0,
             "tempo_confidence":0.9,"beat_phase":0.25,
             "entities":[
               {"id":"block_0","x":0.1,"y":0.2,"z":0.3,"scale":0.4,
                "rotation":450,"brightness":12,"glow":true,"interpolation":1,
                "material":"SEA_LANTERN"}]}
            """);

        RenderDecodeResult result = decoder.decode(message, 9, 1000);

        assertTrue(result.accepted());
        assertNull(result.reason());
        ZoneRenderSnapshot snapshot = result.snapshot();
        assertTrue(snapshot.densePool());
        assertEquals(1, snapshot.entityCount());
        assertEquals(90.0f, snapshot.rotation()[0]);
        assertEquals(128.0, snapshot.bpm(), 1e-9);
        assertEquals(0.9, snapshot.tempoConfidence(), 1e-9);
        assertEquals(0.25, snapshot.beatPhase(), 1e-9);
        assertEquals(42, snapshot.frameSequence());
        assertEquals(700, snapshot.sourceTimeNanos());
        assertEquals(800, snapshot.generatedTimeNanos());
        assertEquals(1000, snapshot.receivedNanos());
        assertTrue(snapshot.beat());
        assertTrue(snapshot.kick());
        assertEquals("SEA_LANTERN", snapshot.materialNames()[0]);
        assertEquals(ZoneRenderSnapshot.ENTITY_VISIBLE | ZoneRenderSnapshot.ENTITY_GLOW,
            snapshot.entityFlags()[0]);

        try (ZoneRenderDrain drain = hub.take("main")) {
            assertSame(snapshot, drain.snapshot());
            assertEquals(1, drain.events().beatCount());
            assertEquals(42, drain.events().beatSequence(0));
            assertTrue(drain.events().kick(0));
        }
    }

    @Test
    void missingZoneAndUnknownMailboxRejectWithoutCreatingState() {
        RenderDecodeResult missing = decoder.decode(
            json("{\"type\":\"batch_update\",\"entities\":[]}"), 1, 10);
        RenderDecodeResult unknown = decoder.decode(
            json("{\"type\":\"batch_update\",\"zone\":\"peer-zone\",\"entities\":[]}"),
            2,
            20);

        assertEquals(RenderDecodeResult.Status.REJECTED, missing.status());
        assertEquals(RenderDecodeResult.Status.REJECTED, unknown.status());
        assertEquals(1, hub.zoneCount());
        assertNull(hub.take("peer-zone").snapshot());
    }

    @Test
    void excessiveEntityCountRejectsWithoutPublishing() {
        RenderDecodeResult result = decoder.decode(json("""
            {"type":"batch_update","zone":"main","entities":[
              {"id":"block_0"},{"id":"block_1"},{"id":"block_2"}]}
            """), 3, 30);

        assertEquals(RenderDecodeResult.Status.REJECTED, result.status());
        assertNull(hub.take("main").snapshot());
    }

    @Test
    void nonFiniteAndOutOfDomainNumbersRejectAndReleaseClaimedSlots() {
        JsonObject nonFinite = validFrame(0.2);
        nonFinite.getAsJsonArray("entities").get(0).getAsJsonObject()
            .addProperty("x", Double.NaN);

        assertEquals(RenderDecodeResult.Status.REJECTED,
            decoder.decode(nonFinite, 4, 40).status());
        assertEquals(RenderDecodeResult.Status.REJECTED,
            decoder.decode(validFrame(1.01), 5, 50).status());
        assertEquals(RenderDecodeResult.Status.REJECTED,
            decoder.decode(json("""
                {"type":"batch_update","zone":"main","entities":[
                  {"id":"block_0","brightness":2.5}]}
                """), 6, 60).status());

        RenderDecodeResult valid = decoder.decode(validFrame(0.75), 7, 70);
        assertTrue(valid.accepted(), "malformed frames must return every claimed slot");
        try (ZoneRenderDrain drain = hub.take("main")) {
            assertSame(valid.snapshot(), drain.snapshot());
            assertEquals(0, drain.events().particleCount());
        }
    }

    @Test
    void sparseIdsPreserveValidatedFallbackNames() {
        RenderDecodeResult result = decoder.decode(json("""
            {"type":"batch_update","zone":"main","entities":[
              {"id":"left-speaker","x":0.1},
              {"id":"block_0","x":0.9}]}
            """), 8, 80);

        assertTrue(result.accepted());
        assertFalse(result.snapshot().densePool());
        assertEquals("left-speaker", result.snapshot().entityIds()[0]);
        assertEquals("block_0", result.snapshot().entityIds()[1]);
        try (ZoneRenderDrain ignored = hub.take("main")) {
            // Return the accepted slot after inspection.
        }
    }

    @Test
    void particleWorkBudgetRejectsWholeFrameWithoutLeakingEventsOrSlots() {
        JsonObject excessive = json("""
            {"type":"batch_update","zone":"main","frame":11,"is_beat":true,
             "beat_intensity":0.7,"entities":[],"particles":[
               {"particle":"NOTE","x":0.5,"y":0.5,"z":0.5,"count":6},
               {"particle":"FLAME","x":0.5,"y":0.5,"z":0.5,"count":5}]}
            """);

        assertEquals(RenderDecodeResult.Status.REJECTED,
            decoder.decode(excessive, 9, 90).status());
        try (ZoneRenderDrain rejected = hub.take("main")) {
            assertNull(rejected.snapshot());
            assertEquals(0, rejected.events().beatCount());
            assertEquals(0, rejected.events().particleCount());
        }

        JsonObject bounded = json("""
            {"type":"batch_update","zone":"main","frame":12,"entities":[],
             "particles":[
               {"particle":"NOTE","x":0.1,"y":0.2,"z":0.3,"count":4},
               {"particle":"FLAME","x":0.7,"y":0.8,"z":0.9,"count":6}]}
            """);
        assertTrue(decoder.decode(bounded, 10, 100).accepted());
        try (ZoneRenderDrain drain = hub.take("main")) {
            assertEquals(2, drain.events().particleCount());
            assertEquals(4, drain.events().particle(0).count());
            assertEquals(6, drain.events().particle(1).count());
        }
    }

    @Test
    void malformedNestedStructureRejectsBeforeAnyTransientEventIsLatched() {
        JsonObject message = json("""
            {"type":"batch_update","zone":"main","frame":13,"is_beat":true,
             "beat_intensity":0.9,"entities":[{"id":"block_0"}],
             "particles":[{"particle":"NOTE","count":1,"unknown":true}]}
            """);

        assertEquals(RenderDecodeResult.Status.REJECTED,
            decoder.decode(message, 11, 110).status());
        try (ZoneRenderDrain drain = hub.take("main")) {
            assertNull(drain.snapshot());
            assertEquals(0, drain.events().beatCount());
            assertEquals(0, drain.events().particleCount());
        }
    }

    private static JsonObject validFrame(double x) {
        return json("""
            {"type":"batch_update","zone":"main","entities":[
              {"id":"block_0","x":%s}]}
            """.formatted(x));
    }

    private static JsonObject json(String source) {
        return JsonParser.parseString(source).getAsJsonObject();
    }
}
