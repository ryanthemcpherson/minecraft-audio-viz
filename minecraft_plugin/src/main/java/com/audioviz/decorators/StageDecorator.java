package com.audioviz.decorators;

import com.audioviz.AudioVizPlugin;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

/**
 * Abstract base class for stage decorators.
 * Each decorator manages its own entity pool (via a dedicated VisualizationZone)
 * and reacts to AudioState on the shared tick loop.
 *
 * Subclasses should:
 * 1. Create their decorator zone + entity pool in onActivate()
 * 2. Clean up in onDeactivate()
 * 3. Update entities based on audio state in tick()
 */
public abstract class StageDecorator {

    protected final String id;
    protected final String displayName;
    protected final Stage stage;
    protected final AudioVizPlugin plugin;
    protected boolean enabled;
    protected DecoratorConfig config;

    // Track tick count for animation timing
    protected long tickCount = 0;

    protected StageDecorator(String id, String displayName, Stage stage, AudioVizPlugin plugin) {
        this.id = id;
        this.displayName = displayName;
        this.stage = stage;
        this.plugin = plugin;
        this.enabled = true;
        this.config = getDefaultConfig();
    }

    // ========== Abstract Methods ==========

    /**
     * Called when the stage is activated. Create entity pools, spawn entities.
     * Use createDecoratorZone() and EntityPoolManager to set up entities.
     */
    public abstract void onActivate();

    /**
     * Called when the stage is deactivated. Clean up entity pools and zones.
     * Use cleanupDecoratorZone() to remove everything.
     */
    public abstract void onDeactivate();

    /**
     * Called every 2 ticks (10 FPS) by StageDecoratorManager.
     * React to audio state and optionally DJ info.
     *
     * @param audio Current audio state (never null, may be silent)
     * @param djInfo Current DJ info (may be DJInfo.none())
     */
    public abstract void tick(AudioState audio, DJInfo djInfo);

    /**
     * Return the default configuration for this decorator type.
     */
    public abstract DecoratorConfig getDefaultConfig();

    // ========== Concrete Helpers ==========

    /**
     * Get the zone name used for this decorator's entity pool.
     * Format: {stageName}_deco_{decoratorId}
     */
    public String getDecoratorZoneName() {
        return stage.getName().toLowerCase() + "_deco_" + id;
    }

    /**
     * Calculate a world location relative to the stage anchor, applying stage rotation.
     */
    protected Location getStageRelativeLocation(double offsetX, double offsetY, double offsetZ) {
        Location anchor = stage.getAnchor();
        double radians = Math.toRadians(stage.getRotation());
        double rotatedX = offsetX * Math.cos(radians) - offsetZ * Math.sin(radians);
        double rotatedZ = offsetX * Math.sin(radians) + offsetZ * Math.cos(radians);
        return anchor.clone().add(rotatedX, offsetY, rotatedZ);
    }

    /**
     * Create a lightweight VisualizationZone for this decorator's entity pool.
     * EntityPoolManager requires a registered zone to spawn entities.
     */
    protected VisualizationZone createDecoratorZone(Location origin, Vector size) {
        String zoneName = getDecoratorZoneName();
        VisualizationZone zone = plugin.getZoneManager().createZone(zoneName, origin);
        if (zone != null) {
            zone.setSize(size);
            zone.setRotation(stage.getRotation());
        }
        return zone;
    }

    /**
     * Clean up this decorator's zone and entity pool.
     */
    protected void cleanupDecoratorZone() {
        String zoneName = getDecoratorZoneName();
        plugin.getEntityPoolManager().cleanupZone(zoneName);
        plugin.getZoneManager().deleteZone(zoneName);
    }

    /**
     * Initialize a block display entity pool for this decorator.
     */
    protected void initBlockPool(int count, Material material) {
        plugin.getEntityPoolManager().initializeBlockPool(getDecoratorZoneName(), count, material);
    }

    /**
     * Initialize a text display entity pool for this decorator.
     */
    protected void initTextPool(int count) {
        plugin.getEntityPoolManager().initializeTextPool(getDecoratorZoneName(), count);
    }

    /**
     * Get the index of the dominant frequency band (highest energy).
     */
    protected int getDominantBand(AudioState audio) {
        double[] bands = audio.getBands();
        int dominant = 0;
        double maxVal = 0;
        for (int i = 0; i < bands.length; i++) {
            if (bands[i] > maxVal) {
                maxVal = bands[i];
                dominant = i;
            }
        }
        return dominant;
    }

    /**
     * Linear interpolation.
     */
    protected double lerp(double a, double b, double t) {
        return a + (b - a) * Math.max(0, Math.min(1, t));
    }

    /**
     * Increment tick counter (called by manager before tick).
     */
    public void incrementTick() {
        tickCount++;
    }

    // ========== Getters/Setters ==========

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Stage getStage() {
        return stage;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DecoratorConfig getConfig() {
        return config;
    }

    public void setConfig(DecoratorConfig config) {
        this.config = config != null ? config : getDefaultConfig();
    }
}
