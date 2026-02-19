package com.audioviz.zones;

import com.audioviz.AudioVizPlugin;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders particle outlines along zone bounding-box edges so operators
 * can see zone placement in-world. Boundaries auto-hide after a timeout
 * to avoid permanent visual clutter.
 *
 * Particles are spawned globally (visible to all nearby players) rather
 * than per-player, keeping the implementation lightweight.
 */
public class ZoneBoundaryRenderer {

    private final AudioVizPlugin plugin;

    /** Zone name (lowercase) -> tick when boundaries should auto-hide. */
    private final Map<String, Long> activeZones = new ConcurrentHashMap<>();

    /** Zones marked as persistent (won't auto-hide). */
    private final Set<String> persistentZones = ConcurrentHashMap.newKeySet();

    /** Currently selected zone name (rendered with a distinct color), or null. */
    private volatile String selectedZone = null;

    /** How long boundaries stay visible before auto-hiding (ticks). 30 seconds = 600 ticks. */
    private static final long DEFAULT_TIMEOUT_TICKS = 600L;

    /** Distance between particles along each edge (blocks). */
    private static final double PARTICLE_SPACING = 0.5;

    /** Max distance (squared) from a zone center for a player to see particles. */
    private static final double VIEW_RANGE_SQ = 64.0 * 64.0;

    /** Render interval in ticks (10 ticks = 0.5 seconds). */
    private static final long RENDER_INTERVAL = 10L;

    private BukkitTask renderTask;

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

    public ZoneBoundaryRenderer(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Start the repeating render task.
     */
    public void start() {
        renderTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::tick, RENDER_INTERVAL, RENDER_INTERVAL
        );
    }

    /**
     * Stop the render task and clear all active boundaries.
     */
    public void stop() {
        if (renderTask != null) {
            renderTask.cancel();
            renderTask = null;
        }
        activeZones.clear();
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
        long currentTick = plugin.getServer().getCurrentTick();
        long expiry = (timeoutTicks == Long.MAX_VALUE) ? Long.MAX_VALUE : currentTick + timeoutTicks;
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
        for (String name : plugin.getZoneManager().getZoneNames()) {
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
            // Ensure it's shown with infinite timeout
            activeZones.put(key, Long.MAX_VALUE);
        } else {
            persistentZones.remove(key);
        }
    }

    /**
     * Set a zone as "selected" — rendered with a distinct highlight color.
     */
    public void setSelected(String zoneName) {
        selectedZone = zoneName != null ? zoneName.toLowerCase() : null;
    }

    /**
     * Clear the current selection highlight.
     */
    public void clearSelection() {
        selectedZone = null;
    }

    /**
     * Show all zones belonging to a stage with persistent boundaries.
     */
    public void showStage(String stageName) {
        Stage stage = plugin.getStageManager().getStage(stageName);
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
        Stage stage = plugin.getStageManager().getStage(stageName);
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

    // ==================== Render Loop ====================

    private void tick() {
        if (activeZones.isEmpty()) return;

        long currentTick = plugin.getServer().getCurrentTick();

        // Remove expired entries and render active ones
        var iterator = activeZones.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (currentTick >= entry.getValue() && !persistentZones.contains(entry.getKey())) {
                iterator.remove();
                continue;
            }

            VisualizationZone zone = plugin.getZoneManager().getZone(entry.getKey());
            if (zone == null) {
                iterator.remove();
                continue;
            }

            // Only render if players are nearby
            if (hasNearbyPlayers(zone)) {
                renderZoneBoundary(zone);
            }
        }
    }

    private boolean hasNearbyPlayers(VisualizationZone zone) {
        Location center = zone.getCenter();
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= VIEW_RANGE_SQ) {
                return true;
            }
        }
        return false;
    }

    private void renderZoneBoundary(VisualizationZone zone) {
        // Compute world-space corners
        Location[] worldCorners = new Location[8];
        for (int i = 0; i < 8; i++) {
            worldCorners[i] = zone.localToWorld(CORNERS[i][0], CORNERS[i][1], CORNERS[i][2]);
        }

        // Use distinct colors for selected zones
        boolean isSelected = zone.getName().equalsIgnoreCase(selectedZone);
        Particle.DustOptions edgeDust = isSelected
            ? new Particle.DustOptions(Color.fromRGB(255, 215, 0), 1.0f)   // Gold for selected
            : new Particle.DustOptions(Color.fromRGB(0, 200, 255), 0.8f);
        Particle.DustOptions cornerDust = isSelected
            ? new Particle.DustOptions(Color.fromRGB(255, 255, 100), 1.5f)  // Bright gold corners
            : new Particle.DustOptions(Color.fromRGB(0, 255, 128), 1.3f);

        // Draw 12 edges
        for (int[] edge : EDGES) {
            drawLine(worldCorners[edge[0]], worldCorners[edge[1]], edgeDust);
        }

        // Draw corners with larger particles
        for (Location corner : worldCorners) {
            corner.getWorld().spawnParticle(Particle.DUST, corner, 1, 0, 0, 0, 0, cornerDust);
        }
    }

    private void drawLine(Location start, Location end, Particle.DustOptions options) {
        Vector direction = end.toVector().subtract(start.toVector());
        double length = direction.length();
        if (length < 0.01) return;
        direction.normalize();

        for (double d = 0; d <= length; d += PARTICLE_SPACING) {
            Location point = start.clone().add(direction.clone().multiply(d));
            start.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, options);
        }
    }
}
