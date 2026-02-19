package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.ChatInputManager;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import com.audioviz.zones.VisualizationZone;
import com.audioviz.zones.ZoneTemplate;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Template picker menu for creating a new zone with predefined settings.
 * Shows available zone templates with descriptions, sizes, and entity counts.
 */
public class ZoneTemplateMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;

    // Template display slots (row 1-2, centered)
    private static final int[] TEMPLATE_SLOTS = {11, 13, 15, 20, 22, 24};
    private static final int SLOT_BACK = 36;
    private static final int SLOT_CUSTOM = 31; // Custom zone option

    public ZoneTemplateMenu(AudioVizPlugin plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    @Override
    public String getTitle() {
        return "\u00A7bChoose a Zone Template";
    }

    @Override
    public int getSize() {
        return 45; // 5 rows
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        // Fill with glass panes
        ItemStack filler = ItemBuilder.glassPane(DyeColor.GRAY);
        for (int i = 0; i < 45; i++) {
            inventory.setItem(i, filler);
        }

        // Header
        ItemStack cyanFiller = ItemBuilder.glassPane(DyeColor.CYAN);
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, cyanFiller);
        }
        inventory.setItem(4, new ItemBuilder(org.bukkit.Material.BEACON)
            .name("&b&lChoose a Template")
            .lore("&7Select a zone size template", "&7or create a custom zone")
            .build());

        // Display templates
        Map<String, ZoneTemplate> templates = ZoneTemplate.getBuiltinTemplates();
        List<Map.Entry<String, ZoneTemplate>> templateList = new ArrayList<>(templates.entrySet());

        for (int i = 0; i < Math.min(templateList.size(), TEMPLATE_SLOTS.length); i++) {
            Map.Entry<String, ZoneTemplate> entry = templateList.get(i);
            ZoneTemplate template = entry.getValue();

            // Build lore
            List<String> lore = new ArrayList<>();
            lore.add("&7" + template.description());
            lore.add("");
            lore.add("&fSize: &7" + formatVector(template.size()));
            lore.add("&fEntities: &7" + template.entityCount());
            lore.add("&fMaterial: &7" + formatMaterial(template.material()));
            lore.add("");
            lore.add("&eClick to select");

            inventory.setItem(TEMPLATE_SLOTS[i], new ItemBuilder(template.material())
                .name("&e&l" + template.getDisplayName())
                .lore(lore)
                .glow()
                .build());
        }

        // Custom zone option
        inventory.setItem(SLOT_CUSTOM, new ItemBuilder(org.bukkit.Material.WRITABLE_BOOK)
            .name("&d&lCustom Zone")
            .lore(
                "&7Create a zone with default settings",
                "&7and customize it later.",
                "",
                "&7Size: &f10 x 10 x 10",
                "&7Entities: &f16",
                "&7Material: &fGlowstone",
                "",
                "&eClick to create custom"
            )
            .build());

        // Back button
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == SLOT_BACK) {
            playClickSound(player);
            menuManager.openMenu(player, new ZoneManagementMenu(plugin, menuManager));
            return;
        }

        if (slot == SLOT_CUSTOM) {
            playClickSound(player);
            player.closeInventory();
            requestZoneName(player, null);
            return;
        }

        // Check if clicked a template slot
        Map<String, ZoneTemplate> templates = ZoneTemplate.getBuiltinTemplates();
        List<Map.Entry<String, ZoneTemplate>> templateList = new ArrayList<>(templates.entrySet());

        for (int i = 0; i < Math.min(templateList.size(), TEMPLATE_SLOTS.length); i++) {
            if (TEMPLATE_SLOTS[i] == slot) {
                String templateName = templateList.get(i).getKey();
                ZoneTemplate template = templateList.get(i).getValue();
                playClickSound(player);
                player.closeInventory();
                requestZoneName(player, template);
                return;
            }
        }
    }

    private void requestZoneName(Player player, ZoneTemplate template) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== Create New Zone ===");
        if (template != null) {
            player.sendMessage(ChatColor.YELLOW + "Template: " + ChatColor.WHITE + template.getDisplayName());
            player.sendMessage(ChatColor.GRAY + "Size: " + formatVector(template.size()) +
                " | Entities: " + template.entityCount());
        } else {
            player.sendMessage(ChatColor.YELLOW + "Template: " + ChatColor.WHITE + "Custom");
            player.sendMessage(ChatColor.GRAY + "Default size: 10x10x10 | 16 entities");
        }
        player.sendMessage(ChatColor.YELLOW + "Enter a name for your zone in chat.");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to cancel.");
        player.sendMessage("");

        plugin.getChatInputManager().requestInput(player, ChatInputManager.InputType.ZONE_NAME, input -> {
            if (input == null) {
                player.sendMessage(ChatColor.YELLOW + "Zone creation cancelled.");
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    menuManager.openMenu(player, new ZoneManagementMenu(plugin, menuManager));
                });
                return;
            }

            String zoneName = input.trim();

            // Validate
            if (zoneName.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Zone name cannot be empty!");
                requestZoneName(player, template);
                return;
            }
            if (zoneName.length() > 32) {
                player.sendMessage(ChatColor.RED + "Zone name too long (max 32 characters)!");
                requestZoneName(player, template);
                return;
            }
            if (!zoneName.matches("^[a-zA-Z0-9_-]+$")) {
                player.sendMessage(ChatColor.RED + "Zone name can only contain letters, numbers, underscores, and hyphens!");
                requestZoneName(player, template);
                return;
            }
            if (plugin.getZoneManager().zoneExists(zoneName)) {
                player.sendMessage(ChatColor.RED + "A zone with that name already exists!");
                requestZoneName(player, template);
                return;
            }

            // Create zone on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                VisualizationZone zone = plugin.getZoneManager().createZone(zoneName, player.getLocation());
                if (zone != null) {
                    // Apply template settings if provided
                    if (template != null) {
                        zone.setSize(template.size());
                        plugin.getZoneManager().saveZones();

                        // Initialize entity pool with template settings
                        plugin.getEntityPoolManager().initializeBlockPool(
                            zoneName,
                            template.entityCount(),
                            template.material()
                        );

                        player.sendMessage(ChatColor.GREEN + "Zone '" + zoneName + "' created with " +
                            template.getDisplayName() + " template!");
                        player.sendMessage(ChatColor.GRAY + "Size: " + formatVector(template.size()) +
                            " | " + template.entityCount() + " entities spawned");
                    } else {
                        player.sendMessage(ChatColor.GREEN + "Zone '" + zoneName + "' created!");
                        player.sendMessage(ChatColor.GRAY + "Use the zone editor to customize size and entities.");
                    }

                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);
                    menuManager.openMenu(player, new ZoneEditorMenu(plugin, menuManager, zone));
                } else {
                    player.sendMessage(ChatColor.RED + "Failed to create zone.");
                    menuManager.openMenu(player, new ZoneManagementMenu(plugin, menuManager));
                }
            });
        });
    }

    private String formatVector(org.bukkit.util.Vector vec) {
        return String.format("%.0fx%.0fx%.0f", vec.getX(), vec.getY(), vec.getZ());
    }

    private String formatMaterial(org.bukkit.Material material) {
        String name = material.name().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }

    private void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }
}
