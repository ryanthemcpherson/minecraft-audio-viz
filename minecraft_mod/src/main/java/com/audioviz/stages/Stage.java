package com.audioviz.stages;

import com.audioviz.decorators.DecoratorConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.*;

/**
 * Represents a stage — a named collection of zones with semantic roles.
 *
 * <p>Ported from Paper: Location → BlockPos + worldName,
 * org.bukkit.util.Vector → org.joml.Vector3f.
 */
public class Stage {

    private final String name;
    private final UUID id;
    private BlockPos anchor;
    private String worldName;
    private float rotation;
    private String templateName;
    private boolean active;

    private final Map<StageZoneRole, String> roleToZone;
    private final Map<StageZoneRole, StageZoneConfig> zoneConfigs;
    private final Map<String, DecoratorConfig> decoratorConfigs;

    private String tag;
    private boolean pinned;
    private long createdAt;
    private long lastActivatedAt;

    public Stage(String name, BlockPos anchor, String worldName, String templateName) {
        this.name = name;
        this.id = UUID.randomUUID();
        this.anchor = anchor;
        this.worldName = worldName;
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

    public Stage(String name, UUID id, BlockPos anchor, String worldName, float rotation,
                 String templateName, boolean active,
                 Map<StageZoneRole, String> roleToZone,
                 Map<StageZoneRole, StageZoneConfig> zoneConfigs,
                 Map<String, DecoratorConfig> decoratorConfigs,
                 String tag, boolean pinned, long createdAt, long lastActivatedAt) {
        this.name = name;
        this.id = id;
        this.anchor = anchor;
        this.worldName = worldName;
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

    public String getName() { return name; }
    public UUID getId() { return id; }
    public BlockPos getAnchor() { return anchor; }
    public String getWorldName() { return worldName; }
    public void setAnchor(BlockPos anchor) { this.anchor = anchor; }
    public void setWorldName(String worldName) { this.worldName = worldName; }
    public float getRotation() { return rotation; }
    public void setRotation(float rotation) { this.rotation = rotation % 360; }
    public String getTemplateName() { return templateName; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Map<StageZoneRole, String> getRoleToZone() { return roleToZone; }
    public Map<StageZoneRole, StageZoneConfig> getZoneConfigs() { return zoneConfigs; }
    public Map<String, DecoratorConfig> getDecoratorConfigs() { return decoratorConfigs; }

    /**
     * Calculate the world position for a zone role based on anchor and rotation.
     */
    public Vec3d getWorldPositionForRole(StageZoneRole role) {
        Vector3f offset = role.getDefaultOffset();

        double radians = Math.toRadians(rotation);
        double rotatedX = offset.x * Math.cos(radians) - offset.z * Math.sin(radians);
        double rotatedZ = offset.x * Math.sin(radians) + offset.z * Math.cos(radians);

        return new Vec3d(
            anchor.getX() + rotatedX,
            anchor.getY() + offset.y,
            anchor.getZ() + rotatedZ
        );
    }

    public String getZoneName(StageZoneRole role) { return roleToZone.get(role); }

    public Collection<String> getZoneNames() {
        return Collections.unmodifiableCollection(roleToZone.values());
    }

    public Set<StageZoneRole> getActiveRoles() {
        return Collections.unmodifiableSet(roleToZone.keySet());
    }

    public boolean ownsZone(String zoneName) {
        for (String name : roleToZone.values()) {
            if (name.equalsIgnoreCase(zoneName)) return true;
        }
        return false;
    }

    public StageZoneRole getRoleForZone(String zoneName) {
        for (Map.Entry<StageZoneRole, String> entry : roleToZone.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(zoneName)) return entry.getKey();
        }
        return null;
    }

    public StageZoneConfig getOrCreateConfig(StageZoneRole role) {
        return zoneConfigs.computeIfAbsent(role, k -> new StageZoneConfig());
    }

    public int getTotalEntityCount() {
        int total = 0;
        for (StageZoneConfig config : zoneConfigs.values()) {
            total += config.getEntityCount();
        }
        return total;
    }

    public DecoratorConfig getDecoratorConfig(String decoratorId) { return decoratorConfigs.get(decoratorId); }
    public void setDecoratorConfig(String decoratorId, DecoratorConfig config) { decoratorConfigs.put(decoratorId, config); }
    public void removeDecoratorConfig(String decoratorId) { decoratorConfigs.remove(decoratorId); }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag != null ? tag.trim().toLowerCase() : ""; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActivatedAt() { return lastActivatedAt; }
    public void setLastActivatedAt(long lastActivatedAt) { this.lastActivatedAt = lastActivatedAt; }

    @Override
    public String toString() {
        return String.format("Stage[%s, template=%s, roles=%d, active=%s]",
            name, templateName, roleToZone.size(), active);
    }
}
