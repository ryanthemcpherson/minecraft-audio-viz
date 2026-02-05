package com.audioviz.particles;

import org.bukkit.Particle;

/**
 * Represents a single particle spawn request.
 * Uses local coordinates (0-1) that will be converted to world coordinates.
 */
public class ParticleSpawn {

    private final Particle type;
    private final double x, y, z;           // Local coords (0-1)
    private final int count;
    private final double offsetX, offsetY, offsetZ;
    private final double speed;
    private final Object data;              // For colored particles, block data, etc.

    private ParticleSpawn(Builder builder) {
        this.type = builder.type;
        this.x = builder.x;
        this.y = builder.y;
        this.z = builder.z;
        this.count = builder.count;
        this.offsetX = builder.offsetX;
        this.offsetY = builder.offsetY;
        this.offsetZ = builder.offsetZ;
        this.speed = builder.speed;
        this.data = builder.data;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public Particle getType() { return type; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public int getCount() { return count; }
    public double getOffsetX() { return offsetX; }
    public double getOffsetY() { return offsetY; }
    public double getOffsetZ() { return offsetZ; }
    public double getSpeed() { return speed; }
    public Object getData() { return data; }

    public static class Builder {
        private Particle type = Particle.FLAME;
        private double x = 0.5, y = 0.5, z = 0.5;
        private int count = 10;
        private double offsetX = 0.1, offsetY = 0.1, offsetZ = 0.1;
        private double speed = 0.05;
        private Object data = null;

        public Builder type(Particle type) {
            this.type = type;
            return this;
        }

        public Builder position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Builder count(int count) {
            this.count = count;
            return this;
        }

        public Builder offset(double x, double y, double z) {
            this.offsetX = x;
            this.offsetY = y;
            this.offsetZ = z;
            return this;
        }

        public Builder speed(double speed) {
            this.speed = speed;
            return this;
        }

        public Builder data(Object data) {
            this.data = data;
            return this;
        }

        public ParticleSpawn build() {
            return new ParticleSpawn(this);
        }
    }
}
