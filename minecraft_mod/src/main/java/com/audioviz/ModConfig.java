package com.audioviz;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    // WebSocket
    public int websocketPort = 8765;
    public int djPort = 9000;

    // Rendering
    public int maxEntitiesPerZone = 1000;
    public int maxPixelsPerZone = 500;
    public int bitmapPixelsPerBlock = 4;
    public String defaultRendererBackend = "MAP"; // MAP, VIRTUAL_ENTITY, PARTICLE

    // Performance
    public boolean useMapRenderer = true;
    public boolean useBundlePackets = true;
    public int mapUpdateIntervalTicks = 1; // every tick = 20fps

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ModConfig load(Path configDir) throws IOException {
        Path file = configDir.resolve("audioviz.json");
        if (Files.exists(file)) {
            return GSON.fromJson(Files.readString(file), ModConfig.class);
        }
        ModConfig config = new ModConfig();
        config.save(configDir);
        return config;
    }

    public void save(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("audioviz.json"), GSON.toJson(this));
    }
}
