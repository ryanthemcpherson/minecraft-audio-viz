package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Settings menu for configuring plugin options.
 * Allows adjustment of performance settings and defaults.
 */
public class SettingsMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;

    // Slot positions
    private static final int SLOT_INFO = 4;
    private static final int SLOT_MAX_ENTITIES = 11;
    private static final int SLOT_UPDATE_INTERVAL = 13;
    private static final int SLOT_BATCH_SIZE = 15;
    private static final int SLOT_VIEW_CULLING = 20;
    private static final int SLOT_CULL_DISTANCE = 22;
    private static final int SLOT_DEFAULT_MATERIAL = 24;
    private static final int SLOT_WEBSOCKET_STATUS = 31;
    private static final int SLOT_BACK = 36;
    private static final int SLOT_RELOAD = 40;
    private static final int SLOT_SAVE = 44;

    public SettingsMenu(AudioVizPlugin plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    @Override
    public String getTitle() {
        return "\u00A7bSettings";
    }

    @Override
    public int getSize() {
        return 45; // 5 rows
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();

        // Fill background
        ItemStack filler = ItemBuilder.glassPane(DyeColor.GRAY);
        for (int i = 0; i < 45; i++) {
            inventory.setItem(i, filler);
        }

        // Header
        inventory.setItem(SLOT_INFO, new ItemBuilder(Material.COMPARATOR)
            .name("&bPlugin Settings")
            .lore(
                "&7Configure AudioViz behavior",
                "",
                "&7Changes require &f/audioviz reload",
                "&7or server restart to take effect."
            )
            .build());

        // Performance section header
        inventory.setItem(9, new ItemBuilder(Material.REDSTONE_TORCH)
            .name("&cPerformance Settings")
            .lore("&7Adjust for your server's needs")
            .build());

        // Max entities per zone
        int maxEntities = plugin.getConfig().getInt("performance.max_entities_per_zone", 100);
        inventory.setItem(SLOT_MAX_ENTITIES, ItemBuilder.slider(
            "&eMax Entities/Zone",
            Math.min(maxEntities / 10, 10),
            10,
            "&7Current: " + maxEntities
        ));

        // Update interval
        int updateInterval = plugin.getConfig().getInt("performance.min_update_interval", 1);
        inventory.setItem(SLOT_UPDATE_INTERVAL, new ItemBuilder(Material.CLOCK)
            .name("&eUpdate Interval")
            .lore(
                "&7Ticks between updates",
                "&7Current: &f" + updateInterval + " tick(s)",
                "",
                "&7Lower = smoother but more CPU",
                "&7Higher = choppier but lighter",
                "",
                "&eLeft-click: +1 tick",
                "&eRight-click: -1 tick"
            )
            .amount(Math.max(1, Math.min(64, updateInterval)))
            .build());

        // Batch size
        int batchSize = plugin.getConfig().getInt("performance.batch_size", 50);
        inventory.setItem(SLOT_BATCH_SIZE, new ItemBuilder(Material.HOPPER)
            .name("&eBatch Size")
            .lore(
                "&7Entities processed per tick",
                "&7Current: &f" + batchSize,
                "",
                "&7Higher = faster updates",
                "&7Lower = smoother server",
                "",
                "&eLeft-click: +10",
                "&eRight-click: -10"
            )
            .amount(Math.max(1, Math.min(64, batchSize)))
            .build());

        // View distance culling toggle
        boolean viewCulling = plugin.getConfig().getBoolean("performance.view_distance_culling", true);
        inventory.setItem(SLOT_VIEW_CULLING, ItemBuilder.toggle(
            "&eView Distance Culling",
            viewCulling,
            "&7Skip updates for entities",
            "&7no player can see"
        ));

        // Cull distance
        int cullDistance = plugin.getConfig().getInt("performance.cull_distance_chunks", 8);
        inventory.setItem(SLOT_CULL_DISTANCE, new ItemBuilder(Material.SPYGLASS)
            .name("&eCull Distance")
            .lore(
                "&7Distance for culling",
                "&7Current: &f" + cullDistance + " chunks",
                "&7(" + (cullDistance * 16) + " blocks)",
                "",
                "&eLeft-click: +1 chunk",
                "&eRight-click: -1 chunk"
            )
            .amount(Math.max(1, Math.min(64, cullDistance)))
            .build());

        // Default material
        String defaultMaterial = plugin.getConfig().getString("defaults.material", "GLOWSTONE");
        inventory.setItem(SLOT_DEFAULT_MATERIAL, new ItemBuilder(Material.valueOf(defaultMaterial))
            .name("&6Default Material")
            .lore(
                "&7Material for new entities",
                "&7Current: &f" + defaultMaterial,
                "",
                "&eClick to cycle materials"
            )
            .build());

        // WebSocket status
        boolean wsConnected = plugin.getWebSocketServer() != null
            && plugin.getWebSocketServer().getConnectionCount() > 0;
        int wsPort = plugin.getConfig().getInt("websocket.port", 8765);
        int connCount = plugin.getWebSocketServer() != null
            ? plugin.getWebSocketServer().getConnectionCount() : 0;

        inventory.setItem(SLOT_WEBSOCKET_STATUS, new ItemBuilder(wsConnected ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK)
            .name("&dWebSocket Server")
            .lore(
                "&7Real-time communication",
                "",
                "&7Status: " + (wsConnected ? "&aConnected" : "&eListening"),
                "&7Port: &f" + wsPort,
                "&7Clients: &f" + connCount,
                "",
                "&7Audio data is received",
                "&7from Python processor"
            )
            .build());

        // Back button
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());

        // Reload config
        inventory.setItem(SLOT_RELOAD, new ItemBuilder(Material.REPEATER)
            .name("&eReload Config")
            .lore(
                "&7Reload configuration from file",
                "",
                "&cNote: Some changes require",
                "&ca server restart",
                "",
                "&eClick to reload"
            )
            .build());

        // Save config
        inventory.setItem(SLOT_SAVE, new ItemBuilder(Material.WRITABLE_BOOK)
            .name("&aSave Config")
            .lore(
                "&7Save current settings to file",
                "",
                "&eClick to save"
            )
            .build());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            // Max entities
            case SLOT_MAX_ENTITIES -> {
                int current = plugin.getConfig().getInt("performance.max_entities_per_zone", 100);
                int step = click.isShiftClick() ? 50 : 10;
                int newValue;
                if (click.isRightClick()) {
                    newValue = Math.max(10, current - step);
                } else {
                    newValue = Math.min(500, current + step);
                }
                plugin.getConfig().set("performance.max_entities_per_zone", newValue);
                playSound(player, Sound.UI_BUTTON_CLICK);
                menuManager.refreshMenu(player);
            }

            // Update interval
            case SLOT_UPDATE_INTERVAL -> {
                int current = plugin.getConfig().getInt("performance.min_update_interval", 1);
                int newValue;
                if (click.isRightClick()) {
                    newValue = Math.max(1, current - 1);
                } else {
                    newValue = Math.min(20, current + 1);
                }
                plugin.getConfig().set("performance.min_update_interval", newValue);
                playSound(player, Sound.UI_BUTTON_CLICK);
                menuManager.refreshMenu(player);
            }

            // Batch size
            case SLOT_BATCH_SIZE -> {
                int current = plugin.getConfig().getInt("performance.batch_size", 50);
                int step = click.isShiftClick() ? 25 : 10;
                int newValue;
                if (click.isRightClick()) {
                    newValue = Math.max(10, current - step);
                } else {
                    newValue = Math.min(200, current + step);
                }
                plugin.getConfig().set("performance.batch_size", newValue);
                playSound(player, Sound.UI_BUTTON_CLICK);
                menuManager.refreshMenu(player);
            }

            // View culling toggle
            case SLOT_VIEW_CULLING -> {
                boolean current = plugin.getConfig().getBoolean("performance.view_distance_culling", true);
                plugin.getConfig().set("performance.view_distance_culling", !current);
                playToggleSound(player, !current);
                menuManager.refreshMenu(player);
            }

            // Cull distance
            case SLOT_CULL_DISTANCE -> {
                int current = plugin.getConfig().getInt("performance.cull_distance_chunks", 8);
                int newValue;
                if (click.isRightClick()) {
                    newValue = Math.max(2, current - 1);
                } else {
                    newValue = Math.min(16, current + 1);
                }
                plugin.getConfig().set("performance.cull_distance_chunks", newValue);
                playSound(player, Sound.UI_BUTTON_CLICK);
                menuManager.refreshMenu(player);
            }

            // Default material
            case SLOT_DEFAULT_MATERIAL -> {
                String current = plugin.getConfig().getString("defaults.material", "GLOWSTONE");
                String[] materials = {"GLOWSTONE", "SEA_LANTERN", "SHROOMLIGHT", "DIAMOND_BLOCK",
                    "EMERALD_BLOCK", "GOLD_BLOCK", "REDSTONE_BLOCK", "LAPIS_BLOCK"};

                int currentIndex = 0;
                for (int i = 0; i < materials.length; i++) {
                    if (materials[i].equals(current)) {
                        currentIndex = i;
                        break;
                    }
                }

                int newIndex;
                if (click.isRightClick()) {
                    newIndex = (currentIndex - 1 + materials.length) % materials.length;
                } else {
                    newIndex = (currentIndex + 1) % materials.length;
                }

                plugin.getConfig().set("defaults.material", materials[newIndex]);
                playSound(player, Sound.UI_BUTTON_CLICK);
                menuManager.refreshMenu(player);
            }

            // Back
            case SLOT_BACK -> menuManager.openMenu(player, new MainMenu(plugin, menuManager));

            // Reload
            case SLOT_RELOAD -> {
                plugin.reloadConfig();
                playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
                player.sendMessage(ChatColor.GREEN + "Configuration reloaded!");
                player.sendMessage(ChatColor.YELLOW + "Note: Some settings require a server restart.");
                menuManager.refreshMenu(player);
            }

            // Save
            case SLOT_SAVE -> {
                plugin.saveConfig();
                playSound(player, Sound.ENTITY_VILLAGER_YES);
                player.sendMessage(ChatColor.GREEN + "Configuration saved!");
            }
        }
    }

    private void playSound(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, 0.5f, 1f);
    }

    private void playToggleSound(Player player, boolean enabled) {
        if (enabled) {
            player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 1.2f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 0.8f);
        }
    }
}
