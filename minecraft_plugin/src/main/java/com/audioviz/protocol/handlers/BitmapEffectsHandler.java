package com.audioviz.protocol.handlers;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.bitmap.BitmapPatternManager;
import com.audioviz.bitmap.effects.ColorPalette;
import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.bitmap.effects.LayerCompositor;
import com.audioviz.bitmap.composition.CompositionManager;
import com.audioviz.bitmap.gamestate.FireworkPattern;
import com.audioviz.bitmap.media.DJLogoPattern;
import com.audioviz.bitmap.media.ImagePattern;
import com.audioviz.bitmap.text.ChatWallPattern;
import com.audioviz.bitmap.text.CountdownPattern;
import com.audioviz.bitmap.text.MarqueePattern;
import com.audioviz.bitmap.text.TrackDisplayPattern;
import com.audioviz.bitmap.transitions.TransitionManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Handles bitmap effects, transitions, text, layers, game integration,
 * image/media, and composition messages:
 * bitmap_transition, get_bitmap_transitions, bitmap_marquee,
 * bitmap_track_display, bitmap_countdown, bitmap_chat, bitmap_effects,
 * bitmap_palette, get_bitmap_palettes, bitmap_layer, bitmap_firework,
 * bitmap_image, bitmap_dj_logo, bitmap_composition.
 */
public class BitmapEffectsHandler extends BaseHandler {

    public BitmapEffectsHandler(AudioVizPlugin plugin) {
        super(plugin);
    }

    @Override
    public String[] getMessageTypes() {
        return new String[]{
            "bitmap_transition", "get_bitmap_transitions",
            "bitmap_marquee", "bitmap_track_display", "bitmap_countdown", "bitmap_chat",
            "bitmap_effects", "bitmap_palette", "get_bitmap_palettes",
            "bitmap_layer", "bitmap_firework", "bitmap_image", "bitmap_dj_logo",
            "bitmap_composition"
        };
    }

    @Override
    public JsonObject handle(String type, JsonObject message) {
        return switch (type) {
            case "bitmap_transition" -> handleBitmapTransition(message);
            case "get_bitmap_transitions" -> handleGetBitmapTransitions();
            case "bitmap_marquee" -> handleBitmapMarquee(message);
            case "bitmap_track_display" -> handleBitmapTrackDisplay(message);
            case "bitmap_countdown" -> handleBitmapCountdown(message);
            case "bitmap_chat" -> handleBitmapChat(message);
            case "bitmap_effects" -> handleBitmapEffects(message);
            case "bitmap_palette" -> handleBitmapPalette(message);
            case "get_bitmap_palettes" -> handleGetBitmapPalettes();
            case "bitmap_layer" -> handleBitmapLayer(message);
            case "bitmap_firework" -> handleBitmapFirework(message);
            case "bitmap_image" -> handleBitmapImage(message);
            case "bitmap_dj_logo" -> handleBitmapDjLogo(message);
            case "bitmap_composition" -> handleBitmapComposition(message);
            default -> createError("Unknown message type: " + type);
        };
    }

    // ========== Transitions ==========

    private JsonObject handleBitmapTransition(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String patternId = message.get("pattern").getAsString();
        String transitionId = message.has("transition") ? message.get("transition").getAsString() : "crossfade";
        int durationTicks = message.has("duration_ticks") ? message.get("duration_ticks").getAsInt() : 20;

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null || !patternMgr.isActive(zone)) {
            return createError("Bitmap zone not active: " + zone);
        }

        BitmapPattern newPattern = patternMgr.getPattern(patternId);
        if (newPattern == null) {
            return createError("Unknown pattern: " + patternId);
        }

        // Route through BitmapPatternManager which owns the render loop
        patternMgr.setPattern(zone, patternId, transitionId, durationTicks);

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_transition_started");
        response.addProperty("zone", zone);
        response.addProperty("pattern", patternId);
        response.addProperty("transition", transitionId);
        response.addProperty("duration_ticks", durationTicks);
        return response;
    }

    private JsonObject handleGetBitmapTransitions() {
        TransitionManager tm = plugin.getBitmapPatternManager().getTransitionManager();
        JsonArray transitions = new JsonArray();
        for (String id : tm.getTransitionIds()) {
            JsonObject t = new JsonObject();
            t.addProperty("id", id);
            t.addProperty("name", tm.getTransition(id).getName());
            transitions.add(t);
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_transitions");
        response.add("transitions", transitions);
        return response;
    }

    // ========== Text ==========

    private JsonObject handleBitmapMarquee(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String text = message.get("text").getAsString();
        int color = message.has("color") ? message.get("color").getAsInt() : 0xFFFFFFFF;

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        // Find or activate marquee pattern
        BitmapPattern pattern = patternMgr.getPattern("bmp_marquee");
        if (pattern instanceof MarqueePattern marquee) {
            marquee.queueMessage(text, color);

            // If zone is not running marquee, switch to it
            if (patternMgr.isActive(zone) && !"bmp_marquee".equals(patternMgr.getActivePatternId(zone))) {
                patternMgr.setPattern(zone, "bmp_marquee");
            }
        }

        return createOk();
    }

    private JsonObject handleBitmapTrackDisplay(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String artist = message.has("artist") ? message.get("artist").getAsString() : "";
        String title = message.has("title") ? message.get("title").getAsString() : "";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_track_display");
        if (pattern instanceof TrackDisplayPattern trackDisplay) {
            if (message.has("artist_color")) trackDisplay.setArtistColor(message.get("artist_color").getAsInt());
            if (message.has("title_color")) trackDisplay.setTitleColor(message.get("title_color").getAsInt());
            trackDisplay.setTrack(artist, title);
        }

        return createOk();
    }

    private JsonObject handleBitmapCountdown(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String action = message.has("action") ? message.get("action").getAsString() : "start";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_countdown");
        if (pattern instanceof CountdownPattern countdown) {
            switch (action) {
                case "start" -> {
                    int seconds = message.has("seconds") ? message.get("seconds").getAsInt() : 10;
                    countdown.start(seconds);
                    if (patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_countdown");
                    }
                }
                case "stop" -> countdown.stop();
            }
        }

        return createOk();
    }

    private JsonObject handleBitmapChat(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String playerName = message.has("player") ? message.get("player").getAsString() : "VJ";
        // Accept both "text" and "message" field names for flexibility
        String text = message.has("message") ? message.get("message").getAsString()
                    : message.has("text") ? message.get("text").getAsString() : "";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_chat_wall");
        if (pattern instanceof ChatWallPattern chatWall) {
            chatWall.addMessage(playerName, text);
        }

        return createOk();
    }

    // ========== Effects ==========

    private JsonObject handleBitmapEffects(JsonObject message) {
        String action = message.get("action").getAsString();

        EffectsProcessor effects = plugin.getGlobalBitmapEffects();
        if (effects == null) return createError("Bitmap effects not initialized");

        switch (action) {
            case "strobe" -> {
                effects.setStrobeEnabled(message.has("enabled") ? message.get("enabled").getAsBoolean() : true);
                if (message.has("divisor")) effects.setStrobeDivisor(message.get("divisor").getAsInt());
                if (message.has("color")) effects.setStrobeColor(message.get("color").getAsInt());
            }
            case "freeze" -> {
                boolean freeze = message.has("enabled") ? message.get("enabled").getAsBoolean() : true;
                if (freeze) {
                    // Freeze current frame of specified zone
                    String zone = message.has("zone") ? message.get("zone").getAsString().toLowerCase() : "";
                    BitmapFrameBuffer buf = plugin.getBitmapPatternManager().getFrameBuffer(zone);
                    if (buf != null) effects.freeze(buf);
                } else {
                    effects.unfreeze();
                }
            }
            case "brightness" -> effects.setBrightness(message.get("level").getAsDouble());
            case "blackout" -> effects.blackout(message.has("enabled") ? message.get("enabled").getAsBoolean() : true);
            case "wash" -> {
                int color = message.get("color").getAsInt();
                double opacity = message.has("opacity") ? message.get("opacity").getAsDouble() : 0.3;
                effects.setWash(color, opacity);
            }
            case "clear_wash" -> effects.clearWash();
            case "beat_flash" -> effects.setBeatFlashEnabled(
                message.has("enabled") ? message.get("enabled").getAsBoolean() : true);
            case "reset" -> effects.reset();
        }

        return createOk();
    }

    private JsonObject handleBitmapPalette(JsonObject message) {
        String paletteId = message.get("palette").getAsString();

        EffectsProcessor effects = plugin.getGlobalBitmapEffects();
        if (effects == null) return createError("Bitmap effects not initialized");

        if ("none".equals(paletteId) || "clear".equals(paletteId)) {
            effects.clearPalette();
        } else {
            ColorPalette palette = null;
            for (ColorPalette p : ColorPalette.BUILT_IN) {
                if (p.getId().equals(paletteId)) {
                    palette = p;
                    break;
                }
            }
            if (palette == null) return createError("Unknown palette: " + paletteId);
            effects.setPalette(palette);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_palette_set");
        response.addProperty("palette", paletteId);
        return response;
    }

    private JsonObject handleGetBitmapPalettes() {
        JsonArray palettes = new JsonArray();
        for (ColorPalette p : ColorPalette.BUILT_IN) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", p.getId());
            obj.addProperty("name", p.getName());
            palettes.add(obj);
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_palettes");
        response.add("palettes", palettes);
        return response;
    }

    // ========== Layers ==========

    private JsonObject handleBitmapLayer(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String action = message.get("action").getAsString();

        CompositionManager comp = plugin.getCompositionManager();
        if (comp == null) return createError("Composition manager not initialized");

        CompositionManager.ZoneState zoneState = comp.getZone(zone);
        if (zoneState == null) return createError("Zone not registered with composition manager: " + zone);

        switch (action) {
            case "set" -> {
                String patternId = message.get("pattern").getAsString();
                String blendMode = message.has("blend_mode") ? message.get("blend_mode").getAsString() : "ADDITIVE";
                double opacity = message.has("opacity") ? message.get("opacity").getAsDouble() : 0.5;

                BitmapPattern pattern = plugin.getBitmapPatternManager().getPattern(patternId);
                if (pattern == null) return createError("Unknown pattern: " + patternId);

                LayerCompositor.BlendMode mode;
                try {
                    mode = LayerCompositor.BlendMode.valueOf(blendMode.toUpperCase());
                } catch (IllegalArgumentException e) {
                    mode = LayerCompositor.BlendMode.ADDITIVE;
                }

                zoneState.setSecondaryLayer(pattern, mode, opacity);
            }
            case "clear" -> zoneState.clearSecondaryLayer();
            case "opacity" -> zoneState.secondaryOpacity = message.get("opacity").getAsDouble();
        }

        return createOk();
    }

    // ========== Game Integration ==========

    private JsonObject handleBitmapFirework(JsonObject message) {
        float x = message.has("x") ? message.get("x").getAsFloat() : 0.5f;
        float y = message.has("y") ? message.get("y").getAsFloat() : 0.3f;

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_fireworks");
        if (pattern instanceof FireworkPattern fireworks) {
            fireworks.spawn(x, y);
        }

        return createOk();
    }

    // ========== Image/Media ==========

    private JsonObject handleBitmapImage(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String action = message.has("action") ? message.get("action").getAsString() : "load";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_image");
        if (!(pattern instanceof ImagePattern imagePattern)) {
            return createError("Image pattern not registered");
        }

        switch (action) {
            case "load_pixels" -> {
                // Load from base64-encoded ARGB pixel array
                if (message.has("pixels")) {
                    String b64 = message.get("pixels").getAsString();
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    int[] pixels = new int[bytes.length / 4];
                    bb.asIntBuffer().get(pixels);

                    int w = message.get("width").getAsInt();
                    int h = message.get("height").getAsInt();
                    imagePattern.loadFromPixels(pixels, w, h);

                    if (patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_image");
                    }
                }
            }
            case "load_file" -> {
                String path = message.get("path").getAsString();
                java.io.File base = plugin.getDataFolder();
                java.io.File file = new java.io.File(base, path);
                try {
                    if (!file.getCanonicalFile().toPath().startsWith(base.getCanonicalFile().toPath())) {
                        plugin.getLogger().warning("Path traversal blocked: " + path);
                        break;
                    }
                } catch (java.io.IOException e) {
                    plugin.getLogger().warning("Invalid path: " + path);
                    break;
                }
                BitmapFrameBuffer buf = patternMgr.getFrameBuffer(zone);
                if (buf != null) {
                    imagePattern.loadFromFile(file, buf.getWidth(), buf.getHeight());
                    if (patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_image");
                    }
                }
            }
            case "set_mode" -> {
                String modeName = message.get("mode").getAsString().toUpperCase();
                try {
                    imagePattern.setMode(ImagePattern.ModulationMode.valueOf(modeName));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        return createOk();
    }

    private JsonObject handleBitmapDjLogo(JsonObject message) {
        String zone = message.get("zone").getAsString().toLowerCase();
        String action = message.has("action") ? message.get("action").getAsString() : "load_file";

        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();
        if (patternMgr == null) return createError("Bitmap not initialized");

        BitmapPattern pattern = patternMgr.getPattern("bmp_dj_logo");
        if (!(pattern instanceof DJLogoPattern logoPattern)) {
            return createError("DJ Logo pattern not registered");
        }

        switch (action) {
            case "load_file" -> {
                String path = message.get("path").getAsString();
                java.io.File base = plugin.getDataFolder();
                java.io.File file = new java.io.File(base, path);
                try {
                    if (!file.getCanonicalFile().toPath().startsWith(base.getCanonicalFile().toPath())) {
                        plugin.getLogger().warning("Path traversal blocked: " + path);
                        break;
                    }
                } catch (java.io.IOException e) {
                    plugin.getLogger().warning("Invalid path: " + path);
                    break;
                }
                BitmapFrameBuffer buf = patternMgr.getFrameBuffer(zone);
                if (buf != null) {
                    boolean ok = logoPattern.loadFromFile(file, buf.getWidth(), buf.getHeight());
                    if (ok && patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_dj_logo");
                    }
                    if (!ok) return createError("Failed to load image: " + path);
                } else {
                    return createError("Zone not active: " + zone);
                }
            }
            case "load_pixels" -> {
                if (message.has("pixels")) {
                    String b64 = message.get("pixels").getAsString();
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    int[] pixels = new int[bytes.length / 4];
                    bb.asIntBuffer().get(pixels);

                    int w = message.get("width").getAsInt();
                    int h = message.get("height").getAsInt();
                    logoPattern.loadFromPixels(pixels, w, h);

                    if (patternMgr.isActive(zone)) {
                        patternMgr.setPattern(zone, "bmp_dj_logo");
                    }
                }
            }
            case "set_mode" -> {
                String modeName = message.get("mode").getAsString().toUpperCase();
                try {
                    logoPattern.setMode(DJLogoPattern.LogoMode.valueOf(modeName));
                } catch (IllegalArgumentException ignored) {
                    return createError("Unknown mode: " + modeName);
                }
            }
            case "set_threshold" -> {
                int threshold = message.get("threshold").getAsInt();
                logoPattern.setThreshold(threshold);
            }
            case "set_palette" -> {
                String paletteId = message.get("palette").getAsString();
                ColorPalette found = null;
                for (ColorPalette p : ColorPalette.BUILT_IN) {
                    if (p.getId().equals(paletteId)) { found = p; break; }
                }
                if (found != null) {
                    logoPattern.setPalette(found);
                } else {
                    return createError("Unknown palette: " + paletteId);
                }
            }
        }

        return createOk();
    }

    // ========== Composition ==========

    private JsonObject handleBitmapComposition(JsonObject message) {
        String action = message.get("action").getAsString();

        CompositionManager comp = plugin.getCompositionManager();
        if (comp == null) return createError("Composition manager not initialized");

        switch (action) {
            case "set_sync_mode" -> {
                String mode = message.get("mode").getAsString().toUpperCase();
                try {
                    comp.setSyncMode(CompositionManager.SyncMode.valueOf(mode));
                } catch (IllegalArgumentException ignored) {}
                if (message.has("mirror_source")) {
                    comp.setMirrorSource(message.get("mirror_source").getAsString().toLowerCase());
                }
            }
            case "set_shared_palette" -> {
                String paletteId = message.get("palette").getAsString();
                ColorPalette palette = null;
                for (ColorPalette p : ColorPalette.BUILT_IN) {
                    if (p.getId().equals(paletteId)) { palette = p; break; }
                }
                if (palette != null) comp.setSharedPalette(palette);
                else comp.clearSharedPalette();
            }
            case "flash_all" -> {
                int color = message.has("color") ? message.get("color").getAsInt() : 0xFFFFFFFF;
                double intensity = message.has("intensity") ? message.get("intensity").getAsDouble() : 0.5;
                comp.flashAll(color, intensity);
            }
            case "get_zones" -> {
                JsonArray zones = new JsonArray();
                for (String z : comp.getZoneNames()) zones.add(z);
                JsonObject response = new JsonObject();
                response.addProperty("type", "bitmap_composition_zones");
                response.add("zones", zones);
                response.addProperty("sync_mode", comp.getSyncMode().name());
                return response;
            }
        }

        return createOk();
    }
}
