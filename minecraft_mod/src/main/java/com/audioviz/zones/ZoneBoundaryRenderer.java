package com.audioviz.zones;

import com.audioviz.AudioVizMod;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders particle outlines along zone bounding-box edges so operators
 * can see zone placement in-world. Boundaries auto-hide after a timeout
 * to avoid permanent visual clutter.
 *
 * <p>Ported from Paper: BukkitTask → tick-based (called from AudioVizMod),
 * Location → Vec3d, Particle.DustOptions → DustParticleEffect,
 * world.spawnParticle → per-player packet sending.
 */
public class ZoneBoundaryRenderer {

    private final AudioVizMod mod;

    /** Zone name (lowercase) -> tick when boundaries should auto-hide. */
    private final Map<String, Long> activeZones = new ConcurrentHashMap<>();

    /** Zones marked as persistent (won't auto-hide). */
    private final Set<String> persistentZones = ConcurrentHashMap.newKeySet();

    /** Currently selected zone names (rendered with a distinct color). */
    private final Set<String> selectedZones = ConcurrentHashMap.newKeySet();

    /** How long boundaries stay visible before auto-hiding (ticks). 30 seconds = 600 ticks. */
    private static final long DEFAULT_TIMEOUT_TICKS = 600L;

    /** Distance between particles along each edge (blocks). */
    private static final double PARTICLE_SPACING = 0.5;

    /** Max distance (squared) from a zone center for a player to see particles. */
    private static final double VIEW_RANGE_SQ = 64.0 * 64.0;

    /** Render interval in ticks (10 ticks = 0.5 seconds). */
    private static final int RENDER_INTERVAL = 10;

    /** Monotonic tick counter (incremented each tick). */
    private long tickCount = 0;

    // Edge / corner definitions for a unit cube [0,1]^3
    private static final double[][] CORNERS = {
        {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}, // Bottom
        {0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}  // Top
    };
    private static final int[][] EDGES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0}, // Bottom
        {4, 5}, {5, 6}, {6, 7}, {7, 4}, // Top
        {0, 4}, {1, 5}, {2, 6}, {3, 7}  // Verticals
    };

    // Particle colors (packed RGB)
    private static final int COLOR_DEFAULT_EDGE = 0x00C8FF;    // Cyan
    private static final int COLOR_DEFAULT_CORNER = 0x00FF80;  // Green
    private static final int COLOR_SELECTED_EDGE = 0xFFD700;   // Gold
    private static final int COLOR_SELECTED_CORNER = 0xFFFF64; // Bright gold

    public ZoneBoundaryRenderer(AudioVizMod mod) {
        this.mod = mod;
    }

    /**
     * Show boundaries for a zone with the default 30-second timeout.
     */
    public void show(String zoneName) {
        show(zoneName, DEFAULT_TIMEOUT_TICKS);
    }

    /**
     * Show boundaries for a zone with a custom timeout (in ticks).
     * Pass {@code Long.MAX_VALUE} for persistent (no auto-hide).
     */
    public void show(String zoneName, long timeoutTicks) {
        long expiry = (timeoutTicks == Long.MAX_VALUE) ? Long.MAX_VALUE : tickCount + timeoutTicks;
        activeZones.put(zoneName.toLowerCase(), expiry);
    }

    /**
     * Hide boundaries for a zone immediately.
     */
    public void hide(String zoneName) {
        activeZones.remove(zoneName.toLowerCase());
    }

    /**
     * Toggle boundaries for a zone (with default timeout).
     * @return true if now showing, false if now hidden
     */
    public boolean toggle(String zoneName) {
        String key = zoneName.toLowerCase();
        if (activeZones.containsKey(key)) {
            activeZones.remove(key);
            return false;
        } else {
            show(zoneName);
            return true;
        }
    }

    /**
     * Show boundaries for ALL zones with the default timeout.
     */
    public void showAll() {
        for (String name : mod.getZoneManager().getZoneNames()) {
            show(name);
        }
    }

    /**
     * Hide all zone boundaries.
     */
    public void hideAll() {
        activeZones.clear();
    }

    /**
     * Mark a zone as persistent (won't auto-hide) or remove persistence.
     */
    public void setPersistent(String zoneName, boolean persistent) {
        String key = zoneName.toLowerCase();
        if (persistent) {
            persistentZones.add(key);
            activeZones.put(key, Long.MAX_VALUE);
        } else {
            persistentZones.remove(key);
        }
    }

    /**
     * Add a zone to the selection — rendered with a distinct highlight color.
     */
    public void setSelected(String zoneName) {
        if (zoneName != null) {
            selectedZones.add(zoneName.toLowerCase());
        }
    }

    /**
     * Remove a zone from the selection highlight.
     */
    public void clearSelected(String zoneName) {
        if (zoneName != null) {
            selectedZones.remove(zoneName.toLowerCase());
        }
    }

    /**
     * Clear all selection highlights.
     */
    public void clearSelection() {
        selectedZones.clear();
    }

    /**
     * Show all zones belonging to a stage with persistent boundaries.
     */
    public void showStage(String stageName) {
        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage == null) return;

        for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
            String zoneName = entry.getValue();
            show(zoneName, Long.MAX_VALUE);
            setPersistent(zoneName, true);
        }
    }

    /**
     * Hide all zones belonging to a stage.
     */
    public void hideStage(String stageName) {
        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage == null) return;

        for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
            String zoneName = entry.getValue();
            hide(zoneName);
            setPersistent(zoneName, false);
        }
    }

    /**
     * Check if boundaries are currently visible for a zone.
     */
    public boolean isShowing(String zoneName) {
        return activeZones.containsKey(zoneName.toLowerCase());
    }

    /**
     * Get the number of zones currently showing boundaries.
     */
    public int activeCount() {
        return activeZones.size();
    }

    // ==================== Tick (called from AudioVizMod) ====================

    /**
     * Called every server tick from the main tick loop.
     * Only performs the render pass every RENDER_INTERVAL ticks.
     */
    public void tick() {
        tickCount++;
        if (tickCount % RENDER_INTERVAL != 0) return;
        if (activeZones.isEmpty()) return;

        var iterator = activeZones.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (tickCount >= entry.getValue() && !persistentZones.contains(entry.getKey())) {
                iterator.remove();
                continue;
            }

            VisualizationZone zone = mod.getZoneManager().getZone(entry.getKey());
            if (zone == null) {
                iterator.remove();
                continue;
            }

            ServerWorld world = zone.getWorld();
            if (world == null) continue;

            // Only render if players are nearby
            Vec3d center = zone.getCenter();
            var nearbyPlayers = world.getPlayers(
                p -> p.squaredDistanceTo(center) <= VIEW_RANGE_SQ);
            if (!nearbyPlayers.isEmpty()) {
                renderZoneBoundary(zone, nearbyPlayers);
            }
        }
    }

    /**
     * Stop the renderer and clear all state.
     */
    public void stop() {
        activeZones.clear();
        persistentZones.clear();
        selectedZones.clear();
    }

    // ==================== Render ====================

    private void renderZoneBoundary(VisualizationZone zone,
                                     java.util.List<ServerPlayerEntity> players) {
        // Compute world-space corners
        Vec3d[] worldCorners = new Vec3d[8];
        for (int i = 0; i < 8; i++) {
            worldCorners[i] = zone.localToWorld(CORNERS[i][0], CORNERS[i][1], CORNERS[i][2]);
        }

        boolean isSelected = selectedZones.contains(zone.getName().toLowerCase());
        int edgeColor = isSelected ? COLOR_SELECTED_EDGE : COLOR_DEFAULT_EDGE;
        int cornerColor = isSelected ? COLOR_SELECTED_CORNER : COLOR_DEFAULT_CORNER;
        float edgeSize = isSelected ? 1.0f : 0.8f;
        float cornerSize = isSelected ? 1.5f : 1.3f;

        // Draw 12 edges
        for (int[] edge : EDGES) {
            drawLine(players, worldCorners[edge[0]], worldCorners[edge[1]], edgeColor, edgeSize);
        }

        // Draw corners with larger particles
        for (Vec3d corner : worldCorners) {
            sendDustToPlayers(players, corner.x, corner.y, corner.z, cornerColor, cornerSize);
        }
    }

    private void drawLine(java.util.List<ServerPlayerEntity> players,
                           Vec3d start, Vec3d end, int color, float size) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.01) return;

        double nx = dx / length;
        double ny = dy / length;
        double nz = dz / length;

        for (double d = 0; d <= length; d += PARTICLE_SPACING) {
            sendDustToPlayers(players,
                start.x + nx * d, start.y + ny * d, start.z + nz * d,
                color, size);
        }
    }

    private void sendDustToPlayers(java.util.List<ServerPlayerEntity> players,
                                    double x, double y, double z,
                                    int color, float size) {
        var packet = new ParticleS2CPacket(
            new DustParticleEffect(color, size),
            true, true, x, y, z, 0f, 0f, 0f, 0f, 1);
        for (ServerPlayerEntity player : players) {
            player.networkHandler.sendPacket(packet);
        }
    }
}
