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
 * Each zone is defined by two opposite corners (like WorldEdit selections).
 *
 * Controls:
 *   Left-click:  Set corner 1, then corner 2
 *   Left-click (after both set): Re-pick corner 1
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
    private BukkitTask renderTask;
    private final List<TextDisplay> roleLabels = new ArrayList<>();

    // Two-corner placement state
    private enum PlacementPhase { CORNER1, CORNER2, CONFIRMING }
    private PlacementPhase phase = PlacementPhase.CORNER1;
    private Location corner1 = null;
    private Location corner2 = null;
    private float currentRotation = 0f; // 0, 90, 180, 270

    // Particle colors
    private static final Color COLOR_CORNER1 = Color.fromRGB(255, 200, 0);      // Gold - corner 1 marker
    private static final Color COLOR_PREVIEW = Color.fromRGB(0, 255, 100);       // Green - live preview
    private static final Color COLOR_CONFIRMED = Color.fromRGB(100, 255, 50);    // Bright green - both set
    private static final Color COLOR_NOT_PLACED = Color.fromRGB(80, 80, 80);     // Gray - unplaced
    private static final Color COLOR_FRONT = Color.fromRGB(0, 200, 255);         // Cyan - front face
    private static final Color COLOR_FRONT_ARROW = Color.fromRGB(0, 255, 255);   // Bright cyan - front arrow

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
    // Front face edges (Z=0 face): 0-1 (bottom), 4-5 (top), 0-4 (left), 1-5 (right)
    private static final int[][] FRONT_EDGES = {
        {0, 1}, {4, 5}, {0, 4}, {1, 5}
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

        // Send instructions
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("=== Zone Placement Wizard ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("Define each zone by selecting two opposite corners.", NamedTextColor.YELLOW));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Controls:", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("  Left-click: ", NamedTextColor.WHITE)
            .append(Component.text("Set corner (1st, then 2nd)", NamedTextColor.GREEN)));
        player.sendMessage(Component.text("  Sneak+Left-click: ", NamedTextColor.WHITE)
            .append(Component.text("Rotate zone 90\u00B0", NamedTextColor.AQUA)));
        player.sendMessage(Component.text("  Right-click: ", NamedTextColor.WHITE)
            .append(Component.text("Confirm zone & advance", NamedTextColor.GREEN)));
        player.sendMessage(Component.text("  Sneak+Right-click: ", NamedTextColor.WHITE)
            .append(Component.text("Skip zone (keep default)", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("  Q (drop): ", NamedTextColor.WHITE)
            .append(Component.text("Cancel placement", NamedTextColor.RED)));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  The ", NamedTextColor.GRAY)
            .append(Component.text("cyan", NamedTextColor.AQUA))
            .append(Component.text(" face with arrow is the FRONT of the zone.", NamedTextColor.GRAY)));
        player.sendMessage(Component.empty());

        StageZoneRole firstRole = rolesToPlace.get(currentIndex);
        player.sendMessage(Component.text("Placing: ", NamedTextColor.GREEN)
            .append(Component.text(firstRole.getDisplayName(), NamedTextColor.GOLD)));

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
     * Apply the two corners to the zone (origin = min corner, size = delta).
     */
    private void applyCorners(StageZoneRole role) {
        if (corner1 == null || corner2 == null) return;

        String zoneName = stage.getZoneName(role);
        if (zoneName == null) return;

        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        // Compute origin (min corner) and size (abs delta)
        double minX = Math.min(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());

        // Ensure minimum 1-block size on each axis
        double sizeX = Math.max(1, maxX - minX);
        double sizeY = Math.max(1, maxY - minY);
        double sizeZ = Math.max(1, maxZ - minZ);

        // When rotated, pivot around the center of the AABB so the box stays in place
        double centerX = minX + sizeX / 2.0;
        double centerZ = minZ + sizeZ / 2.0;
        double radians = Math.toRadians(currentRotation);
        double cosR = Math.cos(radians);
        double sinR = Math.sin(radians);
        double halfX = sizeX / 2.0;
        double halfZ = sizeZ / 2.0;
        double rotOriginX = centerX + (-halfX * cosR - (-halfZ) * sinR);
        double rotOriginZ = centerZ + (-halfX * sinR + (-halfZ) * cosR);

        Location origin = new Location(corner1.getWorld(), rotOriginX, minY, rotOriginZ);
        zone.setOrigin(origin);
        zone.setSize(sizeX, sizeY, sizeZ);
        zone.setRotation(currentRotation);
        plugin.getZoneManager().saveZones();
    }

    /**
     * Advance to the next zone role, or complete if all done.
     */
    private void advanceToNextZone() {
        StageZoneRole currentRole = rolesToPlace.get(currentIndex);
        applyCorners(currentRole);

        // Spawn role label at the placed position
        spawnRoleLabel(currentRole);

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);

        currentIndex++;
        if (currentIndex >= rolesToPlace.size()) {
            complete();
        } else {
            // Reset for next zone
            phase = PlacementPhase.CORNER1;
            corner1 = null;
            corner2 = null;
            currentRotation = 0f;
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

            if (i == currentIndex) {
                renderCurrentZone(zone);
            } else if (i < currentIndex) {
                // Already placed zone
                Color color = ZONE_COLORS[i % ZONE_COLORS.length];
                renderBox(zone.getOrigin(), zone.getSize(), zone.getRotation(), color, 0.7f, 0.7);
            } else {
                // Not yet placed zone
                renderBox(zone.getOrigin(), zone.getSize(), zone.getRotation(), COLOR_NOT_PLACED, 0.5f, 1.0);
            }
        }

        updateActionBar();
    }

    /**
     * Render the zone currently being placed based on the placement phase.
     */
    private void renderCurrentZone(VisualizationZone zone) {
        Location playerBlock = player.getLocation().getBlock().getLocation();

        switch (phase) {
            case CORNER1 -> {
                // Show a small marker at the player's feet
                Particle.DustOptions dust = new Particle.DustOptions(COLOR_CORNER1, 1.5f);
                Location marker = playerBlock.clone().add(0.5, 0.05, 0.5);
                player.spawnParticle(Particle.DUST, marker, 3, 0.2, 0, 0.2, 0, dust);
                // Also show the existing zone dimly
                renderBox(zone.getOrigin(), zone.getSize(), zone.getRotation(), COLOR_NOT_PLACED, 0.4f, 1.2);
            }
            case CORNER2 -> {
                // Corner 1 is set, stretch preview to player position
                Location previewOrigin = computePreviewOrigin(corner1, playerBlock);
                Vector previewSize = computePreviewSize(corner1, playerBlock);

                renderBox(previewOrigin, previewSize, currentRotation, COLOR_PREVIEW, 1.2f, 0.5);
                renderFloor(previewOrigin, previewSize, currentRotation, COLOR_PREVIEW);
                renderFrontFace(previewOrigin, previewSize, currentRotation, 1.5f);
                renderFrontArrow(previewOrigin, previewSize, currentRotation);
                renderCornerMarker(corner1, COLOR_CORNER1);
            }
            case CONFIRMING -> {
                // Both corners set, show finalized box
                Location previewOrigin = computePreviewOrigin(corner1, corner2);
                Vector previewSize = computePreviewSize(corner1, corner2);

                renderBox(previewOrigin, previewSize, currentRotation, COLOR_CONFIRMED, 1.2f, 0.5);
                renderFloor(previewOrigin, previewSize, currentRotation, COLOR_CONFIRMED);
                renderFrontFace(previewOrigin, previewSize, currentRotation, 1.5f);
                renderFrontArrow(previewOrigin, previewSize, currentRotation);
                renderCornerMarker(corner1, COLOR_CORNER1);
                renderCornerMarker(corner2, Color.fromRGB(0, 200, 255));
            }
        }
    }

    /**
     * Compute the preview origin (center-based rotation-aware) for rendering.
     */
    private Location computePreviewOrigin(Location c1, Location c2) {
        double minX = Math.min(c1.getX(), c2.getX());
        double minY = Math.min(c1.getY(), c2.getY());
        double minZ = Math.min(c1.getZ(), c2.getZ());
        double sizeX = Math.max(1, Math.abs(c2.getX() - c1.getX()));
        double sizeZ = Math.max(1, Math.abs(c2.getZ() - c1.getZ()));

        double centerX = minX + sizeX / 2.0;
        double centerZ = minZ + sizeZ / 2.0;

        double radians = Math.toRadians(currentRotation);
        double cosR = Math.cos(radians);
        double sinR = Math.sin(radians);
        double halfX = sizeX / 2.0;
        double halfZ = sizeZ / 2.0;

        double rotOriginX = centerX + (-halfX * cosR - (-halfZ) * sinR);
        double rotOriginZ = centerZ + (-halfX * sinR + (-halfZ) * cosR);

        return new Location(c1.getWorld(), rotOriginX, minY, rotOriginZ);
    }

    /**
     * Compute the preview size from two corners.
     */
    private Vector computePreviewSize(Location c1, Location c2) {
        return new Vector(
            Math.max(1, Math.abs(c2.getX() - c1.getX())),
            Math.max(1, Math.abs(c2.getY() - c1.getY())),
            Math.max(1, Math.abs(c2.getZ() - c1.getZ()))
        );
    }

    /**
     * Render the front face (Z=0 face) in cyan to indicate orientation.
     */
    private void renderFrontFace(Location origin, Vector size, float rotation, float particleSize) {
        Particle.DustOptions frontDust = new Particle.DustOptions(COLOR_FRONT, particleSize);
        Location[] worldCorners = computeWorldCorners(origin, size, rotation);

        for (int[] edge : FRONT_EDGES) {
            drawLine(worldCorners[edge[0]], worldCorners[edge[1]], frontDust, 0.4);
        }
    }

    /**
     * Render an arrow pointing outward from the center of the front face.
     */
    private void renderFrontArrow(Location origin, Vector size, float rotation) {
        Particle.DustOptions arrowDust = new Particle.DustOptions(COLOR_FRONT_ARROW, 1.5f);

        // Center of front face in local coords: (0.5, 0.5, 0)
        Location frontCenter = localToWorld(origin, size, rotation, 0.5, 0.5, 0);

        // Arrow length scales with zone size
        double arrowLength = Math.min(3.0, Math.max(size.getX(), size.getZ()) * 0.3);
        double radians = Math.toRadians(rotation);
        double cosR = Math.cos(radians);
        double sinR = Math.sin(radians);

        // Local -Z direction rotated
        double dirX = sinR;
        double dirZ = -cosR;

        Location arrowEnd = frontCenter.clone().add(dirX * arrowLength, 0, dirZ * arrowLength);
        drawLine(frontCenter, arrowEnd, arrowDust, 0.3);

        // Arrowhead
        double headLength = arrowLength * 0.3;
        double headAngle = Math.toRadians(30);
        for (int side = -1; side <= 1; side += 2) {
            double hdx = -dirX * Math.cos(headAngle) - side * dirZ * Math.sin(headAngle);
            double hdz = -dirZ * Math.cos(headAngle) + side * dirX * Math.sin(headAngle);
            Location headEnd = arrowEnd.clone().add(hdx * headLength, 0, hdz * headLength);
            drawLine(arrowEnd, headEnd, arrowDust, 0.2);
        }
    }

    /**
     * Render a small cross marker at a corner location.
     */
    private void renderCornerMarker(Location corner, Color color) {
        if (corner == null) return;
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.5f);
        Location center = corner.clone().add(0.5, 0.1, 0.5);
        // Cross pattern
        for (double d = -0.5; d <= 0.5; d += 0.2) {
            player.spawnParticle(Particle.DUST, center.clone().add(d, 0, 0), 1, 0, 0, 0, 0, dust);
            player.spawnParticle(Particle.DUST, center.clone().add(0, 0, d), 1, 0, 0, 0, 0, dust);
        }
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
        String rotStr = (int) currentRotation + "\u00B0";

        String status;
        String hint;
        NamedTextColor color;

        switch (phase) {
            case CORNER1 -> {
                status = "Set Corner 1";
                hint = "Left-click to place";
                color = NamedTextColor.AQUA;
            }
            case CORNER2 -> {
                status = "Set Corner 2 [" + rotStr + "]";
                hint = "Left-click | Sneak+L=Rotate";
                color = NamedTextColor.YELLOW;
            }
            case CONFIRMING -> {
                status = "Ready [" + rotStr + "]";
                hint = "R-click=Confirm | Sneak+L=Rotate | L-click=Redo";
                color = NamedTextColor.GREEN;
            }
            default -> {
                status = "";
                hint = "";
                color = NamedTextColor.WHITE;
            }
        }

        player.sendActionBar(Component.text(
            role.getDisplayName() + " (" + progress + ") [" + status + "] " + hint, color));
    }

    // ==================== Labels ====================

    private void spawnRoleLabel(StageZoneRole role) {
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

    private Location getPlayerBlockLocation() {
        return player.getLocation().getBlock().getLocation();
    }

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

        // Sneak+Left-click: Rotate 90 degrees (available once corner 1 is set)
        if (isLeftClick && player.isSneaking() && phase != PlacementPhase.CORNER1) {
            currentRotation = (currentRotation + 90) % 360;
            player.sendMessage(Component.text("Rotated to " + (int) currentRotation + "\u00B0",
                NamedTextColor.AQUA));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.0f + currentRotation / 360f);
            return;
        }

        if (isRightClick && player.isSneaking()) {
            // Sneak+Right-click: Skip zone, keep template default
            StageZoneRole role = rolesToPlace.get(currentIndex);
            player.sendMessage(Component.text("Skipped " + role.getDisplayName() + " (keeping default).",
                NamedTextColor.GRAY));
            spawnRoleLabel(role);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f);

            currentIndex++;
            if (currentIndex >= rolesToPlace.size()) {
                complete();
            } else {
                phase = PlacementPhase.CORNER1;
                corner1 = null;
                corner2 = null;
                currentRotation = 0f;
                updateActionBar();

                StageZoneRole nextRole = rolesToPlace.get(currentIndex);
                player.sendMessage(Component.text("Now placing: ", NamedTextColor.GREEN)
                    .append(Component.text(nextRole.getDisplayName(), NamedTextColor.GOLD)));
            }
            return;
        }

        if (isLeftClick) {
            Location blockLoc = getPlayerBlockLocation();

            switch (phase) {
                case CORNER1 -> {
                    corner1 = blockLoc;
                    phase = PlacementPhase.CORNER2;
                    player.sendMessage(Component.text("Corner 1 set at " + formatLocation(corner1) +
                        ". Walk to the opposite corner and left-click.", NamedTextColor.GREEN));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.5f);
                }
                case CORNER2 -> {
                    corner2 = blockLoc;
                    phase = PlacementPhase.CONFIRMING;

                        Vector boxSize = computePreviewSize(corner1, corner2);
                    player.sendMessage(Component.text("Corner 2 set at " + formatLocation(corner2) +
                        ". Size: " + formatSize(boxSize) +
                        ". Sneak+Left-click to rotate. Right-click to confirm.",
                        NamedTextColor.GREEN));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 2.0f);
                }
                case CONFIRMING -> {
                    // Redo: start over with corner 1
                    corner1 = blockLoc;
                    corner2 = null;
                    currentRotation = 0f;
                    phase = PlacementPhase.CORNER2;
                    player.sendMessage(Component.text("Corner 1 reset to " + formatLocation(corner1) +
                        ". Walk to the opposite corner and left-click.", NamedTextColor.YELLOW));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.5f);
                }
            }
        } else {
            // Right-click (not sneaking): Confirm placement
            if (phase == PlacementPhase.CONFIRMING) {
                advanceToNextZone();
            } else if (phase == PlacementPhase.CORNER2) {
                // Auto-set corner 2 at current position and confirm
                corner2 = getPlayerBlockLocation();
                phase = PlacementPhase.CONFIRMING;
                advanceToNextZone();
            } else {
                player.sendMessage(Component.text("Set corner 1 first (left-click).", NamedTextColor.RED));
            }
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

    private String formatSize(Vector size) {
        return String.format("%.0fx%.0fx%.0f", size.getX(), size.getY(), size.getZ());
    }
}
