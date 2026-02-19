package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Main hub menu for AudioViz.
 * Provides access to all other menus and shows system status.
 */
public class MainMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;

    // Slot positions
    private static final int SLOT_ZONES = 11;
    private static final int SLOT_DJ_PANEL = 13;
    private static final int SLOT_SETTINGS = 15;
    private static final int SLOT_STATUS = 22;
    private static final int SLOT_HELP = 26;

    public MainMenu(AudioVizPlugin plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    @Override
    public String getTitle() {
        return "\u00A76\u00A7lAudioViz Control Panel";
    }

    @Override
    public int getSize() {
        return 27; // 3 rows
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        // Fill border with glass panes
        ItemStack filler = ItemBuilder.glassPane(DyeColor.GRAY);
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        // Zone Management
        inventory.setItem(SLOT_ZONES, new ItemBuilder(Material.CHEST)
            .name("&6Zone Management")
            .lore(
                "&7Manage visualization zones",
                "",
                "&fZones: &e" + plugin.getZoneManager().getZoneCount(),
                "",
                "&eClick to manage zones"
            )
            .build());

        // DJ Control Panel
        inventory.setItem(SLOT_DJ_PANEL, new ItemBuilder(Material.JUKEBOX)
            .name("&dDJ Control Panel")
            .lore(
                "&7Live visualization controls",
                "",
                "&7Adjust effects, intensity,",
                "&7and visual parameters.",
                "",
                "&eClick to open DJ panel"
            )
            .glow()
            .build());

        // Settings
        inventory.setItem(SLOT_SETTINGS, new ItemBuilder(Material.COMPARATOR)
            .name("&bSettings")
            .lore(
                "&7Configure plugin settings",
                "",
                "&7Performance, defaults,",
                "&7and WebSocket options.",
                "",
                "&eClick to open settings"
            )
            .build());

        // Status Display
        int connectionCount = plugin.getWebSocketServer() != null
            ? plugin.getWebSocketServer().getConnectionCount() : 0;
        int totalEntities = getTotalEntityCount();
        long updatesProcessed = plugin.getEntityUpdateStats() != null
            ? plugin.getEntityUpdateStats().getTotalUpdatesProcessed() : 0;

        Material statusMaterial = connectionCount > 0 ? Material.EMERALD : Material.REDSTONE;

        inventory.setItem(SLOT_STATUS, new ItemBuilder(statusMaterial)
            .name("&aSystem Status")
            .lore(
                "&7Current system information",
                "",
                "&fWebSocket: " + (connectionCount > 0 ? "&aConnected" : "&cDisconnected"),
                "&fClients: &e" + connectionCount,
                "&fActive Zones: &e" + plugin.getZoneManager().getZoneCount(),
                "&fTotal Entities: &e" + totalEntities,
                "&fUpdates Processed: &e" + formatNumber(updatesProcessed),
                "",
                "&eClick to refresh"
            )
            .build());

        // Help
        inventory.setItem(SLOT_HELP, new ItemBuilder(Material.BOOK)
            .name("&eHelp & Info")
            .lore(
                "&7AudioViz Plugin",
                "&7Real-time audio visualization",
                "",
                "&fVersion: &71.0.0",
                "&fAPI: &7Paper 1.21.1",
                "",
                "&7Use &f/audioviz help &7for",
                "&7command information."
            )
            .build());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case SLOT_ZONES -> {
                playClickSound(player);
                menuManager.openMenu(player, new ZoneManagementMenu(plugin, menuManager));
            }
            case SLOT_DJ_PANEL -> {
                playClickSound(player);
                menuManager.openMenu(player, new DJControlPanel(plugin, menuManager));
            }
            case SLOT_SETTINGS -> {
                playClickSound(player);
                menuManager.openMenu(player, new SettingsMenu(plugin, menuManager));
            }
            case SLOT_STATUS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
                menuManager.refreshMenu(player);
            }
            case SLOT_HELP -> {
                playClickSound(player);
                player.closeInventory();
                player.performCommand("audioviz help");
            }
        }
    }

    private void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }

    private int getTotalEntityCount() {
        int total = 0;
        for (String zoneName : plugin.getZoneManager().getZoneNames()) {
            total += plugin.getEntityPoolManager().getEntityCount(zoneName);
        }
        return total;
    }

    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }
}
