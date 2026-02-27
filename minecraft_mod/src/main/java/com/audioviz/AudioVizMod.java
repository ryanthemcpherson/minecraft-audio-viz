package com.audioviz;

import com.audioviz.bitmap.BitmapPatternManager;
import com.audioviz.decorators.StageDecoratorManager;
import com.audioviz.gui.MenuManager;
import com.audioviz.patterns.AudioState;
import com.audioviz.protocol.MessageHandler;
import com.audioviz.protocol.MessageQueue;
import com.audioviz.render.MapRendererBackend;
import com.audioviz.render.VirtualEntityRendererBackend;
import com.audioviz.stages.StageManager;
import com.audioviz.voice.VoicechatIntegration;
import com.audioviz.websocket.VizWebSocketServer;
import com.audioviz.zones.ZoneManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;

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

        // Shutdown stage decorators
        if (stageDecoratorManager != null) {
            stageDecoratorManager.shutdown();
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
    public MenuManager getMenuManager() { return menuManager; }
    public VoicechatIntegration getVoicechatIntegration() { return voicechatIntegration; }
    public VizWebSocketServer getWebSocketServer() { return wsServer; }
}
