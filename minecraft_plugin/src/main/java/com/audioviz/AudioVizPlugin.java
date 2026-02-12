package com.audioviz;

import com.audioviz.bedrock.BedrockPlayerListener;
import com.audioviz.bedrock.BedrockSupport;
import com.audioviz.commands.AudioVizCommand;
import com.audioviz.decorators.StageDecoratorManager;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.entities.EntityUpdateStats;
import com.audioviz.gui.ChatInputManager;
import com.audioviz.gui.MenuManager;
import com.audioviz.particles.ParticleVisualizationManager;
import com.audioviz.render.RendererRegistry;
import com.audioviz.stages.StageManager;
import com.audioviz.voice.VoicechatIntegration;
import com.audioviz.websocket.VizWebSocketServer;
import com.audioviz.zones.ZoneEditor;
import com.audioviz.zones.ZoneManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class AudioVizPlugin extends JavaPlugin implements Listener {

    private static AudioVizPlugin instance;
    private ZoneManager zoneManager;
    private EntityPoolManager entityPoolManager;
    private VizWebSocketServer webSocketServer;
    private MenuManager menuManager;
    private ChatInputManager chatInputManager;
    private BeatEventManager beatEventManager;
    private ZoneEditor zoneEditor;
    private EntityUpdateStats entityUpdateStats;
    private ParticleVisualizationManager particleVisualizationManager;
    private RendererRegistry rendererRegistry;
    private StageManager stageManager;
    private StageDecoratorManager decoratorManager;
    private BedrockSupport bedrockSupport;
    private VoicechatIntegration voicechatIntegration;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();

        // Initialize core managers
        this.zoneManager = new ZoneManager(this);
        this.entityPoolManager = new EntityPoolManager(this);
        this.entityUpdateStats = new EntityUpdateStats();

        // Initialize GUI and effects managers
        this.menuManager = new MenuManager(this);
        this.chatInputManager = new ChatInputManager(this);
        this.beatEventManager = new BeatEventManager(this);
        this.zoneEditor = new ZoneEditor(this);

        // Detect Geyser/Floodgate for Bedrock player support
        this.bedrockSupport = new BedrockSupport(getLogger(), getConfig());
        this.bedrockSupport.detect();

        // Initialize particle visualization manager (for Bedrock compatibility)
        this.particleVisualizationManager = new ParticleVisualizationManager(this, bedrockSupport);
        this.particleVisualizationManager.start();

        // Initialize renderer backend registry (backend selection + capability reporting)
        this.rendererRegistry = new RendererRegistry(this);

        // Register event listeners
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(menuManager, this);
        getServer().getPluginManager().registerEvents(chatInputManager, this);
        getServer().getPluginManager().registerEvents(zoneEditor, this);
        getServer().getPluginManager().registerEvents(new BedrockPlayerListener(bedrockSupport), this);

        // Initialize stage manager
        this.stageManager = new StageManager(this);

        // Initialize stage decorator manager
        this.decoratorManager = new StageDecoratorManager(this);
        this.decoratorManager.start();

        // Load zones and stages from config
        zoneManager.loadZones();
        stageManager.loadStages();

        // Register commands
        AudioVizCommand commandExecutor = new AudioVizCommand(this);
        getCommand("audioviz").setExecutor(commandExecutor);
        getCommand("audioviz").setTabCompleter(commandExecutor);

        // Detect Simple Voice Chat for audio streaming support
        // Delay by 1 tick so SVC has time to register its BukkitVoicechatService
        // (softdepend doesn't guarantee load order on Paper)
        getServer().getScheduler().runTask(this, () -> {
            try {
                var voicechatService = getServer().getServicesManager().load(
                        Class.forName("de.maxhenkel.voicechat.api.BukkitVoicechatService"));
                if (voicechatService != null) {
                    voicechatIntegration = new VoicechatIntegration(this);
                    var registerMethod = voicechatService.getClass().getMethod("registerPlugin",
                            Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin"));
                    registerMethod.invoke(voicechatService, voicechatIntegration);
                    getLogger().info("Simple Voice Chat detected - audio streaming enabled");
                } else {
                    getLogger().info("Simple Voice Chat not installed - audio streaming disabled");
                }
            } catch (ClassNotFoundException e) {
                getLogger().info("Simple Voice Chat not installed - audio streaming disabled");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to initialize Simple Voice Chat integration", e);
            }
        });

        // Start WebSocket server with retry (port may linger briefly after restart)
        int wsPort = getConfig().getInt("websocket.port", 8765);
        startWebSocketWithRetry(wsPort, 5, 2000);

        getLogger().info("AudioViz plugin enabled!");
    }

    /**
     * Start the WebSocket server with retries to handle port still held by a
     * previous process (e.g. zombie Java after restart). Each attempt waits
     * {@code delayMs} before retrying, up to {@code maxRetries} times.
     */
    private void startWebSocketWithRetry(int port, int maxRetries, long delayMs) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    VizWebSocketServer server = new VizWebSocketServer(this, port);
                    server.start();
                    // Wait briefly to let the selector thread bind
                    Thread.sleep(500);
                    // Check if it actually started by verifying onStart was reached
                    webSocketServer = server;
                    getLogger().info("WebSocket server started on port " + port +
                        (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                    return;
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    if (attempt < maxRetries) {
                        getLogger().warning("WebSocket bind attempt " + attempt + "/" + maxRetries +
                            " failed: " + msg + " — retrying in " + (delayMs / 1000) + "s");
                        try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { return; }
                    } else {
                        getLogger().log(Level.SEVERE,
                            "Failed to start WebSocket server after " + maxRetries + " attempts", e);
                    }
                }
            }
        });
    }

    @Override
    public void onDisable() {
        // Unregister all event listeners to prevent leaks on plugin reload
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);

        // Clear menu manager sessions (prevent stale player references)
        if (menuManager != null) {
            menuManager.clearAllSessions();
        }

        // Save zones and stages
        if (stageManager != null) {
            stageManager.saveStages();
        }
        if (zoneManager != null) {
            zoneManager.saveZones();
        }

        // Stop decorator manager before entity cleanup
        if (decoratorManager != null) {
            decoratorManager.stop();
        }

        // Shutdown voice chat integration before WebSocket server
        if (voicechatIntegration != null) {
            try {
                voicechatIntegration.shutdown();
            } catch (Exception e) {
                getLogger().warning("Error shutting down voice chat integration: " + e.getMessage());
            }
        }

        // Stop WebSocket server - must complete BEFORE classloader closes the JAR
        // otherwise background threads (WebSocketWorker, WebSocketSelector) crash
        // with "zip file closed" when they try to load classes from the old JAR
        if (webSocketServer != null) {
            try {
                // Full shutdown stops heartbeat tasks, message queue, and socket threads.
                webSocketServer.shutdown();
                getLogger().info("WebSocket server stopped");
            } catch (Exception e) {
                getLogger().warning("Error stopping WebSocket server: " + e.getMessage());
            }
        }

        // Stop particle visualization manager
        if (particleVisualizationManager != null) {
            particleVisualizationManager.stop();
        }

        if (rendererRegistry != null) {
            rendererRegistry.clearAll();
        }

        // Cleanup entity pools synchronously - scheduling new tasks while disabled
        // can throw IllegalPluginAccessException during reload.
        if (entityPoolManager != null) {
            entityPoolManager.cleanupAllSync();
        }

        getLogger().info("AudioViz plugin disabled!");
    }

    /**
     * Clean up entity pools in zones belonging to an unloaded world
     * to prevent stale entity references.
     */
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        String worldName = event.getWorld().getName();
        for (var zone : zoneManager.getAllZones()) {
            if (zone.getWorld().getName().equals(worldName)) {
                getLogger().info("World '" + worldName + "' unloading, cleaning up zone '" + zone.getName() + "'");
                entityPoolManager.cleanupZoneSync(zone.getName());
            }
        }
    }

    public static AudioVizPlugin getInstance() {
        return instance;
    }

    public ZoneManager getZoneManager() {
        return zoneManager;
    }

    public EntityPoolManager getEntityPoolManager() {
        return entityPoolManager;
    }

    public VizWebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    public MenuManager getMenuManager() {
        return menuManager;
    }

    public ChatInputManager getChatInputManager() {
        return chatInputManager;
    }

    public BeatEventManager getBeatEventManager() {
        return beatEventManager;
    }

    public ZoneEditor getZoneEditor() {
        return zoneEditor;
    }

    public EntityUpdateStats getEntityUpdateStats() {
        return entityUpdateStats;
    }

    public ParticleVisualizationManager getParticleVisualizationManager() {
        return particleVisualizationManager;
    }

    public RendererRegistry getRendererRegistry() {
        return rendererRegistry;
    }

    public StageManager getStageManager() {
        return stageManager;
    }

    public StageDecoratorManager getDecoratorManager() {
        return decoratorManager;
    }

    public BedrockSupport getBedrockSupport() {
        return bedrockSupport;
    }

    public VoicechatIntegration getVoicechatIntegration() {
        return voicechatIntegration;
    }
}
