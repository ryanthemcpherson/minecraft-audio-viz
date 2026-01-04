package com.audioviz;

import com.audioviz.commands.AudioVizCommand;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.websocket.VizWebSocketServer;
import com.audioviz.zones.ZoneManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class AudioVizPlugin extends JavaPlugin {

    private static AudioVizPlugin instance;
    private ZoneManager zoneManager;
    private EntityPoolManager entityPoolManager;
    private VizWebSocketServer webSocketServer;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();

        // Initialize managers
        this.zoneManager = new ZoneManager(this);
        this.entityPoolManager = new EntityPoolManager(this);

        // Load zones from config
        zoneManager.loadZones();

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
        // Save zones
        if (zoneManager != null) {
            zoneManager.saveZones();
        }

        // Cleanup entity pools
        if (entityPoolManager != null) {
            entityPoolManager.cleanupAll();
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
}
