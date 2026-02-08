package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.ChatInputManager;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageTemplate;
import com.audioviz.stages.StageZoneRole;
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
 * Template picker menu for creating a new stage.
 * Shows available templates with descriptions and zone counts.
 */
public class StageTemplateMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;

    // Template display slots (row 1, spaced out)
    private static final int[] TEMPLATE_SLOTS = {10, 12, 14, 16};
    private static final int SLOT_BACK = 36;

    public StageTemplateMenu(AudioVizPlugin plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    @Override
    public String getTitle() {
        return "\u00A75Choose a Stage Template";
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
        ItemStack purpleFiller = ItemBuilder.glassPane(DyeColor.PURPLE);
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, purpleFiller);
        }
        inventory.setItem(4, new ItemBuilder(org.bukkit.Material.BEACON)
            .name("&5&lChoose a Template")
            .lore("&7Select a stage layout template")
            .build());

        // Display templates
        Map<String, StageTemplate> templates = plugin.getStageManager().getAllTemplates();
        List<Map.Entry<String, StageTemplate>> templateList = new ArrayList<>(templates.entrySet());

        for (int i = 0; i < Math.min(templateList.size(), TEMPLATE_SLOTS.length); i++) {
            Map.Entry<String, StageTemplate> entry = templateList.get(i);
            StageTemplate template = entry.getValue();

            // Build role list for lore
            List<String> lore = new ArrayList<>();
            lore.add("&7" + template.getDescription());
            lore.add("");
            lore.add("&fZones:");
            for (StageZoneRole role : template.getRoles()) {
                lore.add("&7  - &f" + role.getDisplayName());
            }
            lore.add("");
            lore.add("&7Total zones: &f" + template.getRoleCount());
            lore.add("&7Est. entities: &f~" + template.getEstimatedEntityCount());
            lore.add("");
            lore.add("&eClick to select");

            inventory.setItem(TEMPLATE_SLOTS[i], new ItemBuilder(template.getIcon())
                .name("&e&l" + capitalize(template.getName()))
                .lore(lore)
                .glow()
                .build());
        }

        // Back button
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == SLOT_BACK) {
            playClickSound(player);
            menuManager.openMenu(player, new StageListMenu(plugin, menuManager));
            return;
        }

        // Check if clicked a template slot
        Map<String, StageTemplate> templates = plugin.getStageManager().getAllTemplates();
        List<Map.Entry<String, StageTemplate>> templateList = new ArrayList<>(templates.entrySet());

        for (int i = 0; i < Math.min(templateList.size(), TEMPLATE_SLOTS.length); i++) {
            if (TEMPLATE_SLOTS[i] == slot) {
                String templateName = templateList.get(i).getKey();
                playClickSound(player);
                player.closeInventory();
                requestStageName(player, templateName);
                return;
            }
        }
    }

    private void requestStageName(Player player, String templateName) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== Create New Stage ===");
        player.sendMessage(ChatColor.YELLOW + "Template: " + ChatColor.WHITE + capitalize(templateName));
        player.sendMessage(ChatColor.YELLOW + "Enter a name for your stage in chat.");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to cancel.");
        player.sendMessage("");

        plugin.getChatInputManager().requestInput(player, ChatInputManager.InputType.STAGE_NAME, input -> {
            if (input == null) {
                player.sendMessage(ChatColor.YELLOW + "Stage creation cancelled.");
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    menuManager.openMenu(player, new StageListMenu(plugin, menuManager));
                });
                return;
            }

            String stageName = input.trim();

            // Validate
            if (stageName.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Stage name cannot be empty!");
                requestStageName(player, templateName);
                return;
            }
            if (stageName.length() > 32) {
                player.sendMessage(ChatColor.RED + "Stage name too long (max 32 characters)!");
                requestStageName(player, templateName);
                return;
            }
            if (!stageName.matches("^[a-zA-Z0-9_-]+$")) {
                player.sendMessage(ChatColor.RED + "Stage name can only contain letters, numbers, underscores, and hyphens!");
                requestStageName(player, templateName);
                return;
            }
            if (plugin.getStageManager().stageExists(stageName)) {
                player.sendMessage(ChatColor.RED + "A stage with that name already exists!");
                requestStageName(player, templateName);
                return;
            }

            // Create stage on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Stage stage = plugin.getStageManager().createStage(stageName, player.getLocation(), templateName);
                if (stage != null) {
                    player.sendMessage(ChatColor.GREEN + "Stage '" + stageName + "' created with " +
                        stage.getRoleToZone().size() + " zones!");
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);
                    menuManager.openMenu(player, new StageEditorMenu(plugin, menuManager, stage));
                } else {
                    player.sendMessage(ChatColor.RED + "Failed to create stage.");
                    menuManager.openMenu(player, new StageListMenu(plugin, menuManager));
                }
            });
        });
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }
}
