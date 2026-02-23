package com.audioviz.lighting;

import com.audioviz.zones.VisualizationZone;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;

import java.util.*;
import java.util.logging.Logger;

/**
 * Places invisible Light blocks around visualization zones that pulse
 * with audio intensity. Restores original blocks on teardown.
 */
public class AmbientLightManager {

    private static final int SPACING = 3;        // blocks between light points
    private static final int OFFSET = 1;         // blocks out from zone face
    private static final int MAX_LIGHTS = 40;    // cap per zone
    private static final int UPDATE_INTERVAL = 3; // ticks between updates (150ms)

    private final Logger logger;

    /** zone name -> list of light block locations */
    private final Map<String, List<Location>> zoneLights = new HashMap<>();
    /** location -> original block material (for restoration) */
    private final Map<Location, Material> originalBlocks = new HashMap<>();
    /** zone name -> current light level (avoid redundant updates) */
    private final Map<String, Integer> currentLevels = new HashMap<>();
    /** tick counter for update throttling */
    private int tickCounter = 0;

    public AmbientLightManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Calculate and place light blocks around a zone's perimeter.
     * Only places lights on the floor plane (Y = origin.Y - 1) and
     * on the side columns (X edges), spaced every SPACING blocks.
     */
    public void initializeZone(VisualizationZone zone) {
        String zoneName = zone.getName().toLowerCase();
        if (zoneLights.containsKey(zoneName)) {
            teardownZone(zoneName);
        }

        Location origin = zone.getOrigin();
        double sizeX = zone.getSize().getX();
        double sizeY = zone.getSize().getY();

        List<Location> positions = new ArrayList<>();

        // Floor row: along X at Y-1, Z centered
        for (double x = 0; x <= sizeX; x += SPACING) {
            Location loc = origin.clone().add(x, -1, -OFFSET);
            if (loc.getBlock().getType().isAir() || loc.getBlock().isLiquid()) {
                positions.add(loc);
            }
        }

        // Side columns: along Y at X=-OFFSET and X=sizeX+OFFSET
        for (double y = 0; y <= sizeY; y += SPACING) {
            Location left = origin.clone().add(-OFFSET, y, 0);
            Location right = origin.clone().add(sizeX + OFFSET, y, 0);
            if (left.getBlock().getType().isAir() || left.getBlock().isLiquid()) {
                positions.add(left);
            }
            if (right.getBlock().getType().isAir() || right.getBlock().isLiquid()) {
                positions.add(right);
            }
        }

        // Cap at MAX_LIGHTS
        if (positions.size() > MAX_LIGHTS) {
            positions = positions.subList(0, MAX_LIGHTS);
        }

        // Save originals and place initial light blocks at level 0
        for (Location loc : positions) {
            originalBlocks.put(loc, loc.getBlock().getType());
            setLightLevel(loc, 0);
        }

        zoneLights.put(zoneName, positions);
        currentLevels.put(zoneName, 0);
        logger.info("Ambient lights: initialized " + positions.size() + " lights for zone '" + zoneName + "'");
    }

    /**
     * Called every server tick. Updates light levels based on audio intensity.
     * Throttled to UPDATE_INTERVAL ticks unless isBeat is true (instant response).
     */
    public void tick(String zoneName, float intensity, boolean isBeat) {
        tickCounter++;
        if (!isBeat && tickCounter % UPDATE_INTERVAL != 0) {
            return;
        }

        List<Location> lights = zoneLights.get(zoneName.toLowerCase());
        if (lights == null || lights.isEmpty()) return;

        int targetLevel = Math.round(intensity * 15.0f);
        targetLevel = Math.max(0, Math.min(15, targetLevel));

        // On beat, flash to max
        if (isBeat) {
            targetLevel = 15;
        }

        Integer current = currentLevels.get(zoneName.toLowerCase());
        if (current != null && current == targetLevel) return;

        for (Location loc : lights) {
            setLightLevel(loc, targetLevel);
        }
        currentLevels.put(zoneName.toLowerCase(), targetLevel);
    }

    /** Remove all light blocks for a zone and restore originals. */
    public void teardownZone(String zoneName) {
        String key = zoneName.toLowerCase();
        List<Location> lights = zoneLights.remove(key);
        currentLevels.remove(key);
        if (lights == null) return;

        for (Location loc : lights) {
            Material original = originalBlocks.remove(loc);
            if (original != null) {
                loc.getBlock().setType(original, true);
            } else {
                loc.getBlock().setType(Material.AIR, true);
            }
        }
        logger.info("Ambient lights: cleaned up zone '" + zoneName + "'");
    }

    /** Remove all light blocks for all zones. */
    public void teardownAll() {
        for (String zoneName : new ArrayList<>(zoneLights.keySet())) {
            teardownZone(zoneName);
        }
    }

    private void setLightLevel(Location loc, int level) {
        Block block = loc.getBlock();
        if (level <= 0) {
            // Remove the light block
            if (block.getType() == Material.LIGHT) {
                Material original = originalBlocks.getOrDefault(loc, Material.AIR);
                block.setType(original, true);
            }
            return;
        }
        block.setType(Material.LIGHT, false);
        if (block.getBlockData() instanceof Levelled levelled) {
            levelled.setLevel(level);
            block.setBlockData(levelled, true);
        }
    }

    public boolean hasZone(String zoneName) {
        return zoneLights.containsKey(zoneName.toLowerCase());
    }
}
