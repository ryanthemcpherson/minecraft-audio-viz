package com.audioviz.voice;

import com.audioviz.AudioVizPlugin;
import com.audioviz.zones.VisualizationZone;
import com.google.gson.JsonObject;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple Voice Chat integration for streaming audio to players.
 * Supports both static (non-directional) and locational (3D spatial) audio channels.
 *
 * <p>Audio flows through a shared Opus frame queue. Incoming frames (already Opus-encoded
 * or PCM that gets encoded on arrival) are queued and then drained by a tick-based scheduler
 * task that sends each frame to every connected player's channel.</p>
 *
 * <p>This class implements {@link VoicechatPlugin} and is registered with the
 * Simple Voice Chat API when the mod is detected on the server. All voice chat
 * code is isolated here to prevent ClassNotFoundError when the mod is absent.</p>
 */
public class VoicechatIntegration implements VoicechatPlugin {

    private static final String CATEGORY_ID = "audioviz_music";
    private static final int MAX_BUFFER_SIZE = 50;
    private static final int FRAMES_PER_TICK = 3;

    private final AudioVizPlugin plugin;

    // Voice chat API reference (set when server starts)
    private volatile VoicechatServerApi serverApi;

    // Volume category for AudioViz music
    private VolumeCategory musicCategory;

    // Per-player audio channels (no AudioPlayer - we send Opus frames directly)
    private final Map<UUID, AudioChannel> playerChannels = new ConcurrentHashMap<>();

    // Shared Opus frame queue (thread-safe, fed by WebSocket, drained by tick task)
    private final ConcurrentLinkedQueue<byte[]> opusQueue = new ConcurrentLinkedQueue<>();

    // Track queue size without calling .size() on ConcurrentLinkedQueue (O(n))
    private final AtomicInteger queueSize = new AtomicInteger(0);

    // Shared OpusEncoder for PCM fallback (one encoder, not per-player)
    private volatile OpusEncoder pcmEncoder;

    // Tick-based scheduler task for draining the queue
    private BukkitTask tickTask;

    // Configuration (can be changed at runtime via voice_config messages)
    private volatile boolean enabled = true;
    private volatile String channelType = "static"; // "static" or "locational"
    private volatile double distance = 100.0;
    private volatile String zoneName = "main";

    // Expected PCM frame size: 960 samples = 20ms at 48kHz mono
    private static final int FRAME_SIZE = 960;

    public VoicechatIntegration(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPluginId() {
        return "audioviz";
    }

    @Override
    public void initialize(VoicechatApi api) {
        plugin.getLogger().info("[VoiceChat] AudioViz voice chat plugin initialized");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
    }

    // ========== Event Handlers ==========

    private void onServerStarted(VoicechatServerStartedEvent event) {
        this.serverApi = event.getVoicechat();

        // Register a custom volume category so players can adjust AudioViz music volume independently
        musicCategory = serverApi.volumeCategoryBuilder()
                .setId("audioviz_music")
                .setName("AudioViz Music")
                .setDescription("Volume for AudioViz music streaming")
                .build();
        serverApi.registerVolumeCategory(musicCategory);

        // Create shared PCM encoder for fallback encoding
        pcmEncoder = serverApi.createEncoder(OpusEncoderMode.AUDIO);
        if (pcmEncoder == null) {
            plugin.getLogger().warning("[VoiceChat] Failed to create shared PCM encoder");
        }

        // Start tick task to drain Opus queue and send to all player channels
        startTickTask();

        plugin.getLogger().info("[VoiceChat] Voice chat server started, volume category registered");
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        if (!enabled || serverApi == null) return;

        VoicechatConnection connection = event.getConnection();
        if (connection == null) return;

        Player player = (Player) connection.getPlayer().getPlayer();
        if (player == null) return;

        UUID playerId = player.getUniqueId();
        plugin.getLogger().info("[VoiceChat] Player connected to voice chat: " + player.getName());

        createChannelForPlayer(playerId, player);
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        UUID playerId = event.getPlayerUuid();
        cleanupPlayer(playerId);
        plugin.getLogger().info("[VoiceChat] Player disconnected from voice chat: " + playerId);
    }

    // ========== Channel Management ==========

    /**
     * Create an audio channel for a player.
     * No AudioPlayer is created - the tick task sends Opus frames directly.
     */
    private void createChannelForPlayer(UUID playerId, Player player) {
        if (serverApi == null) return;

        // Clean up any existing channel for this player
        cleanupPlayer(playerId);

        try {
            AudioChannel channel;
            UUID channelId = UUID.randomUUID();

            if ("locational".equals(channelType)) {
                // Spatial 3D audio from the zone's center
                Location centerLoc = getZoneCenter();
                if (centerLoc == null) {
                    plugin.getLogger().warning("[VoiceChat] Cannot create locational channel: zone '" + zoneName + "' not found");
                    return;
                }

                LocationalAudioChannel locChannel = serverApi.createLocationalAudioChannel(
                        channelId,
                        serverApi.fromServerLevel(centerLoc.getWorld()),
                        serverApi.createPosition(centerLoc.getX(), centerLoc.getY(), centerLoc.getZ())
                );
                if (locChannel == null) {
                    plugin.getLogger().warning("[VoiceChat] Failed to create locational audio channel");
                    return;
                }
                locChannel.setDistance((float) distance);
                locChannel.setCategory(CATEGORY_ID);
                channel = locChannel;
            } else {
                // Static channel - player hears it everywhere, non-directional
                VoicechatConnection connection = serverApi.getConnectionOf(playerId);
                if (connection == null) {
                    plugin.getLogger().warning("[VoiceChat] No voice connection for " + player.getName());
                    return;
                }
                StaticAudioChannel staticChannel = serverApi.createStaticAudioChannel(
                        channelId,
                        serverApi.fromServerLevel(player.getWorld()),
                        connection
                );
                if (staticChannel == null) {
                    plugin.getLogger().warning("[VoiceChat] Failed to create static audio channel for " + player.getName());
                    return;
                }
                staticChannel.setCategory(CATEGORY_ID);
                channel = staticChannel;
            }

            playerChannels.put(playerId, channel);

            plugin.getLogger().info("[VoiceChat] Audio channel created for " + player.getName() +
                    " (type=" + channelType + ")");

        } catch (Exception e) {
            plugin.getLogger().warning("[VoiceChat] Error creating channel for player: " + e.getMessage());
        }
    }

    /**
     * Clean up a player's audio channel.
     */
    private void cleanupPlayer(UUID playerId) {
        // Remove channel reference (SVC manages channel lifecycle)
        playerChannels.remove(playerId);
    }

    /**
     * Recreate all active channels (e.g., when channel type changes).
     */
    private void recreateAllChannels() {
        if (serverApi == null) return;

        // Collect current player UUIDs
        UUID[] playerIds = playerChannels.keySet().toArray(new UUID[0]);

        // Clean up all existing channels
        for (UUID playerId : playerIds) {
            cleanupPlayer(playerId);
        }

        // Recreate channels if enabled
        if (!enabled) return;

        for (UUID playerId : playerIds) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                createChannelForPlayer(playerId, player);
            }
        }
    }

    // ========== Tick Task ==========

    /**
     * Start the scheduler task that drains the Opus queue and sends frames to all channels.
     * Runs every 1 tick (50ms), drains up to {@link #FRAMES_PER_TICK} frames per tick.
     */
    private void startTickTask() {
        if (tickTask != null) return;

        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled || playerChannels.isEmpty()) return;

            int drained = 0;
            byte[] opusFrame;
            while (drained < FRAMES_PER_TICK && (opusFrame = opusQueue.poll()) != null) {
                queueSize.decrementAndGet();
                drained++;

                // Send this frame to ALL player channels
                for (var entry : playerChannels.entrySet()) {
                    AudioChannel channel = entry.getValue();
                    if (channel.isClosed()) {
                        // Will be cleaned up on next disconnect event
                        continue;
                    }
                    try {
                        channel.send(opusFrame);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[VoiceChat] Error sending audio to channel: " + e.getMessage());
                    }
                }
            }
        }, 1L, 1L); // delay=1 tick, period=1 tick
    }

    /**
     * Stop the scheduler tick task.
     */
    private void stopTickTask() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    // ========== Public API (called by MessageHandler) ==========

    /**
     * Queue a pre-encoded Opus audio frame for streaming to all connected players.
     * The frame will be sent to all player channels on the next tick.
     *
     * @param opusFrame Opus-encoded audio data
     */
    public void queueOpusFrame(byte[] opusFrame) {
        if (!enabled || serverApi == null || playerChannels.isEmpty()) return;

        if (opusFrame == null || opusFrame.length == 0) {
            return;
        }

        // Apply backpressure: drop oldest frames if buffer is too large
        while (queueSize.get() >= MAX_BUFFER_SIZE) {
            byte[] dropped = opusQueue.poll();
            if (dropped != null) {
                queueSize.decrementAndGet();
            } else {
                break;
            }
        }

        opusQueue.offer(opusFrame);
        queueSize.incrementAndGet();
    }

    /**
     * Queue a raw PCM audio frame by encoding it to Opus first.
     * Frame must be exactly 960 int16 samples (20ms at 48kHz mono).
     *
     * @param pcmSamples PCM audio samples (960 short values)
     */
    public void queuePcmFrame(short[] pcmSamples) {
        if (!enabled || serverApi == null || pcmEncoder == null) return;

        if (pcmSamples == null || pcmSamples.length != FRAME_SIZE) {
            return;
        }

        try {
            byte[] opusData = pcmEncoder.encode(pcmSamples);
            if (opusData != null) {
                queueOpusFrame(opusData);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[VoiceChat] Error encoding PCM to Opus: " + e.getMessage());
        }
    }

    /**
     * Update voice chat configuration at runtime.
     *
     * @param enabled  whether voice chat streaming is enabled
     * @param channelType  "static" or "locational"
     * @param distance  audio distance for locational channels
     * @param zone  zone name for locational channel center position
     */
    public void setConfig(boolean enabled, String channelType, double distance, String zone) {
        boolean needsRecreate = this.enabled != enabled
                || !this.channelType.equals(channelType)
                || !this.zoneName.equals(zone);

        this.enabled = enabled;
        this.channelType = channelType;
        this.distance = distance;
        this.zoneName = zone;

        // Update distance on existing locational channels without recreating
        if (!needsRecreate && "locational".equals(channelType)) {
            for (AudioChannel channel : playerChannels.values()) {
                if (channel instanceof LocationalAudioChannel locChannel) {
                    locChannel.setDistance((float) distance);
                }
            }
        }

        // Recreate channels if the type or zone changed
        if (needsRecreate) {
            recreateAllChannels();
        }

        plugin.getLogger().info("[VoiceChat] Config updated: enabled=" + enabled +
                ", type=" + channelType + ", distance=" + distance + ", zone=" + zone);
    }

    /**
     * Get the current voice chat status as a JSON object.
     *
     * @return JsonObject matching the voice_status message contract
     */
    public JsonObject getStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("type", "voice_status");
        status.addProperty("available", serverApi != null);
        status.addProperty("streaming", enabled && serverApi != null && !playerChannels.isEmpty());
        status.addProperty("channel_type", channelType);
        status.addProperty("connected_players", playerChannels.size());
        status.addProperty("buffer_size", queueSize.get());
        status.addProperty("distance", distance);
        status.addProperty("zone", zoneName);
        return status;
    }

    /**
     * Check if voice chat is available (Simple Voice Chat API loaded and server started).
     */
    public boolean isAvailable() {
        return serverApi != null;
    }

    /**
     * Check if currently streaming audio.
     */
    public boolean isStreaming() {
        return enabled && serverApi != null && !playerChannels.isEmpty();
    }

    /**
     * Graceful shutdown - clean up all resources.
     * Called from {@link AudioVizPlugin#onDisable()}.
     */
    public void shutdown() {
        plugin.getLogger().info("[VoiceChat] Shutting down voice chat integration...");

        // Stop the tick task first
        stopTickTask();

        // Remove all channel references
        playerChannels.clear();

        // Clear the Opus frame buffer
        opusQueue.clear();
        queueSize.set(0);

        // Close the shared PCM encoder (native memory!)
        if (pcmEncoder != null) {
            try {
                pcmEncoder.close();
            } catch (Exception e) {
                plugin.getLogger().warning("[VoiceChat] Error closing PCM encoder: " + e.getMessage());
            }
            pcmEncoder = null;
        }

        // Unregister volume category
        if (serverApi != null && musicCategory != null) {
            try {
                serverApi.unregisterVolumeCategory(musicCategory);
            } catch (Exception e) {
                plugin.getLogger().warning("[VoiceChat] Error unregistering volume category: " + e.getMessage());
            }
        }

        serverApi = null;
        plugin.getLogger().info("[VoiceChat] Voice chat integration shut down");
    }

    // ========== Internal Helpers ==========

    /**
     * Get the center location of the configured zone.
     */
    private Location getZoneCenter() {
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return null;
        return zone.getCenter();
    }

    /**
     * Get the number of players currently receiving audio.
     */
    public int getConnectedPlayerCount() {
        return playerChannels.size();
    }
}
