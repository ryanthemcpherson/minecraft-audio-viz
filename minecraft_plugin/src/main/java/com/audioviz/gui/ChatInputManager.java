package com.audioviz.gui;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.menus.ZoneEditorMenu;
import com.audioviz.gui.menus.ZoneManagementMenu;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages chat-based input for GUI operations like zone creation.
 */
public class ChatInputManager implements Listener {

    private final AudioVizPlugin plugin;
    private final Map<UUID, PendingInput> pendingInputs;

    public ChatInputManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.pendingInputs = new ConcurrentHashMap<>();
    }

    /**
     * Request input from a player via chat.
     *
     * @param player The player
     * @param type The input type (for tracking)
     * @param callback Called with the player's input, or null if cancelled
     */
    public void requestInput(Player player, InputType type, Consumer<String> callback) {
        pendingInputs.put(player.getUniqueId(), new PendingInput(type, callback));
    }

    /**
     * Request zone name input for zone creation.
     */
    public void requestZoneName(Player player) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== Create New Zone ===");
        player.sendMessage(ChatColor.YELLOW + "Enter a name for the new zone in chat.");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to cancel.");
        player.sendMessage("");

        requestInput(player, InputType.ZONE_NAME, input -> {
            if (input == null) {
                player.sendMessage(ChatColor.YELLOW + "Zone creation cancelled.");
                // Reopen zone management menu
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getMenuManager().openMenu(player, new ZoneManagementMenu(plugin, plugin.getMenuManager()));
                });
                return;
            }

            // Validate zone name
            String zoneName = input.trim();
            if (zoneName.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Zone name cannot be empty!");
                requestZoneName(player);
                return;
            }

            if (zoneName.length() > 32) {
                player.sendMessage(ChatColor.RED + "Zone name too long (max 32 characters)!");
                requestZoneName(player);
                return;
            }

            if (!zoneName.matches("^[a-zA-Z0-9_-]+$")) {
                player.sendMessage(ChatColor.RED + "Zone name can only contain letters, numbers, underscores, and hyphens!");
                requestZoneName(player);
                return;
            }

            if (plugin.getZoneManager().zoneExists(zoneName)) {
                player.sendMessage(ChatColor.RED + "A zone with that name already exists!");
                requestZoneName(player);
                return;
            }

            // Create zone at player's location
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                VisualizationZone zone = plugin.getZoneManager().createZone(zoneName, player.getLocation());
                if (zone != null) {
                    player.sendMessage(ChatColor.GREEN + "Zone '" + zoneName + "' created successfully!");
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);

                    // Open zone editor for the new zone
                    plugin.getMenuManager().openMenu(player, new ZoneEditorMenu(plugin, plugin.getMenuManager(), zone));
                } else {
                    player.sendMessage(ChatColor.RED + "Failed to create zone.");
                    plugin.getMenuManager().openMenu(player, new ZoneManagementMenu(plugin, plugin.getMenuManager()));
                }
            });
        });
    }

    /**
     * Check if a player has pending input.
     */
    public boolean hasPendingInput(Player player) {
        return pendingInputs.containsKey(player.getUniqueId());
    }

    /**
     * Cancel pending input for a player.
     */
    public void cancelInput(Player player) {
        PendingInput pending = pendingInputs.remove(player.getUniqueId());
        if (pending != null && pending.callback != null) {
            pending.callback.accept(null);
        }
    }

    // TODO: Migrate to Paper's modern AsyncChatEvent (io.papermc.paper.event.player.AsyncChatEvent)
    //       when dropping support for older Paper builds.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingInput pending = pendingInputs.remove(player.getUniqueId());

        if (pending == null) {
            return;
        }

        // Cancel the chat event
        event.setCancelled(true);

        String message = event.getMessage().trim();

        // Check for cancel
        if (message.equalsIgnoreCase("cancel")) {
            if (pending.callback != null) {
                pending.callback.accept(null);
            }
            return;
        }

        // Execute callback
        if (pending.callback != null) {
            pending.callback.accept(message);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingInputs.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Types of chat input.
     */
    public enum InputType {
        ZONE_NAME,
        STAGE_NAME,
        CONFIRMATION,
        GENERAL
    }

    /**
     * Holds pending input data.
     */
    private static class PendingInput {
        final InputType type;
        final Consumer<String> callback;

        PendingInput(InputType type, Consumer<String> callback) {
            this.type = type;
            this.callback = callback;
        }
    }
}
