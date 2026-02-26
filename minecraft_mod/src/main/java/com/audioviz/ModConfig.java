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
        ModConfig config;
        if (Files.exists(file)) {
            config = GSON.fromJson(Files.readString(file), ModConfig.class);
            if (config == null) config = new ModConfig();
            config.validate();
        } else {
            config = new ModConfig();
        }
        config.save(configDir);
        return config;
    }

    private void validate() {
        websocketPort = clamp(websocketPort, 1024, 65535);
        djPort = clamp(djPort, 1024, 65535);
        maxEntitiesPerZone = clamp(maxEntitiesPerZone, 1, 10000);
        maxPixelsPerZone = clamp(maxPixelsPerZone, 1, 10000);
        bitmapPixelsPerBlock = clamp(bitmapPixelsPerBlock, 1, 16);
        mapUpdateIntervalTicks = clamp(mapUpdateIntervalTicks, 1, 100);
        if (defaultRendererBackend == null) defaultRendererBackend = "MAP";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void save(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("audioviz.json"), GSON.toJson(this));
    }
}
