package com.audioviz.protocol.handlers;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.BannerConfig;
import com.audioviz.decorators.DJInfo;
import com.google.gson.JsonObject;

/**
 * Handles DJ info and banner config messages:
 * dj_info, banner_config.
 */
public class DjInfoHandler extends BaseHandler {

    public DjInfoHandler(AudioVizPlugin plugin) {
        super(plugin);
    }

    @Override
    public String[] getMessageTypes() {
        return new String[]{"dj_info", "banner_config"};
    }

    @Override
    public JsonObject handle(String type, JsonObject message) {
        return switch (type) {
            case "dj_info" -> handleDjInfo(message);
            case "banner_config" -> handleBannerConfig(message);
            default -> createError("Unknown message type: " + type);
        };
    }

    private JsonObject handleDjInfo(JsonObject message) {
        String djName = message.has("dj_name") ? message.get("dj_name").getAsString() : "";
        String djId = message.has("dj_id") ? message.get("dj_id").getAsString() : "";
        double bpm = message.has("bpm") ? message.get("bpm").getAsDouble() : 0.0;
        boolean isActive = message.has("is_active") ? message.get("is_active").getAsBoolean() : true;

        DJInfo djInfo = new DJInfo(djName, djId, bpm, isActive, System.currentTimeMillis());

        if (plugin.getDecoratorManager() != null) {
            plugin.getDecoratorManager().updateDJInfo(djInfo);
        }

        // Notify players of DJ status change
        var listener = plugin.getConnectionStateListener();
        if (listener != null) {
            if (isActive && !djName.isEmpty()) {
                listener.onDjActive(djName);
            } else {
                listener.onDjInactive();
            }
        }

        plugin.getLogger().info("DJ info received: " + djName + " (BPM: " + String.format("%.0f", bpm) + ")");

        JsonObject response = new JsonObject();
        response.addProperty("type", "dj_info_received");
        response.addProperty("dj_name", djName);
        return response;
    }

    private JsonObject handleBannerConfig(JsonObject message) {
        BannerConfig bannerConfig = BannerConfig.fromJson(message);

        if (plugin.getDecoratorManager() != null) {
            plugin.getDecoratorManager().updateBannerConfig(bannerConfig);
        }

        plugin.getLogger().info("Banner config received: " + bannerConfig);

        JsonObject response = new JsonObject();
        response.addProperty("type", "banner_config_received");
        return response;
    }
}
