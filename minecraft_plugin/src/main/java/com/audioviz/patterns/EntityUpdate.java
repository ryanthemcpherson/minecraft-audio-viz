package com.audioviz.patterns;

/**
 * Represents an entity update from a visualization pattern.
 * Contains position, scale, visibility, and band association.
 * This is a lightweight data transfer object for pattern calculations.
 */
public class EntityUpdate {
    private final int id;
    private final double x;
    private final double y;
    private final double z;
    private final double scale;
    private final boolean visible;
    private final int band;
    private final double rotation;

    private EntityUpdate(int id, double x, double y, double z, double scale, boolean visible, int band, double rotation) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.scale = scale;
        this.visible = visible;
        this.band = band;
        this.rotation = rotation;
    }

    public int getId() { return id; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public double getScale() { return scale; }
    public boolean isVisible() { return visible; }
    public int getBand() { return band; }
    public double getRotation() { return rotation; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int id = 0;
        private double x = 0.5;
        private double y = 0.5;
        private double z = 0.5;
        private double scale = 1.0;
        private boolean visible = true;
        private int band = 0;
        private double rotation = 0.0;

        public Builder id(int id) {
            this.id = id;
            return this;
        }

        public Builder position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Builder scale(double scale) {
            this.scale = scale;
            return this;
        }

        public Builder visible(boolean visible) {
            this.visible = visible;
            return this;
        }

        public Builder band(int band) {
            this.band = band;
            return this;
        }

        public Builder rotation(double rotation) {
            this.rotation = rotation;
            return this;
        }

        public EntityUpdate build() {
            return new EntityUpdate(id, x, y, z, scale, visible, band, rotation);
        }
    }
}
