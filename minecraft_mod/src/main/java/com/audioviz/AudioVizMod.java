package com.audioviz;

import com.audioviz.bitmap.BitmapPatternManager;
import com.audioviz.decorators.DecoratorEntityManager;
import com.audioviz.decorators.StageDecoratorManager;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.effects.BeatType;
import com.audioviz.gui.MenuManager;
import com.audioviz.lighting.AmbientLightManager;
import com.audioviz.particles.ParticleEffectManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.audioviz.render.BitmapToEntityBridge;
import com.audioviz.render.MapRendererBackend;
import com.audioviz.render.VirtualEntityRendererBackend;
import com.audioviz.stages.StageManager;
import com.audioviz.stages.ZonePlacementManager;
import com.audioviz.virtual.VirtualEntityPool;
import com.audioviz.voice.VoicechatIntegration;
import com.audioviz.websocket.VizWebSocketServer;
import com.audioviz.zones.VisualizationZone;
import com.audioviz.zones.ZoneBoundaryRenderer;
import com.audioviz.zones.ZoneManager;
import com.audioviz.zones.ZoneSelectionManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.*;

public class AudioVizMod implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private static MinecraftServer server;

    private Path configDir;
    private ModConfig config;
    private ZoneManager zoneManager;
    private MessageHandler messageHandler;
    private MessageQueue messageQueue;
    private VizWebSocketServer wsServer;
    private MapRendererBackend mapRenderer;
    private VirtualEntityRendererBackend virtualRenderer;
    private BitmapToEntityBridge bitmapToEntityBridge;
    private BitmapPatternManager bitmapPatternManager;
    private StageManager stageManager;
    private StageDecoratorManager stageDecoratorManager;
    private DecoratorEntityManager decoratorEntityManager;
    private MenuManager menuManager;
    private VoicechatIntegration voicechatIntegration;
    private ZonePlacementManager zonePlacementManager;
    private ZoneBoundaryRenderer zoneBoundaryRenderer;
    private ZoneSelectionManager zoneSelectionManager;
    private ParticleEffectManager particleEffectManager;
    private BeatEventManager beatEventManager;
    private AmbientLightManager ambientLightManager;

    @Override
    public void onInitializeServer() {
        LOGGER.info("AudioViz Fabric mod initializing...");

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            startup();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> shutdown());

        ServerTickEvents.END_SERVER_TICK.register(s -> tick());

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            com.audioviz.commands.AudioVizCommand.register(dispatcher, this);
        });

        // Register interaction callbacks for zone placement wizard and zone selection
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!(player instanceof ServerPlayerEntity spe)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            // Zone placement takes priority
            if (zonePlacementManager != null) {
                ActionResult result = zonePlacementManager.handleLeftClick(spe);
                if (result != ActionResult.PASS) return result;
            }
            // Zone selection mode
            if (zoneSelectionManager != null && zoneSelectionManager.isInSelectionMode(spe)) {
                boolean consumed = zoneSelectionManager.handleLeftClick(spe);
                if (consumed) return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (zonePlacementManager == null) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity spe)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            return zonePlacementManager.handleRightClick(spe);
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (zonePlacementManager == null) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity spe)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            return zonePlacementManager.handleRightClick(spe);
        });

        // Handle player disconnect for placement and selection sessions
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUuid();
            if (zonePlacementManager != null) {
                zonePlacementManager.handleDisconnect(uuid);
            }
            if (zoneSelectionManager != null) {
                zoneSelectionManager.handleDisconnect(uuid);
            }
            if (menuManager != null) {
                menuManager.removeSession(uuid);
            }
        });
    }

    private void startup() {
        configDir = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("audioviz");

        // Load config
        try {
            config = ModConfig.load(configDir);
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to load config", e);
            config = new ModConfig();
        }

        // Initialize zone system
        zoneManager = new ZoneManager(configDir);
        zoneManager.loadZones(server);

        // Initialize renderers
        mapRenderer = new MapRendererBackend();
        virtualRenderer = new VirtualEntityRendererBackend();
        bitmapToEntityBridge = new BitmapToEntityBridge(virtualRenderer);

        // Initialize bitmap pattern engine
        bitmapPatternManager = new BitmapPatternManager(server, mapRenderer);

        // Initialize stage & decorator systems
        stageManager = new StageManager(this);
        stageManager.loadStages();
        decoratorEntityManager = new DecoratorEntityManager();
        stageDecoratorManager = new StageDecoratorManager(this);

        // Initialize audio-reactive subsystems (particles, beat effects, ambient lights)
        particleEffectManager = new ParticleEffectManager();
        beatEventManager = new BeatEventManager(server, zoneManager);
        ambientLightManager = new AmbientLightManager();

        // Initialize zone placement wizard and zone boundary/selection systems
        zonePlacementManager = new ZonePlacementManager(this);
        zoneBoundaryRenderer = new ZoneBoundaryRenderer(this);
        zoneSelectionManager = new ZoneSelectionManager(this);

        // Initialize GUI
        menuManager = new MenuManager(this);

        // Initialize voice chat (optional — requires Simple Voice Chat mod)
        try {
            voicechatIntegration = VoicechatIntegration.getInstance();
            if (voicechatIntegration != null) {
                voicechatIntegration.setMod(this);
                LOGGER.info("Simple Voice Chat integration active");
            } else {
                LOGGER.info("Simple Voice Chat mod present but plugin not yet loaded");
            }
        } catch (NoClassDefFoundError e) {
            LOGGER.info("Simple Voice Chat not detected, voice streaming disabled");
            voicechatIntegration = null;
        }

        // Initialize WebSocket protocol
        messageHandler = new MessageHandler(this);
        messageHandler.setBatchUpdateHandler(this::handleBatchUpdateRendering);
        messageHandler.setBitmapFrameHandler(this::handleBitmapFrameRendering);
        messageQueue = new MessageQueue(messageHandler);

        // Start WebSocket server
        String wsAddress = "0.0.0.0";
        wsServer = new VizWebSocketServer(wsAddress, config.websocketPort, messageHandler, messageQueue, server);
        wsServer.start();
        LOGGER.info("WebSocket server starting on port {}", config.websocketPort);

        LOGGER.info("AudioViz started ({} bitmap patterns, {} stages)",
            bitmapPatternManager.getPatternIds().size(), stageManager.getStageCount());
    }

    private void tick() {
        if (messageQueue == null) return;

        // 1. Process incoming WebSocket messages
        messageQueue.processTick();

        // 2. Drive WebSocket heartbeat/metrics (replaces BukkitTask)
        if (wsServer != null) {
            wsServer.tick();
        }

        // 3. Feed audio state to subsystems and tick bitmap pattern engine
        AudioState audio = messageHandler.getLatestAudioState();
        if (audio == null) audio = AudioState.silent();

        if (bitmapPatternManager != null) {
            bitmapPatternManager.tick(audio);
        }

        if (stageDecoratorManager != null) {
            stageDecoratorManager.updateAudioState(audio);
        }

        // 3b. Feed audio to particle effects, beat events, and ambient lights per zone
        if (particleEffectManager != null || beatEventManager != null || ambientLightManager != null) {
            for (VisualizationZone zone : zoneManager.getAllZones()) {
                if (particleEffectManager != null) {
                    particleEffectManager.processAudioUpdate(zone, audio);
                }
                if (ambientLightManager != null && ambientLightManager.hasZone(zone.getName())) {
                    ambientLightManager.tick(zone, (float) audio.getAmplitude(), audio.isBeat());
                }
            }
            if (beatEventManager != null && audio.isBeat()) {
                for (String zoneName : zoneManager.getZoneNames()) {
                    beatEventManager.processBeat(zoneName, BeatType.BEAT, audio.getBeatIntensity());
                }
            }
        }

        // 4. Push bitmap frames to entity walls (bridge reads buffer after pattern tick)
        if (bitmapToEntityBridge != null) {
            for (String zoneName : bitmapToEntityBridge.getActiveWalls()) {
                var buffer = bitmapPatternManager.getFrameBuffer(zoneName);
                if (buffer != null) {
                    bitmapToEntityBridge.applyFrame(zoneName,
                        buffer.getRawPixels(), buffer.getWidth(), buffer.getHeight());
                }
            }
        }

        // 5. Flush map display updates to players (every tick = 20 TPS)
        for (String zoneName : mapRenderer.getActiveZones()) {
            var players = getPlayersNearZone(zoneName);
            mapRenderer.flush(zoneName, players);
        }
        mapRenderer.tickHolders();

        // 6. Flush virtual entity updates
        for (String zoneName : virtualRenderer.getActiveZones()) {
            virtualRenderer.flush(zoneName);
        }

        // 7. Tick stage decorators (runs at half rate internally)
        if (stageDecoratorManager != null) {
            stageDecoratorManager.tick();
        }

        // 8. Tick voice chat audio drain
        if (voicechatIntegration != null) {
            voicechatIntegration.tick();
        }

        // 9. Tick zone placement sessions (particle rendering)
        if (zonePlacementManager != null) {
            zonePlacementManager.tick();
        }

        // 10. Tick zone boundary renderer (persistent particle outlines)
        if (zoneBoundaryRenderer != null) {
            zoneBoundaryRenderer.tick();
        }

        // 11. Tick zone selection manager (look-at raycasting)
        if (zoneSelectionManager != null) {
            zoneSelectionManager.tick();
        }
    }

    private void shutdown() {
        if (wsServer != null) {
            wsServer.shutdown();
            LOGGER.info("WebSocket server stopped");
        }

        // Shutdown bitmap pattern engine
        if (bitmapPatternManager != null) {
            bitmapPatternManager.shutdown();
        }

        // Shutdown stage decorators and their entities
        if (stageDecoratorManager != null) {
            stageDecoratorManager.shutdown();
        }
        if (decoratorEntityManager != null) {
            decoratorEntityManager.cleanupAll();
        }

        // Shutdown voice chat
        if (voicechatIntegration != null) {
            voicechatIntegration.shutdown();
        }

        // Teardown ambient lights (restore original blocks)
        if (ambientLightManager != null) {
            ServerWorld overworld = server != null ? server.getOverworld() : null;
            if (overworld != null) {
                ambientLightManager.teardownAll(overworld);
            }
        }

        // Clear beat event configs
        if (beatEventManager != null) {
            beatEventManager.clearAllConfigs();
        }

        // Cancel active placement sessions
        if (zonePlacementManager != null) {
            zonePlacementManager.cancelAll();
        }

        // Stop zone boundary renderer and selection manager
        if (zoneBoundaryRenderer != null) {
            zoneBoundaryRenderer.stop();
        }
        if (zoneSelectionManager != null) {
            zoneSelectionManager.stop();
        }

        // Clear GUI sessions
        if (menuManager != null) {
            menuManager.clearAllSessions();
        }

        // Destroy entity walls
        if (bitmapToEntityBridge != null) {
            for (String zone : new ArrayList<>(bitmapToEntityBridge.getActiveWalls())) {
                bitmapToEntityBridge.destroyWall(zone);
            }
        }

        // Destroy all active displays (copy keyset to avoid ConcurrentModificationException)
        if (mapRenderer != null) {
            for (String zone : new ArrayList<>(mapRenderer.getActiveZones())) {
                mapRenderer.destroyDisplay(zone);
            }
        }
        if (virtualRenderer != null) {
            for (String zone : new ArrayList<>(virtualRenderer.getActiveZones())) {
                virtualRenderer.destroyPool(zone);
            }
        }

        // Save state
        if (stageManager != null) {
            stageManager.saveStages();
        }
        if (zoneManager != null) {
            zoneManager.saveZones();
        }

        LOGGER.info("AudioViz stopped");
    }

    // ========== Rendering Pipeline Handlers ==========

    /**
     * Parse batch_update JSON and push entity updates to the virtual renderer.
     * Converts normalized (0-1) coordinates to holder-relative translations.
     */
    private JsonObject handleBatchUpdateRendering(JsonObject message) {
        try {
            String zone = message.has("zone") ? message.get("zone").getAsString() : null;
            if (zone == null) return null;

            VisualizationZone vizZone = zoneManager.getZone(zone);
            if (vizZone == null || !virtualRenderer.hasPool(zone)) return null;

            JsonArray entities = message.has("entities") ? message.getAsJsonArray("entities") : null;
            if (entities == null || entities.isEmpty()) return null;

            Vec3d origin = vizZone.getOriginVec3d();
            List<VirtualEntityPool.EntityUpdate> updates = new ArrayList<>();

            for (var jsonEl : entities) {
                JsonObject e = jsonEl.getAsJsonObject();
                String id = e.has("id") ? e.get("id").getAsString() : null;
                if (id == null) continue;

                int index = parseEntityIndex(id);
                if (index < 0) continue;

                // Handle visibility — if explicitly false, hide entity
                Boolean visible = e.has("visible") ? e.get("visible").getAsBoolean() : null;

                double x = e.has("x") ? e.get("x").getAsDouble() : 0.5;
                double y = e.has("y") ? e.get("y").getAsDouble() : 0.5;
                double z = e.has("z") ? e.get("z").getAsDouble() : 0.5;
                Vec3d worldPos = vizZone.localToWorld(x, y, z);
                Vec3d relative = worldPos.subtract(origin);

                float scale = e.has("scale") ? e.get("scale").getAsFloat() : 0.2f;
                Vector3f scaleVec = new Vector3f(scale, scale, scale);

                // Per-entity material override (e.g. "GOLD_BLOCK", "NETHER_BRICKS")
                net.minecraft.block.BlockState blockState = null;
                if (e.has("material")) {
                    blockState = com.audioviz.render.MaterialResolver.resolve(
                        e.get("material").getAsString());
                }

                updates.add(new VirtualEntityPool.EntityUpdate(index, relative, scaleVec, blockState, visible));
            }

            if (!updates.isEmpty()) {
                virtualRenderer.applyBatchUpdate(zone, updates);
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("Error processing batch_update: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse bitmap_frame JSON (base64 or array) and push pixels to the appropriate renderer.
     * Supports both map displays and entity wall backends.
     */
    private JsonObject handleBitmapFrameRendering(JsonObject message) {
        try {
            String zone = message.has("zone") ? message.get("zone").getAsString() : null;
            if (zone == null) return null;

            boolean hasMap = mapRenderer.hasDisplay(zone);
            boolean hasWall = bitmapToEntityBridge != null && bitmapToEntityBridge.hasWall(zone);
            if (!hasMap && !hasWall) return null;

            var buffer = bitmapPatternManager != null ? bitmapPatternManager.getFrameBuffer(zone) : null;
            int width = buffer != null ? buffer.getWidth() : 128;
            int height = buffer != null ? buffer.getHeight() : 128;

            int[] pixels = null;
            if (message.has("pixels")) {
                byte[] decoded = Base64.getDecoder().decode(message.get("pixels").getAsString());
                pixels = new int[decoded.length / 4];
                ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
            } else if (message.has("pixel_array")) {
                JsonArray arr = message.getAsJsonArray("pixel_array");
                pixels = new int[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    pixels[i] = arr.get(i).getAsInt();
                }
            }

            if (pixels != null) {
                if (hasMap) {
                    mapRenderer.applyFrame(zone, pixels, width, height);
                }
                if (hasWall) {
                    bitmapToEntityBridge.applyFrame(zone, pixels, width, height);
                }
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("Error processing bitmap_frame: {}", e.getMessage(), e);
            return null;
        }
    }

    /** Extract numeric index from entity IDs like "block_42". */
    private static int parseEntityIndex(String id) {
        int underscore = id.lastIndexOf('_');
        if (underscore < 0 || underscore == id.length() - 1) return -1;
        try {
            return Integer.parseInt(id.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Collection<ServerPlayerEntity> getPlayersNearZone(String zoneName) {
        var zone = zoneManager.getZone(zoneName);
        if (zone == null) return Collections.emptyList();
        ServerWorld world = zone.getWorld();
        if (world == null) return Collections.emptyList();
        Vec3d origin = zone.getOriginVec3d();
        double range = 128.0;
        return world.getPlayers(p -> p.squaredDistanceTo(origin) < range * range);
    }

    // Accessors for subsystems
    public MinecraftServer getServer() { return server; }
    public Path getConfigDir() { return configDir; }
    public ModConfig getConfig() { return config; }
    public ZoneManager getZoneManager() { return zoneManager; }
    public MessageHandler getMessageHandler() { return messageHandler; }
    public MapRendererBackend getMapRenderer() { return mapRenderer; }
    public VirtualEntityRendererBackend getVirtualRenderer() { return virtualRenderer; }
    public BitmapToEntityBridge getBitmapToEntityBridge() { return bitmapToEntityBridge; }
    public BitmapPatternManager getBitmapPatternManager() { return bitmapPatternManager; }
    public StageManager getStageManager() { return stageManager; }
    public StageDecoratorManager getStageDecoratorManager() { return stageDecoratorManager; }
    public DecoratorEntityManager getDecoratorEntityManager() { return decoratorEntityManager; }
    public MenuManager getMenuManager() { return menuManager; }
    public VoicechatIntegration getVoicechatIntegration() { return voicechatIntegration; }
    public VizWebSocketServer getWebSocketServer() { return wsServer; }
    public ZonePlacementManager getZonePlacementManager() { return zonePlacementManager; }
    public ZoneBoundaryRenderer getZoneBoundaryRenderer() { return zoneBoundaryRenderer; }
    public ZoneSelectionManager getZoneSelectionManager() { return zoneSelectionManager; }
    public ParticleEffectManager getParticleEffectManager() { return particleEffectManager; }
    public BeatEventManager getBeatEventManager() { return beatEventManager; }
    public AmbientLightManager getAmbientLightManager() { return ambientLightManager; }
}
