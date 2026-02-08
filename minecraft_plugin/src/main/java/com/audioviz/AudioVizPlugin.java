package com.audioviz;

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
import com.audioviz.websocket.VizWebSocketServer;
import com.audioviz.zones.ZoneEditor;
import com.audioviz.zones.ZoneManager;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class AudioVizPlugin extends JavaPlugin {

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

        // Initialize particle visualization manager (for Bedrock compatibility)
        this.particleVisualizationManager = new ParticleVisualizationManager(this);
        this.particleVisualizationManager.start();

        // Initialize renderer backend registry (backend selection + capability reporting)
        this.rendererRegistry = new RendererRegistry(this);

        // Register event listeners
        getServer().getPluginManager().registerEvents(menuManager, this);
        getServer().getPluginManager().registerEvents(chatInputManager, this);
        getServer().getPluginManager().registerEvents(zoneEditor, this);

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

        // Start WebSocket server
        int wsPort = getConfig().getInt("websocket.port", 8765);
        try {
            webSocketServer = new VizWebSocketServer(this, wsPort);
            webSocketServer.start();
            getLogger().info("WebSocket server started on port " + wsPort);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start WebSocket server", e);
        }

        getLogger().info("AudioViz plugin enabled!");
    }

    @Override
    public void onDisable() {
        // Unregister all event listeners to prevent leaks on plugin reload
        HandlerList.unregisterAll(this);

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

        // Cleanup entity pools
        if (entityPoolManager != null) {
            entityPoolManager.cleanupAll();
        }

        // Stop particle visualization manager
        if (particleVisualizationManager != null) {
            particleVisualizationManager.stop();
        }

        if (rendererRegistry != null) {
            rendererRegistry.clearAll();
        }

        // Stop WebSocket server
        if (webSocketServer != null) {
            try {
                webSocketServer.stop(1000);
                getLogger().info("WebSocket server stopped");
            } catch (InterruptedException e) {
                getLogger().warning("Error stopping WebSocket server: " + e.getMessage());
            }
        }

        getLogger().info("AudioViz plugin disabled!");
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
}
