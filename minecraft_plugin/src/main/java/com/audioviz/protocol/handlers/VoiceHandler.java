package com.audioviz.protocol.handlers;

import com.audioviz.AudioVizPlugin;
import com.audioviz.voice.VoicechatIntegration;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Handles voice chat messages:
 * voice_audio, voice_config, get_voice_status.
 */
public class VoiceHandler extends BaseHandler {

    /** Expected byte count for a voice audio frame: 960 samples * 2 bytes per int16 */
    private static final int VOICE_FRAME_BYTES = 1920;

    /** Expected sample count per voice audio frame (20ms at 48kHz mono) */
    private static final int VOICE_FRAME_SAMPLES = 960;

    public VoiceHandler(AudioVizPlugin plugin) {
        super(plugin);
    }

    @Override
    public String[] getMessageTypes() {
        return new String[]{"voice_audio", "voice_config", "get_voice_status"};
    }

    @Override
    public JsonObject handle(String type, JsonObject message) {
        return switch (type) {
            case "voice_audio" -> handleVoiceAudio(message);
            case "voice_config" -> handleVoiceConfig(message);
            case "get_voice_status" -> handleGetVoiceStatus();
            default -> createError("Unknown message type: " + type);
        };
    }

    /**
     * Handle incoming audio frames for voice chat streaming.
     * Supports two codecs:
     * <ul>
     *   <li>{@code "opus"} - data is already Opus-encoded, sent directly to channels</li>
     *   <li>{@code "pcm"} - data is raw PCM (960 int16 samples, little-endian), encoded to Opus first</li>
     * </ul>
     * Defaults to {@code "pcm"} if the codec field is not present (backward compatibility).
     */
    private JsonObject handleVoiceAudio(JsonObject message) {
        VoicechatIntegration voiceChat = plugin.getVoicechatIntegration();
        if (voiceChat == null || !voiceChat.isAvailable()) {
            // Silent drop - voice chat not available, don't spam errors for high-frequency messages
            return createOk();
        }

        if (!message.has("data")) {
            return createError("Missing required field: data");
        }

        // Determine codec: "opus" or "pcm" (default for backward compatibility)
        String codec = message.has("codec") ? message.get("codec").getAsString() : "pcm";

        try {
            String base64Data = message.get("data").getAsString();
            byte[] rawBytes = Base64.getDecoder().decode(base64Data);

            if ("opus".equals(codec)) {
                // Data is already Opus-encoded - queue directly
                voiceChat.queueOpusFrame(rawBytes);
            } else {
                // PCM fallback: decode to int16 samples, encode to Opus, then queue
                if (rawBytes.length != VOICE_FRAME_BYTES) {
                    return createError("Invalid PCM voice frame size: expected " + VOICE_FRAME_BYTES +
                            " bytes, got " + rawBytes.length);
                }

                short[] samples = new short[VOICE_FRAME_SAMPLES];
                ByteBuffer buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < VOICE_FRAME_SAMPLES; i++) {
                    samples[i] = buffer.getShort();
                }

                voiceChat.queuePcmFrame(samples);
            }

        } catch (IllegalArgumentException e) {
            return createError("Invalid base64 data: " + e.getMessage());
        }

        // Silent response for high-frequency message
        return createOk();
    }

    /**
     * Handle voice chat configuration changes.
     * Updates channel type, distance, zone, and enabled state.
     * Broadcasts the resulting voice_status to all connected WebSocket clients.
     */
    private JsonObject handleVoiceConfig(JsonObject message) {
        VoicechatIntegration voiceChat = plugin.getVoicechatIntegration();
        if (voiceChat == null) {
            return createError("Simple Voice Chat is not installed");
        }

        boolean enabled = message.has("enabled") ? message.get("enabled").getAsBoolean() : true;
        String channelType = message.has("channel_type") ? message.get("channel_type").getAsString() : "static";
        double distance = message.has("distance") ? message.get("distance").getAsDouble() : 100.0;
        String zone = message.has("zone") ? message.get("zone").getAsString() : "main";

        // Validate channel type
        if (!"static".equals(channelType) && !"locational".equals(channelType)) {
            return createError("Invalid channel_type: " + channelType + ". Use 'static' or 'locational'");
        }

        // Validate distance
        if (distance <= 0 || distance > 1000) {
            return createError("Invalid distance: must be between 0 and 1000");
        }

        voiceChat.setConfig(enabled, channelType, distance, zone);

        // Broadcast voice_status to ALL connected WebSocket clients
        JsonObject status = voiceChat.getStatus();
        if (plugin.getWebSocketServer() != null) {
            plugin.getWebSocketServer().broadcast(status);
        }

        // Also return voice_status as the direct response to the sender
        return status;
    }

    private JsonObject handleGetVoiceStatus() {
        VoicechatIntegration voiceChat = plugin.getVoicechatIntegration();
        if (voiceChat == null) {
            JsonObject status = new JsonObject();
            status.addProperty("type", "voice_status");
            status.addProperty("available", false);
            status.addProperty("streaming", false);
            status.addProperty("channel_type", "static");
            status.addProperty("connected_players", 0);
            return status;
        }
        return voiceChat.getStatus();
    }
}
