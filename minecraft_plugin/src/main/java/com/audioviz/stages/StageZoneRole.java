package com.audioviz.stages;

import org.bukkit.Material;
import org.bukkit.util.Vector;

/**
 * Defines the semantic purpose of each zone within a stage.
 * Each role carries default relative placement, size, and a suggested pattern.
 */
public enum StageZoneRole {

    MAIN_STAGE("Main Stage", new Vector(0, 0, 0), new Vector(16, 12, 10),
        "spectrum", Material.JUKEBOX),

    LEFT_WING("Left Wing", new Vector(-14, 0, 0), new Vector(8, 10, 8),
        "laser", Material.NOTE_BLOCK),

    RIGHT_WING("Right Wing", new Vector(14, 0, 0), new Vector(8, 10, 8),
        "laser", Material.NOTE_BLOCK),

    SKYBOX("Skybox", new Vector(0, 14, 0), new Vector(20, 6, 16),
        "aurora", Material.BEACON),

    AUDIENCE("Audience", new Vector(0, 0, 14), new Vector(24, 4, 12),
        "wave", Material.CAMPFIRE),

    BACKSTAGE("Backstage", new Vector(0, 0, -10), new Vector(10, 6, 6),
        "bars", Material.IRON_BLOCK),

    RUNWAY("Runway", new Vector(0, 0, 8), new Vector(4, 6, 16),
        "vortex", Material.AMETHYST_BLOCK),

    BALCONY("Balcony", new Vector(0, 8, 14), new Vector(20, 4, 6),
        "mandala", Material.PRISMARINE);

    private final String displayName;
    private final Vector defaultOffset;
    private final Vector defaultSize;
    private final String suggestedPattern;
    private final Material icon;

    StageZoneRole(String displayName, Vector defaultOffset, Vector defaultSize,
                  String suggestedPattern, Material icon) {
        this.displayName = displayName;
        this.defaultOffset = defaultOffset;
        this.defaultSize = defaultSize;
        this.suggestedPattern = suggestedPattern;
        this.icon = icon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Vector getDefaultOffset() {
        return defaultOffset.clone();
    }

    public Vector getDefaultSize() {
        return defaultSize.clone();
    }

    public String getSuggestedPattern() {
        return suggestedPattern;
    }

    public Material getIcon() {
        return icon;
    }
}
