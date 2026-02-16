package com.audioviz.stages;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.menus.StageEditorMenu;
import com.audioviz.zones.VisualizationZone;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player in-world placement session for positioning stage zones one-by-one.
 * The player walks to each zone's desired location, sees a particle outline preview,
 * and confirms placement before advancing to the next zone.
 *
 * Controls:
 *   Left-click:  Lock zone origin to player position (outline stops following)
 *   Left-click again: Reposition (re-lock at new position)
 *   Right-click: Confirm placement, advance to next zone
 *   Sneak+Right-click: Skip zone (keep template default), advance
 *   Q (drop): Cancel entire placement
 *   PlayerQuit: Auto-cancel
 */
public class StageZonePlacementSession implements Listener {

    private final AudioVizPlugin plugin;
    private final Player player;
    private final Stage stage;
    private final StageTemplate template;
    private final List<StageZoneRole> rolesToPlace;

    private int currentIndex = 0;
    private Location currentOrigin = null;
    private boolean originLocked = false;
    private BukkitTask renderTask;
    private final List<TextDisplay> roleLabels = new ArrayList<>();

    // Particle colors
    private static final Color COLOR_CURRENT_FOLLOWING = Color.fromRGB(0, 255, 100);
    private static final Color COLOR_CURRENT_LOCKED = Color.fromRGB(100, 255, 50);
    private static final Color COLOR_NOT_PLACED = Color.fromRGB(80, 80, 80);

    // Color palette for placed zones (matches ZoneBoundaryRenderer.ZONE_COLORS)
    private static final Color[] ZONE_COLORS = {
        Color.fromRGB(255, 60, 60),    // Red
        Color.fromRGB(60, 120, 255),   // Blue
        Color.fromRGB(60, 255, 60),    // Green
        Color.fromRGB(255, 165, 0),    // Orange
        Color.fromRGB(180, 60, 255),   // Purple
        Color.fromRGB(255, 255, 60),   // Yellow
        Color.fromRGB(255, 105, 180),  // Pink
        Color.fromRGB(255, 255, 255),  // White
    };

    // Box corners and edges for a unit cube [0,1]^3
    private static final double[][] CORNERS = {
        {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1},
        {0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}
    };
    private static final int[][] EDGES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0},
        {4, 5}, {5, 6}, {6, 7}, {7, 4},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    public StageZonePlacementSession(AudioVizPlugin plugin, Player player, Stage stage) {
        this.plugin = plugin;
        this.player = player;
        this.stage = stage;

        // Resolve template
        this.template = plugin.getStageManager().getTemplate(stage.getTemplateName());

        // Build ordered list of roles to place
        this.rolesToPlace = new ArrayList<>(stage.getActiveRoles());
    }

    /**
     * Start the placement session: register events, start render task, send instructions.
     */
    public void start() {
        if (rolesToPlace.isEmpty()) {
            player.sendMessage(Component.text("No zones to place!", NamedTextColor.RED));
            stop(true);
            return;
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        renderTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::render, 0L, 5L);

        // Initialize current origin to the zone's current position
        initCurrentOrigin();

        // Send instructions
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("=== Zone Placement Wizard ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("Place each zone in your stage one-by-one.", NamedTextColor.YELLOW));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Controls:", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("  Left-click: ", NamedTextColor.WHITE)
            .append(Component.text("Lock zone at your position", NamedTextColor.GREEN)));
        player.sendMessage(Component.text("  Right-click: ", NamedTextColor.WHITE)
            .append(Component.text("Confirm & advance to next zone", NamedTextColor.GREEN)));
        player.sendMessage(Component.text("  Sneak+Right-click: ", NamedTextColor.WHITE)
            .append(Component.text("Skip zone (keep default)", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("  Q (drop): ", NamedTextColor.WHITE)
            .append(Component.text("Cancel placement", NamedTextColor.RED)));
        player.sendMessage(Component.empty());

        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        updateActionBar();
    }

    /**
     * Stop the placement session and clean up.
     */
    public void stop(boolean cancelled) {
        if (renderTask != null) {
            renderTask.cancel();
            renderTask = null;
        }

        HandlerList.unregisterAll(this);
        cleanupLabels();

        plugin.getZonePlacementManager().removeSession(player.getUniqueId());

        if (cancelled) {
            player.sendMessage(Component.text("Zone placement cancelled.", NamedTextColor.YELLOW));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
        }
    }

    /**
     * Advance to the next zone role, or complete if all done.
     */
    private void advanceToNextZone() {
        // Save current origin to the zone
        StageZoneRole currentRole = rolesToPlace.get(currentIndex);
        if (currentOrigin != null) {
            String zoneName = stage.getZoneName(currentRole);
            if (zoneName != null) {
                VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
                if (zone != null) {
                    zone.setOrigin(currentOrigin);
                    plugin.getZoneManager().saveZones();
                }
            }
        }

        // Spawn role label at the placed position
        spawnRoleLabel(currentRole);

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);

        currentIndex++;
        if (currentIndex >= rolesToPlace.size()) {
            complete();
        } else {
            originLocked = false;
            initCurrentOrigin();
            updateActionBar();

            StageZoneRole nextRole = rolesToPlace.get(currentIndex);
            player.sendMessage(Component.text("Now placing: ", NamedTextColor.GREEN)
                .append(Component.text(nextRole.getDisplayName(), NamedTextColor.GOLD)));
        }
    }

    /**
     * Complete the placement session - all zones placed.
     */
    private void complete() {
        // Save stages
        plugin.getStageManager().saveStages();

        stop(false);

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("All zones placed!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);

        // Open the stage editor menu
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getMenuManager().openMenu(player, new StageEditorMenu(plugin, plugin.getMenuManager(), stage));
        });
    }

    /**
     * Initialize currentOrigin to the zone's existing world position.
     */
    private void initCurrentOrigin() {
        StageZoneRole role = rolesToPlace.get(currentIndex);
        String zoneName = stage.getZoneName(role);
        if (zoneName != null) {
            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone != null) {
                currentOrigin = zone.getOrigin();
            } else {
                currentOrigin = stage.getWorldLocationForRole(role);
            }
        } else {
            currentOrigin = stage.getWorldLocationForRole(role);
        }
    }

    // ==================== Rendering ====================

    /**
     * Per-tick particle rendering for all zone states.
     */
    private void render() {
        if (!player.isOnline()) {
            stop(true);
            return;
        }

        for (int i = 0; i < rolesToPlace.size(); i++) {
            StageZoneRole role = rolesToPlace.get(i);
            String zoneName = stage.getZoneName(role);
            if (zoneName == null) continue;

            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            Vector size = zone.getSize();
            float rotation = zone.getRotation();

            if (i == currentIndex) {
                // Current zone being placed
                Location origin;
                if (!originLocked) {
                    // Follow player position
                    currentOrigin = player.getLocation().getBlock().getLocation();
                    origin = currentOrigin;
                } else {
                    origin = currentOrigin;
                }

                Color color = originLocked ? COLOR_CURRENT_LOCKED : COLOR_CURRENT_FOLLOWING;
                renderBox(origin, size, rotation, color, 1.2f, 0.5);
                renderFloor(origin, size, rotation, color);
                renderAxisIndicators(origin, rotation);
            } else if (i < currentIndex) {
                // Already placed zone
                Color color = ZONE_COLORS[i % ZONE_COLORS.length];
                Color dimColor = darken(color, 0.4);
                renderBox(zone.getOrigin(), size, rotation, color, 0.7f, 0.7);
                renderFloor(zone.getOrigin(), size, rotation, dimColor);
            } else {
                // Not yet placed zone
                renderBox(zone.getOrigin(), size, rotation, COLOR_NOT_PLACED, 0.5f, 1.0);
            }
        }

        updateActionBar();
    }

    /**
     * Render a box outline with the given parameters (per-player particles).
     */
    private void renderBox(Location origin, Vector size, float rotation,
                           Color color, float particleSize, double spacing) {
        Particle.DustOptions dust = new Particle.DustOptions(color, particleSize);

        Location[] worldCorners = computeWorldCorners(origin, size, rotation);

        for (int[] edge : EDGES) {
            drawLine(worldCorners[edge[0]], worldCorners[edge[1]], dust, spacing);
        }
    }

    /**
     * Render a subtle floor plane at Y=0 of the zone.
     */
    private void renderFloor(Location origin, Vector size, float rotation, Color color) {
        Particle.DustOptions dust = new Particle.DustOptions(darken(color, 0.3), 0.5f);

        double floorSpacing = 1.0;
        for (double x = 0; x <= 1.0; x += floorSpacing / Math.max(1, size.getX())) {
            for (double z = 0; z <= 1.0; z += floorSpacing / Math.max(1, size.getZ())) {
                Location point = localToWorld(origin, size, rotation, x, 0, z);
                player.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    /**
     * Render axis indicators at origin (Red=+X, Green=+Y, Blue=+Z).
     */
    private void renderAxisIndicators(Location origin, float rotation) {
        double axisLength = 3.0;
        double axisSpacing = 0.3;

        double radians = Math.toRadians(rotation);
        double cosR = Math.cos(radians);
        double sinR = Math.sin(radians);

        // +X axis (rotated)
        Particle.DustOptions xDust = new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.0f);
        Location xEnd = origin.clone().add(cosR * axisLength, 0, sinR * axisLength);
        drawLine(origin, xEnd, xDust, axisSpacing);

        // +Y axis
        Particle.DustOptions yDust = new Particle.DustOptions(Color.fromRGB(0, 255, 0), 1.0f);
        Location yEnd = origin.clone().add(0, axisLength, 0);
        drawLine(origin, yEnd, yDust, axisSpacing);

        // +Z axis (rotated)
        Particle.DustOptions zDust = new Particle.DustOptions(Color.fromRGB(0, 100, 255), 1.0f);
        Location zEnd = origin.clone().add(-sinR * axisLength, 0, cosR * axisLength);
        drawLine(origin, zEnd, zDust, axisSpacing);
    }

    /**
     * Compute world-space corners from origin, size, and rotation.
     */
    private Location[] computeWorldCorners(Location origin, Vector size, float rotation) {
        Location[] worldCorners = new Location[8];
        double radians = Math.toRadians(rotation);
        double cosR = Math.cos(radians);
        double sinR = Math.sin(radians);

        for (int i = 0; i < 8; i++) {
            double scaledX = CORNERS[i][0] * size.getX();
            double scaledY = CORNERS[i][1] * size.getY();
            double scaledZ = CORNERS[i][2] * size.getZ();
            double rotatedX = scaledX * cosR - scaledZ * sinR;
            double rotatedZ = scaledX * sinR + scaledZ * cosR;
            worldCorners[i] = origin.clone().add(rotatedX, scaledY, rotatedZ);
        }
        return worldCorners;
    }

    /**
     * Convert a local [0,1] position to world coordinates.
     */
    private Location localToWorld(Location origin, Vector size, float rotation,
                                  double localX, double localY, double localZ) {
        double scaledX = localX * size.getX();
        double scaledY = localY * size.getY();
        double scaledZ = localZ * size.getZ();
        double radians = Math.toRadians(rotation);
        double rotatedX = scaledX * Math.cos(radians) - scaledZ * Math.sin(radians);
        double rotatedZ = scaledX * Math.sin(radians) + scaledZ * Math.cos(radians);
        return origin.clone().add(rotatedX, scaledY, rotatedZ);
    }

    /**
     * Draw a particle line between two points (per-player).
     */
    private void drawLine(Location start, Location end, Particle.DustOptions dust, double spacing) {
        org.bukkit.util.Vector direction = end.toVector().subtract(start.toVector());
        double length = direction.length();
        if (length < 0.01) return;
        direction.normalize();

        for (double d = 0; d <= length; d += spacing) {
            Location point = start.clone().add(direction.clone().multiply(d));
            player.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
        }
    }

    // ==================== Action Bar ====================

    private void updateActionBar() {
        if (currentIndex >= rolesToPlace.size()) return;

        StageZoneRole role = rolesToPlace.get(currentIndex);
        String progress = (currentIndex + 1) + "/" + rolesToPlace.size();
        String status = originLocked ? "LOCKED" : "following";
        String hint = originLocked ? "Right-click=Confirm" : "Left-click=Lock position";

        player.sendActionBar(Component.text(role.getDisplayName() + " (" + progress + ") [" + status + "] " + hint,
            originLocked ? NamedTextColor.GREEN : NamedTextColor.AQUA));
    }

    // ==================== Labels ====================

    private void spawnRoleLabel(StageZoneRole role) {
        if (currentOrigin == null) return;

        String zoneName = stage.getZoneName(role);
        if (zoneName == null) return;

        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        Location labelLoc = zone.getCenter().add(0, zone.getSize().getY() * 0.5 + 1.5, 0);

        labelLoc.getWorld().spawn(labelLoc, TextDisplay.class, textDisplay -> {
            textDisplay.setText(ChatColor.GOLD + role.getDisplayName());
            textDisplay.setBillboard(Display.Billboard.CENTER);
            textDisplay.setBackgroundColor(org.bukkit.Color.fromARGB(160, 0, 0, 0));
            textDisplay.setShadowed(true);
            textDisplay.setSeeThrough(true);
            textDisplay.setViewRange(64.0f);
            roleLabels.add(textDisplay);
        });
    }

    private void cleanupLabels() {
        for (TextDisplay label : roleLabels) {
            if (label != null && !label.isDead()) {
                label.remove();
            }
        }
        roleLabels.clear();
    }

    // ==================== Helpers ====================

    private static Color darken(Color color, double factor) {
        int r = (int) (color.getRed() * (1 - factor));
        int g = (int) (color.getGreen() * (1 - factor));
        int b = (int) (color.getBlue() * (1 - factor));
        return Color.fromRGB(r, g, b);
    }

    public Player getPlayer() {
        return player;
    }

    // ==================== Event Handlers ====================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean isRightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;

        if (!isLeftClick && !isRightClick) return;

        event.setCancelled(true);

        if (isRightClick && player.isSneaking()) {
            // Sneak+Right-click: Skip zone, keep template default
            StageZoneRole role = rolesToPlace.get(currentIndex);
            player.sendMessage(Component.text("Skipped " + role.getDisplayName() + " (keeping default).",
                NamedTextColor.GRAY));
            // Don't update origin, just advance
            spawnRoleLabel(role);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f);

            currentIndex++;
            if (currentIndex >= rolesToPlace.size()) {
                complete();
            } else {
                originLocked = false;
                initCurrentOrigin();
                updateActionBar();

                StageZoneRole nextRole = rolesToPlace.get(currentIndex);
                player.sendMessage(Component.text("Now placing: ", NamedTextColor.GREEN)
                    .append(Component.text(nextRole.getDisplayName(), NamedTextColor.GOLD)));
            }
        } else if (isLeftClick) {
            // Left-click: Lock/reposition origin at player location
            currentOrigin = player.getLocation().getBlock().getLocation();
            originLocked = true;

            player.sendMessage(Component.text("Position locked at " +
                formatLocation(currentOrigin) + ". Right-click to confirm.",
                NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.5f);
        } else {
            // Right-click (not sneaking): Confirm placement
            if (!originLocked) {
                // Auto-lock at current position first
                currentOrigin = player.getLocation().getBlock().getLocation();
                originLocked = true;
            }
            advanceToNextZone();
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;

        event.setCancelled(true);
        stop(true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;

        stop(true);
    }

    private String formatLocation(Location loc) {
        return String.format("%.0f, %.0f, %.0f", loc.getX(), loc.getY(), loc.getZ());
    }
}
