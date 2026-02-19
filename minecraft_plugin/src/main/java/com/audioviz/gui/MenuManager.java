package com.audioviz.gui;

import com.audioviz.AudioVizPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central manager for all GUI menus.
 * Handles inventory events and routes them to the appropriate menu handlers.
 */
public class MenuManager implements Listener {

    private final AudioVizPlugin plugin;

    // Track active menu sessions per player
    private final Map<UUID, MenuSession> activeSessions;

    public MenuManager(AudioVizPlugin plugin) {
        this.plugin = plugin;
        this.activeSessions = new HashMap<>();
    }

    /**
     * Open a menu for a player.
     *
     * @param player The player to show the menu to
     * @param menu The menu to display
     */
    public void openMenu(Player player, Menu menu) {
        // Create the inventory
        Inventory inventory = Bukkit.createInventory(
            new MenuHolder(menu),
            menu.getSize(),
            menu.getTitle()
        );

        // Build the menu contents
        menu.build(inventory, player);

        // Store the session
        MenuSession session = new MenuSession(menu, inventory);
        activeSessions.put(player.getUniqueId(), session);

        // Open the inventory
        player.openInventory(inventory);

        // Notify the menu
        menu.onOpen(player);
    }

    /**
     * Refresh the current menu for a player.
     * Useful when menu contents need to update dynamically.
     */
    public void refreshMenu(Player player) {
        MenuSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        session.menu.build(session.inventory, player);
    }

    /**
     * Close the current menu for a player.
     */
    public void closeMenu(Player player) {
        player.closeInventory();
    }

    /**
     * Get the current menu a player has open, if any.
     */
    public Menu getCurrentMenu(Player player) {
        MenuSession session = activeSessions.get(player.getUniqueId());
        return session != null ? session.menu : null;
    }

    /**
     * Check if a player has a menu open.
     */
    public boolean hasMenuOpen(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    /**
     * Clear all active menu sessions.
     * Called on plugin disable to prevent memory leaks.
     */
    public void clearAllSessions() {
        activeSessions.clear();
    }

    /**
     * Remove a specific player's session.
     * Called when a player quits.
     */
    public void removeSession(UUID playerId) {
        activeSessions.remove(playerId);
    }

    // ========== Event Handlers ==========

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Check if this player has an active menu session
        MenuSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        // Verify this is the same inventory we're tracking
        if (!event.getInventory().equals(session.inventory)) return;

        // Check if click was in the menu inventory (top) or player inventory (bottom)
        Inventory clickedInventory = event.getClickedInventory();

        // Handle clicks in the menu inventory
        if (clickedInventory != null && clickedInventory.equals(session.inventory)) {
            // Cancel by default (prevents item theft)
            if (!session.menu.allowItemTake()) {
                event.setCancelled(true);
            }

            // Notify the menu of the click
            session.menu.onClick(player, event.getSlot(), event.getClick());
        } else {
            // Click in player's inventory while menu is open, or clicked outside
            if (!session.menu.allowItemPlace()) {
                // Prevent shift-clicking items into the menu
                if (event.isShiftClick()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Check if this player has an active menu session
        MenuSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        // Verify this is the same inventory we're tracking
        if (!event.getInventory().equals(session.inventory)) return;

        // Cancel dragging in menus by default
        if (!session.menu.allowItemPlace()) {
            // Check if any slots are in the top inventory
            for (int slot : event.getRawSlots()) {
                if (slot < event.getInventory().getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        MenuSession session = activeSessions.get(player.getUniqueId());
        if (session != null && event.getInventory().equals(session.inventory)) {
            activeSessions.remove(player.getUniqueId());
            session.menu.onClose(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up any menu session when player quits to prevent memory leak
        activeSessions.remove(event.getPlayer().getUniqueId());
    }

    // ========== Inner Classes ==========

    /**
     * Holds a reference to the menu for identification.
     */
    public static class MenuHolder implements InventoryHolder {
        private final Menu menu;

        public MenuHolder(Menu menu) {
            this.menu = menu;
        }

        public Menu getMenu() {
            return menu;
        }

        @Override
        public Inventory getInventory() {
            return null; // Not used
        }
    }

    /**
     * Represents an active menu session.
     */
    private static class MenuSession {
        final Menu menu;
        final Inventory inventory;

        MenuSession(Menu menu, Inventory inventory) {
            this.menu = menu;
            this.inventory = inventory;
        }
    }
}
