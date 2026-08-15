package com.audioviz.protocol.handlers;

import com.audioviz.AudioVizPlugin;
import com.audioviz.bedrock.BedrockSupport;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.particles.ParticleVisualizationManager;
import com.audioviz.render.RendererBackendType;
import com.audioviz.render.RendererRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Handles render mode and renderer backend messages:
 * set_render_mode, set_renderer_backend, renderer_capabilities,
 * get_renderer_capabilities, set_hologram_config.
 */
public class RenderModeHandler extends BaseHandler {

    public RenderModeHandler(AudioVizPlugin plugin) {
        super(plugin);
    }

    @Override
    public String[] getMessageTypes() {
        return new String[]{
            "set_render_mode", "set_renderer_backend",
            "renderer_capabilities", "get_renderer_capabilities",
            "set_hologram_config"
        };
    }

    @Override
    public JsonObject handle(String type, JsonObject message) {
        return switch (type) {
            case "set_render_mode" -> handleSetRenderMode(message);
            case "set_renderer_backend" -> handleSetRendererBackend(message);
            case "renderer_capabilities", "get_renderer_capabilities" -> handleRendererCapabilities(message);
            case "set_hologram_config" -> handleSetHologramConfig(message);
            default -> createError("Unknown message type: " + type);
        };
    }

    private JsonObject handleSetRenderMode(JsonObject message) {
        if (!message.has("zone") || !message.has("mode")) {
            return createError("Missing required field: zone or mode");
        }
        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        String mode = message.get("mode").getAsString();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        // Valid modes: "entities", "particles", "hybrid"
        if (!mode.equals("entities") && !mode.equals("particles") && !mode.equals("hybrid")) {
            return createError("Invalid render mode: " + mode + ". Use 'entities', 'particles', or 'hybrid'");
        }

        applyLegacyModeToRuntime(zoneName, mode);

        // Keep renderer backend registry in sync with legacy render mode control.
        RendererRegistry rendererRegistry = plugin.getRendererRegistry();
        if (mode.equals("particles")) {
            rendererRegistry.setZoneBackends(
                zoneName,
                RendererBackendType.PARTICLES,
                RendererBackendType.DISPLAY_ENTITIES
            );
        } else {
            rendererRegistry.setZoneBackends(
                zoneName,
                RendererBackendType.DISPLAY_ENTITIES,
                RendererBackendType.PARTICLES
            );
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "render_mode_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("mode", mode);
        response.addProperty("active_backend", rendererRegistry.getActiveBackend(zoneName).key());
        response.addProperty("fallback_backend", rendererRegistry.getFallbackBackend(zoneName).key());

        return response;
    }

    private JsonObject handleSetRendererBackend(JsonObject message) {
        if (!message.has("zone") || !message.has("backend")) {
            return createError("Missing required field: zone or backend");
        }

        String zoneName = message.get("zone").getAsString();
        if (!isValidZoneName(zoneName)) {
            return createError("Invalid zone name");
        }
        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        RendererBackendType backendType = RendererBackendType.fromKey(message.get("backend").getAsString());
        if (backendType == null) {
            return createError("Invalid renderer backend");
        }

        RendererRegistry rendererRegistry = plugin.getRendererRegistry();
        if (!rendererRegistry.isBackendSupported(backendType)) {
            return createError("Backend not supported on this server: " + backendType.key());
        }

        RendererBackendType fallbackType = RendererBackendType.DISPLAY_ENTITIES;
        if (message.has("fallback_backend")) {
            RendererBackendType requestedFallback = RendererBackendType.fromKey(
                message.get("fallback_backend").getAsString()
            );
            if (requestedFallback != null) {
                fallbackType = requestedFallback;
            }
        }

        rendererRegistry.setZoneBackends(zoneName, backendType, fallbackType);
        RendererBackendType active = rendererRegistry.getActiveBackend(zoneName);
        RendererBackendType effective = rendererRegistry.getEffectiveBackend(zoneName);
        boolean usingFallback = active != effective;

        // Apply what is currently executable in runtime.
        if (effective == RendererBackendType.PARTICLES) {
            applyLegacyModeToRuntime(zoneName, "particles");
        } else if (effective == RendererBackendType.DISPLAY_ENTITIES) {
            applyLegacyModeToRuntime(zoneName, "entities");
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "renderer_backend_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("backend", active.key());
        response.addProperty("fallback_backend", rendererRegistry.getFallbackBackend(zoneName).key());
        response.addProperty("effective_backend", effective.key());
        response.addProperty("using_fallback", usingFallback);

        if (active == RendererBackendType.HOLOGRAM && usingFallback) {
            response.addProperty("note", "Hologram backend selected but currently falling back");
        }

        return response;
    }

    private JsonObject handleRendererCapabilities(JsonObject message) {
        String zoneName = message.has("zone") ? message.get("zone").getAsString() : "main";
        RendererRegistry rendererRegistry = plugin.getRendererRegistry();

        JsonObject response = new JsonObject();
        response.addProperty("type", "renderer_capabilities");
        response.addProperty("zone", zoneName);

        JsonArray supported = new JsonArray();
        for (String key : rendererRegistry.getSupportedBackendKeys()) {
            supported.add(key);
        }
        response.add("supported_backends", supported);

        JsonArray experimental = new JsonArray();
        for (String key : rendererRegistry.getExperimentalBackendKeys()) {
            experimental.add(key);
        }
        response.add("experimental_backends", experimental);

        response.addProperty("active_backend", rendererRegistry.getActiveBackend(zoneName).key());
        response.addProperty("fallback_backend", rendererRegistry.getFallbackBackend(zoneName).key());

        JsonObject providers = new JsonObject();
        JsonObject hologramProvider = new JsonObject();
        hologramProvider.addProperty("available", rendererRegistry.isHologramProviderAvailable());
        hologramProvider.addProperty("provider", rendererRegistry.getHologramProviderName());
        hologramProvider.addProperty("implemented", rendererRegistry.isHologramBackendImplemented());
        providers.add("hologram", hologramProvider);
        response.add("providers", providers);

        // Bitmap backend info
        JsonObject bitmap = new JsonObject();
        bitmap.addProperty("implemented", true);
        bitmap.addProperty("active_zones", plugin.getBitmapRenderer() != null ?
            (plugin.getBitmapRenderer().isBitmapZone(zoneName)) : false);
        JsonArray bitmapPatterns = new JsonArray();
        if (plugin.getBitmapPatternManager() != null) {
            for (String id : plugin.getBitmapPatternManager().getPatternIds()) {
                bitmapPatterns.add(id);
            }
        }
        bitmap.add("patterns", bitmapPatterns);
        providers.add("bitmap", bitmap);

        // Bedrock support status
        BedrockSupport bedrockSupport = plugin.getBedrockSupport();
        JsonObject bedrock = new JsonObject();
        bedrock.addProperty("geyser_present", bedrockSupport.isGeyserPresent());
        bedrock.addProperty("floodgate_present", bedrockSupport.isFloodgatePresent());
        bedrock.addProperty("geyser_display_entity", bedrockSupport.isGeyserDisplayEntityPresent());
        bedrock.addProperty("particle_fallback_active", bedrockSupport.needsParticleFallback());
        bedrock.addProperty("bedrock_players_online", bedrockSupport.getBedrockPlayers().size());
        response.add("bedrock", bedrock);

        return response;
    }

    private JsonObject handleSetHologramConfig(JsonObject message) {
        if (!message.has("zone") || !message.has("config")) {
            return createError("Missing required field: zone or config");
        }
        String zoneName = message.get("zone").getAsString();
        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        JsonObject config = message.getAsJsonObject("config");
        plugin.getRendererRegistry().setHologramConfig(zoneName, config);

        JsonObject response = new JsonObject();
        response.addProperty("type", "hologram_config_updated");
        response.addProperty("zone", zoneName);
        response.add("config", plugin.getRendererRegistry().getHologramConfig(zoneName));
        return response;
    }

    /**
     * Apply legacy render mode to runtime (particle manager visibility and entity pool visibility).
     * Package-visible so other handlers can reuse if needed.
     */
    void applyLegacyModeToRuntime(String zoneName, String mode) {
        ParticleVisualizationManager particleViz = plugin.getParticleVisualizationManager();
        particleViz.setRenderMode(zoneName, mode);

        EntityPoolManager pool = plugin.getEntityPoolManager();
        if (mode.equals("particles")) {
            for (String entityId : pool.getEntityIds(zoneName)) {
                pool.setEntityVisible(zoneName, entityId, false);
            }
        } else if (mode.equals("entities") || mode.equals("hybrid")) {
            for (String entityId : pool.getEntityIds(zoneName)) {
                pool.setEntityVisible(zoneName, entityId, true);
            }
        }
    }
}
