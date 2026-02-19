package com.audioviz.zones;

import com.audioviz.AudioVizPlugin;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Manages interactive zone selection via look-at detection with particle feedback.
 * Players in selection mode can look at zones to highlight them, then click to
 * select/deselect. Integrates with ZoneBoundaryRenderer for visual states.
 */
public class ZoneSelectionManager implements Listener {

    private final AudioVizPlugin plugin;

    /** Players currently in selection mode. */
    private final Map<UUID, SelectionState> activePlayers = new HashMap<>();

    /** The repeating raycast task (runs every 4 ticks). */
    private BukkitTask raycastTask;

    /** Maximum raycast distance in blocks. */
    private static final double MAX_RAY_DISTANCE = 64.0;

    public ZoneSelectionManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Start the repeating raycast check task.
     */
    public void start() {
        raycastTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 4L, 4L);
    }

    /**
     * Stop the raycast task and clear all state.
     */
    public void stop() {
        if (raycastTask != null) {
            raycastTask.cancel();
            raycastTask = null;
        }
        // Clear all visual states
        for (SelectionState state : activePlayers.values()) {
            clearVisuals(state);
        }
        activePlayers.clear();
    }

    /**
     * Toggle selection mode for a player.
     * @return true if now in selection mode, false if exited
     */
    public boolean toggleSelectionMode(Player player) {
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
    public void enterSelectionMode(Player player) {
        if (activePlayers.containsKey(player.getUniqueId())) {
            return; // Already in selection mode
        }

        SelectionState state = new SelectionState(player);
        activePlayers.put(player.getUniqueId(), state);

        // Show all zone boundaries persistently
        ZoneBoundaryRenderer renderer = plugin.getZoneBoundaryRenderer();
        for (String zoneName : plugin.getZoneManager().getZoneNames()) {
            renderer.show(zoneName, Long.MAX_VALUE);
            renderer.setPersistent(zoneName, true);
        }

        player.sendMessage(Component.text("Selection Mode ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text("ENABLED", NamedTextColor.GREEN, TextDecoration.BOLD)));
        player.sendMessage(Component.text("  Look at zones to highlight them", NamedTextColor.GRAY));
        player.sendMessage(Component.text("  Left-click to select/deselect", NamedTextColor.GRAY));
        player.sendMessage(Component.text("  Shift+click to add to selection", NamedTextColor.GRAY));
        player.sendMessage(Component.text("  Use /audioviz zone select clear to clear", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    /**
     * Exit selection mode for a player, cleaning up all visual state.
     */
    public void exitSelectionMode(Player player) {
        SelectionState state = activePlayers.remove(player.getUniqueId());
        if (state == null) return;

        clearVisuals(state);

        player.sendMessage(Component.text("Selection Mode ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text("DISABLED", NamedTextColor.RED, TextDecoration.BOLD)));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f);
    }

    /**
     * Check if a player is in selection mode.
     */
    public boolean isInSelectionMode(Player player) {
        return activePlayers.containsKey(player.getUniqueId());
    }

    /**
     * Get the set of selected zone names for a player.
     */
    public Set<String> getSelectedZones(Player player) {
        SelectionState state = activePlayers.get(player.getUniqueId());
        if (state == null) return Collections.emptySet();
        return Collections.unmodifiableSet(state.selectedZones);
    }

    /**
     * Get the currently hovered zone name for a player, or null.
     */
    public String getHoveredZone(Player player) {
        SelectionState state = activePlayers.get(player.getUniqueId());
        return state != null ? state.hoveredZone : null;
    }

    /**
     * Clear all selected zones for a player.
     */
    public void clearSelection(Player player) {
        SelectionState state = activePlayers.get(player.getUniqueId());
        if (state == null) return;

        plugin.getZoneBoundaryRenderer().clearSelection();
        state.selectedZones.clear();

        player.sendMessage(Component.text("Selection cleared.", NamedTextColor.YELLOW));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }

    // ==================== Raycast Tick ====================

    private void tick() {
        Iterator<Map.Entry<UUID, SelectionState>> it = activePlayers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SelectionState> entry = it.next();
            SelectionState state = entry.getValue();
            Player player = state.player;

            if (!player.isOnline()) {
                clearVisuals(state);
                it.remove();
                continue;
            }

            // Perform raycast to find hovered zone
            String newHovered = raycastForZone(player);
            String oldHovered = state.hoveredZone;

            // Update hover state if changed
            if (!Objects.equals(oldHovered, newHovered)) {
                onHoverChanged(state, oldHovered, newHovered);
            }

            // Update action bar
            updateActionBar(state);
        }
    }

    /**
     * Cast a ray from the player's eye position along their look direction,
     * returning the name of the nearest intersecting zone (or null).
     */
    private String raycastForZone(Player player) {
        Location eyeLocation = player.getEyeLocation();
        Vector direction = eyeLocation.getDirection().normalize();
        World world = eyeLocation.getWorld();

        String nearestZone = null;
        double nearestDistance = Double.MAX_VALUE;

        for (VisualizationZone zone : plugin.getZoneManager().getAllZones()) {
            if (!zone.getWorld().equals(world)) continue;

            double hitDistance = rayIntersectsZone(eyeLocation, direction, zone);
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
    private double rayIntersectsZone(Location eyeLocation, Vector direction, VisualizationZone zone) {
        Location origin = zone.getOrigin();
        Vector size = zone.getSize();
        float rotation = zone.getRotation();

        // Transform ray origin into zone-local space
        double dx = eyeLocation.getX() - origin.getX();
        double dy = eyeLocation.getY() - origin.getY();
        double dz = eyeLocation.getZ() - origin.getZ();

        // Inverse-rotate around Y axis
        double radians = Math.toRadians(-rotation);
        double cosR = Math.cos(radians);
        double sinR = Math.sin(radians);

        double localOriginX = dx * cosR - dz * sinR;
        double localOriginY = dy;
        double localOriginZ = dx * sinR + dz * cosR;

        double localDirX = direction.getX() * cosR - direction.getZ() * sinR;
        double localDirY = direction.getY();
        double localDirZ = direction.getX() * sinR + direction.getZ() * cosR;

        // AABB bounds in local space: [0, sizeX] x [0, sizeY] x [0, sizeZ]
        double minX = 0, maxX = size.getX();
        double minY = 0, maxY = size.getY();
        double minZ = 0, maxZ = size.getZ();

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

    // ==================== Visual State Management ====================

    private void onHoverChanged(SelectionState state, String oldHovered, String newHovered) {
        ZoneBoundaryRenderer renderer = plugin.getZoneBoundaryRenderer();

        // Unhighlight old hovered zone (unless it's selected)
        if (oldHovered != null && !state.selectedZones.contains(oldHovered)) {
            renderer.clearSelection();
        }

        // Highlight new hovered zone
        if (newHovered != null) {
            renderer.setSelected(newHovered);
        }

        state.hoveredZone = newHovered;

        // Play subtle sound on hover change
        if (newHovered != null) {
            state.player.playSound(state.player.getLocation(), Sound.UI_BUTTON_CLICK, 0.15f, 1.5f);
        }
    }

    private void updateActionBar(SelectionState state) {
        Player player = state.player;
        Component message;

        if (state.hoveredZone != null) {
            // Show hovered zone info
            String zoneName = state.hoveredZone;
            String roleInfo = getZoneRoleInfo(zoneName);

            Component hoverText = Component.text("Looking at: ", NamedTextColor.GRAY)
                .append(Component.text(zoneName, NamedTextColor.AQUA, TextDecoration.BOLD));

            if (roleInfo != null) {
                hoverText = hoverText.append(Component.text(" (" + roleInfo + ")", NamedTextColor.DARK_AQUA));
            }

            if (!state.selectedZones.isEmpty()) {
                hoverText = hoverText.append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Selected: " + state.selectedZones.size(), NamedTextColor.GREEN));
            }

            message = hoverText;
        } else if (!state.selectedZones.isEmpty()) {
            // Show selection count
            message = Component.text("Selected: " + state.selectedZones.size() + " zone(s)", NamedTextColor.GREEN)
                .append(Component.text(" | Look at a zone to highlight", NamedTextColor.GRAY));
        } else {
            // Default message
            message = Component.text("Selection Mode", NamedTextColor.GOLD)
                .append(Component.text(" | Look at a zone to highlight", NamedTextColor.GRAY));
        }

        player.sendActionBar(message);
    }

    /**
     * Get a display string for the zone's role within its stage, if any.
     */
    private String getZoneRoleInfo(String zoneName) {
        Stage stage = plugin.getStageManager().findStageForZone(zoneName);
        if (stage == null) return null;

        StageZoneRole role = stage.getRoleForZone(zoneName);
        if (role == null) return null;

        return role.getDisplayName();
    }

    private void clearVisuals(SelectionState state) {
        ZoneBoundaryRenderer renderer = plugin.getZoneBoundaryRenderer();

        // Clear selection highlight
        renderer.clearSelection();

        // Remove persistent mode from all zones
        for (String zoneName : plugin.getZoneManager().getZoneNames()) {
            renderer.setPersistent(zoneName, false);
        }

        state.hoveredZone = null;
        state.selectedZones.clear();
    }

    // ==================== Click Handling ====================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        SelectionState state = activePlayers.get(player.getUniqueId());
        if (state == null) return;

        Action action = event.getAction();
        boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        if (!isLeftClick) return;

        // Only process if we're hovering over a zone
        if (state.hoveredZone == null) return;

        event.setCancelled(true);
        String zoneName = state.hoveredZone;

        if (player.isSneaking()) {
            // Shift+click: add to / remove from multi-selection
            if (state.selectedZones.contains(zoneName)) {
                state.selectedZones.remove(zoneName);
                plugin.getZoneBoundaryRenderer().clearSelection();
                player.sendMessage(Component.text("Removed ", NamedTextColor.YELLOW)
                    .append(Component.text(zoneName, NamedTextColor.AQUA))
                    .append(Component.text(" from selection", NamedTextColor.YELLOW)));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            } else {
                state.selectedZones.add(zoneName);
                plugin.getZoneBoundaryRenderer().setSelected(zoneName);
                player.sendMessage(Component.text("Added ", NamedTextColor.GREEN)
                    .append(Component.text(zoneName, NamedTextColor.AQUA))
                    .append(Component.text(" to selection (" + state.selectedZones.size() + " total)", NamedTextColor.GREEN)));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.2f);
            }
        } else {
            // Normal click: toggle single selection (clear others first)
            ZoneBoundaryRenderer renderer = plugin.getZoneBoundaryRenderer();

            if (state.selectedZones.size() == 1 && state.selectedZones.contains(zoneName)) {
                // Deselect
                state.selectedZones.remove(zoneName);
                renderer.clearSelection();
                player.sendMessage(Component.text("Deselected ", NamedTextColor.YELLOW)
                    .append(Component.text(zoneName, NamedTextColor.AQUA)));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            } else {
                // Clear previous and select new
                renderer.clearSelection();
                state.selectedZones.clear();
                state.selectedZones.add(zoneName);
                renderer.setSelected(zoneName);
                player.sendMessage(Component.text("Selected ", NamedTextColor.GREEN)
                    .append(Component.text(zoneName, NamedTextColor.AQUA)));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.2f);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        SelectionState state = activePlayers.remove(event.getPlayer().getUniqueId());
        if (state != null) {
            clearVisuals(state);
        }
    }

    // ==================== State ====================

    private static class SelectionState {
        final Player player;
        String hoveredZone;
        final Set<String> selectedZones = new LinkedHashSet<>();

        SelectionState(Player player) {
            this.player = player;
        }
    }
}
