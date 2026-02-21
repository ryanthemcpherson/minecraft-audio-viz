package com.audioviz.protocol;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.*;
import com.audioviz.bitmap.composition.CompositionManager;
import com.audioviz.bitmap.effects.ColorPalette;
import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.bitmap.effects.LayerCompositor;
import com.audioviz.bitmap.gamestate.FireworkPattern;
import com.audioviz.bitmap.text.ChatWallPattern;
import com.audioviz.bitmap.text.CountdownPattern;
import com.audioviz.bitmap.text.MarqueePattern;
import com.audioviz.bitmap.text.TrackDisplayPattern;
import com.audioviz.bitmap.transitions.TransitionManager;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.particles.ParticleVisualizationManager;
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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for bitmap protocol message handlers in MessageHandler.
 *
 * <p>Covers the 13 bitmap message types added in Phase 3:
 * transitions, text/marquee, effects, palettes, layers,
 * game integration (fireworks), and image/composition handlers.
 */
class BitmapMessageHandlerTest {

    @Mock private AudioVizPlugin plugin;
    @Mock private ZoneManager zoneManager;
    @Mock private EntityPoolManager entityPoolManager;
    @Mock private ParticleVisualizationManager particleVizManager;
    @Mock private BeatEventManager beatEventManager;
    @Mock private RendererRegistry rendererRegistry;
    @Mock private BitmapPatternManager bitmapPatternManager;
    @Mock private BitmapRendererBackend bitmapRenderer;
    @Mock private CompositionManager compositionManager;
    @Mock private EffectsProcessor effectsProcessor;
    @Mock private TransitionManager transitionManager;
    @Mock private World mockWorld;

    private MessageHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Core subsystems
        when(plugin.getZoneManager()).thenReturn(zoneManager);
        when(plugin.getEntityPoolManager()).thenReturn(entityPoolManager);
        when(plugin.getParticleVisualizationManager()).thenReturn(particleVizManager);
        when(plugin.getBeatEventManager()).thenReturn(beatEventManager);
        when(plugin.getRendererRegistry()).thenReturn(rendererRegistry);

        // Bitmap subsystems
        when(plugin.getBitmapPatternManager()).thenReturn(bitmapPatternManager);
        when(plugin.getBitmapRenderer()).thenReturn(bitmapRenderer);
        when(plugin.getCompositionManager()).thenReturn(compositionManager);
        when(plugin.getGlobalBitmapEffects()).thenReturn(effectsProcessor);
        when(bitmapPatternManager.getTransitionManager()).thenReturn(transitionManager);

        // Default zone state
        when(mockWorld.getName()).thenReturn("world");
        when(bitmapPatternManager.isActive("main")).thenReturn(true);

        handler = new MessageHandler(plugin);
    }

    // ========== Transition Protocol ==========

    @Nested
    @DisplayName("Bitmap Transitions")
    class TransitionTests {

        @Test
        @DisplayName("bitmap_transition returns started confirmation")
        void transitionReturnsStarted() {
            BitmapPattern mockPattern = mock(BitmapPattern.class);
            when(bitmapPatternManager.getPattern("bmp_plasma")).thenReturn(mockPattern);
            when(compositionManager.getZone("main")).thenReturn(null); // No composition zone

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("pattern", "bmp_plasma");
            msg.addProperty("transition", "crossfade");
            msg.addProperty("duration_ticks", 40);

            JsonObject response = handler.handleMessage("bitmap_transition", msg);

            assertEquals("bitmap_transition_started", response.get("type").getAsString());
            assertEquals("main", response.get("zone").getAsString());
            assertEquals("bmp_plasma", response.get("pattern").getAsString());
            assertEquals("crossfade", response.get("transition").getAsString());
            assertEquals(40, response.get("duration_ticks").getAsInt());
        }

        @Test
        @DisplayName("bitmap_transition with inactive zone returns error")
        void transitionInactiveZoneReturnsError() {
            when(bitmapPatternManager.isActive("dead_zone")).thenReturn(false);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "dead_zone");
            msg.addProperty("pattern", "bmp_plasma");

            JsonObject response = handler.handleMessage("bitmap_transition", msg);

            assertEquals("error", response.get("type").getAsString());
            assertTrue(response.get("message").getAsString().contains("not active"));
        }

        @Test
        @DisplayName("bitmap_transition with unknown pattern returns error")
        void transitionUnknownPatternReturnsError() {
            when(bitmapPatternManager.getPattern("nonexistent")).thenReturn(null);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("pattern", "nonexistent");

            JsonObject response = handler.handleMessage("bitmap_transition", msg);

            assertEquals("error", response.get("type").getAsString());
            assertTrue(response.get("message").getAsString().contains("Unknown pattern"));
        }

        @Test
        @DisplayName("bitmap_transition defaults to crossfade with 20 tick duration")
        void transitionUsesDefaults() {
            BitmapPattern mockPattern = mock(BitmapPattern.class);
            when(bitmapPatternManager.getPattern("bmp_waveform")).thenReturn(mockPattern);
            when(compositionManager.getZone("main")).thenReturn(null);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("pattern", "bmp_waveform");
            // No transition or duration_ticks fields

            JsonObject response = handler.handleMessage("bitmap_transition", msg);

            assertEquals("crossfade", response.get("transition").getAsString());
            assertEquals(20, response.get("duration_ticks").getAsInt());
        }

        @Test
        @DisplayName("get_bitmap_transitions returns transition list")
        void getTransitionsReturnsList() {
            when(transitionManager.getTransitionIds()).thenReturn(
                List.of("crossfade", "wipe_left", "dissolve"));

            com.audioviz.bitmap.transitions.BitmapTransition mockTrans =
                mock(com.audioviz.bitmap.transitions.BitmapTransition.class);
            when(mockTrans.getName()).thenReturn("Crossfade");
            when(transitionManager.getTransition(anyString())).thenReturn(mockTrans);

            JsonObject response = handler.handleMessage("get_bitmap_transitions", new JsonObject());

            assertEquals("bitmap_transitions", response.get("type").getAsString());
            JsonArray transitions = response.getAsJsonArray("transitions");
            assertEquals(3, transitions.size());
            assertTrue(transitions.get(0).getAsJsonObject().has("id"));
            assertTrue(transitions.get(0).getAsJsonObject().has("name"));
        }
    }

    // ========== Text/Marquee Protocol ==========

    @Nested
    @DisplayName("Bitmap Text Handlers")
    class TextTests {

        @Test
        @DisplayName("bitmap_marquee queues message on MarqueePattern")
        void marqueeQueuesMessage() {
            MarqueePattern marquee = mock(MarqueePattern.class);
            when(bitmapPatternManager.getPattern("bmp_marquee")).thenReturn(marquee);
            when(bitmapPatternManager.getActivePatternId("main")).thenReturn("bmp_spectrum_bars");

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("text", "HELLO WORLD");
            msg.addProperty("color", 0xFFFF0000);

            JsonObject response = handler.handleMessage("bitmap_marquee", msg);

            assertEquals("ok", response.get("type").getAsString());
            verify(marquee).queueMessage("HELLO WORLD", 0xFFFF0000);
        }

        @Test
        @DisplayName("bitmap_marquee switches zone to marquee pattern if different")
        void marqueeSwitchesToPattern() {
            MarqueePattern marquee = mock(MarqueePattern.class);
            when(bitmapPatternManager.getPattern("bmp_marquee")).thenReturn(marquee);
            when(bitmapPatternManager.isActive("main")).thenReturn(true);
            when(bitmapPatternManager.getActivePatternId("main")).thenReturn("bmp_plasma");

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("text", "TEST");

            handler.handleMessage("bitmap_marquee", msg);

            verify(bitmapPatternManager).setPattern("main", "bmp_marquee");
        }

        @Test
        @DisplayName("bitmap_track_display sets artist and title")
        void trackDisplaySetsFields() {
            TrackDisplayPattern trackDisplay = mock(TrackDisplayPattern.class);
            when(bitmapPatternManager.getPattern("bmp_track_display")).thenReturn(trackDisplay);
            when(bitmapPatternManager.isActive("main")).thenReturn(true);
            when(bitmapPatternManager.getActivePatternId("main")).thenReturn("bmp_track_display");

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("artist", "Daft Punk");
            msg.addProperty("title", "Around The World");

            JsonObject response = handler.handleMessage("bitmap_track_display", msg);

            assertEquals("ok", response.get("type").getAsString());
            verify(trackDisplay).setTrack("Daft Punk", "Around The World");
        }

        @Test
        @DisplayName("bitmap_countdown starts countdown")
        void countdownStartsTimer() {
            CountdownPattern countdown = mock(CountdownPattern.class);
            when(bitmapPatternManager.getPattern("bmp_countdown")).thenReturn(countdown);
            when(bitmapPatternManager.isActive("main")).thenReturn(true);
            when(bitmapPatternManager.getActivePatternId("main")).thenReturn("bmp_countdown");

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("seconds", 10);

            JsonObject response = handler.handleMessage("bitmap_countdown", msg);

            assertEquals("ok", response.get("type").getAsString());
            verify(countdown).start(eq(10));
        }

        @Test
        @DisplayName("bitmap_chat adds message to ChatWallPattern")
        void chatAddsMessage() {
            ChatWallPattern chat = mock(ChatWallPattern.class);
            when(bitmapPatternManager.getPattern("bmp_chat_wall")).thenReturn(chat);
            when(bitmapPatternManager.isActive("main")).thenReturn(true);
            when(bitmapPatternManager.getActivePatternId("main")).thenReturn("bmp_chat_wall");

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("player", "Steve");
            msg.addProperty("message", "gg");

            JsonObject response = handler.handleMessage("bitmap_chat", msg);

            assertEquals("ok", response.get("type").getAsString());
            verify(chat).addMessage("Steve", "gg");
        }
    }

    // ========== Effects Protocol ==========

    @Nested
    @DisplayName("Bitmap Effects Handlers")
    class EffectsTests {

        @Test
        @DisplayName("bitmap_effects strobe action configures strobe")
        void strobeConfiguresEffect() {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "strobe");
            msg.addProperty("enabled", true);
            msg.addProperty("divisor", 4);
            msg.addProperty("color", 0xFFFFFFFF);

            JsonObject response = handler.handleMessage("bitmap_effects", msg);

            assertEquals("ok", response.get("type").getAsString());
            verify(effectsProcessor).setStrobeEnabled(true);
            verify(effectsProcessor).setStrobeDivisor(4);
            verify(effectsProcessor).setStrobeColor(0xFFFFFFFF);
        }

        @Test
        @DisplayName("bitmap_effects brightness sets level")
        void brightnessSetslevel() {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "brightness");
            msg.addProperty("level", 0.5);

            JsonObject response = handler.handleMessage("bitmap_effects", msg);

            assertEquals("ok", response.get("type").getAsString());
            verify(effectsProcessor).setBrightness(0.5);
        }

        @Test
        @DisplayName("bitmap_effects blackout enables blackout")
        void blackoutWorks() {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "blackout");
            msg.addProperty("enabled", true);

            handler.handleMessage("bitmap_effects", msg);

            verify(effectsProcessor).blackout(true);
        }

        @Test
        @DisplayName("bitmap_effects wash sets color and opacity")
        void washSetsColorOpacity() {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "wash");
            msg.addProperty("color", 0xFFFF0000);
            msg.addProperty("opacity", 0.7);

            handler.handleMessage("bitmap_effects", msg);

            verify(effectsProcessor).setWash(0xFFFF0000, 0.7);
        }

        @Test
        @DisplayName("bitmap_effects reset resets all effects")
        void resetClearsEffects() {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "reset");

            handler.handleMessage("bitmap_effects", msg);

            verify(effectsProcessor).reset();
        }

        @Test
        @DisplayName("bitmap_effects with null processor returns error")
        void nullProcessorReturnsError() {
            when(plugin.getGlobalBitmapEffects()).thenReturn(null);
            handler = new MessageHandler(plugin);

            JsonObject msg = new JsonObject();
            msg.addProperty("action", "brightness");
            msg.addProperty("level", 0.5);

            JsonObject response = handler.handleMessage("bitmap_effects", msg);

            assertEquals("error", response.get("type").getAsString());
        }

        @Test
        @DisplayName("bitmap_effects freeze captures zone frame buffer")
        void freezeSnapshotsBuffer() {
            BitmapFrameBuffer mockBuf = mock(BitmapFrameBuffer.class);
            when(bitmapPatternManager.getFrameBuffer("main")).thenReturn(mockBuf);

            JsonObject msg = new JsonObject();
            msg.addProperty("action", "freeze");
            msg.addProperty("enabled", true);
            msg.addProperty("zone", "main");

            handler.handleMessage("bitmap_effects", msg);

            verify(effectsProcessor).freeze(mockBuf);
        }
    }

    // ========== Palette Protocol ==========

    @Nested
    @DisplayName("Bitmap Palette Handlers")
    class PaletteTests {

        @Test
        @DisplayName("bitmap_palette sets known palette")
        void setPaletteKnown() {
            // ColorPalette.BUILT_IN has at least "fire", "ocean", "neon", etc.
            String validPaletteId = ColorPalette.BUILT_IN[0].getId();

            JsonObject msg = new JsonObject();
            msg.addProperty("palette", validPaletteId);

            JsonObject response = handler.handleMessage("bitmap_palette", msg);

            assertEquals("bitmap_palette_set", response.get("type").getAsString());
            assertEquals(validPaletteId, response.get("palette").getAsString());
            verify(effectsProcessor).setPalette(any(ColorPalette.class));
        }

        @Test
        @DisplayName("bitmap_palette 'none' clears palette")
        void clearPaletteWithNone() {
            JsonObject msg = new JsonObject();
            msg.addProperty("palette", "none");

            JsonObject response = handler.handleMessage("bitmap_palette", msg);

            assertEquals("bitmap_palette_set", response.get("type").getAsString());
            verify(effectsProcessor).clearPalette();
        }

        @Test
        @DisplayName("bitmap_palette 'clear' clears palette")
        void clearPaletteWithClear() {
            JsonObject msg = new JsonObject();
            msg.addProperty("palette", "clear");

            handler.handleMessage("bitmap_palette", msg);

            verify(effectsProcessor).clearPalette();
        }

        @Test
        @DisplayName("bitmap_palette unknown returns error")
        void unknownPaletteReturnsError() {
            JsonObject msg = new JsonObject();
            msg.addProperty("palette", "totally_made_up");

            JsonObject response = handler.handleMessage("bitmap_palette", msg);

            assertEquals("error", response.get("type").getAsString());
            assertTrue(response.get("message").getAsString().contains("Unknown palette"));
        }

        @Test
        @DisplayName("get_bitmap_palettes returns all built-ins")
        void getPalettesReturnsAll() {
            JsonObject response = handler.handleMessage("get_bitmap_palettes", new JsonObject());

            assertEquals("bitmap_palettes", response.get("type").getAsString());
            JsonArray palettes = response.getAsJsonArray("palettes");
            assertTrue(palettes.size() >= 4, "Should have at least 4 built-in palettes");

            // Each palette should have id and name
            for (int i = 0; i < palettes.size(); i++) {
                JsonObject p = palettes.get(i).getAsJsonObject();
                assertNotNull(p.get("id"));
                assertNotNull(p.get("name"));
                assertFalse(p.get("id").getAsString().isEmpty());
                assertFalse(p.get("name").getAsString().isEmpty());
            }
        }
    }

    // ========== Layer Protocol ==========

    @Nested
    @DisplayName("Bitmap Layer Handlers")
    class LayerTests {

        @Test
        @DisplayName("bitmap_layer with no composition manager returns error")
        void noCompositionReturnsError() {
            when(plugin.getCompositionManager()).thenReturn(null);
            handler = new MessageHandler(plugin);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("action", "set");
            msg.addProperty("pattern", "bmp_plasma");

            JsonObject response = handler.handleMessage("bitmap_layer", msg);

            assertEquals("error", response.get("type").getAsString());
        }

        @Test
        @DisplayName("bitmap_layer set with unknown zone returns error")
        void unknownZoneReturnsError() {
            when(compositionManager.getZone("unknown")).thenReturn(null);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "unknown");
            msg.addProperty("action", "set");
            msg.addProperty("pattern", "bmp_plasma");

            JsonObject response = handler.handleMessage("bitmap_layer", msg);

            assertEquals("error", response.get("type").getAsString());
        }
    }

    // ========== Game Integration Protocol ==========

    @Nested
    @DisplayName("Bitmap Game Integration Handlers")
    class GameTests {

        @Test
        @DisplayName("bitmap_firework spawns at specified coordinates")
        void fireworkSpawnsAtCoords() {
            FireworkPattern fireworks = mock(FireworkPattern.class);
            when(bitmapPatternManager.getPattern("bmp_fireworks")).thenReturn(fireworks);

            JsonObject msg = new JsonObject();
            msg.addProperty("x", 0.3f);
            msg.addProperty("y", 0.7f);

            JsonObject response = handler.handleMessage("bitmap_firework", msg);

            assertEquals("ok", response.get("type").getAsString());
            verify(fireworks).spawn(0.3f, 0.7f);
        }

        @Test
        @DisplayName("bitmap_firework uses default coordinates when not specified")
        void fireworkUsesDefaults() {
            FireworkPattern fireworks = mock(FireworkPattern.class);
            when(bitmapPatternManager.getPattern("bmp_fireworks")).thenReturn(fireworks);

            JsonObject msg = new JsonObject();

            handler.handleMessage("bitmap_firework", msg);

            verify(fireworks).spawn(0.5f, 0.3f);
        }
    }

    // ========== Pattern Management Protocol ==========

    @Nested
    @DisplayName("Bitmap Pattern Management")
    class PatternManagementTests {

        @Test
        @DisplayName("set_bitmap_pattern on active zone succeeds")
        void setPatternSucceeds() {
            BitmapPattern mockPattern = mock(BitmapPattern.class);
            when(bitmapPatternManager.getPattern("bmp_plasma")).thenReturn(mockPattern);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("pattern", "bmp_plasma");

            JsonObject response = handler.handleMessage("set_bitmap_pattern", msg);

            assertEquals("bitmap_pattern_set", response.get("type").getAsString());
            assertEquals("main", response.get("zone").getAsString());
            assertEquals("bmp_plasma", response.get("pattern").getAsString());
            verify(bitmapPatternManager).setPattern("main", "bmp_plasma");
        }

        @Test
        @DisplayName("set_bitmap_pattern on inactive zone returns error")
        void setPatternInactiveZoneReturnsError() {
            when(bitmapPatternManager.isActive("dead")).thenReturn(false);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "dead");
            msg.addProperty("pattern", "bmp_plasma");

            JsonObject response = handler.handleMessage("set_bitmap_pattern", msg);

            assertEquals("error", response.get("type").getAsString());
            assertTrue(response.get("message").getAsString().contains("init_bitmap"));
        }

        @Test
        @DisplayName("set_bitmap_pattern with missing fields returns error")
        void setPatternMissingFieldsReturnsError() {
            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            // Missing "pattern" field

            JsonObject response = handler.handleMessage("set_bitmap_pattern", msg);

            assertEquals("error", response.get("type").getAsString());
        }

        @Test
        @DisplayName("set_bitmap_pattern with unknown pattern returns error")
        void setPatternUnknownReturnsError() {
            when(bitmapPatternManager.getPattern("fake")).thenReturn(null);
            when(bitmapPatternManager.getPatternIds()).thenReturn(List.of("bmp_plasma", "bmp_waveform"));

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");
            msg.addProperty("pattern", "fake");

            JsonObject response = handler.handleMessage("set_bitmap_pattern", msg);

            assertEquals("error", response.get("type").getAsString());
            assertTrue(response.get("message").getAsString().contains("Unknown bitmap pattern"));
            assertTrue(response.get("message").getAsString().contains("bmp_plasma"));
        }

        @Test
        @DisplayName("get_bitmap_patterns returns all registered patterns")
        void getPatternsList() {
            BitmapPattern p1 = mock(BitmapPattern.class);
            when(p1.getId()).thenReturn("bmp_plasma");
            when(p1.getName()).thenReturn("Plasma");
            when(p1.getDescription()).thenReturn("Audio-reactive plasma");

            BitmapPattern p2 = mock(BitmapPattern.class);
            when(p2.getId()).thenReturn("bmp_waveform");
            when(p2.getName()).thenReturn("Waveform");
            when(p2.getDescription()).thenReturn("Oscilloscope display");

            when(bitmapPatternManager.getPatternIds()).thenReturn(List.of("bmp_plasma", "bmp_waveform"));
            when(bitmapPatternManager.getPattern("bmp_plasma")).thenReturn(p1);
            when(bitmapPatternManager.getPattern("bmp_waveform")).thenReturn(p2);

            JsonObject response = handler.handleMessage("get_bitmap_patterns", new JsonObject());

            assertEquals("bitmap_patterns", response.get("type").getAsString());
            JsonArray patterns = response.getAsJsonArray("patterns");
            assertEquals(2, patterns.size());
            assertEquals("bmp_plasma", patterns.get(0).getAsJsonObject().get("id").getAsString());
            assertEquals("Plasma", patterns.get(0).getAsJsonObject().get("name").getAsString());
        }

        @Test
        @DisplayName("get_bitmap_status returns zone info when active")
        void getBitmapStatusActive() {
            when(bitmapRenderer.isBitmapZone("main")).thenReturn(true);
            when(bitmapPatternManager.getActivePatternId("main")).thenReturn("bmp_spectrum_bars");

            BitmapRendererBackend.BitmapGridConfig config = mock(BitmapRendererBackend.BitmapGridConfig.class);
            when(config.width()).thenReturn(48);
            when(config.height()).thenReturn(27);
            when(config.pixelCount()).thenReturn(1296);
            when(config.interpolationTicks()).thenReturn(2);
            when(bitmapRenderer.getGridConfig("main")).thenReturn(config);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "main");

            JsonObject response = handler.handleMessage("get_bitmap_status", msg);

            assertEquals("bitmap_status", response.get("type").getAsString());
            assertTrue(response.get("active").getAsBoolean());
            assertEquals(48, response.get("width").getAsInt());
            assertEquals(27, response.get("height").getAsInt());
            assertEquals(1296, response.get("pixel_count").getAsInt());
            assertEquals("bmp_spectrum_bars", response.get("pattern").getAsString());
        }

        @Test
        @DisplayName("get_bitmap_status returns inactive for non-bitmap zone")
        void getBitmapStatusInactive() {
            when(bitmapRenderer.isBitmapZone("regular")).thenReturn(false);

            JsonObject msg = new JsonObject();
            msg.addProperty("zone", "regular");

            JsonObject response = handler.handleMessage("get_bitmap_status", msg);

            assertEquals("bitmap_status", response.get("type").getAsString());
            assertFalse(response.get("active").getAsBoolean());
            assertFalse(response.has("width"));
        }
    }
}
