package com.audioviz.gui;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.menus.MainMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

/**
 * Listener for the physical menu item.
 * Right-clicking the AudioViz menu item opens the main menu.
 */
public class MenuItemListener implements Listener {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;
    private final NamespacedKey menuKey;

    public MenuItemListener(AudioVizPlugin plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
        this.menuKey = new NamespacedKey(plugin, "audioviz_menu");
    }

    /**
     * Create the menu item that players can use to open the GUI.
     */
    public ItemStack createMenuItem() {
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "AudioViz Control");
        meta.setLore(Arrays.asList(
            "",
            ChatColor.GRAY + "Right-click to open the",
            ChatColor.GRAY + "AudioViz control panel.",
            "",
            ChatColor.DARK_PURPLE + "Audio Visualization System"
        ));

        // Mark this item with our custom key
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(menuKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Check if an item is the AudioViz menu item.
     */
    public boolean isMenuItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (!item.hasItemMeta()) return false;

        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        return container.has(menuKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (!isMenuItem(item)) return;

        event.setCancelled(true);

        Player player = event.getPlayer();

        // Check permission
        if (!player.hasPermission("audioviz.menu")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use the AudioViz menu.");
            return;
        }

        // Open main menu
        menuManager.openMenu(player, new MainMenu(plugin, menuManager));
    }
}
