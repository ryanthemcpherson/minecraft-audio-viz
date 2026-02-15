package com.audioviz.zones;

import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Predefined zone templates for quick zone setup.
 * Each template includes dimensions, entity count, and default material.
 */
public record ZoneTemplate(
    String name,
    String description,
    Vector size,
    int entityCount,
    Material material
) {

    /**
     * Built-in zone templates.
     */
    private static final Map<String, ZoneTemplate> BUILTIN_TEMPLATES = new LinkedHashMap<>();

    static {
        // Small Stage - compact setup for intimate settings
        BUILTIN_TEMPLATES.put("small_stage", new ZoneTemplate(
            "small_stage",
            "Compact 5x3x5 zone, perfect for intimate DJ sets",
            new Vector(5, 3, 5),
            16,
            Material.GLOWSTONE
        ));

        // Concert Hall - standard event size
        BUILTIN_TEMPLATES.put("concert_hall", new ZoneTemplate(
            "concert_hall",
            "Standard 10x6x10 zone for medium-sized events",
            new Vector(10, 6, 10),
            64,
            Material.SEA_LANTERN
        ));

        // DJ Booth - compact overhead display
        BUILTIN_TEMPLATES.put("dj_booth", new ZoneTemplate(
            "dj_booth",
            "Compact 3x4x3 overhead booth display",
            new Vector(3, 4, 3),
            32,
            Material.REDSTONE_LAMP
        ));

        // Arena - large-scale events
        BUILTIN_TEMPLATES.put("arena", new ZoneTemplate(
            "arena",
            "Large 15x8x15 zone for massive events",
            new Vector(15, 8, 15),
            100,
            Material.BEACON
        ));
    }

    /**
     * Get all built-in templates.
     */
    public static Map<String, ZoneTemplate> getBuiltinTemplates() {
        return Collections.unmodifiableMap(BUILTIN_TEMPLATES);
    }

    /**
     * Get a built-in template by name.
     */
    public static ZoneTemplate getBuiltin(String name) {
        return BUILTIN_TEMPLATES.get(name.toLowerCase());
    }

    /**
     * Check if a template name exists.
     */
    public static boolean exists(String name) {
        return BUILTIN_TEMPLATES.containsKey(name.toLowerCase());
    }

    /**
     * Get a display-friendly version of the template name.
     */
    public String getDisplayName() {
        return name.replace("_", " ")
            .substring(0, 1).toUpperCase() +
            name.replace("_", " ").substring(1).toLowerCase();
    }
}
