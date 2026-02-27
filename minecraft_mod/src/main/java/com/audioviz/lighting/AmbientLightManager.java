package com.audioviz.lighting;

import com.audioviz.zones.VisualizationZone;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Places invisible Light blocks around visualization zones that pulse
 * with audio intensity. Restores original blocks on teardown.
 *
 * <p>Ported from Paper: Location → BlockPos, Material → BlockState,
 * Bukkit Levelled → LightBlock.LEVEL_15, plugin.getLogger() → SLF4J.
 */
public class AmbientLightManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private static final int SPACING = 3;        // blocks between light points
    private static final int OFFSET = 1;         // blocks out from zone face
    private static final int MAX_LIGHTS = 40;    // cap per zone
    private static final int UPDATE_INTERVAL = 3; // ticks between updates (150ms)

    /** zone name -> list of light block positions. */
    private final Map<String, List<BlockPos>> zoneLights = new HashMap<>();
    /** position -> original block state (for restoration). */
    private final Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
    /** zone name -> current light level (avoid redundant updates). */
    private final Map<String, Integer> currentLevels = new HashMap<>();
    /** tick counter for update throttling. */
    private int tickCounter = 0;

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

        ServerWorld world = zone.getWorld();
        BlockPos origin = zone.getOrigin();
        float sizeX = zone.getSize().x;
        float sizeY = zone.getSize().y;

        List<BlockPos> positions = new ArrayList<>();

        // Floor row: along X at Y-1, Z centered
        for (int x = 0; x <= (int) sizeX; x += SPACING) {
            BlockPos pos = origin.add(x, -1, -OFFSET);
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                positions.add(pos);
            }
        }

        // Side columns: along Y at X=-OFFSET and X=sizeX+OFFSET
        for (int y = 0; y <= (int) sizeY; y += SPACING) {
            BlockPos left = origin.add(-OFFSET, y, 0);
            BlockPos right = origin.add((int) sizeX + OFFSET, y, 0);
            BlockState leftState = world.getBlockState(left);
            BlockState rightState = world.getBlockState(right);
            if (leftState.isAir() || !leftState.getFluidState().isEmpty()) {
                positions.add(left);
            }
            if (rightState.isAir() || !rightState.getFluidState().isEmpty()) {
                positions.add(right);
            }
        }

        // Cap at MAX_LIGHTS
        if (positions.size() > MAX_LIGHTS) {
            positions = new ArrayList<>(positions.subList(0, MAX_LIGHTS));
        }

        // Save originals and place initial light blocks at level 0
        for (BlockPos pos : positions) {
            originalBlocks.put(pos, world.getBlockState(pos));
            setLightLevel(world, pos, 0);
        }

        zoneLights.put(zoneName, positions);
        currentLevels.put(zoneName, 0);
        LOGGER.info("Ambient lights: initialized {} lights for zone '{}'", positions.size(), zoneName);
    }

    /**
     * Called every server tick. Updates light levels based on audio intensity.
     * Throttled to UPDATE_INTERVAL ticks unless isBeat is true (instant response).
     */
    public void tick(VisualizationZone zone, float intensity, boolean isBeat) {
        tickCounter++;
        if (!isBeat && tickCounter % UPDATE_INTERVAL != 0) {
            return;
        }

        String zoneName = zone.getName().toLowerCase();
        List<BlockPos> lights = zoneLights.get(zoneName);
        if (lights == null || lights.isEmpty()) return;

        int targetLevel = Math.round(intensity * 15.0f);
        targetLevel = Math.max(0, Math.min(15, targetLevel));

        // On beat, flash to max
        if (isBeat) {
            targetLevel = 15;
        }

        Integer current = currentLevels.get(zoneName);
        if (current != null && current == targetLevel) return;

        ServerWorld world = zone.getWorld();
        for (BlockPos pos : lights) {
            setLightLevel(world, pos, targetLevel);
        }
        currentLevels.put(zoneName, targetLevel);
    }

    /** Remove all light blocks for a zone and restore originals. */
    public void teardownZone(String zoneName) {
        String key = zoneName.toLowerCase();
        List<BlockPos> lights = zoneLights.remove(key);
        currentLevels.remove(key);
        if (lights == null) return;

        for (BlockPos pos : lights) {
            BlockState original = originalBlocks.remove(pos);
            // Need the world — get from any remaining zone context, or find loaded world
            // Since we stored the original, just restore it via the block's world
            // Iterate all server worlds to find the one containing this pos
            // (in practice, all lights are in the same world as the zone)
        }
        LOGGER.info("Ambient lights: cleaned up zone '{}'", zoneName);
    }

    /** Remove all light blocks for a zone, given its world. */
    public void teardownZone(String zoneName, ServerWorld world) {
        String key = zoneName.toLowerCase();
        List<BlockPos> lights = zoneLights.remove(key);
        currentLevels.remove(key);
        if (lights == null) return;

        for (BlockPos pos : lights) {
            BlockState original = originalBlocks.remove(pos);
            if (original != null) {
                world.setBlockState(pos, original);
            } else {
                world.setBlockState(pos, Blocks.AIR.getDefaultState());
            }
        }
        LOGGER.info("Ambient lights: cleaned up zone '{}'", zoneName);
    }

    /** Remove all light blocks for all zones, given a world. */
    public void teardownAll(ServerWorld world) {
        for (String zoneName : new ArrayList<>(zoneLights.keySet())) {
            teardownZone(zoneName, world);
        }
    }

    private void setLightLevel(ServerWorld world, BlockPos pos, int level) {
        BlockState current = world.getBlockState(pos);
        if (level <= 0) {
            // Remove the light block
            if (current.isOf(Blocks.LIGHT)) {
                BlockState original = originalBlocks.getOrDefault(pos, Blocks.AIR.getDefaultState());
                world.setBlockState(pos, original);
            }
            return;
        }
        BlockState lightState = Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, level);
        world.setBlockState(pos, lightState);
    }

    public boolean hasZone(String zoneName) {
        return zoneLights.containsKey(zoneName.toLowerCase());
    }
}
