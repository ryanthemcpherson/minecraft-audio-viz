package com.audioviz.zones;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Represents a visualization zone in the world where audio-reactive
 * entities can be spawned and controlled.
 * Ported from Paper: Location → BlockPos/Vec3d, Vector → Vector3f.
 */
public class VisualizationZone {

    private final String name;
    private final UUID id;
    private BlockPos origin;
    private Vector3f size;
    private float rotation; // Y-axis rotation in degrees
    private boolean glowOnBeat;
    private boolean dynamicBrightness;
    private ServerWorld world;

    public VisualizationZone(String name, ServerWorld world, BlockPos origin) {
        this.name = name;
        this.id = UUID.randomUUID();
        this.world = world;
        this.origin = origin;
        this.size = new Vector3f(10, 10, 10);
        this.rotation = 0f;
    }

    public VisualizationZone(String name, UUID id, ServerWorld world, BlockPos origin, Vector3f size, float rotation) {
        this.name = name;
        this.id = id;
        this.world = world;
        this.origin = origin;
        this.size = new Vector3f(size);
        this.rotation = rotation;
    }

    public String getName() { return name; }
    public UUID getId() { return id; }
    public BlockPos getOrigin() { return origin; }
    public ServerWorld getWorld() { return world; }
    public Vector3f getSize() { return new Vector3f(size); }
    public float getRotation() { return rotation; }
    public boolean isGlowOnBeat() { return glowOnBeat; }
    public boolean isDynamicBrightness() { return dynamicBrightness; }

    public void setOrigin(BlockPos origin) { this.origin = origin; }
    public void setWorld(ServerWorld world) { this.world = world; }
    public void setSize(Vector3f size) { this.size = new Vector3f(size); }
    public void setSize(float x, float y, float z) { this.size = new Vector3f(x, y, z); }
    public void setRotation(float rotation) { this.rotation = rotation % 360; }
    public void setGlowOnBeat(boolean glowOnBeat) { this.glowOnBeat = glowOnBeat; }
    public void setDynamicBrightness(boolean dynamicBrightness) { this.dynamicBrightness = dynamicBrightness; }

    /**
     * Get origin as Vec3d (for distance calculations, Polymer attachment, etc.).
     */
    public Vec3d getOriginVec3d() {
        return new Vec3d(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
    }

    /**
     * Convert a local position (0-1 normalized) to world coordinates
     * taking into account zone origin, size, and rotation.
     */
    public Vec3d localToWorld(double localX, double localY, double localZ) {
        double scaledX = localX * size.x;
        double scaledY = localY * size.y;
        double scaledZ = localZ * size.z;

        // Apply rotation around Y axis
        double radians = Math.toRadians(rotation);
        double rotatedX = scaledX * Math.cos(radians) - scaledZ * Math.sin(radians);
        double rotatedZ = scaledX * Math.sin(radians) + scaledZ * Math.cos(radians);

        return new Vec3d(
            origin.getX() + rotatedX,
            origin.getY() + scaledY,
            origin.getZ() + rotatedZ
        );
    }

    /**
     * Get the center of the zone in world coordinates.
     */
    public Vec3d getCenter() {
        return localToWorld(0.5, 0.5, 0.5);
    }

    /**
     * Check if a world position is within this zone's bounds (AABB, ignoring rotation).
     */
    public boolean contains(Vec3d position) {
        double dx = position.x - origin.getX();
        double dy = position.y - origin.getY();
        double dz = position.z - origin.getZ();

        return dx >= 0 && dx <= size.x
            && dy >= 0 && dy <= size.y
            && dz >= 0 && dz <= size.z;
    }

    @Override
    public String toString() {
        return String.format("Zone[%s @ (%d, %d, %d), size=(%.1f, %.1f, %.1f), rot=%.1f]",
            name, origin.getX(), origin.getY(), origin.getZ(),
            size.x, size.y, size.z, rotation);
    }
}
