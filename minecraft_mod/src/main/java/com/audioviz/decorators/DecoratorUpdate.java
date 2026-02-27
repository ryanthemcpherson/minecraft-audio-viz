package com.audioviz.decorators;

import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Immutable update record for a decorator display entity.
 * Replaces the plugin's EntityUpdate + Transformation combo.
 *
 * <p>All fields except entityId are nullable (null = don't change).
 * Position is absolute world coordinates; the DecoratorEntityManager
 * converts to holder-relative offsets internally.
 */
public class DecoratorUpdate {

    private final String entityId;
    private final Vec3d position;
    private final Vector3f translation;
    private final Vector3f scale;
    private final Quaternionf leftRotation;
    private final Integer brightness;
    private final Boolean glow;
    private final Integer interpolationDuration;

    private DecoratorUpdate(Builder b) {
        this.entityId = b.entityId;
        this.position = b.position;
        this.translation = b.translation;
        this.scale = b.scale;
        this.leftRotation = b.leftRotation;
        this.brightness = b.brightness;
        this.glow = b.glow;
        this.interpolationDuration = b.interpolationDuration;
    }

    public static Builder builder(String entityId) {
        return new Builder(entityId);
    }

    public String entityId() { return entityId; }
    public Vec3d position() { return position; }
    public Vector3f translation() { return translation; }
    public Vector3f scale() { return scale; }
    public Quaternionf leftRotation() { return leftRotation; }
    public Integer brightness() { return brightness; }
    public Boolean glow() { return glow; }
    public Integer interpolationDuration() { return interpolationDuration; }

    /**
     * Extract the slot index from an entity ID like "text_3" or "block_7".
     */
    public int slotIndex() {
        int underscore = entityId.lastIndexOf('_');
        if (underscore < 0) return 0;
        try {
            return Integer.parseInt(entityId.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static class Builder {
        private final String entityId;
        private Vec3d position;
        private Vector3f translation;
        private Vector3f scale;
        private Quaternionf leftRotation;
        private Integer brightness;
        private Boolean glow;
        private Integer interpolationDuration;

        Builder(String entityId) {
            this.entityId = entityId;
        }

        public Builder position(Vec3d position) { this.position = position; return this; }
        public Builder translation(Vector3f translation) { this.translation = translation; return this; }
        public Builder scale(Vector3f scale) { this.scale = scale; return this; }
        public Builder leftRotation(Quaternionf leftRotation) { this.leftRotation = leftRotation; return this; }
        public Builder brightness(int brightness) { this.brightness = brightness; return this; }
        public Builder glow(boolean glow) { this.glow = glow; return this; }
        public Builder interpolationDuration(int ticks) { this.interpolationDuration = ticks; return this; }

        public DecoratorUpdate build() {
            return new DecoratorUpdate(this);
        }
    }
}
