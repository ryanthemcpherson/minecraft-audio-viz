package com.audioviz.zones;

import com.audioviz.AudioVizPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all visualization zones - creation, persistence, and lookup.
 */
public class ZoneManager {

    private final AudioVizPlugin plugin;
    private final Map<String, VisualizationZone> zones;
    private final File zonesFile;

    public ZoneManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.zones = new ConcurrentHashMap<>();
        this.zonesFile = new File(plugin.getDataFolder(), "zones.yml");
    }

    /**
     * Create a new zone at the given location.
     */
    public VisualizationZone createZone(String name, Location origin) {
        if (zones.containsKey(name.toLowerCase())) {
            return null; // Zone already exists
        }

        VisualizationZone zone = new VisualizationZone(name, origin);
        zones.put(name.toLowerCase(), zone);
        saveZones();
        return zone;
    }

    /**
     * Get a zone by name.
     */
    public VisualizationZone getZone(String name) {
        return zones.get(name.toLowerCase());
    }

    /**
     * Get all zones.
     */
    public Collection<VisualizationZone> getAllZones() {
        return Collections.unmodifiableCollection(zones.values());
    }

    /**
     * Get zone names.
     */
    public Set<String> getZoneNames() {
        return Collections.unmodifiableSet(zones.keySet());
    }

    /**
     * Get the number of zones.
     */
    public int getZoneCount() {
        return zones.size();
    }

    /**
     * Delete a zone.
     */
    public boolean deleteZone(String name) {
        VisualizationZone removed = zones.remove(name.toLowerCase());
        if (removed != null) {
            // Cleanup entities in this zone
            plugin.getEntityPoolManager().cleanupZone(name.toLowerCase());
            saveZones();
            return true;
        }
        return false;
    }

    /**
     * Check if a zone exists.
     */
    public boolean zoneExists(String name) {
        return zones.containsKey(name.toLowerCase());
    }

    /**
     * Find zone containing a location.
     */
    public VisualizationZone findZoneAt(Location location) {
        for (VisualizationZone zone : zones.values()) {
            if (zone.contains(location)) {
                return zone;
            }
        }
        return null;
    }

    /**
     * Load zones from config file.
     */
    public void loadZones() {
        if (!zonesFile.exists()) {
            plugin.getLogger().info("No zones file found, starting fresh.");
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(zonesFile);
        ConfigurationSection zonesSection = config.getConfigurationSection("zones");

        if (zonesSection == null) {
            return;
        }

        for (String zoneName : zonesSection.getKeys(false)) {
            ConfigurationSection zoneData = zonesSection.getConfigurationSection(zoneName);
            if (zoneData == null) continue;

            try {
                // Load zone data
                UUID id = UUID.fromString(zoneData.getString("id", UUID.randomUUID().toString()));
                String worldName = zoneData.getString("world", "world");
                World world = Bukkit.getWorld(worldName);

                if (world == null) {
                    plugin.getLogger().warning("World '" + worldName + "' not found for zone '" + zoneName + "'");
                    continue;
                }

                Location origin = new Location(
                    world,
                    zoneData.getDouble("origin.x", 0),
                    zoneData.getDouble("origin.y", 64),
                    zoneData.getDouble("origin.z", 0)
                );

                Vector size = new Vector(
                    zoneData.getDouble("size.x", 10),
                    zoneData.getDouble("size.y", 10),
                    zoneData.getDouble("size.z", 10)
                );

                float rotation = (float) zoneData.getDouble("rotation", 0);

                VisualizationZone zone = new VisualizationZone(zoneName, id, origin, size, rotation);
                zones.put(zoneName.toLowerCase(), zone);

                plugin.getLogger().info("Loaded zone: " + zone);

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load zone '" + zoneName + "': " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + zones.size() + " visualization zone(s)");
    }

    /**
     * Save zones to config file.
     */
    public void saveZones() {
        FileConfiguration config = new YamlConfiguration();

        for (VisualizationZone zone : zones.values()) {
            String path = "zones." + zone.getName();
            config.set(path + ".id", zone.getId().toString());
            config.set(path + ".world", zone.getWorld().getName());
            config.set(path + ".origin.x", zone.getOrigin().getX());
            config.set(path + ".origin.y", zone.getOrigin().getY());
            config.set(path + ".origin.z", zone.getOrigin().getZ());
            config.set(path + ".size.x", zone.getSize().getX());
            config.set(path + ".size.y", zone.getSize().getY());
            config.set(path + ".size.z", zone.getSize().getZ());
            config.set(path + ".rotation", zone.getRotation());
        }

        try {
            config.save(zonesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save zones: " + e.getMessage());
        }
    }
}
