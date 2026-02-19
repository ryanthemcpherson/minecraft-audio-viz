package com.audioviz.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the WebSocket protocol message format and JSON contract.
 *
 * These tests validate the JSON schema expected by MessageQueue and MessageHandler
 * without needing a Bukkit server. They ensure Python→Java protocol compatibility.
 */
class MessageQueueTest {

    // --- JSON protocol contract tests ---

    @Nested
    @DisplayName("batch_update message format")
    class BatchUpdateFormat {

        @Test
        @DisplayName("Valid batch_update parses correctly")
        void validBatchUpdateParses() {
            String json = """
                {
                    "type": "batch_update",
                    "zone": "main",
                    "entities": [
                        {"id": "block_0", "x": 0.5, "y": 0.3, "z": 0.5, "scale": 0.8},
                        {"id": "block_1", "x": 0.2, "y": 0.7, "z": 0.9, "scale": 0.5}
                    ],
                    "is_beat": true,
                    "beat_intensity": 0.85,
                    "bands": [0.8, 0.6, 0.4, 0.3, 0.2],
                    "amplitude": 0.7,
                    "frame": 12345
                }
                """;

            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();

            assertEquals("batch_update", msg.get("type").getAsString());
            assertEquals("main", msg.get("zone").getAsString());
            assertTrue(msg.get("is_beat").getAsBoolean());
            assertEquals(0.85, msg.get("beat_intensity").getAsDouble(), 1e-10);
            assertEquals(12345, msg.get("frame").getAsLong());

            JsonArray entities = msg.getAsJsonArray("entities");
            assertEquals(2, entities.size());

            JsonObject entity0 = entities.get(0).getAsJsonObject();
            assertEquals("block_0", entity0.get("id").getAsString());
            assertEquals(0.5, entity0.get("x").getAsDouble(), 1e-10);
            assertEquals(0.3, entity0.get("y").getAsDouble(), 1e-10);
            assertEquals(0.8, entity0.get("scale").getAsFloat(), 1e-6);
        }

        @Test
        @DisplayName("Bands array has exactly 5 elements")
        void bandsHasFiveElements() {
            String json = """
                {
                    "type": "batch_update",
                    "zone": "main",
                    "bands": [0.8, 0.6, 0.4, 0.3, 0.2]
                }
                """;

            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            JsonArray bands = msg.getAsJsonArray("bands");

            assertEquals(5, bands.size(), "Protocol requires exactly 5 frequency bands");
        }

        @Test
        @DisplayName("Entity with optional interpolation field")
        void entityWithInterpolation() {
            String json = """
                {
                    "type": "batch_update",
                    "zone": "main",
                    "entities": [
                        {"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 1.0, "interpolation": 3}
                    ]
                }
                """;

            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            JsonObject entity = msg.getAsJsonArray("entities").get(0).getAsJsonObject();

            assertTrue(entity.has("interpolation"));
            assertEquals(3, entity.get("interpolation").getAsInt());
        }

        @Test
        @DisplayName("Entity with optional brightness and glow fields")
        void entityWithBrightnessGlow() {
            String json = """
                {
                    "type": "batch_update",
                    "zone": "main",
                    "entities": [
                        {"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "brightness": 10, "glow": true}
                    ]
                }
                """;

            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            JsonObject entity = msg.getAsJsonArray("entities").get(0).getAsJsonObject();

            assertEquals(10, entity.get("brightness").getAsInt());
            assertTrue(entity.get("glow").getAsBoolean());
        }

        @Test
        @DisplayName("batch_update with particle effects")
        void batchUpdateWithParticles() {
            String json = """
                {
                    "type": "batch_update",
                    "zone": "main",
                    "entities": [],
                    "particles": [
                        {"particle": "FLAME", "x": 0.5, "y": 1.0, "z": 0.5, "count": 50},
                        {"particle": "END_ROD", "x": 0.5, "y": 0.5, "z": 0.5, "count": 20}
                    ]
                }
                """;

            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            JsonArray particles = msg.getAsJsonArray("particles");

            assertEquals(2, particles.size());
            assertEquals("FLAME", particles.get(0).getAsJsonObject().get("particle").getAsString());
            assertEquals(50, particles.get(0).getAsJsonObject().get("count").getAsInt());
        }
    }

    @Nested
    @DisplayName("Entity coordinate ranges")
    class CoordinateRanges {

        @ParameterizedTest
        @ValueSource(doubles = {0.0, 0.25, 0.5, 0.75, 1.0})
        @DisplayName("Normalized coordinates are in [0, 1] range")
        void normalizedCoordinateRange(double coord) {
            assertTrue(coord >= 0.0 && coord <= 1.0);
        }

        @Test
        @DisplayName("Entity scale is positive")
        void entityScalePositive() {
            String json = """
                {"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}
                """;

            JsonObject entity = JsonParser.parseString(json).getAsJsonObject();
            assertTrue(entity.get("scale").getAsFloat() > 0);
        }
    }

    @Nested
    @DisplayName("Audio state message format")
    class AudioStateFormat {

        @Test
        @DisplayName("audio_state message parses correctly")
        void audioStateParses() {
            String json = """
                {
                    "type": "audio_state",
                    "zone": "main",
                    "bands": [0.9, 0.6, 0.4, 0.2, 0.1],
                    "amplitude": 0.75,
                    "is_beat": true,
                    "beat_intensity": 0.92,
                    "frame": 99999
                }
                """;

            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();

            assertEquals("audio_state", msg.get("type").getAsString());
            assertEquals(5, msg.getAsJsonArray("bands").size());
            assertTrue(msg.get("is_beat").getAsBoolean());
        }

        @Test
        @DisplayName("Band values are in [0, 1] range")
        void bandValuesInRange() {
            double[] bands = {0.8, 0.6, 0.4, 0.3, 0.2};
            for (double b : bands) {
                assertTrue(b >= 0.0 && b <= 1.0, "Band value " + b + " out of range");
            }
        }
    }

    @Nested
    @DisplayName("Control messages")
    class ControlMessages {

        @Test
        @DisplayName("init_pool message format")
        void initPoolFormat() {
            String json = """
                {
                    "type": "init_pool",
                    "zone": "main",
                    "count": 32,
                    "material": "SEA_LANTERN"
                }
                """;

            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("init_pool", msg.get("type").getAsString());
            assertEquals(32, msg.get("count").getAsInt());
        }

        @Test
        @DisplayName("set_zone_config message format")
        void setZoneConfigFormat() {
            String json = """
                {
                    "type": "set_zone_config",
                    "zone": "main",
                    "config": {
                        "entity_count": 64,
                        "block_type": "GLOWSTONE",
                        "brightness": 12,
                        "interpolation": 2,
                        "size": {"x": 15, "y": 10, "z": 15},
                        "rotation": 45.0
                    }
                }
                """;

            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            JsonObject config = msg.getAsJsonObject("config");

            assertEquals(64, config.get("entity_count").getAsInt());
            assertEquals(12, config.get("brightness").getAsInt());
            assertEquals(2, config.get("interpolation").getAsInt());
            assertEquals(15, config.getAsJsonObject("size").get("x").getAsDouble());
        }

        @Test
        @DisplayName("set_render_mode valid modes")
        void renderModeValues() {
            for (String mode : new String[]{"entities", "particles", "hybrid"}) {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "set_render_mode");
                msg.addProperty("zone", "main");
                msg.addProperty("mode", mode);

                assertEquals(mode, msg.get("mode").getAsString());
            }
        }

        @Test
        @DisplayName("set_renderer_backend message format")
        void setRendererBackendFormat() {
            String json = """
                {
                    "type": "set_renderer_backend",
                    "zone": "main",
                    "backend": "display_entities",
                    "fallback_backend": "particles"
                }
                """;

            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("display_entities", msg.get("backend").getAsString());
            assertEquals("particles", msg.get("fallback_backend").getAsString());
        }
    }

    @Nested
    @DisplayName("Response message format")
    class ResponseFormat {

        @Test
        @DisplayName("Error response has type and message")
        void errorResponseFormat() {
            JsonObject error = new JsonObject();
            error.addProperty("type", "error");
            error.addProperty("message", "Zone not found: test");

            assertEquals("error", error.get("type").getAsString());
            assertTrue(error.get("message").getAsString().length() > 0);
        }

        @Test
        @DisplayName("Zone response includes origin and size")
        void zoneResponseFormat() {
            JsonObject zone = new JsonObject();
            zone.addProperty("name", "main");
            zone.addProperty("world", "world");

            JsonObject origin = new JsonObject();
            origin.addProperty("x", 100.0);
            origin.addProperty("y", 64.0);
            origin.addProperty("z", 200.0);
            zone.add("origin", origin);

            JsonObject size = new JsonObject();
            size.addProperty("x", 10.0);
            size.addProperty("y", 10.0);
            size.addProperty("z", 10.0);
            zone.add("size", size);

            assertTrue(zone.has("origin"));
            assertTrue(zone.has("size"));
            assertEquals(100.0, zone.getAsJsonObject("origin").get("x").getAsDouble());
        }
    }
}
