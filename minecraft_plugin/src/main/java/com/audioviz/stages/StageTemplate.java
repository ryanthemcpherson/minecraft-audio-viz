package com.audioviz.stages;

import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Defines a predefined stage layout template.
 * Each template specifies which zone roles to include with optional size/offset/config overrides.
 */
public class StageTemplate {

    private final String name;
    private final String description;
    private final Material icon;
    private final Set<StageZoneRole> roles;
    private final Map<StageZoneRole, Vector> sizeOverrides;
    private final Map<StageZoneRole, Vector> offsetOverrides;
    private final Map<StageZoneRole, StageZoneConfig> defaultConfigs;
    private final int defaultEntityCount;

    private StageTemplate(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.icon = builder.icon;
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(builder.roles));
        this.sizeOverrides = Collections.unmodifiableMap(new EnumMap<>(builder.sizeOverrides));
        this.offsetOverrides = Collections.unmodifiableMap(new EnumMap<>(builder.offsetOverrides));
        this.defaultConfigs = Collections.unmodifiableMap(new EnumMap<>(builder.defaultConfigs));
        this.defaultEntityCount = builder.defaultEntityCount;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Material getIcon() {
        return icon;
    }

    public Set<StageZoneRole> getRoles() {
        return roles;
    }

    public int getRoleCount() {
        return roles.size();
    }

    public Map<StageZoneRole, Vector> getSizeOverrides() {
        return sizeOverrides;
    }

    public Map<StageZoneRole, Vector> getOffsetOverrides() {
        return offsetOverrides;
    }

    public Map<StageZoneRole, StageZoneConfig> getDefaultConfigs() {
        return defaultConfigs;
    }

    public int getDefaultEntityCount() {
        return defaultEntityCount;
    }

    /**
     * Get the effective size for a role (override or default).
     */
    public Vector getSizeForRole(StageZoneRole role) {
        return sizeOverrides.getOrDefault(role, role.getDefaultSize());
    }

    /**
     * Get the effective offset for a role (override or default).
     */
    public Vector getOffsetForRole(StageZoneRole role) {
        return offsetOverrides.getOrDefault(role, role.getDefaultOffset());
    }

    /**
     * Estimate total entity count based on default configs.
     */
    public int getEstimatedEntityCount() {
        int total = 0;
        for (StageZoneRole role : roles) {
            StageZoneConfig config = defaultConfigs.get(role);
            total += (config != null) ? config.getEntityCount() : defaultEntityCount;
        }
        return total;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    // ========== Built-in Templates ==========

    private static final Map<String, StageTemplate> BUILTIN_TEMPLATES = new LinkedHashMap<>();

    static {
        // Club - small intimate setup
        BUILTIN_TEMPLATES.put("club", builder("club")
            .description("Small intimate setup with main stage and audience")
            .icon(Material.NOTE_BLOCK)
            .defaultEntityCount(16)
            .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, new Vector(8, 8, 6))
                .configOverride(StageZoneRole.MAIN_STAGE, config -> {
                    config.setEntityCount(16);
                    config.setPattern("spectrum");
                })
            .role(StageZoneRole.AUDIENCE)
                .sizeOverride(StageZoneRole.AUDIENCE, new Vector(12, 3, 8))
                .configOverride(StageZoneRole.AUDIENCE, config -> {
                    config.setEntityCount(8);
                    config.setPattern("wave");
                })
            .build());

        // Concert - standard 5-zone layout
        BUILTIN_TEMPLATES.put("concert", builder("concert")
            .description("Standard concert with wings, skybox, and audience")
            .icon(Material.JUKEBOX)
            .defaultEntityCount(16)
            .role(StageZoneRole.MAIN_STAGE)
                .configOverride(StageZoneRole.MAIN_STAGE, config -> {
                    config.setEntityCount(32);
                    config.setPattern("spectrum");
                })
            .role(StageZoneRole.LEFT_WING)
                .configOverride(StageZoneRole.LEFT_WING, config -> {
                    config.setEntityCount(16);
                    config.setPattern("laser");
                })
            .role(StageZoneRole.RIGHT_WING)
                .configOverride(StageZoneRole.RIGHT_WING, config -> {
                    config.setEntityCount(16);
                    config.setPattern("laser");
                })
            .role(StageZoneRole.SKYBOX)
                .configOverride(StageZoneRole.SKYBOX, config -> {
                    config.setEntityCount(16);
                    config.setPattern("aurora");
                })
            .role(StageZoneRole.AUDIENCE)
                .configOverride(StageZoneRole.AUDIENCE, config -> {
                    config.setEntityCount(16);
                    config.setPattern("wave");
                })
            .build());

        // Arena - large 7-zone layout
        BUILTIN_TEMPLATES.put("arena", builder("arena")
            .description("Large arena with backstage, runway, and full surround")
            .icon(Material.END_PORTAL_FRAME)
            .defaultEntityCount(24)
            .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, new Vector(20, 16, 12))
                .configOverride(StageZoneRole.MAIN_STAGE, config -> {
                    config.setEntityCount(64);
                    config.setPattern("spectrum");
                })
            .role(StageZoneRole.LEFT_WING)
                .sizeOverride(StageZoneRole.LEFT_WING, new Vector(10, 12, 10))
                .offsetOverride(StageZoneRole.LEFT_WING, new Vector(-18, 0, 0))
                .configOverride(StageZoneRole.LEFT_WING, config -> {
                    config.setEntityCount(24);
                    config.setPattern("laser");
                })
            .role(StageZoneRole.RIGHT_WING)
                .sizeOverride(StageZoneRole.RIGHT_WING, new Vector(10, 12, 10))
                .offsetOverride(StageZoneRole.RIGHT_WING, new Vector(18, 0, 0))
                .configOverride(StageZoneRole.RIGHT_WING, config -> {
                    config.setEntityCount(24);
                    config.setPattern("laser");
                })
            .role(StageZoneRole.SKYBOX)
                .sizeOverride(StageZoneRole.SKYBOX, new Vector(30, 8, 20))
                .offsetOverride(StageZoneRole.SKYBOX, new Vector(0, 18, 0))
                .configOverride(StageZoneRole.SKYBOX, config -> {
                    config.setEntityCount(32);
                    config.setPattern("aurora");
                })
            .role(StageZoneRole.AUDIENCE)
                .sizeOverride(StageZoneRole.AUDIENCE, new Vector(30, 4, 16))
                .offsetOverride(StageZoneRole.AUDIENCE, new Vector(0, 0, 18))
                .configOverride(StageZoneRole.AUDIENCE, config -> {
                    config.setEntityCount(24);
                    config.setPattern("wave");
                })
            .role(StageZoneRole.BACKSTAGE)
                .sizeOverride(StageZoneRole.BACKSTAGE, new Vector(12, 8, 8))
                .offsetOverride(StageZoneRole.BACKSTAGE, new Vector(0, 0, -14))
                .configOverride(StageZoneRole.BACKSTAGE, config -> {
                    config.setEntityCount(16);
                    config.setPattern("bars");
                })
            .role(StageZoneRole.RUNWAY)
                .sizeOverride(StageZoneRole.RUNWAY, new Vector(4, 8, 20))
                .configOverride(StageZoneRole.RUNWAY, config -> {
                    config.setEntityCount(16);
                    config.setPattern("vortex");
                })
            .build());

        // Festival - massive 8-zone layout with scaled-up sizes
        BUILTIN_TEMPLATES.put("festival", builder("festival")
            .description("Massive festival with all zones at 1.5x scale")
            .icon(Material.DRAGON_EGG)
            .defaultEntityCount(32)
            .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, new Vector(30, 24, 18))
                .configOverride(StageZoneRole.MAIN_STAGE, config -> {
                    config.setEntityCount(100);
                    config.setPattern("spectrum");
                })
            .role(StageZoneRole.LEFT_WING)
                .sizeOverride(StageZoneRole.LEFT_WING, new Vector(15, 18, 15))
                .offsetOverride(StageZoneRole.LEFT_WING, new Vector(-27, 0, 0))
                .configOverride(StageZoneRole.LEFT_WING, config -> {
                    config.setEntityCount(36);
                    config.setPattern("laser");
                })
            .role(StageZoneRole.RIGHT_WING)
                .sizeOverride(StageZoneRole.RIGHT_WING, new Vector(15, 18, 15))
                .offsetOverride(StageZoneRole.RIGHT_WING, new Vector(27, 0, 0))
                .configOverride(StageZoneRole.RIGHT_WING, config -> {
                    config.setEntityCount(36);
                    config.setPattern("laser");
                })
            .role(StageZoneRole.SKYBOX)
                .sizeOverride(StageZoneRole.SKYBOX, new Vector(45, 12, 30))
                .offsetOverride(StageZoneRole.SKYBOX, new Vector(0, 27, 0))
                .configOverride(StageZoneRole.SKYBOX, config -> {
                    config.setEntityCount(48);
                    config.setPattern("aurora");
                })
            .role(StageZoneRole.AUDIENCE)
                .sizeOverride(StageZoneRole.AUDIENCE, new Vector(45, 6, 24))
                .offsetOverride(StageZoneRole.AUDIENCE, new Vector(0, 0, 27))
                .configOverride(StageZoneRole.AUDIENCE, config -> {
                    config.setEntityCount(36);
                    config.setPattern("wave");
                })
            .role(StageZoneRole.BACKSTAGE)
                .sizeOverride(StageZoneRole.BACKSTAGE, new Vector(18, 12, 12))
                .offsetOverride(StageZoneRole.BACKSTAGE, new Vector(0, 0, -21))
                .configOverride(StageZoneRole.BACKSTAGE, config -> {
                    config.setEntityCount(24);
                    config.setPattern("bars");
                })
            .role(StageZoneRole.RUNWAY)
                .sizeOverride(StageZoneRole.RUNWAY, new Vector(6, 12, 30))
                .offsetOverride(StageZoneRole.RUNWAY, new Vector(0, 0, 12))
                .configOverride(StageZoneRole.RUNWAY, config -> {
                    config.setEntityCount(24);
                    config.setPattern("vortex");
                })
            .role(StageZoneRole.BALCONY)
                .sizeOverride(StageZoneRole.BALCONY, new Vector(30, 6, 9))
                .offsetOverride(StageZoneRole.BALCONY, new Vector(0, 12, 21))
                .configOverride(StageZoneRole.BALCONY, config -> {
                    config.setEntityCount(24);
                    config.setPattern("mandala");
                })
            .build());
    }

    /**
     * Get all built-in templates.
     */
    public static Map<String, StageTemplate> getBuiltinTemplates() {
        return Collections.unmodifiableMap(BUILTIN_TEMPLATES);
    }

    /**
     * Get a built-in template by name.
     */
    public static StageTemplate getBuiltin(String name) {
        return BUILTIN_TEMPLATES.get(name.toLowerCase());
    }

    // ========== Builder ==========

    public static class Builder {
        private final String name;
        private String description = "";
        private Material icon = Material.JUKEBOX;
        private final Set<StageZoneRole> roles = new LinkedHashSet<>();
        private final Map<StageZoneRole, Vector> sizeOverrides = new EnumMap<>(StageZoneRole.class);
        private final Map<StageZoneRole, Vector> offsetOverrides = new EnumMap<>(StageZoneRole.class);
        private final Map<StageZoneRole, StageZoneConfig> defaultConfigs = new EnumMap<>(StageZoneRole.class);
        private int defaultEntityCount = 16;

        private Builder(String name) {
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder icon(Material icon) {
            this.icon = icon;
            return this;
        }

        public Builder defaultEntityCount(int count) {
            this.defaultEntityCount = count;
            return this;
        }

        public Builder role(StageZoneRole role) {
            roles.add(role);
            return this;
        }

        public Builder sizeOverride(StageZoneRole role, Vector size) {
            sizeOverrides.put(role, size);
            return this;
        }

        public Builder offsetOverride(StageZoneRole role, Vector offset) {
            offsetOverrides.put(role, offset);
            return this;
        }

        public Builder configOverride(StageZoneRole role, java.util.function.Consumer<StageZoneConfig> configurer) {
            StageZoneConfig config = defaultConfigs.computeIfAbsent(role, k -> new StageZoneConfig());
            configurer.accept(config);
            return this;
        }

        public StageTemplate build() {
            return new StageTemplate(this);
        }
    }
}
