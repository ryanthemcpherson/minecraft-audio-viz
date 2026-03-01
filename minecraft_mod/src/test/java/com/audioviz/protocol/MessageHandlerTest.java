package com.audioviz.protocol;

import com.audioviz.patterns.AudioState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MessageHandler focusing on pure-data handlers (ping, audio_state, unknown type)
 * that don't require AudioVizMod — avoids Mockito/ByteBuddy compatibility issues.
 */
class MessageHandlerTest {

    private MessageHandler handler;

    @BeforeEach
    void setUp() {
        // ping, audio_state, and unknown-type handlers don't touch mod
        handler = new MessageHandler(null);
    }

    // ========== ping ==========

    @Nested
    class PingTests {

        @Test
        void pingReturnsPong() {
            JsonObject msg = new JsonObject();
            JsonObject response = handler.handleMessage("ping", msg);

            assertNotNull(response);
            assertEquals("pong", response.get("type").getAsString());
        }

        @Test
        void pongContainsTimestamp() {
            JsonObject msg = new JsonObject();
            long before = System.currentTimeMillis();
            JsonObject response = handler.handleMessage("ping", msg);
            long after = System.currentTimeMillis();

            assertTrue(response.has("timestamp"));
            long ts = response.get("timestamp").getAsLong();
            assertTrue(ts >= before && ts <= after,
                "Timestamp should be between " + before + " and " + after + " but was " + ts);
        }
    }

    // ========== Unknown message type ==========

    @Nested
    class UnknownTypeTests {

        @Test
        void unknownTypeReturnsErrorWithoutThrowing() {
            JsonObject msg = new JsonObject();
            JsonObject response = assertDoesNotThrow(
                () -> handler.handleMessage("totally_bogus_type", msg));

            assertNotNull(response);
            assertEquals("error", response.get("type").getAsString());
            assertTrue(response.get("message").getAsString().contains("Unknown message type"));
        }

        @Test
        void errorMessageIncludesTypeName() {
            JsonObject msg = new JsonObject();
            JsonObject response = handler.handleMessage("nonexistent_handler", msg);

            assertTrue(response.get("message").getAsString().contains("nonexistent_handler"));
        }
    }

    // ========== Audio state ==========

    @Nested
    class AudioStateTests {

        @Test
        void audioStateReturnsNull() {
            JsonObject msg = new JsonObject();
            JsonArray bands = new JsonArray();
            for (int i = 0; i < 5; i++) bands.add(0.5);
            msg.add("bands", bands);
            msg.addProperty("amplitude", 0.8);
            msg.addProperty("is_beat", true);
            msg.addProperty("beat_intensity", 0.9);
            msg.addProperty("frame", 42);

            JsonObject response = handler.handleMessage("audio_state", msg);
            assertNull(response, "audio_state should return null (no response)");
        }

        @Test
        void audioStateStoredAndRetrievable() {
            JsonObject msg = new JsonObject();
            JsonArray bands = new JsonArray();
            bands.add(0.1);
            bands.add(0.2);
            bands.add(0.3);
            bands.add(0.4);
            bands.add(0.5);
            msg.add("bands", bands);
            msg.addProperty("amplitude", 0.75);
            msg.addProperty("is_beat", true);
            msg.addProperty("beat_intensity", 0.9);
            msg.addProperty("tempo_confidence", 0.85);
            msg.addProperty("beat_phase", 0.5);
            msg.addProperty("frame", 100);

            handler.handleMessage("audio_state", msg);

            AudioState state = handler.getLatestAudioState();
            assertNotNull(state);
            assertEquals(0.1, state.getBand(0), 1e-9);
            assertEquals(0.2, state.getBand(1), 1e-9);
            assertEquals(0.3, state.getBand(2), 1e-9);
            assertEquals(0.4, state.getBand(3), 1e-9);
            assertEquals(0.5, state.getBand(4), 1e-9);
            assertEquals(0.75, state.getAmplitude(), 1e-9);
            assertTrue(state.isBeat());
            assertEquals(0.9, state.getBeatIntensity(), 1e-9);
            assertEquals(0.85, state.getTempoConfidence(), 1e-9);
            assertEquals(0.5, state.getBeatPhase(), 1e-9);
            assertEquals(100, state.getFrame());
        }

        @Test
        void audioStateBandsClampedToZeroOne() {
            JsonObject msg = new JsonObject();
            JsonArray bands = new JsonArray();
            bands.add(-0.5);
            bands.add(1.5);
            bands.add(0.5);
            bands.add(0.0);
            bands.add(1.0);
            msg.add("bands", bands);
            msg.addProperty("amplitude", 2.0);

            handler.handleMessage("audio_state", msg);

            AudioState state = handler.getLatestAudioState();
            assertNotNull(state);
            assertEquals(0.0, state.getBand(0), 1e-9, "Negative should clamp to 0");
            assertEquals(1.0, state.getBand(1), 1e-9, "Over 1 should clamp to 1");
            assertEquals(0.5, state.getBand(2), 1e-9, "In-range should pass through");
            assertEquals(1.0, state.getAmplitude(), 1e-9, "Amplitude over 1 should clamp");
        }

        @Test
        void audioStateMissingFieldsDefaultToZero() {
            JsonObject msg = new JsonObject();
            // Send minimal message — no bands, no amplitude, etc.

            handler.handleMessage("audio_state", msg);

            AudioState state = handler.getLatestAudioState();
            assertNotNull(state);
            assertEquals(0.0, state.getAmplitude(), 1e-9);
            assertFalse(state.isBeat());
            assertEquals(0.0, state.getBeatIntensity(), 1e-9);
            assertEquals(0, state.getFrame());
            for (int i = 0; i < 5; i++) {
                assertEquals(0.0, state.getBand(i), 1e-9, "Band " + i + " should default to 0");
            }
        }

        @Test
        void multipleAudioStatesOverwrite() {
            // First state
            JsonObject msg1 = new JsonObject();
            msg1.addProperty("amplitude", 0.3);
            msg1.addProperty("frame", 1);
            handler.handleMessage("audio_state", msg1);
            assertEquals(1, handler.getLatestAudioState().getFrame());

            // Second state overwrites
            JsonObject msg2 = new JsonObject();
            msg2.addProperty("amplitude", 0.8);
            msg2.addProperty("frame", 2);
            handler.handleMessage("audio_state", msg2);
            assertEquals(2, handler.getLatestAudioState().getFrame());
            assertEquals(0.8, handler.getLatestAudioState().getAmplitude(), 1e-9);
        }
    }

    // ========== Initial state ==========

    @Test
    void latestAudioStateIsNullBeforeAnyMessage() {
        assertNull(handler.getLatestAudioState());
    }
}
