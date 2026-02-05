package com.audioviz.render;

/**
 * Stable backend keys used in protocol contracts.
 */
public enum RendererBackendType {
    DISPLAY_ENTITIES("display_entities"),
    PARTICLES("particles"),
    HOLOGRAM("hologram");

    private final String key;

    RendererBackendType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static RendererBackendType fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (RendererBackendType type : values()) {
            if (type.key.equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }
}
