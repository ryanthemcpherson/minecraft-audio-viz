package com.audioviz.zones;

import com.audioviz.AudioVizPlugin;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Visual zone editor allowing players to adjust zone boundaries in-game.
 * Uses particle outlines and item-based controls.
 */
public class ZoneEditor implements Listener {

    private final AudioVizPlugin plugin;
    private final Map<UUID, EditSession> activeSessions;

    public ZoneEditor(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.activeSessions = new HashMap<>();
    }

    /**
     * Start editing a zone.
     */
    public void startEditing(Player player, VisualizationZone zone) {
        // Check for existing session
        if (activeSessions.containsKey(player.getUniqueId())) {
            stopEditing(player);
        }

        EditSession session = new EditSession(player, zone);
        activeSessions.put(player.getUniqueId(), session);

        // Start particle rendering task
        session.particleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            renderZoneOutline(session);
        }, 0L, 5L); // Every 5 ticks (0.25 seconds)

        // Send instructions
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== Zone Editor: " + zone.getName() + " ===");
        player.sendMessage(ChatColor.YELLOW + "Controls:");
        player.sendMessage(ChatColor.WHITE + "  Left-click: Move zone to your position");
        player.sendMessage(ChatColor.WHITE + "  Right-click: Adjust size (look direction)");
        player.sendMessage(ChatColor.WHITE + "  Shift + Left-click: Rotate -15\u00B0");
        player.sendMessage(ChatColor.WHITE + "  Shift + Right-click: Rotate +15\u00B0");
        player.sendMessage(ChatColor.WHITE + "  Sneak + Jump: Exit editor");
        player.sendMessage(ChatColor.GRAY + "Hold any tool to adjust. Particle outline shows zone bounds.");
        player.sendMessage("");

        // Play sound
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    /**
     * Stop editing.
     */
    public void stopEditing(Player player) {
        EditSession session = activeSessions.remove(player.getUniqueId());
        if (session != null) {
            if (session.particleTask != null) {
                session.particleTask.cancel();
            }
            plugin.getZoneManager().saveZones();
            player.sendMessage(ChatColor.GREEN + "Zone editor closed. Changes saved.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f);
        }
    }

    /**
     * Check if a player is currently editing.
     */
    public boolean isEditing(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    /**
     * Render zone outline with particles.
     */
    private void renderZoneOutline(EditSession session) {
        VisualizationZone zone = session.zone;
        Player player = session.player;

        if (!player.isOnline()) {
            stopEditing(player);
            return;
        }

        Location origin = zone.getOrigin();
        Vector size = zone.getSize();
        float rotation = zone.getRotation();

        // Particle settings
        Particle particle = Particle.DUST;
        Particle.DustOptions dustOptions = new Particle.DustOptions(Color.YELLOW, 1.0f);
        Particle.DustOptions cornerDust = new Particle.DustOptions(Color.LIME, 1.5f);
        double spacing = 0.5; // Distance between particles

        // Draw the 12 edges of the zone box (rotated)
        double[][] corners = {
            {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}, // Bottom
            {0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}  // Top
        };

        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, // Bottom
            {4, 5}, {5, 6}, {6, 7}, {7, 4}, // Top
            {0, 4}, {1, 5}, {2, 6}, {3, 7}  // Verticals
        };

        // Convert corners to world positions
        Location[] worldCorners = new Location[8];
        for (int i = 0; i < 8; i++) {
            double localX = corners[i][0] * size.getX();
            double localY = corners[i][1] * size.getY();
            double localZ = corners[i][2] * size.getZ();
            worldCorners[i] = zone.localToWorld(
                corners[i][0],
                corners[i][1],
                corners[i][2]
            );
        }

        // Draw edges
        for (int[] edge : edges) {
            Location start = worldCorners[edge[0]];
            Location end = worldCorners[edge[1]];
            drawLine(player, start, end, particle, dustOptions, spacing);
        }

        // Draw corners with larger particles
        for (Location corner : worldCorners) {
            player.spawnParticle(particle, corner, 1, 0, 0, 0, 0, cornerDust);
        }

        // Draw origin marker
        player.spawnParticle(Particle.HAPPY_VILLAGER, origin, 3, 0.1, 0.1, 0.1, 0);

        // Draw center marker
        Location center = zone.getCenter();
        player.spawnParticle(Particle.END_ROD, center, 1, 0, 0, 0, 0);

        // Draw rotation indicator (arrow pointing in rotation direction)
        double rad = Math.toRadians(rotation);
        Location arrowStart = center.clone();
        Location arrowEnd = center.clone().add(Math.sin(rad) * 3, 0, Math.cos(rad) * 3);
        Particle.DustOptions arrowDust = new Particle.DustOptions(Color.RED, 1.0f);
        drawLine(player, arrowStart, arrowEnd, particle, arrowDust, 0.3);
    }

    /**
     * Draw a particle line between two points.
     */
    private void drawLine(Player player, Location start, Location end, Particle particle,
                          Particle.DustOptions options, double spacing) {
        Vector direction = end.toVector().subtract(start.toVector());
        double length = direction.length();
        direction.normalize();

        for (double d = 0; d <= length; d += spacing) {
            Location point = start.clone().add(direction.clone().multiply(d));
            player.spawnParticle(particle, point, 1, 0, 0, 0, 0, options);
        }
    }

    // ==================== Event Handlers ====================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        EditSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        Action action = event.getAction();
        boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean isRightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;

        if (!isLeftClick && !isRightClick) return;

        event.setCancelled(true);
        VisualizationZone zone = session.zone;

        if (player.isSneaking()) {
            // Rotation control
            float currentRotation = zone.getRotation();
            if (isLeftClick) {
                zone.setRotation((currentRotation - 15 + 360) % 360);
                player.sendMessage(ChatColor.YELLOW + "Rotation: " + (int) zone.getRotation() + "\u00B0");
            } else {
                zone.setRotation((currentRotation + 15) % 360);
                player.sendMessage(ChatColor.YELLOW + "Rotation: " + (int) zone.getRotation() + "\u00B0");
            }
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
        } else if (isLeftClick) {
            // Move zone origin to player position
            Location newOrigin = player.getLocation().getBlock().getLocation();
            zone.setOrigin(newOrigin);
            player.sendMessage(ChatColor.GREEN + "Zone moved to your position");
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f);
        } else {
            // Adjust size based on look direction
            Vector direction = player.getLocation().getDirection();
            Vector size = zone.getSize();

            // Determine which axis to adjust based on look direction
            double absX = Math.abs(direction.getX());
            double absY = Math.abs(direction.getY());
            double absZ = Math.abs(direction.getZ());

            int adjustment = player.isSneaking() ? 5 : 1;

            if (absY > absX && absY > absZ) {
                // Looking up/down - adjust Y
                int newY = (int) Math.max(1, Math.min(100, size.getY() + (direction.getY() > 0 ? adjustment : -adjustment)));
                zone.setSize(new Vector(size.getX(), newY, size.getZ()));
                player.sendMessage(ChatColor.AQUA + "Y Size: " + newY);
            } else if (absX > absZ) {
                // Looking left/right - adjust X
                int newX = (int) Math.max(1, Math.min(100, size.getX() + (direction.getX() > 0 ? adjustment : -adjustment)));
                zone.setSize(new Vector(newX, size.getY(), size.getZ()));
                player.sendMessage(ChatColor.RED + "X Size: " + newX);
            } else {
                // Looking forward/back - adjust Z
                int newZ = (int) Math.max(1, Math.min(100, size.getZ() + (direction.getZ() > 0 ? adjustment : -adjustment)));
                zone.setSize(new Vector(size.getX(), size.getY(), newZ));
                player.sendMessage(ChatColor.BLUE + "Z Size: " + newZ);
            }
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        EditSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        // Check for sneak + jump to exit
        if (player.isSneaking() && event.getTo() != null &&
            event.getTo().getY() > event.getFrom().getY()) {
            stopEditing(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopEditing(event.getPlayer());
    }

    /**
     * Holds an active editing session.
     */
    private static class EditSession {
        final Player player;
        final VisualizationZone zone;
        BukkitTask particleTask;

        EditSession(Player player, VisualizationZone zone) {
            this.player = player;
            this.zone = zone;
        }
    }
}
