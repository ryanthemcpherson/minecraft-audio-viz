package com.audioviz.stages;

import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;

/**
 * Defines a predefined stage layout template.
 * Ported from Paper: Material → String (icon block), Vector → Vector3f.
 */
public class StageTemplate {

    private final String name;
    private final String description;
    private final String iconBlock;
    private final Set<StageZoneRole> roles;
    private final Map<StageZoneRole, Vector3f> sizeOverrides;
    private final Map<StageZoneRole, Vector3f> offsetOverrides;
    private final Map<StageZoneRole, StageZoneConfig> defaultConfigs;
    private final int defaultEntityCount;

    private StageTemplate(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.iconBlock = builder.iconBlock;
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(builder.roles));
        this.sizeOverrides = Collections.unmodifiableMap(new EnumMap<>(builder.sizeOverrides));
        this.offsetOverrides = Collections.unmodifiableMap(new EnumMap<>(builder.offsetOverrides));
        this.defaultConfigs = Collections.unmodifiableMap(new EnumMap<>(builder.defaultConfigs));
        this.defaultEntityCount = builder.defaultEntityCount;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getIconBlock() { return iconBlock; }
    public Set<StageZoneRole> getRoles() { return roles; }
    public int getRoleCount() { return roles.size(); }
    public int getDefaultEntityCount() { return defaultEntityCount; }

    public Vector3f getSizeForRole(StageZoneRole role) {
        return sizeOverrides.getOrDefault(role, role.getDefaultSize());
    }

    public Vector3f getOffsetForRole(StageZoneRole role) {
        return offsetOverrides.getOrDefault(role, role.getDefaultOffset());
    }

    public int getEstimatedEntityCount() {
        int total = 0;
        for (StageZoneRole role : roles) {
            StageZoneConfig config = defaultConfigs.get(role);
            total += (config != null) ? config.getEntityCount() : defaultEntityCount;
        }
        return total;
    }

    public static Builder builder(String name) { return new Builder(name); }

    // ========== Built-in Templates ==========

    private static final Map<String, StageTemplate> BUILTIN_TEMPLATES = new LinkedHashMap<>();

    static {
        BUILTIN_TEMPLATES.put("club", builder("club")
            .description("Small intimate setup with main stage and audience")
            .iconBlock("note_block")
            .defaultEntityCount(16)
            .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, new Vector3f(8, 8, 6))
                .configOverride(StageZoneRole.MAIN_STAGE, c -> { c.setEntityCount(16); c.setPattern("spectrum"); })
            .role(StageZoneRole.AUDIENCE)
                .sizeOverride(StageZoneRole.AUDIENCE, new Vector3f(12, 3, 8))
                .configOverride(StageZoneRole.AUDIENCE, c -> { c.setEntityCount(8); c.setPattern("wave"); })
            .build());

        BUILTIN_TEMPLATES.put("concert", builder("concert")
            .description("Standard concert with wings, skybox, and audience")
            .iconBlock("jukebox")
            .defaultEntityCount(16)
            .role(StageZoneRole.MAIN_STAGE)
                .configOverride(StageZoneRole.MAIN_STAGE, c -> { c.setEntityCount(32); c.setPattern("spectrum"); })
            .role(StageZoneRole.LEFT_WING)
                .configOverride(StageZoneRole.LEFT_WING, c -> { c.setEntityCount(16); c.setPattern("laser"); })
            .role(StageZoneRole.RIGHT_WING)
                .configOverride(StageZoneRole.RIGHT_WING, c -> { c.setEntityCount(16); c.setPattern("laser"); })
            .role(StageZoneRole.SKYBOX)
                .configOverride(StageZoneRole.SKYBOX, c -> { c.setEntityCount(16); c.setPattern("aurora"); })
            .role(StageZoneRole.AUDIENCE)
                .configOverride(StageZoneRole.AUDIENCE, c -> { c.setEntityCount(16); c.setPattern("wave"); })
            .build());

        BUILTIN_TEMPLATES.put("arena", builder("arena")
            .description("Large arena with backstage, runway, and full surround")
            .iconBlock("end_portal_frame")
            .defaultEntityCount(24)
            .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, new Vector3f(20, 16, 12))
                .configOverride(StageZoneRole.MAIN_STAGE, c -> { c.setEntityCount(64); c.setPattern("spectrum"); })
            .role(StageZoneRole.LEFT_WING)
                .sizeOverride(StageZoneRole.LEFT_WING, new Vector3f(10, 12, 10))
                .offsetOverride(StageZoneRole.LEFT_WING, new Vector3f(-18, 0, 0))
                .configOverride(StageZoneRole.LEFT_WING, c -> { c.setEntityCount(24); c.setPattern("laser"); })
            .role(StageZoneRole.RIGHT_WING)
                .sizeOverride(StageZoneRole.RIGHT_WING, new Vector3f(10, 12, 10))
                .offsetOverride(StageZoneRole.RIGHT_WING, new Vector3f(18, 0, 0))
                .configOverride(StageZoneRole.RIGHT_WING, c -> { c.setEntityCount(24); c.setPattern("laser"); })
            .role(StageZoneRole.SKYBOX)
                .sizeOverride(StageZoneRole.SKYBOX, new Vector3f(30, 8, 20))
                .offsetOverride(StageZoneRole.SKYBOX, new Vector3f(0, 18, 0))
                .configOverride(StageZoneRole.SKYBOX, c -> { c.setEntityCount(32); c.setPattern("aurora"); })
            .role(StageZoneRole.AUDIENCE)
                .sizeOverride(StageZoneRole.AUDIENCE, new Vector3f(30, 4, 16))
                .offsetOverride(StageZoneRole.AUDIENCE, new Vector3f(0, 0, 18))
                .configOverride(StageZoneRole.AUDIENCE, c -> { c.setEntityCount(24); c.setPattern("wave"); })
            .role(StageZoneRole.BACKSTAGE)
                .sizeOverride(StageZoneRole.BACKSTAGE, new Vector3f(12, 8, 8))
                .offsetOverride(StageZoneRole.BACKSTAGE, new Vector3f(0, 0, -14))
                .configOverride(StageZoneRole.BACKSTAGE, c -> { c.setEntityCount(16); c.setPattern("bars"); })
            .role(StageZoneRole.RUNWAY)
                .sizeOverride(StageZoneRole.RUNWAY, new Vector3f(4, 8, 20))
                .configOverride(StageZoneRole.RUNWAY, c -> { c.setEntityCount(16); c.setPattern("vortex"); })
            .build());

        BUILTIN_TEMPLATES.put("festival", builder("festival")
            .description("Massive festival with all zones at 1.5x scale")
            .iconBlock("dragon_egg")
            .defaultEntityCount(32)
            .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, new Vector3f(30, 24, 18))
                .configOverride(StageZoneRole.MAIN_STAGE, c -> { c.setEntityCount(100); c.setPattern("spectrum"); })
            .role(StageZoneRole.LEFT_WING)
                .sizeOverride(StageZoneRole.LEFT_WING, new Vector3f(15, 18, 15))
                .offsetOverride(StageZoneRole.LEFT_WING, new Vector3f(-27, 0, 0))
                .configOverride(StageZoneRole.LEFT_WING, c -> { c.setEntityCount(36); c.setPattern("laser"); })
            .role(StageZoneRole.RIGHT_WING)
                .sizeOverride(StageZoneRole.RIGHT_WING, new Vector3f(15, 18, 15))
                .offsetOverride(StageZoneRole.RIGHT_WING, new Vector3f(27, 0, 0))
                .configOverride(StageZoneRole.RIGHT_WING, c -> { c.setEntityCount(36); c.setPattern("laser"); })
            .role(StageZoneRole.SKYBOX)
                .sizeOverride(StageZoneRole.SKYBOX, new Vector3f(45, 12, 30))
                .offsetOverride(StageZoneRole.SKYBOX, new Vector3f(0, 27, 0))
                .configOverride(StageZoneRole.SKYBOX, c -> { c.setEntityCount(48); c.setPattern("aurora"); })
            .role(StageZoneRole.AUDIENCE)
                .sizeOverride(StageZoneRole.AUDIENCE, new Vector3f(45, 6, 24))
                .offsetOverride(StageZoneRole.AUDIENCE, new Vector3f(0, 0, 27))
                .configOverride(StageZoneRole.AUDIENCE, c -> { c.setEntityCount(36); c.setPattern("wave"); })
            .role(StageZoneRole.BACKSTAGE)
                .sizeOverride(StageZoneRole.BACKSTAGE, new Vector3f(18, 12, 12))
                .offsetOverride(StageZoneRole.BACKSTAGE, new Vector3f(0, 0, -21))
                .configOverride(StageZoneRole.BACKSTAGE, c -> { c.setEntityCount(24); c.setPattern("bars"); })
            .role(StageZoneRole.RUNWAY)
                .sizeOverride(StageZoneRole.RUNWAY, new Vector3f(6, 12, 30))
                .offsetOverride(StageZoneRole.RUNWAY, new Vector3f(0, 0, 12))
                .configOverride(StageZoneRole.RUNWAY, c -> { c.setEntityCount(24); c.setPattern("vortex"); })
            .role(StageZoneRole.BALCONY)
                .sizeOverride(StageZoneRole.BALCONY, new Vector3f(30, 6, 9))
                .offsetOverride(StageZoneRole.BALCONY, new Vector3f(0, 12, 21))
                .configOverride(StageZoneRole.BALCONY, c -> { c.setEntityCount(24); c.setPattern("mandala"); })
            .build());
    }

    public static Map<String, StageTemplate> getBuiltinTemplates() {
        return Collections.unmodifiableMap(BUILTIN_TEMPLATES);
    }

    public static StageTemplate getBuiltin(String name) {
        return BUILTIN_TEMPLATES.get(name.toLowerCase());
    }

    // ========== Builder ==========

    public static class Builder {
        private final String name;
        private String description = "";
        private String iconBlock = "jukebox";
        private final Set<StageZoneRole> roles = new LinkedHashSet<>();
        private final Map<StageZoneRole, Vector3f> sizeOverrides = new EnumMap<>(StageZoneRole.class);
        private final Map<StageZoneRole, Vector3f> offsetOverrides = new EnumMap<>(StageZoneRole.class);
        private final Map<StageZoneRole, StageZoneConfig> defaultConfigs = new EnumMap<>(StageZoneRole.class);
        private int defaultEntityCount = 16;

        private Builder(String name) { this.name = name; }

        public Builder description(String d) { this.description = d; return this; }
        public Builder iconBlock(String i) { this.iconBlock = i; return this; }
        public Builder defaultEntityCount(int c) { this.defaultEntityCount = c; return this; }
        public Builder role(StageZoneRole role) { roles.add(role); return this; }
        public Builder sizeOverride(StageZoneRole role, Vector3f size) { sizeOverrides.put(role, size); return this; }
        public Builder offsetOverride(StageZoneRole role, Vector3f offset) { offsetOverrides.put(role, offset); return this; }
        public Builder configOverride(StageZoneRole role, Consumer<StageZoneConfig> configurer) {
            StageZoneConfig config = defaultConfigs.computeIfAbsent(role, k -> new StageZoneConfig());
            configurer.accept(config);
            return this;
        }
        public StageTemplate build() { return new StageTemplate(this); }
    }
}
