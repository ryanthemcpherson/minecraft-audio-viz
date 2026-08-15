package com.audioviz.protocol.handlers;

import com.audioviz.AudioVizPlugin;
import com.audioviz.effects.BeatEffectConfig;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.effects.BeatType;
import com.audioviz.particles.ParticleVisualizationManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.protocol.BeatProjectionUtil;
import com.audioviz.protocol.InputSanitizer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles particle visualization and audio state messages:
 * set_particle_viz_config, set_particle_effect, set_particle_config, audio_state.
 */
public class ParticleHandler extends BaseHandler {

    private final Map<String, Long> lastBeatTimestampByZone = new ConcurrentHashMap<>();

    public ParticleHandler(AudioVizPlugin plugin) {
        super(plugin);
    }

    @Override
    public String[] getMessageTypes() {
        return new String[]{
            "set_particle_viz_config", "set_particle_effect",
            "set_particle_config", "audio_state"
        };
    }

    @Override
    public JsonObject handle(String type, JsonObject message) {
        return switch (type) {
            case "set_particle_viz_config" -> handleSetParticleVizConfig(message);
            case "set_particle_effect" -> handleSetParticleEffect(message);
            case "set_particle_config" -> handleSetParticleConfig(message);
            case "audio_state" -> handleAudioState(message);
            default -> createError("Unknown message type: " + type);
        };
    }

    private JsonObject handleSetParticleVizConfig(JsonObject message) {
        if (!message.has("zone") || !message.has("config")) {
            return createError("Missing required field: zone or config");
        }
        String zoneName = message.get("zone").getAsString();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        ParticleVisualizationManager particleViz = plugin.getParticleVisualizationManager();
        ParticleVisualizationManager.ParticleVizConfig config = particleViz.getOrCreateConfig(zoneName);

        JsonObject configJson = message.getAsJsonObject("config");

        if (configJson.has("particle_type")) {
            config.setParticleType(configJson.get("particle_type").getAsString());
        }

        if (configJson.has("density")) {
            config.setDensity(configJson.get("density").getAsInt());
        }

        if (configJson.has("color_mode")) {
            config.setColorMode(configJson.get("color_mode").getAsString());
        }

        if (configJson.has("fixed_color")) {
            config.setFixedColor(configJson.get("fixed_color").getAsString());
        }

        if (configJson.has("particle_size")) {
            config.setParticleSize(configJson.get("particle_size").getAsFloat());
        }

        if (configJson.has("trail")) {
            config.setTrail(configJson.get("trail").getAsBoolean());
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "particle_viz_config_updated");
        response.addProperty("zone", zoneName);
        response.add("config", configJson);

        return response;
    }

    private JsonObject handleSetParticleEffect(JsonObject message) {
        if (!message.has("zone") || !message.has("effect")) {
            return createError("Missing required field: zone or effect");
        }
        String zoneName = message.get("zone").getAsString();
        String effectId = message.get("effect").getAsString();
        boolean enabled = message.has("enabled") ? message.get("enabled").getAsBoolean() : true;

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        BeatEventManager beatManager = plugin.getBeatEventManager();
        BeatEffectConfig config = beatManager.getZoneConfig(zoneName);
        if (config == null) {
            config = new BeatEffectConfig();
            beatManager.setZoneConfig(zoneName, config);
        }

        // Get the effect
        var effect = beatManager.get(effectId);
        if (effect == null) {
            return createError("Unknown effect: " + effectId);
        }

        // Enable/disable the effect for beat type
        BeatType beatType;
        if (message.has("beat_type")) {
            try {
                beatType = BeatType.valueOf(message.get("beat_type").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                return createError("Unknown beat type: " + message.get("beat_type").getAsString());
            }
        } else {
            beatType = BeatType.BEAT;
        }

        if (enabled) {
            config.addEffect(beatType, effect);
        } else {
            config.removeEffect(beatType, effect);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "particle_effect_updated");
        response.addProperty("zone", zoneName);
        response.addProperty("effect", effectId);
        response.addProperty("enabled", enabled);

        return response;
    }

    private JsonObject handleSetParticleConfig(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            return createError("Zone not found: " + zoneName);
        }

        BeatEventManager beatManager = plugin.getBeatEventManager();
        BeatEffectConfig config = beatManager.getZoneConfig(zoneName);
        if (config == null) {
            config = new BeatEffectConfig();
            beatManager.setZoneConfig(zoneName, config);
        }

        // Update threshold if provided
        if (message.has("threshold")) {
            double threshold = message.get("threshold").getAsDouble();
            config.setThreshold(BeatType.BEAT, threshold);
        }

        // Update cooldown if provided
        if (message.has("cooldown_ms")) {
            long cooldown = message.get("cooldown_ms").getAsLong();
            config.setCooldown(BeatType.BEAT, cooldown);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "particle_config_updated");
        response.addProperty("zone", zoneName);

        return response;
    }

    private JsonObject handleAudioState(JsonObject message) {
        if (!message.has("zone")) {
            return createError("Missing required field: zone");
        }
        String zoneName = message.get("zone").getAsString();

        boolean explicitBeat = message.has("is_beat") && message.get("is_beat").getAsBoolean();
        double explicitBeatIntensity = InputSanitizer.sanitizeDouble(
            message.has("beat_intensity") ? message.get("beat_intensity").getAsDouble() : 0.0,
            0.0, 1.0, 0.0);
        double bpm = InputSanitizer.sanitizeDouble(
            message.has("bpm") ? message.get("bpm").getAsDouble() : 0.0,
            0.0, 300.0, 0.0);
        double tempoConfidence = InputSanitizer.sanitizeDouble(
            message.has("tempo_confidence") ? message.get("tempo_confidence").getAsDouble()
                : (message.has("tempo_conf") ? message.get("tempo_conf").getAsDouble() : 0.0),
            0.0, 1.0, 0.0);
        double beatPhase = InputSanitizer.sanitizeDouble(
            message.has("beat_phase") ? message.get("beat_phase").getAsDouble() : 0.0,
            0.0, 1.0, 0.0);

        BeatProjectionUtil.BeatProjection projection = BeatProjectionUtil.projectBeat(
            zoneName, explicitBeat, explicitBeatIntensity, bpm, tempoConfidence, beatPhase,
            lastBeatTimestampByZone);
        boolean isBeat = projection.isBeat();
        double beatIntensity = projection.beatIntensity();

        // Trigger beat effects if this is a beat
        if (isBeat && beatIntensity > 0) {
            BeatEventManager beatManager = plugin.getBeatEventManager();
            beatManager.processBeat(zoneName, BeatType.BEAT, beatIntensity);
        }

        // Update particle visualization with audio state
        if (message.has("bands")) {
            JsonArray bandsJson = message.getAsJsonArray("bands");
            int bandCount = Math.min(bandsJson.size(), 10); // Cap array size
            double[] bands = new double[bandCount];
            for (int i = 0; i < bands.length; i++) {
                bands[i] = InputSanitizer.sanitizeBandValue(bandsJson.get(i).getAsDouble());
            }
            double amplitude = InputSanitizer.sanitizeAmplitude(
                message.has("amplitude") ? message.get("amplitude").getAsDouble() : 0.0);
            long frame = message.has("frame") ? message.get("frame").getAsLong() : 0;

            AudioState audioState = new AudioState(
                bands, amplitude, isBeat, beatIntensity, tempoConfidence, beatPhase, frame);
            plugin.getParticleVisualizationManager().updateAudioState(audioState);

            // Forward audio state to decorator manager
            if (plugin.getDecoratorManager() != null) {
                plugin.getDecoratorManager().updateAudioState(audioState);
            }

            // Update bitmap audio state (pattern manager self-ticks at 20 TPS)
            if (plugin.getBitmapPatternManager() != null) {
                plugin.getBitmapPatternManager().updateAudioState(audioState);
            }
        }

        // Record network latency if timestamp present
        if (message.has("ts")) {
            double remoteTs = message.get("ts").getAsDouble();
            var latencyTracker = plugin.getLatencyTracker();
            if (latencyTracker != null) {
                latencyTracker.recordNetworkLatency(remoteTs, System.currentTimeMillis());
            }
        }

        // Silent response (high-frequency message)
        return createOk();
    }
}
