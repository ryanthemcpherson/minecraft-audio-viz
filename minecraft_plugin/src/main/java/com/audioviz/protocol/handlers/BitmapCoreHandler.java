package com.audioviz.protocol.handlers;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bitmap.BitmapFrameBuffer;
import com.audioviz.bitmap.BitmapPattern;
import com.audioviz.bitmap.BitmapPatternManager;
import com.audioviz.bitmap.BitmapRendererBackend;
import com.audioviz.bitmap.composition.CompositionManager;
import com.audioviz.lighting.AmbientLightManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Handles core bitmap rendering messages:
 * init_bitmap, teardown_bitmap, bitmap_frame, set_bitmap_pattern,
 * get_bitmap_patterns, get_bitmap_status.
 */
public class BitmapCoreHandler extends BaseHandler {

    public BitmapCoreHandler(AudioVizPlugin plugin) {
        super(plugin);
    }

    @Override
    public String[] getMessageTypes() {
        return new String[]{
            "init_bitmap", "teardown_bitmap", "bitmap_frame",
            "set_bitmap_pattern", "get_bitmap_patterns", "get_bitmap_status"
        };
    }

    @Override
    public JsonObject handle(String type, JsonObject message) {
        return switch (type) {
            case "init_bitmap" -> handleInitBitmap(message);
            case "teardown_bitmap" -> handleTeardownBitmap(message);
            case "bitmap_frame" -> handleBitmapFrame(message);
            case "set_bitmap_pattern" -> handleSetBitmapPattern(message);
            case "get_bitmap_patterns" -> handleGetBitmapPatterns();
            case "get_bitmap_status" -> handleGetBitmapStatus(message);
            default -> createError("Unknown message type: " + type);
        };
    }

    private JsonObject handleInitBitmap(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        boolean hasWidth = message.has("width") && message.get("width").getAsInt() > 0;
        boolean hasHeight = message.has("height") && message.get("height").getAsInt() > 0;
        String patternId = message.has("pattern") ? message.get("pattern").getAsString() : "bmp_spectrum";

        BitmapRendererBackend renderer = plugin.getBitmapRenderer();
        BitmapPatternManager patternMgr = plugin.getBitmapPatternManager();

        var zone = plugin.getZoneManager().getZone(zoneName);
        int[] actualDims;

        if (hasWidth && hasHeight) {
            // Explicit dimensions provided
            int width = Math.max(2, Math.min(128, message.get("width").getAsInt()));
            int height = Math.max(2, Math.min(128, message.get("height").getAsInt()));
            actualDims = renderer.initializeBitmapGrid(zone, width, height);
        } else {
            // Auto-size from zone geometry
            actualDims = renderer.initializeBitmapGrid(zone);
        }
        int actualWidth = actualDims[0];
        int actualHeight = actualDims[1];

        // Activate the pattern manager with the ACTUAL scaled dimensions
        patternMgr.activateZone(zoneName, patternId, actualWidth, actualHeight);

        // Register zone with composition manager for layers/transitions/sync
        CompositionManager comp = plugin.getCompositionManager();
        if (comp != null) {
            comp.registerZone(zoneName.toLowerCase(), actualWidth, actualHeight);
        }

        // Set zone backend to BITMAP in registry
        plugin.getRendererRegistry().setZoneBackends(zoneName,
            com.audioviz.render.RendererBackendType.BITMAP,
            com.audioviz.render.RendererBackendType.DISPLAY_ENTITIES);

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_initialized");
        response.addProperty("zone", zoneName);
        response.addProperty("width", actualWidth);
        response.addProperty("height", actualHeight);
        response.addProperty("pattern", patternMgr.getActivePatternId(zoneName));
        response.addProperty("pixel_count", actualWidth * actualHeight);
        response.addProperty("active", true);

        // Auto-init ambient lights for this zone
        AmbientLightManager ambientMgr = plugin.getAmbientLightManager();
        if (ambientMgr != null && !ambientMgr.hasZone(zoneName)) {
            var ambientZone = plugin.getZoneManager().getZone(zoneName);
            if (ambientZone != null) {
                ambientMgr.initializeZone(ambientZone);
            }
        }

        return response;
    }

    private JsonObject handleTeardownBitmap(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }

        // Deactivate pattern manager for this zone
        plugin.getBitmapPatternManager().deactivateZone(zoneName);

        // Teardown bitmap renderer (despawns TextDisplay entities)
        plugin.getBitmapRenderer().teardown(zoneName);

        // Revert zone backend to DISPLAY_ENTITIES
        plugin.getRendererRegistry().setZoneBackends(zoneName,
            com.audioviz.render.RendererBackendType.DISPLAY_ENTITIES,
            com.audioviz.render.RendererBackendType.DISPLAY_ENTITIES);

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_teardown");
        response.addProperty("zone", zoneName);
        return response;
    }

    private JsonObject handleBitmapFrame(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }

        BitmapRendererBackend renderer = plugin.getBitmapRenderer();
        if (!renderer.isBitmapZone(zoneName)) {
            return createError("Zone '" + zoneName + "' is not in bitmap mode. Call init_bitmap first.");
        }

        var config = renderer.getGridConfig(zoneName);
        int pixelCount = config.pixelCount();

        int[] pixels;

        if (message.has("pixels")) {
            // Base64-encoded binary format (fast path)
            try {
                String base64 = message.get("pixels").getAsString();
                byte[] bytes = Base64.getDecoder().decode(base64);

                if (bytes.length != pixelCount * 4) {
                    return createError("Pixel data size mismatch: expected " + (pixelCount * 4) +
                        " bytes, got " + bytes.length);
                }

                pixels = new int[pixelCount];
                ByteBuffer buf = ByteBuffer.wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < pixelCount; i++) {
                    pixels[i] = buf.getInt();
                }
            } catch (IllegalArgumentException e) {
                return createError("Invalid base64 pixel data: " + e.getMessage());
            }
        } else if (message.has("pixel_array")) {
            // JSON array format (debug-friendly)
            var arr = message.getAsJsonArray("pixel_array");
            pixels = new int[Math.min(arr.size(), pixelCount)];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = arr.get(i).getAsInt();
            }
        } else {
            return createError("Missing pixel data: provide 'pixels' (base64) or 'pixel_array' (JSON)");
        }

        // Parse optional per-pixel brightness
        int[] brightnessArray = null;
        if (message.has("brightness")) {
            try {
                String b64Brightness = message.get("brightness").getAsString();
                byte[] brightnessBytes = Base64.getDecoder().decode(b64Brightness);
                if (brightnessBytes.length == pixelCount) {
                    brightnessArray = new int[pixelCount];
                    for (int i = 0; i < pixelCount; i++) {
                        brightnessArray[i] = Math.max(0, Math.min(15, brightnessBytes[i] & 0xFF));
                    }
                }
            } catch (Exception e) {
                // Ignore malformed brightness, continue with null
            }
        }

        renderer.applyRawFrame(zoneName, pixels, brightnessArray);

        // Tick ambient lights based on frame luminance
        AmbientLightManager ambientMgr = plugin.getAmbientLightManager();
        if (ambientMgr != null && ambientMgr.hasZone(zoneName)) {
            float intensity = averagePixelLuminance(pixels);
            ambientMgr.tick(zoneName, intensity, false);
        }

        // Silent OK for high-frequency message
        return createOk();
    }

    private JsonObject handleSetBitmapPattern(JsonObject message) {
        if (!message.has("zone") || !message.has("pattern")) {
            return createError("Missing required fields: zone, pattern");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        String patternId = message.get("pattern").getAsString();

        BitmapPatternManager mgr = plugin.getBitmapPatternManager();
        if (!mgr.isActive(zoneName)) {
            return createError("Zone '" + zoneName + "' has no active bitmap. Call init_bitmap first.");
        }
        if (mgr.getPattern(patternId) == null) {
            return createError("Unknown bitmap pattern: " + patternId +
                ". Available: " + String.join(", ", mgr.getPatternIds()));
        }

        mgr.setPattern(zoneName, patternId);

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_pattern_set");
        response.addProperty("zone", zoneName);
        response.addProperty("pattern", patternId);
        return response;
    }

    private JsonObject handleGetBitmapPatterns() {
        BitmapPatternManager mgr = plugin.getBitmapPatternManager();

        JsonArray patterns = new JsonArray();
        for (String id : mgr.getPatternIds()) {
            var pattern = mgr.getPattern(id);
            JsonObject p = new JsonObject();
            p.addProperty("id", pattern.getId());
            p.addProperty("name", pattern.getName());
            p.addProperty("description", pattern.getDescription());
            patterns.add(p);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_patterns");
        response.add("patterns", patterns);
        return response;
    }

    private JsonObject handleGetBitmapStatus(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();

        BitmapRendererBackend renderer = plugin.getBitmapRenderer();
        BitmapPatternManager mgr = plugin.getBitmapPatternManager();

        JsonObject response = new JsonObject();
        response.addProperty("type", "bitmap_status");
        response.addProperty("zone", zoneName);
        response.addProperty("active", renderer.isBitmapZone(zoneName));

        if (renderer.isBitmapZone(zoneName)) {
            var config = renderer.getGridConfig(zoneName);
            response.addProperty("width", config.width());
            response.addProperty("height", config.height());
            response.addProperty("pixel_count", config.pixelCount());
            response.addProperty("interpolation_ticks", config.interpolationTicks());
            response.addProperty("pattern", mgr.getActivePatternId(zoneName));
        }

        return response;
    }

    private float averagePixelLuminance(int[] argbPixels) {
        if (argbPixels == null || argbPixels.length == 0) return 0f;
        long sum = 0;
        for (int argb : argbPixels) {
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            sum += (int)(0.2126 * r + 0.7152 * g + 0.0722 * b);
        }
        return (float)(sum / argbPixels.length) / 255.0f;
    }
}
