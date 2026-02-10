package com.audioviz.zones;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * Represents a visualization zone in the world where audio-reactive
 * entities can be spawned and controlled.
 */
public class VisualizationZone {

    private final String name;
    private final UUID id;
    private Location origin;
    private Vector size;
    private float rotation; // Y-axis rotation in degrees
    private boolean glowOnBeat; // Flash glow effect on beat detection
    private boolean dynamicBrightness; // Scale brightness with audio amplitude

    public VisualizationZone(String name, Location origin) {
        this.name = name;
        this.id = UUID.randomUUID();
        this.origin = origin.clone();
        this.size = new Vector(10, 10, 10); // Default 10x10x10 zone
        this.rotation = 0f;
        this.glowOnBeat = false;
        this.dynamicBrightness = false;
    }

    public VisualizationZone(String name, UUID id, Location origin, Vector size, float rotation) {
        this.name = name;
        this.id = id;
        this.origin = origin.clone();
        this.size = size.clone();
        this.rotation = rotation;
        this.glowOnBeat = false;
        this.dynamicBrightness = false;
    }

    public String getName() {
        return name;
    }

    public UUID getId() {
        return id;
    }

    public Location getOrigin() {
        return origin.clone();
    }

    public void setOrigin(Location origin) {
        this.origin = origin.clone();
    }

    public Vector getSize() {
        return size.clone();
    }

    public void setSize(Vector size) {
        this.size = size.clone();
    }

    public void setSize(double x, double y, double z) {
        this.size = new Vector(x, y, z);
    }

    public float getRotation() {
        return rotation;
    }

    public void setRotation(float rotation) {
        this.rotation = rotation % 360;
    }

    public World getWorld() {
        return origin.getWorld();
    }

    public boolean isGlowOnBeat() {
        return glowOnBeat;
    }

    public void setGlowOnBeat(boolean glowOnBeat) {
        this.glowOnBeat = glowOnBeat;
    }

    public boolean isDynamicBrightness() {
        return dynamicBrightness;
    }

    public void setDynamicBrightness(boolean dynamicBrightness) {
        this.dynamicBrightness = dynamicBrightness;
    }

    /**
     * Convert a local position (0-1 normalized) to world coordinates
     * taking into account zone origin, size, and rotation.
     */
    public Location localToWorld(double localX, double localY, double localZ) {
        // Scale to zone size
        double scaledX = localX * size.getX();
        double scaledY = localY * size.getY();
        double scaledZ = localZ * size.getZ();

        // Apply rotation around Y axis
        double radians = Math.toRadians(rotation);
        double rotatedX = scaledX * Math.cos(radians) - scaledZ * Math.sin(radians);
        double rotatedZ = scaledX * Math.sin(radians) + scaledZ * Math.cos(radians);

        // Translate to world position
        return origin.clone().add(rotatedX, scaledY, rotatedZ);
    }

    /**
     * Get the center of the zone in world coordinates.
     */
    public Location getCenter() {
        return localToWorld(0.5, 0.5, 0.5);
    }

    /**
     * Check if a world location is within this zone's bounds.
     */
    public boolean contains(Location location) {
        if (!location.getWorld().equals(origin.getWorld())) {
            return false;
        }

        // Simple AABB check (ignoring rotation for now)
        double dx = location.getX() - origin.getX();
        double dy = location.getY() - origin.getY();
        double dz = location.getZ() - origin.getZ();

        return dx >= 0 && dx <= size.getX()
            && dy >= 0 && dy <= size.getY()
            && dz >= 0 && dz <= size.getZ();
    }

    @Override
    public String toString() {
        return String.format("Zone[%s @ %s, size=%s, rot=%.1f]",
            name,
            formatLocation(origin),
            formatVector(size),
            rotation);
    }

    private String formatLocation(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private String formatVector(Vector vec) {
        return String.format("(%.1f, %.1f, %.1f)", vec.getX(), vec.getY(), vec.getZ());
    }
}
