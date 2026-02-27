package com.audioviz.stages;

import org.joml.Vector3f;

/**
 * Defines the semantic purpose of each zone within a stage.
 *
 * <p>Ported from Paper: org.bukkit.util.Vector → org.joml.Vector3f,
 * org.bukkit.Material removed (icon stored as string for SGUI lookup).
 */
public enum StageZoneRole {

    MAIN_STAGE("Main Stage", new Vector3f(0, 0, 0), new Vector3f(16, 12, 10),
        "spectrum", "jukebox"),

    LEFT_WING("Left Wing", new Vector3f(-14, 0, 0), new Vector3f(8, 10, 8),
        "laser", "note_block"),

    RIGHT_WING("Right Wing", new Vector3f(14, 0, 0), new Vector3f(8, 10, 8),
        "laser", "note_block"),

    SKYBOX("Skybox", new Vector3f(0, 14, 0), new Vector3f(20, 6, 16),
        "aurora", "beacon"),

    AUDIENCE("Audience", new Vector3f(0, 0, 14), new Vector3f(24, 4, 12),
        "wave", "campfire"),

    BACKSTAGE("Backstage", new Vector3f(0, 0, -10), new Vector3f(10, 6, 6),
        "bars", "iron_block"),

    RUNWAY("Runway", new Vector3f(0, 0, 8), new Vector3f(4, 6, 16),
        "vortex", "amethyst_block"),

    BALCONY("Balcony", new Vector3f(0, 8, 14), new Vector3f(20, 4, 6),
        "mandala", "prismarine");

    private final String displayName;
    private final Vector3f defaultOffset;
    private final Vector3f defaultSize;
    private final String suggestedPattern;
    private final String iconBlock;

    StageZoneRole(String displayName, Vector3f defaultOffset, Vector3f defaultSize,
                  String suggestedPattern, String iconBlock) {
        this.displayName = displayName;
        this.defaultOffset = defaultOffset;
        this.defaultSize = defaultSize;
        this.suggestedPattern = suggestedPattern;
        this.iconBlock = iconBlock;
    }

    public String getDisplayName() { return displayName; }
    public Vector3f getDefaultOffset() { return new Vector3f(defaultOffset); }
    public Vector3f getDefaultSize() { return new Vector3f(defaultSize); }
    public String getSuggestedPattern() { return suggestedPattern; }
    public String getIconBlock() { return iconBlock; }
}
