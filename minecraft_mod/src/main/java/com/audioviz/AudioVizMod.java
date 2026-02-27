package com.audioviz;

import com.audioviz.bitmap.BitmapPatternManager;
import com.audioviz.decorators.DecoratorEntityManager;
import com.audioviz.decorators.StageDecoratorManager;
import com.audioviz.gui.MenuManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.audioviz.render.MapRendererBackend;
import com.audioviz.render.VirtualEntityRendererBackend;
import com.audioviz.stages.StageManager;
import com.audioviz.virtual.VirtualEntityPool;
import com.audioviz.voice.VoicechatIntegration;
import com.audioviz.websocket.VizWebSocketServer;
import com.audioviz.zones.VisualizationZone;
import com.audioviz.zones.ZoneManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import java.util.*;;

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
    private BitmapPatternManager bitmapPatternManager;
    private StageManager stageManager;
    private StageDecoratorManager stageDecoratorManager;
    private DecoratorEntityManager decoratorEntityManager;
    private MenuManager menuManager;
    private VoicechatIntegration voicechatIntegration;

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

        // Initialize bitmap pattern engine
        bitmapPatternManager = new BitmapPatternManager(server, mapRenderer);

        // Initialize stage & decorator systems
        stageManager = new StageManager(this);
        stageManager.loadStages();
        decoratorEntityManager = new DecoratorEntityManager();
        stageDecoratorManager = new StageDecoratorManager(this);

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
        wsServer = new VizWebSocketServer(wsAddress, config.websocketPort, messageHandler, messageQueue);
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

        // 4. Flush map display updates to players
        for (String zoneName : mapRenderer.getActiveZones()) {
            var players = getPlayersNearZone(zoneName);
            mapRenderer.flush(zoneName, players);
        }

        // 5. Flush virtual entity updates
        for (String zoneName : virtualRenderer.getActiveZones()) {
            virtualRenderer.flush(zoneName);
        }

        // 6. Tick stage decorators (runs at half rate internally)
        if (stageDecoratorManager != null) {
            stageDecoratorManager.tick();
        }

        // 7. Tick voice chat audio drain
        if (voicechatIntegration != null) {
            voicechatIntegration.tick();
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

        // Clear GUI sessions
        if (menuManager != null) {
            menuManager.clearAllSessions();
        }

        // Destroy all active displays
        if (mapRenderer != null) {
            for (String zone : mapRenderer.getActiveZones()) {
                mapRenderer.destroyDisplay(zone);
            }
        }
        if (virtualRenderer != null) {
            for (String zone : virtualRenderer.getActiveZones()) {
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

            double x = e.has("x") ? e.get("x").getAsDouble() : 0.5;
            double y = e.has("y") ? e.get("y").getAsDouble() : 0.5;
            double z = e.has("z") ? e.get("z").getAsDouble() : 0.5;
            Vec3d worldPos = vizZone.localToWorld(x, y, z);
            Vec3d relative = worldPos.subtract(origin);

            float scale = e.has("scale") ? e.get("scale").getAsFloat() : 0.2f;
            Vector3f scaleVec = new Vector3f(scale, scale, scale);

            updates.add(new VirtualEntityPool.EntityUpdate(index, relative, scaleVec, null));
        }

        if (!updates.isEmpty()) {
            virtualRenderer.applyBatchUpdate(zone, updates);
        }
        return null;
    }

    /**
     * Parse bitmap_frame JSON (base64 or array) and push pixels to the map renderer.
     */
    private JsonObject handleBitmapFrameRendering(JsonObject message) {
        String zone = message.has("zone") ? message.get("zone").getAsString() : null;
        if (zone == null) return null;

        if (!mapRenderer.hasDisplay(zone)) return null;

        var buffer = bitmapPatternManager.getFrameBuffer(zone);
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
            mapRenderer.applyFrame(zone, pixels, width, height);
        }
        return null;
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
    public BitmapPatternManager getBitmapPatternManager() { return bitmapPatternManager; }
    public StageManager getStageManager() { return stageManager; }
    public StageDecoratorManager getStageDecoratorManager() { return stageDecoratorManager; }
    public DecoratorEntityManager getDecoratorEntityManager() { return decoratorEntityManager; }
    public MenuManager getMenuManager() { return menuManager; }
    public VoicechatIntegration getVoicechatIntegration() { return voicechatIntegration; }
    public VizWebSocketServer getWebSocketServer() { return wsServer; }
}
