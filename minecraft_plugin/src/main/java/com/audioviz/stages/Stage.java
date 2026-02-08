package com.audioviz.stages;

import com.audioviz.decorators.DecoratorConfig;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Represents a stage - a named collection of zones with semantic roles.
 * A stage has an anchor point in the world, a rotation, and maps zone roles
 * to actual zone names managed by ZoneManager.
 */
public class Stage {

    private final String name;
    private final UUID id;
    private Location anchor;
    private float rotation;
    private String templateName;
    private boolean active;

    private final Map<StageZoneRole, String> roleToZone;
    private final Map<StageZoneRole, StageZoneConfig> zoneConfigs;

    // Decorator configurations (decorator type id -> config)
    private final Map<String, DecoratorConfig> decoratorConfigs;

    // Organization metadata
    private String tag;
    private boolean pinned;
    private long createdAt;
    private long lastActivatedAt;

    /**
     * Create a new stage.
     */
    public Stage(String name, Location anchor, String templateName) {
        this.name = name;
        this.id = UUID.randomUUID();
        this.anchor = anchor.clone();
        this.rotation = 0f;
        this.templateName = templateName;
        this.active = false;
        this.roleToZone = new EnumMap<>(StageZoneRole.class);
        this.zoneConfigs = new EnumMap<>(StageZoneRole.class);
        this.decoratorConfigs = new LinkedHashMap<>();
        this.tag = "";
        this.pinned = false;
        this.createdAt = System.currentTimeMillis();
        this.lastActivatedAt = 0L;
    }

    /**
     * Full constructor for deserialization.
     */
    public Stage(String name, UUID id, Location anchor, float rotation,
                 String templateName, boolean active,
                 Map<StageZoneRole, String> roleToZone,
                 Map<StageZoneRole, StageZoneConfig> zoneConfigs,
                 Map<String, DecoratorConfig> decoratorConfigs,
                 String tag, boolean pinned, long createdAt, long lastActivatedAt) {
        this.name = name;
        this.id = id;
        this.anchor = anchor.clone();
        this.rotation = rotation;
        this.templateName = templateName;
        this.active = active;
        this.roleToZone = new EnumMap<>(roleToZone);
        this.zoneConfigs = new EnumMap<>(zoneConfigs);
        this.decoratorConfigs = decoratorConfigs != null ? new LinkedHashMap<>(decoratorConfigs) : new LinkedHashMap<>();
        this.tag = tag != null ? tag : "";
        this.pinned = pinned;
        this.createdAt = createdAt;
        this.lastActivatedAt = lastActivatedAt;
    }

    public String getName() {
        return name;
    }

    public UUID getId() {
        return id;
    }

    public Location getAnchor() {
        return anchor.clone();
    }

    public void setAnchor(Location anchor) {
        this.anchor = anchor.clone();
    }

    public float getRotation() {
        return rotation;
    }

    public void setRotation(float rotation) {
        this.rotation = rotation % 360;
    }

    public String getTemplateName() {
        return templateName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Map<StageZoneRole, String> getRoleToZone() {
        return roleToZone;
    }

    public Map<StageZoneRole, StageZoneConfig> getZoneConfigs() {
        return zoneConfigs;
    }

    /**
     * Calculate the world location for a zone role based on anchor, rotation, and role offset.
     */
    public Location getWorldLocationForRole(StageZoneRole role) {
        Vector offset = role.getDefaultOffset();

        // Check for template-specific offset overrides
        StageTemplate template = StageTemplate.getBuiltin(templateName);
        if (template != null && template.getOffsetOverrides().containsKey(role)) {
            offset = template.getOffsetOverrides().get(role);
        }

        // Apply stage rotation to the offset
        double radians = Math.toRadians(rotation);
        double rotatedX = offset.getX() * Math.cos(radians) - offset.getZ() * Math.sin(radians);
        double rotatedZ = offset.getX() * Math.sin(radians) + offset.getZ() * Math.cos(radians);

        return anchor.clone().add(rotatedX, offset.getY(), rotatedZ);
    }

    /**
     * Get the zone name for a given role.
     */
    public String getZoneName(StageZoneRole role) {
        return roleToZone.get(role);
    }

    /**
     * Get all zone names managed by this stage.
     */
    public Collection<String> getZoneNames() {
        return Collections.unmodifiableCollection(roleToZone.values());
    }

    /**
     * Get all active roles in this stage.
     */
    public Set<StageZoneRole> getActiveRoles() {
        return Collections.unmodifiableSet(roleToZone.keySet());
    }

    /**
     * Check if a zone name belongs to this stage.
     */
    public boolean ownsZone(String zoneName) {
        for (String name : roleToZone.values()) {
            if (name.equalsIgnoreCase(zoneName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the role for a zone name.
     */
    public StageZoneRole getRoleForZone(String zoneName) {
        for (Map.Entry<StageZoneRole, String> entry : roleToZone.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(zoneName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Get the config for a role, creating a default if none exists.
     */
    public StageZoneConfig getOrCreateConfig(StageZoneRole role) {
        return zoneConfigs.computeIfAbsent(role, k -> new StageZoneConfig());
    }

    /**
     * Get the total entity count across all zone configs.
     */
    public int getTotalEntityCount() {
        int total = 0;
        for (StageZoneConfig config : zoneConfigs.values()) {
            total += config.getEntityCount();
        }
        return total;
    }

    // ========== Decorator Configs ==========

    public Map<String, DecoratorConfig> getDecoratorConfigs() {
        return decoratorConfigs;
    }

    public DecoratorConfig getDecoratorConfig(String decoratorId) {
        return decoratorConfigs.get(decoratorId);
    }

    public void setDecoratorConfig(String decoratorId, DecoratorConfig config) {
        decoratorConfigs.put(decoratorId, config);
    }

    public void removeDecoratorConfig(String decoratorId) {
        decoratorConfigs.remove(decoratorId);
    }

    // ========== Organization Metadata ==========

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag != null ? tag.trim().toLowerCase() : "";
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastActivatedAt() {
        return lastActivatedAt;
    }

    public void setLastActivatedAt(long lastActivatedAt) {
        this.lastActivatedAt = lastActivatedAt;
    }

    @Override
    public String toString() {
        return String.format("Stage[%s, template=%s, roles=%d, active=%s]",
            name, templateName, roleToZone.size(), active);
    }
}
