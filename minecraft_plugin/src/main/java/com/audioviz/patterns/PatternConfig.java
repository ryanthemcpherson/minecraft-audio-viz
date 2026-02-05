package com.audioviz.patterns;

/**
 * Configuration for visualization patterns.
 */
public class PatternConfig {

    private int entityCount;
    private double zoneSize = 10.0;
    private double beatBoost = 1.0;
    private double baseScale = 0.3;
    private double maxScale = 1.2;

    public PatternConfig(int entityCount) {
        this.entityCount = entityCount;
    }

    public int getEntityCount() { return entityCount; }
    public void setEntityCount(int entityCount) { this.entityCount = entityCount; }

    public double getZoneSize() { return zoneSize; }
    public void setZoneSize(double zoneSize) { this.zoneSize = zoneSize; }

    public double getBeatBoost() { return beatBoost; }
    public void setBeatBoost(double beatBoost) { this.beatBoost = beatBoost; }

    public double getBaseScale() { return baseScale; }
    public void setBaseScale(double baseScale) { this.baseScale = baseScale; }

    public double getMaxScale() { return maxScale; }
    public void setMaxScale(double maxScale) { this.maxScale = maxScale; }
}
