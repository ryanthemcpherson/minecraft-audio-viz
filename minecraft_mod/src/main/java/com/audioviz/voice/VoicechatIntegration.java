package com.audioviz.voice;

import com.audioviz.AudioVizMod;
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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple Voice Chat integration for streaming audio to players.
 *
 * <p>Ported from Paper: Bukkit Location/Player → Fabric Vec3d/ServerPlayerEntity,
 * BukkitTask → tick-based (called from AudioVizMod's tick loop),
 * plugin.getLogger() → SLF4J.
 *
 * <p>The Simple Voice Chat API is the same across Paper and Fabric — only the
 * Minecraft types used for position/player differ.
 */
public class VoicechatIntegration implements VoicechatPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private static final String CATEGORY_ID = "audioviz_music";
    private static final int MAX_BUFFER_SIZE = 50;
    private static final int FRAMES_PER_TICK = 3;
    private static final int FRAME_SIZE = 960;

    // Singleton: the voicechat entrypoint creates the instance via no-arg constructor.
    // AudioVizMod retrieves it via getInstance() and calls setMod() during startup.
    private static volatile VoicechatIntegration instance;

    private volatile AudioVizMod mod;

    private volatile VoicechatServerApi serverApi;
    private VolumeCategory musicCategory;

    private final Map<UUID, AudioChannel> playerChannels = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<byte[]> opusQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private volatile OpusEncoder pcmEncoder;

    private volatile boolean enabled = true;
    private volatile String channelType = "static";
    private volatile double distance = 100.0;
    private volatile String zoneName = "main";

    /** No-arg constructor required by Fabric entrypoint system. */
    public VoicechatIntegration() {
        instance = this;
        LOGGER.info("[VoiceChat] VoicechatIntegration entrypoint loaded");
    }

    /** Set the mod reference after AudioVizMod startup. */
    public void setMod(AudioVizMod mod) {
        this.mod = mod;
    }

    /** Get the singleton instance created by the voicechat entrypoint, or null if not loaded. */
    public static VoicechatIntegration getInstance() {
        return instance;
    }

    @Override
    public String getPluginId() {
        return "audioviz";
    }

    @Override
    public void initialize(VoicechatApi api) {
        LOGGER.info("[VoiceChat] AudioViz voice chat plugin initialized");
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

        musicCategory = serverApi.volumeCategoryBuilder()
                .setId("audioviz_music")
                .setName("AudioViz Music")
                .setDescription("Volume for AudioViz music streaming")
                .build();
        serverApi.registerVolumeCategory(musicCategory);

        pcmEncoder = serverApi.createEncoder(OpusEncoderMode.AUDIO);
        if (pcmEncoder == null) {
            LOGGER.warn("[VoiceChat] Failed to create shared PCM encoder");
        }

        LOGGER.info("[VoiceChat] Voice chat server started, volume category registered");
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        if (!enabled || serverApi == null) return;

        VoicechatConnection connection = event.getConnection();
        if (connection == null) return;

        ServerPlayerEntity player = (ServerPlayerEntity) connection.getPlayer().getPlayer();
        if (player == null) return;

        UUID playerId = player.getUuid();
        LOGGER.info("[VoiceChat] Player connected to voice chat: {}", player.getName().getString());

        createChannelForPlayer(playerId, player);
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        UUID playerId = event.getPlayerUuid();
        cleanupPlayer(playerId);
        LOGGER.info("[VoiceChat] Player disconnected from voice chat: {}", playerId);
    }

    // ========== Channel Management ==========

    private void createChannelForPlayer(UUID playerId, ServerPlayerEntity player) {
        if (serverApi == null) return;

        cleanupPlayer(playerId);

        try {
            AudioChannel channel;
            UUID channelId = UUID.randomUUID();

            if ("locational".equals(channelType)) {
                Vec3d center = getZoneCenter();
                if (center == null) {
                    LOGGER.warn("[VoiceChat] Cannot create locational channel: zone '{}' not found", zoneName);
                    return;
                }

                LocationalAudioChannel locChannel = serverApi.createLocationalAudioChannel(
                        channelId,
                        serverApi.fromServerLevel(player.getEntityWorld()),
                        serverApi.createPosition(center.x, center.y, center.z)
                );
                if (locChannel == null) {
                    LOGGER.warn("[VoiceChat] Failed to create locational audio channel");
                    return;
                }
                locChannel.setDistance((float) distance);
                locChannel.setCategory(CATEGORY_ID);
                channel = locChannel;
            } else {
                VoicechatConnection connection = serverApi.getConnectionOf(playerId);
                if (connection == null) {
                    LOGGER.warn("[VoiceChat] No voice connection for {}", player.getName().getString());
                    return;
                }
                StaticAudioChannel staticChannel = serverApi.createStaticAudioChannel(
                        channelId,
                        serverApi.fromServerLevel(player.getEntityWorld()),
                        connection
                );
                if (staticChannel == null) {
                    LOGGER.warn("[VoiceChat] Failed to create static audio channel for {}",
                        player.getName().getString());
                    return;
                }
                staticChannel.setCategory(CATEGORY_ID);
                channel = staticChannel;
            }

            playerChannels.put(playerId, channel);
            LOGGER.info("[VoiceChat] Audio channel created for {} (type={})",
                player.getName().getString(), channelType);

        } catch (Exception e) {
            LOGGER.warn("[VoiceChat] Error creating channel for player: {}", e.getMessage());
        }
    }

    private void cleanupPlayer(UUID playerId) {
        playerChannels.remove(playerId);
    }

    private void recreateAllChannels() {
        if (serverApi == null || mod == null || mod.getServer() == null) return;

        UUID[] playerIds = playerChannels.keySet().toArray(new UUID[0]);
        for (UUID playerId : playerIds) {
            cleanupPlayer(playerId);
        }

        if (!enabled) return;

        for (UUID playerId : playerIds) {
            ServerPlayerEntity player = mod.getServer().getPlayerManager().getPlayer(playerId);
            if (player != null) {
                createChannelForPlayer(playerId, player);
            }
        }
    }

    // ========== Tick (called from AudioVizMod) ==========

    /**
     * Drain the Opus queue and send frames to all channels.
     * Called every server tick from AudioVizMod.
     */
    public void tick() {
        if (!enabled || playerChannels.isEmpty()) return;

        int drained = 0;
        byte[] opusFrame;
        while (drained < FRAMES_PER_TICK && (opusFrame = opusQueue.poll()) != null) {
            queueSize.decrementAndGet();
            drained++;

            for (var entry : playerChannels.entrySet()) {
                AudioChannel channel = entry.getValue();
                if (channel.isClosed()) continue;
                try {
                    channel.send(opusFrame);
                } catch (Exception e) {
                    LOGGER.warn("[VoiceChat] Error sending audio to channel: {}", e.getMessage());
                }
            }
        }
    }

    // ========== Public API ==========

    public void queueOpusFrame(byte[] opusFrame) {
        if (!enabled || serverApi == null || playerChannels.isEmpty()) return;
        if (opusFrame == null || opusFrame.length == 0) return;

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

    public void queuePcmFrame(short[] pcmSamples) {
        if (!enabled || serverApi == null || pcmEncoder == null) return;
        if (pcmSamples == null || pcmSamples.length != FRAME_SIZE) return;

        try {
            byte[] opusData = pcmEncoder.encode(pcmSamples);
            if (opusData != null) {
                queueOpusFrame(opusData);
            }
        } catch (Exception e) {
            LOGGER.warn("[VoiceChat] Error encoding PCM to Opus: {}", e.getMessage());
        }
    }

    public void setConfig(boolean enabled, String channelType, double distance, String zone) {
        boolean needsRecreate = this.enabled != enabled
                || !this.channelType.equals(channelType)
                || !this.zoneName.equals(zone);

        this.enabled = enabled;
        this.channelType = channelType;
        this.distance = distance;
        this.zoneName = zone;

        if (!needsRecreate && "locational".equals(channelType)) {
            for (AudioChannel channel : playerChannels.values()) {
                if (channel instanceof LocationalAudioChannel locChannel) {
                    locChannel.setDistance((float) distance);
                }
            }
        }

        if (needsRecreate) {
            recreateAllChannels();
        }

        LOGGER.info("[VoiceChat] Config updated: enabled={}, type={}, distance={}, zone={}",
            enabled, channelType, distance, zone);
    }

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

    public boolean isAvailable() { return serverApi != null; }
    public boolean isStreaming() { return enabled && serverApi != null && !playerChannels.isEmpty(); }
    public int getConnectedPlayerCount() { return playerChannels.size(); }

    public void shutdown() {
        LOGGER.info("[VoiceChat] Shutting down voice chat integration...");
        playerChannels.clear();
        opusQueue.clear();
        queueSize.set(0);

        if (pcmEncoder != null) {
            try { pcmEncoder.close(); } catch (Exception e) {
                LOGGER.warn("[VoiceChat] Error closing PCM encoder: {}", e.getMessage());
            }
            pcmEncoder = null;
        }

        if (serverApi != null && musicCategory != null) {
            try { serverApi.unregisterVolumeCategory(musicCategory); } catch (Exception e) {
                LOGGER.warn("[VoiceChat] Error unregistering volume category: {}", e.getMessage());
            }
        }

        serverApi = null;
        LOGGER.info("[VoiceChat] Voice chat integration shut down");
    }

    // ========== Helpers ==========

    private Vec3d getZoneCenter() {
        if (mod == null || mod.getZoneManager() == null) return null;
        VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
        if (zone == null) return null;
        return zone.getCenter();
    }
}
