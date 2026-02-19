package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu for managing visualization zones.
 * Supports pagination and zone selection for editing.
 */
public class ZoneManagementMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;

    // Pagination
    private int currentPage = 0;
    private static final int ZONES_PER_PAGE = 28; // 4 rows of 7 slots
    private static final int[] ZONE_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    // Control slots
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREV_PAGE = 48;
    private static final int SLOT_CREATE = 49;
    private static final int SLOT_NEXT_PAGE = 50;
    private static final int SLOT_REFRESH = 53;

    public ZoneManagementMenu(AudioVizPlugin plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    public ZoneManagementMenu(AudioVizPlugin plugin, MenuManager menuManager, int page) {
        this(plugin, menuManager);
        this.currentPage = page;
    }

    @Override
    public String getTitle() {
        return "\u00A76Zone Management";
    }

    @Override
    public int getSize() {
        return 54; // 6 rows
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();

        // Fill borders
        ItemStack filler = ItemBuilder.glassPane(DyeColor.GRAY);
        for (int i = 0; i < 9; i++) inventory.setItem(i, filler);
        for (int i = 45; i < 54; i++) inventory.setItem(i, filler);
        inventory.setItem(9, filler);
        inventory.setItem(17, filler);
        inventory.setItem(18, filler);
        inventory.setItem(26, filler);
        inventory.setItem(27, filler);
        inventory.setItem(35, filler);
        inventory.setItem(36, filler);
        inventory.setItem(44, filler);

        // Get zones
        List<String> zoneNames = new ArrayList<>(plugin.getZoneManager().getZoneNames());
        int totalPages = Math.max(1, (int) Math.ceil(zoneNames.size() / (double) ZONES_PER_PAGE));

        // Clamp current page
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));

        // Display zones for current page
        int startIndex = currentPage * ZONES_PER_PAGE;
        int endIndex = Math.min(startIndex + ZONES_PER_PAGE, zoneNames.size());

        for (int i = 0; i < ZONE_SLOTS.length; i++) {
            int zoneIndex = startIndex + i;
            if (zoneIndex < endIndex) {
                String zoneName = zoneNames.get(zoneIndex);
                VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
                inventory.setItem(ZONE_SLOTS[i], createZoneItem(zone));
            }
        }

        // Back button
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());

        // Previous page
        if (currentPage > 0) {
            inventory.setItem(SLOT_PREV_PAGE, ItemBuilder.previousPage(currentPage + 1));
        }

        // Create new zone
        inventory.setItem(SLOT_CREATE, new ItemBuilder(Material.EMERALD)
            .name("&aCreate New Zone")
            .lore(
                "&7Create a new visualization",
                "&7zone at your location.",
                "",
                "&eClick to create"
            )
            .glow()
            .build());

        // Next page
        if (currentPage < totalPages - 1) {
            inventory.setItem(SLOT_NEXT_PAGE, ItemBuilder.nextPage(currentPage + 1, totalPages));
        }

        // Refresh
        inventory.setItem(SLOT_REFRESH, new ItemBuilder(Material.SUNFLOWER)
            .name("&eRefresh")
            .lore("&7Reload zone list")
            .build());

        // Page indicator in title area
        inventory.setItem(4, new ItemBuilder(Material.PAPER)
            .name("&fPage " + (currentPage + 1) + "/" + totalPages)
            .lore("&7" + zoneNames.size() + " total zones")
            .build());
    }

    private ItemStack createZoneItem(VisualizationZone zone) {
        if (zone == null) return null;

        int entityCount = plugin.getEntityPoolManager().getEntityCount(zone.getName());
        boolean hasEntities = entityCount > 0;

        Material material = hasEntities ? Material.BEACON : Material.GLASS;

        return new ItemBuilder(material)
            .name("&e" + zone.getName())
            .lore(
                "&7ID: &f" + zone.getId(),
                "",
                "&7Location:",
                "&f  World: &7" + zone.getOrigin().getWorld().getName(),
                "&f  X: &7" + String.format("%.1f", zone.getOrigin().getX()),
                "&f  Y: &7" + String.format("%.1f", zone.getOrigin().getY()),
                "&f  Z: &7" + String.format("%.1f", zone.getOrigin().getZ()),
                "",
                "&7Size: &f" + (int) zone.getSize().getX() + " x " +
                    (int) zone.getSize().getY() + " x " +
                    (int) zone.getSize().getZ(),
                "&7Rotation: &f" + (int) zone.getRotation() + "\u00B0",
                "&7Entities: &f" + entityCount,
                "",
                "&eLeft-click to edit",
                "&cShift-click to delete"
            )
            .build();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case SLOT_BACK -> {
                playClickSound(player);
                menuManager.openMenu(player, new MainMenu(plugin, menuManager));
            }

            case SLOT_PREV_PAGE -> {
                if (currentPage > 0) {
                    playClickSound(player);
                    currentPage--;
                    menuManager.refreshMenu(player);
                }
            }

            case SLOT_CREATE -> {
                playClickSound(player);
                player.closeInventory();
                plugin.getChatInputManager().requestZoneName(player);
            }

            case SLOT_NEXT_PAGE -> {
                playClickSound(player);
                currentPage++;
                menuManager.refreshMenu(player);
            }

            case SLOT_REFRESH -> {
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
                menuManager.refreshMenu(player);
            }

            default -> {
                // Check if it's a zone slot
                String zoneName = getZoneAtSlot(slot);
                if (zoneName != null) {
                    VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
                    if (zone != null) {
                        if (click.isShiftClick()) {
                            // Delete zone
                            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 1f);
                            player.closeInventory();
                            player.sendMessage(ChatColor.RED + "Deleting zone: " + zoneName);
                            plugin.getEntityPoolManager().cleanupZone(zoneName);
                            plugin.getZoneManager().deleteZone(zoneName);
                            player.sendMessage(ChatColor.GREEN + "Zone deleted.");
                        } else {
                            // Edit zone
                            playClickSound(player);
                            menuManager.openMenu(player, new ZoneEditorMenu(plugin, menuManager, zone));
                        }
                    }
                }
            }
        }
    }

    private void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }

    private String getZoneAtSlot(int slot) {
        // Find index in ZONE_SLOTS array
        int slotIndex = -1;
        for (int i = 0; i < ZONE_SLOTS.length; i++) {
            if (ZONE_SLOTS[i] == slot) {
                slotIndex = i;
                break;
            }
        }

        if (slotIndex == -1) return null;

        List<String> zoneNames = new ArrayList<>(plugin.getZoneManager().getZoneNames());
        int zoneIndex = currentPage * ZONES_PER_PAGE + slotIndex;

        if (zoneIndex >= 0 && zoneIndex < zoneNames.size()) {
            return zoneNames.get(zoneIndex);
        }

        return null;
    }
}
