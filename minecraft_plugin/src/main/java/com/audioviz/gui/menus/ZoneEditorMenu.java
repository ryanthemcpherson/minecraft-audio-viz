package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Menu for editing a single visualization zone.
 * Allows adjusting size, rotation, and managing the entity pool.
 */
public class ZoneEditorMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;
    private final VisualizationZone zone;

    // Slot positions
    private static final int SLOT_INFO = 4;
    private static final int SLOT_SIZE_X_DOWN = 10;
    private static final int SLOT_SIZE_X = 11;
    private static final int SLOT_SIZE_X_UP = 12;
    private static final int SLOT_SIZE_Y_DOWN = 13;
    private static final int SLOT_SIZE_Y = 14;
    private static final int SLOT_SIZE_Y_UP = 15;
    private static final int SLOT_SIZE_Z_DOWN = 16;
    private static final int SLOT_SIZE_Z = 17;
    private static final int SLOT_SIZE_Z_UP = 18;
    private static final int SLOT_ROTATION_DOWN = 19;
    private static final int SLOT_ROTATION = 20;
    private static final int SLOT_ROTATION_UP = 21;
    private static final int SLOT_TELEPORT = 23;
    private static final int SLOT_INIT_POOL = 24;
    private static final int SLOT_CLEANUP = 25;
    private static final int SLOT_BACK = 27;
    private static final int SLOT_BOUNDARIES = 29;
    private static final int SLOT_VISUAL_EDIT = 31;
    private static final int SLOT_DELETE = 35;

    public ZoneEditorMenu(AudioVizPlugin plugin, MenuManager menuManager, VisualizationZone zone) {
        this.plugin = plugin;
        this.menuManager = menuManager;
        this.zone = zone;
    }

    @Override
    public String getTitle() {
        return "\u00A7eEditing: " + zone.getName();
    }

    @Override
    public int getSize() {
        return 36; // 4 rows
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();

        // Fill with glass panes
        ItemStack filler = ItemBuilder.glassPane(DyeColor.GRAY);
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, filler);
        }

        Vector size = zone.getSize();
        int entityCount = plugin.getEntityPoolManager().getEntityCount(zone.getName());

        // Zone info header
        inventory.setItem(SLOT_INFO, new ItemBuilder(Material.BEACON)
            .name("&e" + zone.getName())
            .lore(
                "&7Zone ID: &f" + zone.getId(),
                "",
                "&7Location: &f" + formatLocation(zone.getOrigin()),
                "&7Size: &f" + (int) size.getX() + " x " + (int) size.getY() + " x " + (int) size.getZ(),
                "&7Rotation: &f" + (int) zone.getRotation() + "\u00B0",
                "&7Entities: &f" + entityCount
            )
            .glow()
            .build());

        // Size X controls
        inventory.setItem(SLOT_SIZE_X_DOWN, createDecrementButton("X Size", (int) size.getX()));
        inventory.setItem(SLOT_SIZE_X, new ItemBuilder(Material.RED_CONCRETE)
            .name("&cX Size: &f" + (int) size.getX())
            .lore("&7Width of the zone")
            .amount(Math.max(1, Math.min(64, (int) size.getX())))
            .build());
        inventory.setItem(SLOT_SIZE_X_UP, createIncrementButton("X Size", (int) size.getX()));

        // Size Y controls
        inventory.setItem(SLOT_SIZE_Y_DOWN, createDecrementButton("Y Size", (int) size.getY()));
        inventory.setItem(SLOT_SIZE_Y, new ItemBuilder(Material.GREEN_CONCRETE)
            .name("&aY Size: &f" + (int) size.getY())
            .lore("&7Height of the zone")
            .amount(Math.max(1, Math.min(64, (int) size.getY())))
            .build());
        inventory.setItem(SLOT_SIZE_Y_UP, createIncrementButton("Y Size", (int) size.getY()));

        // Size Z controls
        inventory.setItem(SLOT_SIZE_Z_DOWN, createDecrementButton("Z Size", (int) size.getZ()));
        inventory.setItem(SLOT_SIZE_Z, new ItemBuilder(Material.BLUE_CONCRETE)
            .name("&9Z Size: &f" + (int) size.getZ())
            .lore("&7Depth of the zone")
            .amount(Math.max(1, Math.min(64, (int) size.getZ())))
            .build());
        inventory.setItem(SLOT_SIZE_Z_UP, createIncrementButton("Z Size", (int) size.getZ()));

        // Rotation controls
        inventory.setItem(SLOT_ROTATION_DOWN, new ItemBuilder(Material.ARROW)
            .name("&e- 15\u00B0")
            .lore("&7Rotate counter-clockwise")
            .build());
        inventory.setItem(SLOT_ROTATION, new ItemBuilder(Material.COMPASS)
            .name("&6Rotation: &f" + (int) zone.getRotation() + "\u00B0")
            .lore("&7Y-axis rotation")
            .build());
        inventory.setItem(SLOT_ROTATION_UP, new ItemBuilder(Material.ARROW)
            .name("&e+ 15\u00B0")
            .lore("&7Rotate clockwise")
            .build());

        // Teleport to zone
        inventory.setItem(SLOT_TELEPORT, new ItemBuilder(Material.ENDER_PEARL)
            .name("&5Teleport to Zone")
            .lore(
                "&7Teleport to the zone center",
                "",
                "&eClick to teleport"
            )
            .build());

        // Initialize entity pool
        inventory.setItem(SLOT_INIT_POOL, new ItemBuilder(Material.SPAWNER)
            .name("&bInitialize Entity Pool")
            .lore(
                "&7Spawn display entities",
                "",
                "&7Current: &f" + entityCount + " entities",
                "",
                "&eLeft-click: 16 entities",
                "&eRight-click: 64 entities",
                "&eShift-click: 100 entities"
            )
            .build());

        // Cleanup entities
        inventory.setItem(SLOT_CLEANUP, new ItemBuilder(Material.TNT)
            .name("&cCleanup Entities")
            .lore(
                "&7Remove all entities",
                "&7from this zone",
                "",
                "&7Current: &f" + entityCount + " entities",
                "",
                "&cClick to cleanup"
            )
            .build());

        // Back button
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());

        // Show boundaries toggle
        boolean boundariesActive = plugin.getZoneBoundaryRenderer().isShowing(zone.getName());
        inventory.setItem(SLOT_BOUNDARIES, ItemBuilder.toggle(
            "&bShow Boundaries",
            boundariesActive,
            "&7Render particle outlines",
            "&7along zone edges so you",
            "&7can see placement in-world.",
            "&7Auto-hides after 30 seconds."
        ));

        // Visual editor (placeholder for Phase 3)
        inventory.setItem(SLOT_VISUAL_EDIT, new ItemBuilder(Material.SPYGLASS)
            .name("&dVisual Zone Editor")
            .lore(
                "&7Edit zone with particles",
                "&7and in-game controls",
                "",
                "&eClick to start editing"
            )
            .glow()
            .build());

        // Delete zone
        inventory.setItem(SLOT_DELETE, new ItemBuilder(Material.BARRIER)
            .name("&4Delete Zone")
            .lore(
                "&cPermanently delete this zone",
                "&cand all its entities",
                "",
                "&cShift-click to delete"
            )
            .build());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Vector size = zone.getSize();
        int step = click.isShiftClick() ? 5 : 1;

        switch (slot) {
            // Size X controls
            case SLOT_SIZE_X_DOWN -> {
                int newSize = Math.max(1, (int) size.getX() - step);
                zone.setSize(new Vector(newSize, size.getY(), size.getZ()));
                plugin.getZoneManager().saveZones();
                menuManager.refreshMenu(player);
            }
            case SLOT_SIZE_X_UP -> {
                int newSize = Math.min(100, (int) size.getX() + step);
                zone.setSize(new Vector(newSize, size.getY(), size.getZ()));
                plugin.getZoneManager().saveZones();
                menuManager.refreshMenu(player);
            }

            // Size Y controls
            case SLOT_SIZE_Y_DOWN -> {
                int newSize = Math.max(1, (int) size.getY() - step);
                zone.setSize(new Vector(size.getX(), newSize, size.getZ()));
                plugin.getZoneManager().saveZones();
                menuManager.refreshMenu(player);
            }
            case SLOT_SIZE_Y_UP -> {
                int newSize = Math.min(100, (int) size.getY() + step);
                zone.setSize(new Vector(size.getX(), newSize, size.getZ()));
                plugin.getZoneManager().saveZones();
                menuManager.refreshMenu(player);
            }

            // Size Z controls
            case SLOT_SIZE_Z_DOWN -> {
                int newSize = Math.max(1, (int) size.getZ() - step);
                zone.setSize(new Vector(size.getX(), size.getY(), newSize));
                plugin.getZoneManager().saveZones();
                menuManager.refreshMenu(player);
            }
            case SLOT_SIZE_Z_UP -> {
                int newSize = Math.min(100, (int) size.getZ() + step);
                zone.setSize(new Vector(size.getX(), size.getY(), newSize));
                plugin.getZoneManager().saveZones();
                menuManager.refreshMenu(player);
            }

            // Rotation controls
            case SLOT_ROTATION_DOWN -> {
                float newRotation = (zone.getRotation() - 15 + 360) % 360;
                zone.setRotation(newRotation);
                plugin.getZoneManager().saveZones();
                menuManager.refreshMenu(player);
            }
            case SLOT_ROTATION_UP -> {
                float newRotation = (zone.getRotation() + 15) % 360;
                zone.setRotation(newRotation);
                plugin.getZoneManager().saveZones();
                menuManager.refreshMenu(player);
            }

            // Teleport
            case SLOT_TELEPORT -> {
                Location center = zone.getCenter();
                player.teleport(center);
                player.sendMessage(ChatColor.GREEN + "Teleported to zone: " + zone.getName());
            }

            // Initialize pool
            case SLOT_INIT_POOL -> {
                int count;
                if (click.isShiftClick()) {
                    count = 100;
                } else if (click.isRightClick()) {
                    count = 64;
                } else {
                    count = 16;
                }

                Material material = Material.valueOf(
                    plugin.getConfig().getString("defaults.material", "GLOWSTONE")
                );
                plugin.getEntityPoolManager().initializeBlockPool(zone.getName(), count, material);
                player.sendMessage(ChatColor.GREEN + "Initialized " + count + " entities for zone: " + zone.getName());

                // Refresh after a tick to show new count
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (menuManager.hasMenuOpen(player)) {
                        menuManager.refreshMenu(player);
                    }
                }, 2L);
            }

            // Cleanup
            case SLOT_CLEANUP -> {
                plugin.getEntityPoolManager().cleanupZone(zone.getName());
                player.sendMessage(ChatColor.YELLOW + "Cleaned up entities for zone: " + zone.getName());
                menuManager.refreshMenu(player);
            }

            // Show boundaries toggle
            case SLOT_BOUNDARIES -> {
                boolean showing = plugin.getZoneBoundaryRenderer().toggle(zone.getName());
                player.sendMessage(showing
                    ? ChatColor.GREEN + "Boundaries shown for zone: " + zone.getName()
                    : ChatColor.YELLOW + "Boundaries hidden for zone: " + zone.getName());
                menuManager.refreshMenu(player);
            }

            // Back
            case SLOT_BACK -> menuManager.openMenu(player, new ZoneManagementMenu(plugin, menuManager));

            // Visual editor
            case SLOT_VISUAL_EDIT -> {
                player.closeInventory();
                plugin.getZoneEditor().startEditing(player, zone);
            }

            // Delete
            case SLOT_DELETE -> {
                if (click.isShiftClick()) {
                    String zoneName = zone.getName();
                    plugin.getEntityPoolManager().cleanupZone(zoneName);
                    plugin.getZoneManager().deleteZone(zoneName);
                    player.sendMessage(ChatColor.RED + "Deleted zone: " + zoneName);
                    menuManager.openMenu(player, new ZoneManagementMenu(plugin, menuManager));
                } else {
                    player.sendMessage(ChatColor.RED + "Shift-click to confirm deletion!");
                }
            }
        }
    }

    private ItemStack createIncrementButton(String label, int currentValue) {
        return new ItemBuilder(Material.LIME_DYE)
            .name("&a+ Increase " + label)
            .lore(
                "&7Current: &f" + currentValue,
                "",
                "&eClick: +1",
                "&eShift-click: +5"
            )
            .build();
    }

    private ItemStack createDecrementButton(String label, int currentValue) {
        return new ItemBuilder(Material.RED_DYE)
            .name("&c- Decrease " + label)
            .lore(
                "&7Current: &f" + currentValue,
                "",
                "&eClick: -1",
                "&eShift-click: -5"
            )
            .build();
    }

    private String formatLocation(Location loc) {
        return String.format("%s (%.0f, %.0f, %.0f)",
            loc.getWorld().getName(),
            loc.getX(), loc.getY(), loc.getZ());
    }
}
