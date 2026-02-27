package com.audioviz.zones;

import com.audioviz.AudioVizMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all visualization zones — creation, persistence, and lookup.
 * Ported from Paper: YAML persistence → JSON (Gson), Bukkit World → ServerWorld.
 */
public class ZoneManager {

    private final Map<String, VisualizationZone> zones = new ConcurrentHashMap<>();
    private final Path zonesFile;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public ZoneManager(Path configDir) {
        this.zonesFile = configDir.resolve("zones.json");
    }

    public VisualizationZone createZone(String name, ServerWorld world, BlockPos origin) {
        if (zones.containsKey(name.toLowerCase())) {
            return null;
        }

        VisualizationZone zone = new VisualizationZone(name, world, origin);
        zones.put(name.toLowerCase(), zone);
        saveZones();
        return zone;
    }

    public VisualizationZone getZone(String name) {
        return zones.get(name.toLowerCase());
    }

    public Collection<VisualizationZone> getAllZones() {
        return Collections.unmodifiableCollection(zones.values());
    }

    public Set<String> getZoneNames() {
        return Collections.unmodifiableSet(zones.keySet());
    }

    public int getZoneCount() {
        return zones.size();
    }

    public boolean deleteZone(String name) {
        VisualizationZone removed = zones.remove(name.toLowerCase());
        if (removed != null) {
            saveZones();
            return true;
        }
        return false;
    }

    public boolean zoneExists(String name) {
        return zones.containsKey(name.toLowerCase());
    }

    public VisualizationZone findZoneAt(Vec3d position) {
        for (VisualizationZone zone : zones.values()) {
            if (zone.contains(position)) {
                return zone;
            }
        }
        return null;
    }

    /**
     * Load zones from JSON config file.
     */
    public void loadZones(MinecraftServer server) {
        if (!Files.exists(zonesFile)) {
            AudioVizMod.LOGGER.info("No zones file found, starting fresh");
            return;
        }

        try {
            String json = Files.readString(zonesFile);
            JsonArray zonesArray = JsonParser.parseString(json).getAsJsonArray();

            for (var elem : zonesArray) {
                JsonObject zoneData = elem.getAsJsonObject();
                try {
                    String name = zoneData.get("name").getAsString();
                    UUID id = UUID.fromString(zoneData.get("id").getAsString());
                    String worldId = zoneData.has("world") ? zoneData.get("world").getAsString() : "minecraft:overworld";

                    ServerWorld world = findWorld(server, worldId);
                    if (world == null) {
                        AudioVizMod.LOGGER.warn("World '{}' not found for zone '{}'", worldId, name);
                        continue;
                    }

                    BlockPos origin = new BlockPos(
                        zoneData.get("origin_x").getAsInt(),
                        zoneData.get("origin_y").getAsInt(),
                        zoneData.get("origin_z").getAsInt()
                    );

                    Vector3f size = new Vector3f(
                        zoneData.has("size_x") ? zoneData.get("size_x").getAsFloat() : 10,
                        zoneData.has("size_y") ? zoneData.get("size_y").getAsFloat() : 10,
                        zoneData.has("size_z") ? zoneData.get("size_z").getAsFloat() : 10
                    );

                    float rotation = zoneData.has("rotation") ? zoneData.get("rotation").getAsFloat() : 0;

                    VisualizationZone zone = new VisualizationZone(name, id, world, origin, size, rotation);
                    zones.put(name.toLowerCase(), zone);
                    AudioVizMod.LOGGER.info("Loaded zone: {}", zone);

                } catch (Exception e) {
                    AudioVizMod.LOGGER.warn("Failed to load zone: {}", e.getMessage());
                }
            }

            AudioVizMod.LOGGER.info("Loaded {} visualization zone(s)", zones.size());

        } catch (IOException e) {
            AudioVizMod.LOGGER.error("Failed to read zones file", e);
        }
    }

    /**
     * Save zones to JSON config file.
     */
    public void saveZones() {
        JsonArray zonesArray = new JsonArray();

        for (VisualizationZone zone : zones.values()) {
            JsonObject zoneData = new JsonObject();
            zoneData.addProperty("name", zone.getName());
            zoneData.addProperty("id", zone.getId().toString());
            zoneData.addProperty("world", zone.getWorld().getRegistryKey().getValue().toString());
            zoneData.addProperty("origin_x", zone.getOrigin().getX());
            zoneData.addProperty("origin_y", zone.getOrigin().getY());
            zoneData.addProperty("origin_z", zone.getOrigin().getZ());
            zoneData.addProperty("size_x", zone.getSize().x);
            zoneData.addProperty("size_y", zone.getSize().y);
            zoneData.addProperty("size_z", zone.getSize().z);
            zoneData.addProperty("rotation", zone.getRotation());
            zonesArray.add(zoneData);
        }

        try {
            Files.createDirectories(zonesFile.getParent());
            Files.writeString(zonesFile, GSON.toJson(zonesArray));
        } catch (IOException e) {
            AudioVizMod.LOGGER.error("Failed to save zones: {}", e.getMessage());
        }
    }

    private ServerWorld findWorld(MinecraftServer server, String worldId) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(worldId)) {
                return world;
            }
        }
        // Fallback: try matching just the path (e.g., "overworld" matches "minecraft:overworld")
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().getPath().equals(worldId)) {
                return world;
            }
        }
        return null;
    }
}
