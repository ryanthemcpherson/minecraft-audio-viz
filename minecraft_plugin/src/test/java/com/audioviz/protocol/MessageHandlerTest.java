package com.audioviz.protocol;

import com.audioviz.AudioVizPlugin;
import com.audioviz.effects.BeatEffectConfig;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.entities.EntityUpdate;
import com.audioviz.particles.ParticleVisualizationManager;
import com.audioviz.render.RendererBackendType;
import com.audioviz.render.RendererRegistry;
import com.audioviz.zones.VisualizationZone;
import com.audioviz.zones.ZoneManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MessageHandler protocol message parsing and response generation.
 * Mocks the plugin and its subsystems to test handler logic in isolation.
 */
class MessageHandlerTest {

    @Mock private AudioVizPlugin plugin;
    @Mock private ZoneManager zoneManager;
    @Mock private EntityPoolManager entityPoolManager;
    @Mock private ParticleVisualizationManager particleVizManager;
    @Mock private BeatEventManager beatEventManager;
    @Mock private RendererRegistry rendererRegistry;
    @Mock private World mockWorld;

    private MessageHandler handler;
    private VisualizationZone testZone;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(plugin.getZoneManager()).thenReturn(zoneManager);
        when(plugin.getEntityPoolManager()).thenReturn(entityPoolManager);
        when(plugin.getParticleVisualizationManager()).thenReturn(particleVizManager);
        when(plugin.getBeatEventManager()).thenReturn(beatEventManager);
        when(plugin.getRendererRegistry()).thenReturn(rendererRegistry);

        when(mockWorld.getName()).thenReturn("world");

        Location origin = new Location(mockWorld, 100, 64, 200);
        testZone = new VisualizationZone("main", UUID.randomUUID(), origin,
            new Vector(10, 10, 10), 0f);

        handler = new MessageHandler(plugin);
    }

    // --- Ping ---

    @Test
    @DisplayName("ping returns pong with timestamp")
    void pingReturnsPong() {
        JsonObject response = handler.handleMessage("ping", new JsonObject());

        assertEquals("pong", response.get("type").getAsString());
        assertTrue(response.has("timestamp"));
        assertTrue(response.get("timestamp").getAsLong() > 0);
    }

    // --- Unknown message type ---

    @Test
    @DisplayName("Unknown type returns error")
    void unknownTypeReturnsError() {
        JsonObject response = handler.handleMessage("foobar", new JsonObject());

        assertEquals("error", response.get("type").getAsString());
        assertTrue(response.get("message").getAsString().contains("foobar"));
    }

    // --- get_zones ---

    @Test
    @DisplayName("get_zones returns empty array when no zones")
    void getZonesEmpty() {
        when(zoneManager.getAllZones()).thenReturn(Collections.emptyList());

        JsonObject response = handler.handleMessage("get_zones", new JsonObject());

        assertEquals("zones", response.get("type").getAsString());
        assertEquals(0, response.getAsJsonArray("zones").size());
    }

    @Test
    @DisplayName("get_zones returns zone data")
    void getZonesReturnsData() {
        when(zoneManager.getAllZones()).thenReturn(List.of(testZone));

        JsonObject response = handler.handleMessage("get_zones", new JsonObject());

        assertEquals("zones", response.get("type").getAsString());
        JsonArray zones = response.getAsJsonArray("zones");
        assertEquals(1, zones.size());

        JsonObject zoneJson = zones.get(0).getAsJsonObject();
        assertEquals("main", zoneJson.get("name").getAsString());
        assertEquals("world", zoneJson.get("world").getAsString());
        assertTrue(zoneJson.has("origin"));
        assertTrue(zoneJson.has("size"));
    }

    // --- get_zone ---

    @Test
    @DisplayName("get_zone returns error for missing zone field")
    void getZoneMissingField() {
        JsonObject response = handler.handleMessage("get_zone", new JsonObject());
        assertEquals("error", response.get("type").getAsString());
    }

    @Test
    @DisplayName("get_zone returns error for non-existent zone")
    void getZoneNotFound() {
        when(zoneManager.getZone("nonexistent")).thenReturn(null);

        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "nonexistent");

        JsonObject response = handler.handleMessage("get_zone", msg);
        assertEquals("error", response.get("type").getAsString());
        assertTrue(response.get("message").getAsString().contains("nonexistent"));
    }

    @Test
    @DisplayName("get_zone returns zone data")
    void getZoneReturnsData() {
        when(zoneManager.getZone("main")).thenReturn(testZone);
        when(entityPoolManager.getEntityCount("main")).thenReturn(16);

        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");

        JsonObject response = handler.handleMessage("get_zone", msg);

        assertEquals("zone", response.get("type").getAsString());
        assertEquals(16, response.get("entity_count").getAsInt());
    }

    // --- init_pool ---

    @Test
    @DisplayName("init_pool returns error for missing zone")
    void initPoolMissingZone() {
        JsonObject response = handler.handleMessage("init_pool", new JsonObject());
        assertEquals("error", response.get("type").getAsString());
    }

    @Test
    @DisplayName("init_pool initializes pool with defaults")
    void initPoolDefaults() {
        when(zoneManager.zoneExists("main")).thenReturn(true);

        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");

        JsonObject response = handler.handleMessage("init_pool", msg);

        assertEquals("pool_initialized", response.get("type").getAsString());
        assertEquals("main", response.get("zone").getAsString());
        assertEquals(16, response.get("count").getAsInt()); // default
        assertEquals("GLOWSTONE", response.get("material").getAsString()); // default
    }

    @Test
    @DisplayName("init_pool with custom count and material")
    void initPoolCustom() {
        when(zoneManager.zoneExists("main")).thenReturn(true);

        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");
        msg.addProperty("count", 32);
        msg.addProperty("material", "SEA_LANTERN");

        JsonObject response = handler.handleMessage("init_pool", msg);

        assertEquals(32, response.get("count").getAsInt());
        assertEquals("SEA_LANTERN", response.get("material").getAsString());
    }

    // --- batch_update ---

    @Nested
    @DisplayName("batch_update tests")
    class BatchUpdateTests {

        @Test
        @DisplayName("batch_update returns error for missing zone")
        void missingZone() {
            JsonObject response = handler.handleMessage("batch_update", new JsonObject());
            assertEquals("error", response.get("type").getAsString());
        }

        @Test
        @DisplayName("batch_update with entities returns count")
        void withEntities() {
            when(zoneManager.getZone("main")).thenReturn(testZone);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");

            JsonArray entities = new JsonArray();
            JsonObject entity1 = new JsonObject();
            entity1.addProperty("id", "block_0");
            entity1.addProperty("x", 0.5);
            entity1.addProperty("y", 0.3);
            entity1.addProperty("z", 0.5);
            entity1.addProperty("scale", 0.8);
            entities.add(entity1);
            msg.add("entities", entities);

            JsonObject response = handler.handleMessage("batch_update", msg);

            assertEquals("batch_updated", response.get("type").getAsString());
            assertEquals(1, response.get("updated").getAsInt());
        }

        @Test
        @DisplayName("batch_update with no entities returns 0 count")
        void noEntities() {
            when(zoneManager.getZone("main")).thenReturn(testZone);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");

            JsonObject response = handler.handleMessage("batch_update", msg);

            assertEquals("batch_updated", response.get("type").getAsString());
            assertEquals(0, response.get("updated").getAsInt());
        }

        @Test
        @DisplayName("batch_update calls batchUpdateEntities with all entity updates")
        void callsBatchUpdate() {
            when(zoneManager.getZone("main")).thenReturn(testZone);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");

            JsonArray entities = new JsonArray();
            for (int i = 0; i < 3; i++) {
                JsonObject e = new JsonObject();
                e.addProperty("id", "block_" + i);
                e.addProperty("x", 0.1 * i);
                e.addProperty("y", 0.2 * i);
                e.addProperty("z", 0.3 * i);
                entities.add(e);
            }
            msg.add("entities", entities);

            JsonObject response = handler.handleMessage("batch_update", msg);

            assertEquals(3, response.get("updated").getAsInt());
            // Verify single batch call instead of individual updateEntityPosition calls
            verify(entityPoolManager, times(1)).batchUpdateEntities(
                eq("main"), anyList());
        }
    }

    // --- set_visible ---

    @Test
    @DisplayName("set_visible returns error for missing fields")
    void setVisibleMissingFields() {
        JsonObject response = handler.handleMessage("set_visible", new JsonObject());
        assertEquals("error", response.get("type").getAsString());
    }

    @Test
    @DisplayName("set_visible updates visibility")
    void setVisibleUpdates() {
        when(zoneManager.zoneExists("main")).thenReturn(true);
        when(entityPoolManager.getEntityIds("main")).thenReturn(Set.of("block_0", "block_1"));

        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");
        msg.addProperty("visible", false);

        JsonObject response = handler.handleMessage("set_visible", msg);

        assertEquals("visibility_updated", response.get("type").getAsString());
        assertFalse(response.get("visible").getAsBoolean());
        verify(entityPoolManager, times(2)).setEntityVisible(eq("main"), anyString(), eq(false));
    }

    // --- set_render_mode ---

    @Test
    @DisplayName("set_render_mode rejects invalid mode")
    void renderModeInvalid() {
        when(zoneManager.zoneExists("main")).thenReturn(true);

        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");
        msg.addProperty("mode", "invalid_mode");

        JsonObject response = handler.handleMessage("set_render_mode", msg);

        assertEquals("error", response.get("type").getAsString());
        assertTrue(response.get("message").getAsString().contains("invalid_mode"));
    }

    // --- audio_state ---

    @Test
    @DisplayName("audio_state processes beat event")
    void audioStateProcessesBeat() {
        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");
        msg.addProperty("is_beat", true);
        msg.addProperty("beat_intensity", 0.9);

        JsonArray bands = new JsonArray();
        for (int i = 0; i < 5; i++) bands.add(0.5);
        msg.add("bands", bands);

        handler.handleMessage("audio_state", msg);

        verify(beatEventManager).processBeat(eq("main"), any(), eq(0.9));
    }

    @Test
    @DisplayName("audio_state without beat skips beat processing")
    void audioStateNoBeat() {
        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");
        msg.addProperty("is_beat", false);
        msg.addProperty("beat_intensity", 0.0);

        handler.handleMessage("audio_state", msg);

        verify(beatEventManager, never()).processBeat(anyString(), any(), anyDouble());
    }

    @Test
    @DisplayName("audio_state projects beat near phase edge with good tempo confidence")
    void audioStatePhaseAssistProjectsBeat() {
        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");
        msg.addProperty("is_beat", false);
        msg.addProperty("bpm", 140.0);
        msg.addProperty("tempo_confidence", 0.9);
        msg.addProperty("beat_phase", 0.95);
        msg.add("bands", fiveBands(0.4));

        handler.handleMessage("audio_state", msg);

        verify(beatEventManager, times(1)).processBeat(eq("main"), any(), anyDouble());
    }

    @Test
    @DisplayName("audio_state phase assist suppressed when confidence is low")
    void audioStatePhaseAssistSuppressedLowConfidence() {
        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");
        msg.addProperty("is_beat", false);
        msg.addProperty("bpm", 140.0);
        msg.addProperty("tempo_confidence", 0.3);
        msg.addProperty("beat_phase", 0.95);
        msg.add("bands", fiveBands(0.4));

        handler.handleMessage("audio_state", msg);

        verify(beatEventManager, never()).processBeat(anyString(), any(), anyDouble());
    }

    @Test
    @DisplayName("audio_state phase assist uses per-zone cooldown")
    void audioStatePhaseAssistUsesCooldown() {
        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");
        msg.addProperty("is_beat", false);
        msg.addProperty("bpm", 150.0);
        msg.addProperty("tempo_confidence", 0.9);
        msg.addProperty("beat_phase", 0.94);
        msg.add("bands", fiveBands(0.4));

        handler.handleMessage("audio_state", msg);
        handler.handleMessage("audio_state", msg);

        verify(beatEventManager, times(1)).processBeat(eq("main"), any(), anyDouble());
    }

    @Test
    @DisplayName("audio_state accepts tempo_conf alias and forwards phase metadata")
    void audioStateTempoConfAliasAndPhaseForwarded() {
        JsonObject msg = new JsonObject();
        msg.addProperty("zone", "main");
        msg.addProperty("is_beat", false);
        msg.addProperty("bpm", 128.0);
        msg.addProperty("tempo_conf", 0.82);
        msg.addProperty("beat_phase", 0.2);
        msg.addProperty("amplitude", 0.6);
        msg.add("bands", fiveBands(0.3));

        handler.handleMessage("audio_state", msg);

        var captor = org.mockito.ArgumentCaptor.forClass(com.audioviz.patterns.AudioState.class);
        verify(particleVizManager).updateAudioState(captor.capture());
        var state = captor.getValue();
        assertEquals(0.82, state.getTempoConfidence(), 1e-10);
        assertEquals(0.2, state.getBeatPhase(), 1e-10);
    }

    private static JsonArray fiveBands(double value) {
        JsonArray bands = new JsonArray();
        for (int i = 0; i < 5; i++) {
            bands.add(value);
        }
        return bands;
    }

    // --- Error responses ---

    @Test
    @DisplayName("Error responses have type 'error' and message field")
    void errorResponseFormat() {
        JsonObject response = handler.handleMessage("get_zone", new JsonObject());

        assertEquals("error", response.get("type").getAsString());
        assertTrue(response.has("message"));
        assertFalse(response.get("message").getAsString().isEmpty());
    }
}
