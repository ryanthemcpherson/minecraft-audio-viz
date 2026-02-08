package com.audioviz.entities;

import org.bukkit.Location;
import org.bukkit.util.Transformation;

/**
 * Immutable data class for batched entity updates.
 * Collects all update data for a single entity to be applied in one batch.
 */
public record EntityUpdate(
    String entityId,
    Location location,
    Transformation transformation,
    boolean hasLocation,
    boolean hasTransform,
    int brightness,
    boolean hasBrightness,
    boolean glow,
    boolean hasGlow,
    int interpolationDuration,
    boolean hasInterpolation
) {
    /**
     * Create an update with only position change.
     */
    public static EntityUpdate position(String entityId, Location location) {
        return new EntityUpdate(entityId, location, null, true, false, 15, false, false, false, -1, false);
    }

    /**
     * Create an update with only transformation change.
     */
    public static EntityUpdate transform(String entityId, Transformation transformation) {
        return new EntityUpdate(entityId, null, transformation, false, true, 15, false, false, false, -1, false);
    }

    /**
     * Create an update with both position and transformation.
     */
    public static EntityUpdate full(String entityId, Location location, Transformation transformation) {
        return new EntityUpdate(entityId, location, transformation, true, true, 15, false, false, false, -1, false);
    }

    /**
     * Create an update with all display properties.
     */
    public static EntityUpdate complete(String entityId, Location location, Transformation transformation,
                                         int brightness, boolean glow) {
        return new EntityUpdate(entityId, location, transformation, true, true, brightness, true, glow, true, -1, false);
    }

    /**
     * Create a builder for flexible update construction.
     */
    public static Builder builder(String entityId) {
        return new Builder(entityId);
    }

    /**
     * Builder for constructing EntityUpdate with optional fields.
     */
    public static class Builder {
        private final String entityId;
        private Location location = null;
        private Transformation transformation = null;
        private boolean hasLocation = false;
        private boolean hasTransform = false;
        private int brightness = 15;
        private boolean hasBrightness = false;
        private boolean glow = false;
        private boolean hasGlow = false;
        private int interpolationDuration = -1;
        private boolean hasInterpolation = false;

        public Builder(String entityId) {
            this.entityId = entityId;
        }

        public Builder location(Location location) {
            this.location = location;
            this.hasLocation = true;
            return this;
        }

        public Builder transformation(Transformation transformation) {
            this.transformation = transformation;
            this.hasTransform = true;
            return this;
        }

        public Builder brightness(int brightness) {
            this.brightness = brightness;
            this.hasBrightness = true;
            return this;
        }

        public Builder glow(boolean glow) {
            this.glow = glow;
            this.hasGlow = true;
            return this;
        }

        public Builder interpolationDuration(int interpolationDuration) {
            this.interpolationDuration = interpolationDuration;
            this.hasInterpolation = true;
            return this;
        }

        public EntityUpdate build() {
            return new EntityUpdate(entityId, location, transformation,
                                     hasLocation, hasTransform,
                                     brightness, hasBrightness,
                                     glow, hasGlow,
                                     interpolationDuration, hasInterpolation);
        }
    }
}
