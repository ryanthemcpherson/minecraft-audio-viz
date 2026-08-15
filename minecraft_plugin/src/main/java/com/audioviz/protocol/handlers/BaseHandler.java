package com.audioviz.protocol.handlers;

import com.audioviz.AudioVizPlugin;
import com.audioviz.zones.VisualizationZone;
import com.google.gson.JsonObject;

import java.util.regex.Pattern;

/**
 * Base class for typed message handlers providing shared utilities.
 */
public abstract class BaseHandler implements TypedMessageHandler {

    protected final AudioVizPlugin plugin;

    /** Valid zone/stage name: 1-64 alphanumeric characters, underscores, and hyphens. */
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final int MAX_NAME_LENGTH = 64;

    protected BaseHandler(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Validate a zone or stage name from WebSocket input.
     * Must be non-null, non-empty, max 64 chars, and match [a-zA-Z0-9_-]+.
     */
    protected static boolean isValidZoneName(String name) {
        return name != null
            && !name.isEmpty()
            && name.length() <= MAX_NAME_LENGTH
            && VALID_NAME_PATTERN.matcher(name).matches();
    }

    protected JsonObject createError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        return error;
    }

    protected JsonObject createOk() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "ok");
        return response;
    }

    /**
     * Convert a VisualizationZone to JSON representation.
     */
    protected JsonObject zoneToJson(VisualizationZone zone) {
        JsonObject json = new JsonObject();
        json.addProperty("name", zone.getName());
        json.addProperty("id", zone.getId().toString());
        json.addProperty("world", zone.getWorld().getName());

        JsonObject origin = new JsonObject();
        origin.addProperty("x", zone.getOrigin().getX());
        origin.addProperty("y", zone.getOrigin().getY());
        origin.addProperty("z", zone.getOrigin().getZ());
        json.add("origin", origin);

        JsonObject size = new JsonObject();
        size.addProperty("x", zone.getSize().getX());
        size.addProperty("y", zone.getSize().getY());
        size.addProperty("z", zone.getSize().getZ());
        json.add("size", size);

        json.addProperty("rotation", zone.getRotation());
        json.addProperty("glow_on_beat", zone.isGlowOnBeat());
        json.addProperty("dynamic_brightness", zone.isDynamicBrightness());

        return json;
    }
}
