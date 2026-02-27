package com.audioviz.zones;

import com.audioviz.AudioVizMod;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages interactive zone selection via look-at detection with particle feedback.
 * Players in selection mode can look at zones to highlight them, then click to
 * select/deselect. Integrates with ZoneBoundaryRenderer for visual states.
 *
 * <p>Ported from Paper: Bukkit events → method calls from AudioVizMod callbacks,
 * BukkitTask → tick-based, Component → Text, Location → Vec3d,
 * player.sendActionBar → player.sendMessage(text, true).
 */
public class ZoneSelectionManager {

    private final AudioVizMod mod;

    /** Players currently in selection mode. */
    private final Map<UUID, SelectionState> activePlayers = new ConcurrentHashMap<>();

    /** Maximum raycast distance in blocks. */
    private static final double MAX_RAY_DISTANCE = 64.0;

    /** Raycast check interval in ticks (4 ticks = 200ms). */
    private static final int RAYCAST_INTERVAL = 4;

    private long tickCount = 0;

    public ZoneSelectionManager(AudioVizMod mod) {
        this.mod = mod;
    }

    /**
     * Toggle selection mode for a player.
     * @return true if now in selection mode, false if exited
     */
    public boolean toggleSelectionMode(ServerPlayerEntity player) {
        if (isInSelectionMode(player)) {
            exitSelectionMode(player);
            return false;
        } else {
            enterSelectionMode(player);
            return true;
        }
    }

    /**
     * Enter selection mode for a player.
     */
    public void enterSelectionMode(ServerPlayerEntity player) {
        if (activePlayers.containsKey(player.getUuid())) return;

        SelectionState state = new SelectionState(player);
        activePlayers.put(player.getUuid(), state);

        // Show all zone boundaries persistently
        ZoneBoundaryRenderer renderer = mod.getZoneBoundaryRenderer();
        if (renderer != null) {
            for (String zoneName : mod.getZoneManager().getZoneNames()) {
                renderer.show(zoneName, Long.MAX_VALUE);
                renderer.setPersistent(zoneName, true);
            }
        }

        player.sendMessage(Text.literal("Selection Mode ")
            .formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal("ENABLED").formatted(Formatting.GREEN, Formatting.BOLD)));
        player.sendMessage(Text.literal("  Look at zones to highlight them").formatted(Formatting.GRAY));
        player.sendMessage(Text.literal("  Left-click to select/deselect").formatted(Formatting.GRAY));
        player.sendMessage(Text.literal("  Shift+click to add to selection").formatted(Formatting.GRAY));
        player.sendMessage(Text.literal("  /audioviz select clear to clear").formatted(Formatting.GRAY));
        player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1f, 1.5f);
    }

    /**
     * Exit selection mode for a player, cleaning up all visual state.
     */
    public void exitSelectionMode(ServerPlayerEntity player) {
        SelectionState state = activePlayers.remove(player.getUuid());
        if (state == null) return;

        clearVisualsForPlayer(state);

        player.sendMessage(Text.literal("Selection Mode ")
            .formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal("DISABLED").formatted(Formatting.RED, Formatting.BOLD)));
        player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1f, 0.8f);
    }

    public boolean isInSelectionMode(ServerPlayerEntity player) {
        return activePlayers.containsKey(player.getUuid());
    }

    /**
     * Get the set of selected zone names for a player.
     */
    public Set<String> getSelectedZones(ServerPlayerEntity player) {
        SelectionState state = activePlayers.get(player.getUuid());
        if (state == null) return Collections.emptySet();
        return Collections.unmodifiableSet(state.selectedZones);
    }

    /**
     * Get the currently hovered zone name for a player, or null.
     */
    public String getHoveredZone(ServerPlayerEntity player) {
        SelectionState state = activePlayers.get(player.getUuid());
        return state != null ? state.hoveredZone : null;
    }

    /**
     * Clear all selected zones for a player.
     */
    public void clearSelection(ServerPlayerEntity player) {
        SelectionState state = activePlayers.get(player.getUuid());
        if (state == null) return;

        ZoneBoundaryRenderer renderer = mod.getZoneBoundaryRenderer();
        if (renderer != null) {
            for (String zoneName : state.selectedZones) {
                renderer.clearSelected(zoneName);
            }
        }
        state.selectedZones.clear();

        player.sendMessage(Text.literal("Selection cleared.").formatted(Formatting.YELLOW));
        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1f);
    }

    // ==================== Click Handling ====================

    /**
     * Handle left-click from a player in selection mode.
     * Called from AudioVizMod's AttackBlockCallback.
     * @return true if consumed (should cancel default behavior)
     */
    public boolean handleLeftClick(ServerPlayerEntity player) {
        SelectionState state = activePlayers.get(player.getUuid());
        if (state == null) return false;
        if (state.hoveredZone == null) return false;

        String zoneName = state.hoveredZone;
        ZoneBoundaryRenderer renderer = mod.getZoneBoundaryRenderer();

        if (player.isSneaking()) {
            // Shift+click: add to / remove from multi-selection
            if (state.selectedZones.contains(zoneName)) {
                state.selectedZones.remove(zoneName);
                renderer.clearSelected(zoneName);
                player.sendMessage(Text.literal("Removed ").formatted(Formatting.YELLOW)
                    .append(Text.literal(zoneName).formatted(Formatting.AQUA))
                    .append(Text.literal(" from selection").formatted(Formatting.YELLOW)));
                player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.5f, 0.8f);
            } else {
                state.selectedZones.add(zoneName);
                renderer.setSelected(zoneName);
                player.sendMessage(Text.literal("Added ").formatted(Formatting.GREEN)
                    .append(Text.literal(zoneName).formatted(Formatting.AQUA))
                    .append(Text.literal(" to selection (" + state.selectedZones.size() + " total)")
                        .formatted(Formatting.GREEN)));
                player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.5f, 1.2f);
            }
        } else {
            // Normal click: toggle single selection (clear others first)
            if (state.selectedZones.size() == 1 && state.selectedZones.contains(zoneName)) {
                state.selectedZones.remove(zoneName);
                renderer.clearSelection();
                player.sendMessage(Text.literal("Deselected ").formatted(Formatting.YELLOW)
                    .append(Text.literal(zoneName).formatted(Formatting.AQUA)));
                player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.5f, 0.8f);
            } else {
                renderer.clearSelection();
                state.selectedZones.clear();
                state.selectedZones.add(zoneName);
                renderer.setSelected(zoneName);
                player.sendMessage(Text.literal("Selected ").formatted(Formatting.GREEN)
                    .append(Text.literal(zoneName).formatted(Formatting.AQUA)));
                player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.5f, 1.2f);
            }
        }
        return true;
    }

    // ==================== Tick ====================

    /**
     * Called every server tick from AudioVizMod. Performs raycast at RAYCAST_INTERVAL.
     */
    public void tick() {
        tickCount++;
        if (tickCount % RAYCAST_INTERVAL != 0) return;
        if (activePlayers.isEmpty()) return;

        var iterator = activePlayers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            SelectionState state = entry.getValue();
            ServerPlayerEntity player = state.player;

            if (!player.isAlive() || player.isDisconnected()) {
                iterator.remove();
                clearVisualsForPlayer(state);
                continue;
            }

            // Perform raycast to find hovered zone
            String newHovered = raycastForZone(player);
            String oldHovered = state.hoveredZone;

            if (!Objects.equals(oldHovered, newHovered)) {
                onHoverChanged(state, oldHovered, newHovered);
            }

            updateActionBar(state);
        }
    }

    // ==================== Lifecycle ====================

    /**
     * Handle player disconnect.
     */
    public void handleDisconnect(UUID playerUuid) {
        SelectionState state = activePlayers.remove(playerUuid);
        if (state != null) {
            clearVisualsForPlayer(state);
        }
    }

    /**
     * Stop all sessions (called on server shutdown).
     */
    public void stop() {
        activePlayers.clear();
        // On shutdown, clean up all persistent/selected state
        ZoneBoundaryRenderer renderer = mod.getZoneBoundaryRenderer();
        if (renderer != null) {
            renderer.clearSelection();
            for (String zoneName : mod.getZoneManager().getZoneNames()) {
                renderer.setPersistent(zoneName, false);
            }
        }
    }

    // ==================== Raycast ====================

    /**
     * Cast a ray from the player's eye position along their look direction,
     * returning the name of the nearest intersecting zone (or null).
     */
    private String raycastForZone(ServerPlayerEntity player) {
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0f);

        String nearestZone = null;
        double nearestDistance = Double.MAX_VALUE;

        for (VisualizationZone zone : mod.getZoneManager().getAllZones()) {
            if (zone.getWorld() == null || !zone.getWorld().equals(player.getEntityWorld())) continue;

            double hitDistance = rayIntersectsZone(eyePos, lookVec, zone);
            if (hitDistance >= 0 && hitDistance < nearestDistance) {
                nearestDistance = hitDistance;
                nearestZone = zone.getName();
            }
        }

        return nearestZone;
    }

    /**
     * Test ray-AABB intersection for a zone. Accounts for zone rotation by
     * inverse-rotating the ray into the zone's local space.
     *
     * @return distance to intersection point, or -1 if no intersection
     */
    private double rayIntersectsZone(Vec3d eyePos, Vec3d direction, VisualizationZone zone) {
        double ox = zone.getOrigin().getX();
        double oy = zone.getOrigin().getY();
        double oz = zone.getOrigin().getZ();
        var size = zone.getSize();
        float rotation = zone.getRotation();

        // Transform ray origin into zone-local space
        double dx = eyePos.x - ox;
        double dy = eyePos.y - oy;
        double dz = eyePos.z - oz;

        // Inverse-rotate around Y axis
        double radians = Math.toRadians(-rotation);
        double cosR = Math.cos(radians);
        double sinR = Math.sin(radians);

        double localOriginX = dx * cosR - dz * sinR;
        double localOriginY = dy;
        double localOriginZ = dx * sinR + dz * cosR;

        double localDirX = direction.x * cosR - direction.z * sinR;
        double localDirY = direction.y;
        double localDirZ = direction.x * sinR + direction.z * cosR;

        // AABB bounds in local space: [0, sizeX] x [0, sizeY] x [0, sizeZ]
        double minX = 0, maxX = size.x;
        double minY = 0, maxY = size.y;
        double minZ = 0, maxZ = size.z;

        // Slab intersection test
        double tMin = 0;
        double tMax = MAX_RAY_DISTANCE;

        // X slab
        if (Math.abs(localDirX) < 1e-9) {
            if (localOriginX < minX || localOriginX > maxX) return -1;
        } else {
            double t1 = (minX - localOriginX) / localDirX;
            double t2 = (maxX - localOriginX) / localDirX;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }

        // Y slab
        if (Math.abs(localDirY) < 1e-9) {
            if (localOriginY < minY || localOriginY > maxY) return -1;
        } else {
            double t1 = (minY - localOriginY) / localDirY;
            double t2 = (maxY - localOriginY) / localDirY;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }

        // Z slab
        if (Math.abs(localDirZ) < 1e-9) {
            if (localOriginZ < minZ || localOriginZ > maxZ) return -1;
        } else {
            double t1 = (minZ - localOriginZ) / localDirZ;
            double t2 = (maxZ - localOriginZ) / localDirZ;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }

        return tMin >= 0 ? tMin : -1;
    }

    // ==================== Visual State ====================

    private void onHoverChanged(SelectionState state, String oldHovered, String newHovered) {
        ZoneBoundaryRenderer renderer = mod.getZoneBoundaryRenderer();

        // Unhighlight old hovered zone (unless it's selected)
        if (oldHovered != null && !state.selectedZones.contains(oldHovered)) {
            renderer.clearSelected(oldHovered);
        }

        // Highlight new hovered zone
        if (newHovered != null) {
            renderer.setSelected(newHovered);
        }

        state.hoveredZone = newHovered;

        // Play subtle sound on hover change
        if (newHovered != null) {
            state.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.15f, 1.5f);
        }
    }

    private void updateActionBar(SelectionState state) {
        ServerPlayerEntity player = state.player;
        Text message;

        if (state.hoveredZone != null) {
            String zoneName = state.hoveredZone;
            String roleInfo = getZoneRoleInfo(zoneName);

            MutableText hoverText = Text.literal("Looking at: ").formatted(Formatting.GRAY)
                .append(Text.literal(zoneName).formatted(Formatting.AQUA, Formatting.BOLD));

            if (roleInfo != null) {
                hoverText = hoverText.append(Text.literal(" (" + roleInfo + ")").formatted(Formatting.DARK_AQUA));
            }

            if (!state.selectedZones.isEmpty()) {
                hoverText = hoverText
                    .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("Selected: " + state.selectedZones.size()).formatted(Formatting.GREEN));
            }

            message = hoverText;
        } else if (!state.selectedZones.isEmpty()) {
            message = Text.literal("Selected: " + state.selectedZones.size() + " zone(s)").formatted(Formatting.GREEN)
                .append(Text.literal(" | Look at a zone to highlight").formatted(Formatting.GRAY));
        } else {
            message = Text.literal("Selection Mode").formatted(Formatting.GOLD)
                .append(Text.literal(" | Look at a zone to highlight").formatted(Formatting.GRAY));
        }

        player.sendMessage(message, true);
    }

    private String getZoneRoleInfo(String zoneName) {
        Stage stage = mod.getStageManager().findStageForZone(zoneName);
        if (stage == null) return null;

        StageZoneRole role = stage.getRoleForZone(zoneName);
        if (role == null) return null;

        return role.getDisplayName();
    }

    /**
     * Clear visual state for a departing player. Only removes persistent boundaries
     * if no other players remain in selection mode (avoids breaking their visuals).
     */
    private void clearVisualsForPlayer(SelectionState state) {
        ZoneBoundaryRenderer renderer = mod.getZoneBoundaryRenderer();
        if (renderer == null) return;

        // Clear this player's selection highlights
        for (String zoneName : state.selectedZones) {
            renderer.clearSelected(zoneName);
        }
        if (state.hoveredZone != null) {
            renderer.clearSelected(state.hoveredZone);
        }

        // Only remove persistent boundaries if no one else is in selection mode
        if (activePlayers.isEmpty()) {
            renderer.clearSelection();
            for (String zoneName : mod.getZoneManager().getZoneNames()) {
                renderer.setPersistent(zoneName, false);
            }
        }
    }

    // ==================== State ====================

    private static class SelectionState {
        final ServerPlayerEntity player;
        String hoveredZone;
        final Set<String> selectedZones = new LinkedHashSet<>();

        SelectionState(ServerPlayerEntity player) {
            this.player = player;
        }
    }
}
