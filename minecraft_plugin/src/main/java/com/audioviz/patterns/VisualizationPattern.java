package com.audioviz.patterns;

import java.util.List;

/**
 * Base class for visualization patterns.
 * Patterns calculate entity positions based on audio state.
 */
public abstract class VisualizationPattern {

    private final String id;
    private final String name;
    private final String description;
    protected PatternConfig config;

    public VisualizationPattern(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.config = new PatternConfig(16);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public PatternConfig getConfig() { return config; }

    public void setConfig(PatternConfig config) {
        this.config = config;
    }

    /**
     * Calculate entity positions for the current audio state.
     */
    public abstract List<EntityUpdate> calculate(AudioState audio);

    /**
     * Reset pattern state (called when switching patterns).
     */
    public void reset() {
        // Override in subclasses if needed
    }

    /**
     * Update internal state (called each frame).
     */
    public void update(double deltaTime) {
        // Override in subclasses if needed
    }

    /**
     * Calculate scale based on audio band and pattern config.
     */
    protected double calculateScale(AudioState audio, int band) {
        double baseScale = config.getBaseScale();
        double maxScale = config.getMaxScale();
        double bandValue = audio.getBand(band);
        double beatBoost = audio.isBeat() ? config.getBeatBoost() * 0.2 : 0;
        return baseScale + (maxScale - baseScale) * bandValue + beatBoost;
    }
}
