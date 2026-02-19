package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.decorators.DecoratorType;
import com.audioviz.gui.ChatInputManager;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import com.audioviz.stages.Stage;
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
import java.util.Map;

/**
 * GUI menu for configuring stage decorators.
 * Shows all 6 decorator types with enable/disable toggles and config editing.
 */
public class StageDecoratorMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;
    private final Stage stage;

    // Decorator slots (row 1-2, centered)
    private static final int[] DECO_SLOTS = {10, 12, 14, 16, 19, 22};

    // Control slots (row 5)
    private static final int SLOT_BACK = 45;
    private static final int SLOT_ENABLE_ALL = 48;
    private static final int SLOT_DISABLE_ALL = 50;

    public StageDecoratorMenu(AudioVizPlugin plugin, MenuManager menuManager, Stage stage) {
        this.plugin = plugin;
        this.menuManager = menuManager;
        this.stage = stage;
    }

    @Override
    public String getTitle() {
        return "\u00A7d\u26A1 Decorators: " + stage.getName();
    }

    @Override
    public int getSize() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();

        // Header (row 0)
        ItemStack purpleFiller = ItemBuilder.glassPane(DyeColor.PURPLE);
        for (int i = 0; i < 9; i++) inventory.setItem(i, purpleFiller);

        inventory.setItem(4, new ItemBuilder(Material.PAINTING)
            .name("\u00A7d\u00A7l\u26A1 Stage Decorators")
            .lore(
                "\u00A77Configure visual effects",
                "\u00A77for stage: \u00A7f" + stage.getName(),
                "",
                "\u00A77Active: \u00A7f" + countEnabled() + "/" + DecoratorType.values().length
            )
            .glow()
            .build());

        // Filler for rows 1-2
        ItemStack darkFiller = ItemBuilder.glassPane(DyeColor.BLACK);
        for (int row = 1; row <= 2; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = row * 9 + col;
                inventory.setItem(slot, darkFiller);
            }
        }

        // Place decorator items
        DecoratorType[] types = DecoratorType.values();
        for (int i = 0; i < types.length && i < DECO_SLOTS.length; i++) {
            inventory.setItem(DECO_SLOTS[i], createDecoratorItem(types[i]));
        }

        // Separator (row 3)
        ItemStack grayFiller = ItemBuilder.glassPane(DyeColor.GRAY);
        for (int i = 27; i < 36; i++) inventory.setItem(i, grayFiller);

        // Info section (row 3-4)
        for (int i = 36; i < 45; i++) inventory.setItem(i, darkFiller);

        inventory.setItem(40, new ItemBuilder(Material.BOOK)
            .name("\u00A7eHow Decorators Work")
            .lore(
                "\u00A77Decorators add visual effects",
                "\u00A77on top of your stage patterns.",
                "",
                "\u00A77They react to audio in real-time:",
                "\u00A77beat pulses, bass ripples,",
                "\u00A77sweeping spotlights, and more.",
                "",
                "\u00A7eLeft-click \u00A77to toggle on/off",
                "\u00A7eRight-click \u00A77to edit settings"
            )
            .build());

        // Control buttons (row 5)
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());

        inventory.setItem(SLOT_ENABLE_ALL, new ItemBuilder(Material.LIME_DYE)
            .name("\u00A7aEnable All")
            .lore("\u00A77Enable all decorators")
            .build());

        inventory.setItem(SLOT_DISABLE_ALL, new ItemBuilder(Material.GRAY_DYE)
            .name("\u00A77Disable All")
            .lore("\u00A77Disable all decorators")
            .build());
    }

    private ItemStack createDecoratorItem(DecoratorType type) {
        DecoratorConfig decoConfig = stage.getDecoratorConfig(type.getId());
        boolean isEnabled = decoConfig != null && decoConfig.isEnabled();

        List<String> lore = new ArrayList<>();
        lore.add("\u00A77" + type.getDescription());
        lore.add("");
        lore.add("\u00A77Status: " + (isEnabled ? "\u00A7aENABLED" : "\u00A78DISABLED"));

        // Show key config values if configured
        if (decoConfig != null && !decoConfig.getSettings().isEmpty()) {
            lore.add("");
            lore.add("\u00A76Settings:");
            int count = 0;
            for (Map.Entry<String, Object> entry : decoConfig.getSettings().entrySet()) {
                if (count >= 4) {
                    lore.add("\u00A78  ... and more");
                    break;
                }
                lore.add("\u00A78  " + entry.getKey() + ": \u00A7f" + entry.getValue());
                count++;
            }
        }

        lore.add("");
        lore.add("\u00A7eLeft-click to " + (isEnabled ? "disable" : "enable"));
        lore.add("\u00A7eRight-click to edit settings");

        ItemBuilder builder = new ItemBuilder(type.getIcon())
            .name((isEnabled ? "\u00A7a" : "\u00A78") + type.getDisplayName())
            .lore(lore);

        if (isEnabled) {
            builder.glow();
        }

        return builder.build();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        // Check decorator slots
        DecoratorType clickedType = getTypeAtSlot(slot);
        if (clickedType != null) {
            if (click.isRightClick()) {
                // Edit settings via chat
                playClickSound(player);
                editDecoratorSettings(player, clickedType);
            } else {
                // Toggle enabled/disabled
                playClickSound(player);
                toggleDecorator(player, clickedType);
            }
            return;
        }

        switch (slot) {
            case SLOT_BACK -> {
                playClickSound(player);
                menuManager.openMenu(player, new StageEditorMenu(plugin, menuManager, stage));
            }
            case SLOT_ENABLE_ALL -> {
                playClickSound(player);
                for (DecoratorType type : DecoratorType.values()) {
                    ensureConfig(type).setEnabled(true);
                }
                plugin.getStageManager().saveStages();
                reactivateIfActive();
                player.sendMessage(ChatColor.GREEN + "All decorators enabled.");
                menuManager.refreshMenu(player);
            }
            case SLOT_DISABLE_ALL -> {
                playClickSound(player);
                for (DecoratorType type : DecoratorType.values()) {
                    DecoratorConfig decoConfig = stage.getDecoratorConfig(type.getId());
                    if (decoConfig != null) {
                        decoConfig.setEnabled(false);
                    }
                }
                plugin.getStageManager().saveStages();
                reactivateIfActive();
                player.sendMessage(ChatColor.YELLOW + "All decorators disabled.");
                menuManager.refreshMenu(player);
            }
        }
    }

    private void toggleDecorator(Player player, DecoratorType type) {
        DecoratorConfig decoConfig = stage.getDecoratorConfig(type.getId());
        if (decoConfig == null) {
            // First time enabling: create config with defaults
            decoConfig = type.create(stage, plugin).getDefaultConfig();
            decoConfig.setEnabled(true);
            stage.setDecoratorConfig(type.getId(), decoConfig);
        } else {
            decoConfig.setEnabled(!decoConfig.isEnabled());
        }

        plugin.getStageManager().saveStages();
        reactivateIfActive();

        String status = decoConfig.isEnabled() ? ChatColor.GREEN + "enabled" : ChatColor.GRAY + "disabled";
        player.sendMessage(ChatColor.LIGHT_PURPLE + type.getDisplayName() + " " + status);
        menuManager.refreshMenu(player);
    }

    private void editDecoratorSettings(Player player, DecoratorType type) {
        DecoratorConfig decoConfig = ensureConfig(type);

        player.closeInventory();
        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "=== Edit " + type.getDisplayName() + " ===");
        player.sendMessage(ChatColor.GRAY + "Current settings:");
        for (Map.Entry<String, Object> entry : decoConfig.getSettings().entrySet()) {
            player.sendMessage(ChatColor.YELLOW + "  " + entry.getKey() + ChatColor.GRAY + " = " +
                ChatColor.WHITE + entry.getValue());
        }
        player.sendMessage("");
        player.sendMessage(ChatColor.YELLOW + "Type: " + ChatColor.WHITE + "key=value" +
            ChatColor.GRAY + " to change a setting");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to go back.");
        player.sendMessage("");

        plugin.getChatInputManager().requestInput(player, ChatInputManager.InputType.GENERAL, input -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (input != null && input.contains("=")) {
                    String[] parts = input.split("=", 2);
                    String key = parts[0].trim();
                    String value = parts[1].trim();

                    // Try to parse as number or boolean
                    Object parsed = parseValue(value);
                    decoConfig.set(key, parsed);
                    plugin.getStageManager().saveStages();
                    reactivateIfActive();
                    player.sendMessage(ChatColor.GREEN + "Set " + key + " = " + parsed);
                } else if (input != null && !"cancel".equalsIgnoreCase(input.trim())) {
                    player.sendMessage(ChatColor.RED + "Invalid format. Use: key=value");
                }

                menuManager.openMenu(player, new StageDecoratorMenu(plugin, menuManager, stage));
            });
        });
    }

    private Object parseValue(String value) {
        // Try integer
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) {}
        // Try double
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) {}
        // Try boolean
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        // Default to string
        return value;
    }

    private DecoratorConfig ensureConfig(DecoratorType type) {
        DecoratorConfig decoConfig = stage.getDecoratorConfig(type.getId());
        if (decoConfig == null) {
            decoConfig = type.create(stage, plugin).getDefaultConfig();
            stage.setDecoratorConfig(type.getId(), decoConfig);
        }
        return decoConfig;
    }

    private void reactivateIfActive() {
        if (stage.isActive() && plugin.getDecoratorManager() != null) {
            plugin.getDecoratorManager().deactivateDecorators(stage);
            plugin.getDecoratorManager().activateDecorators(stage);
        }
    }

    private int countEnabled() {
        int count = 0;
        for (DecoratorType type : DecoratorType.values()) {
            DecoratorConfig decoConfig = stage.getDecoratorConfig(type.getId());
            if (decoConfig != null && decoConfig.isEnabled()) count++;
        }
        return count;
    }

    private DecoratorType getTypeAtSlot(int slot) {
        DecoratorType[] types = DecoratorType.values();
        for (int i = 0; i < types.length && i < DECO_SLOTS.length; i++) {
            if (DECO_SLOTS[i] == slot) return types[i];
        }
        return null;
    }

    private void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }
}
