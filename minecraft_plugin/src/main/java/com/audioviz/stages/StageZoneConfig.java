package com.audioviz.stages;

/**
 * Per-zone configuration within a stage.
 * Holds pattern, entity count, render mode, and display settings.
 */
public class StageZoneConfig {

    private String pattern;
    private int entityCount;
    private String renderMode;
    private String blockType;
    private int brightness;
    private boolean glowOnBeat;
    private float intensityMultiplier;

    public StageZoneConfig() {
        this.pattern = "spectrum";
        this.entityCount = 16;
        this.renderMode = "entities";
        this.blockType = "SEA_LANTERN";
        this.brightness = 15;
        this.glowOnBeat = false;
        this.intensityMultiplier = 1.0f;
    }

    public StageZoneConfig(StageZoneConfig other) {
        this.pattern = other.pattern;
        this.entityCount = other.entityCount;
        this.renderMode = other.renderMode;
        this.blockType = other.blockType;
        this.brightness = other.brightness;
        this.glowOnBeat = other.glowOnBeat;
        this.intensityMultiplier = other.intensityMultiplier;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public int getEntityCount() {
        return entityCount;
    }

    public void setEntityCount(int entityCount) {
        this.entityCount = Math.max(1, Math.min(1000, entityCount));
    }

    public String getRenderMode() {
        return renderMode;
    }

    public void setRenderMode(String renderMode) {
        this.renderMode = renderMode;
    }

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public int getBrightness() {
        return brightness;
    }

    public void setBrightness(int brightness) {
        this.brightness = Math.max(0, Math.min(15, brightness));
    }

    public boolean isGlowOnBeat() {
        return glowOnBeat;
    }

    public void setGlowOnBeat(boolean glowOnBeat) {
        this.glowOnBeat = glowOnBeat;
    }

    public float getIntensityMultiplier() {
        return intensityMultiplier;
    }

    public void setIntensityMultiplier(float intensityMultiplier) {
        this.intensityMultiplier = Math.max(0.1f, Math.min(3.0f, intensityMultiplier));
    }
}
