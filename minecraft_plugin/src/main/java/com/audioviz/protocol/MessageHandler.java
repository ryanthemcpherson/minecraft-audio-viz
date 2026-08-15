package com.audioviz.protocol;

import com.audioviz.AudioVizPlugin;
import com.audioviz.protocol.handlers.BaseHandler;
import com.audioviz.protocol.handlers.BitmapCoreHandler;
import com.audioviz.protocol.handlers.BitmapEffectsHandler;
import com.audioviz.protocol.handlers.CoreEntityHandler;
import com.audioviz.protocol.handlers.DjInfoHandler;
import com.audioviz.protocol.handlers.ParticleHandler;
import com.audioviz.protocol.handlers.RenderModeHandler;
import com.audioviz.protocol.handlers.StageHandler;
import com.audioviz.protocol.handlers.TypedMessageHandler;
import com.audioviz.protocol.handlers.VoiceHandler;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Routes incoming WebSocket messages to the appropriate typed handler.
 *
 * <p>This class is a slim dispatcher — all message-specific logic lives in
 * the handler classes under {@code com.audioviz.protocol.handlers}.
 */
public class MessageHandler {

    private final AudioVizPlugin plugin;
    private final Map<String, TypedMessageHandler> handlerMap;

    public MessageHandler(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.handlerMap = new HashMap<>();

        // Register all typed handlers
        registerHandler(new CoreEntityHandler(plugin));
        registerHandler(new RenderModeHandler(plugin));
        registerHandler(new ParticleHandler(plugin));
        registerHandler(new StageHandler(plugin));
        registerHandler(new BitmapCoreHandler(plugin));
        registerHandler(new BitmapEffectsHandler(plugin));
        registerHandler(new VoiceHandler(plugin));
        registerHandler(new DjInfoHandler(plugin));
    }

    /**
     * Register a typed handler for all its declared message types.
     */
    private void registerHandler(TypedMessageHandler handler) {
        for (String type : handler.getMessageTypes()) {
            TypedMessageHandler existing = handlerMap.put(type, handler);
            if (existing != null) {
                plugin.getLogger().warning("Duplicate message type registration: '" + type +
                    "' — handler " + handler.getClass().getSimpleName() +
                    " overrides " + existing.getClass().getSimpleName());
            }
        }
    }

    /**
     * Handle an incoming message and return a response.
     */
    public JsonObject handleMessage(String type, JsonObject message) {
        TypedMessageHandler handler = handlerMap.get(type);
        if (handler != null) {
            return handler.handle(type, message);
        }
        return createError("Unknown message type: " + type);
    }

    private JsonObject createError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        return error;
    }
}
