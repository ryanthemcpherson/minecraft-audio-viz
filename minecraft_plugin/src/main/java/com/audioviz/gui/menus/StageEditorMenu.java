package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.decorators.DecoratorType;
import com.audioviz.gui.ChatInputManager;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneConfig;
import com.audioviz.stages.StageZoneRole;
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
 * Main stage configuration screen with a spatial zone grid layout.
 * Shows zones in their approximate spatial arrangement and provides
 * controls for move, rotate, activate/deactivate, and VJ access.
 */
public class StageEditorMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;
    private final Stage stage;

    // Zone grid positions (rows 1-3, approximate spatial layout)
    // Row 1: LEFT_WING(10) ... MAIN_STAGE(13) ... RIGHT_WING(16)
    // Row 2: BACKSTAGE(19) ... RUNWAY(22) ... AUDIENCE(25)
    // Row 3: (28) ... SKYBOX(31) ... BALCONY(34)
    private static final int SLOT_LEFT_WING = 10;
    private static final int SLOT_MAIN_STAGE = 13;
    private static final int SLOT_RIGHT_WING = 16;
    private static final int SLOT_BACKSTAGE = 19;
    private static final int SLOT_RUNWAY = 22;
    private static final int SLOT_AUDIENCE = 25;
    private static final int SLOT_SKYBOX = 31;
    private static final int SLOT_BALCONY = 34;

    // Organization controls (row 4, embedded in separator)
    private static final int SLOT_PIN = 38;
    private static final int SLOT_TAG = 40;

    // Control buttons (row 5)
    private static final int SLOT_BACK = 45;
    private static final int SLOT_MOVE = 46;
    private static final int SLOT_ROTATE = 47;
    private static final int SLOT_TOGGLE_ACTIVE = 48;
    private static final int SLOT_VJ_MENU = 49;
    private static final int SLOT_ADD_ZONE = 50;
    private static final int SLOT_SAVE_TEMPLATE = 51;
    private static final int SLOT_DECORATORS = 52;
    private static final int SLOT_DELETE = 53;

    public StageEditorMenu(AudioVizPlugin plugin, MenuManager menuManager, Stage stage) {
        this.plugin = plugin;
        this.menuManager = menuManager;
        this.stage = stage;
    }

    @Override
    public String getTitle() {
        return "\u00A75Stage: " + stage.getName();
    }

    @Override
    public int getSize() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();

        // Header row (purple glass)
        ItemStack purpleFiller = ItemBuilder.glassPane(DyeColor.PURPLE);
        for (int i = 0; i < 9; i++) inventory.setItem(i, purpleFiller);

        // Stage info header
        List<String> headerLore = new ArrayList<>();
        headerLore.add("&7Template: &f" + stage.getTemplateName());
        headerLore.add("&7Status: " + (stage.isActive() ? "&aACTIVE" : "&7Inactive"));
        if (stage.isPinned()) headerLore.add("&6\u2605 Pinned");
        if (!stage.getTag().isEmpty()) headerLore.add("&7Tag: &f" + stage.getTag());
        headerLore.add("&7Zones: &f" + stage.getRoleToZone().size());
        headerLore.add("&7Rotation: &f" + (int) stage.getRotation() + "\u00B0");
        headerLore.add("&7Anchor: &f" + formatLocation(stage.getAnchor()));

        inventory.setItem(4, new ItemBuilder(Material.BEACON)
            .name("&5&l" + stage.getName())
            .lore(headerLore)
            .glow()
            .build());

        // Fill zone area with dark glass
        ItemStack darkFiller = ItemBuilder.glassPane(DyeColor.BLACK);
        for (int row = 1; row <= 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = row * 9 + col;
                inventory.setItem(slot, darkFiller);
            }
        }

        // Place zone items
        placeZoneItem(inventory, StageZoneRole.LEFT_WING, SLOT_LEFT_WING);
        placeZoneItem(inventory, StageZoneRole.MAIN_STAGE, SLOT_MAIN_STAGE);
        placeZoneItem(inventory, StageZoneRole.RIGHT_WING, SLOT_RIGHT_WING);
        placeZoneItem(inventory, StageZoneRole.BACKSTAGE, SLOT_BACKSTAGE);
        placeZoneItem(inventory, StageZoneRole.RUNWAY, SLOT_RUNWAY);
        placeZoneItem(inventory, StageZoneRole.AUDIENCE, SLOT_AUDIENCE);
        placeZoneItem(inventory, StageZoneRole.SKYBOX, SLOT_SKYBOX);
        placeZoneItem(inventory, StageZoneRole.BALCONY, SLOT_BALCONY);

        // Row 4 - separator with pin/tag controls
        ItemStack grayFiller = ItemBuilder.glassPane(DyeColor.GRAY);
        for (int i = 36; i < 45; i++) inventory.setItem(i, grayFiller);

        // Pin toggle
        inventory.setItem(SLOT_PIN, new ItemBuilder(
                stage.isPinned() ? Material.GOLD_INGOT : Material.IRON_INGOT)
            .name(stage.isPinned() ? "&6\u2605 Pinned" : "&7Not Pinned")
            .lore(
                "&7Pin this stage for quick",
                "&7access in the stage list.",
                "",
                "&eClick to toggle"
            )
            .glow(stage.isPinned())
            .build());

        // Tag editor
        String tagDisplay = stage.getTag().isEmpty() ? "&7None" : "&f" + stage.getTag();
        inventory.setItem(SLOT_TAG, new ItemBuilder(Material.NAME_TAG)
            .name("&eTag: " + tagDisplay)
            .lore(
                "&7Categorize this stage",
                "&7for easier filtering.",
                "",
                "&eLeft-click to set tag",
                "&eRight-click to clear"
            )
            .build());

        // Control buttons (row 5)
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());

        inventory.setItem(SLOT_MOVE, new ItemBuilder(Material.ENDER_PEARL)
            .name("&bMove Stage")
            .lore(
                "&7Move the stage anchor to",
                "&7your current location.",
                "&7All zones will reposition.",
                "",
                "&eClick to move"
            )
            .build());

        inventory.setItem(SLOT_ROTATE, new ItemBuilder(Material.COMPASS)
            .name("&bRotate Stage")
            .lore(
                "&7Current: &f" + (int) stage.getRotation() + "\u00B0",
                "",
                "&eLeft-click: +15\u00B0",
                "&eRight-click: -15\u00B0",
                "&eShift-click: +/-45\u00B0"
            )
            .build());

        // Active toggle
        boolean active = stage.isActive();
        inventory.setItem(SLOT_TOGGLE_ACTIVE, new ItemBuilder(active ? Material.LIME_DYE : Material.GRAY_DYE)
            .name(active ? "&aDeactivate Stage" : "&aActivate Stage")
            .lore(
                "&7Status: " + (active ? "&aACTIVE" : "&7Inactive"),
                "",
                active ? "&eClick to remove entities" : "&eClick to spawn entities",
                active ? "&7and deactivate" : "&7and activate"
            )
            .build());

        inventory.setItem(SLOT_VJ_MENU, new ItemBuilder(Material.JUKEBOX)
            .name("&dVJ Control")
            .lore(
                "&7Open the live VJ panel",
                "&7for this stage.",
                "",
                "&eClick to open"
            )
            .glow()
            .build());

        inventory.setItem(SLOT_ADD_ZONE, new ItemBuilder(Material.EMERALD)
            .name("&aAdd Zone Role")
            .lore(
                "&7Add a missing zone role",
                "&7to this stage.",
                "",
                "&eClick to add"
            )
            .build());

        inventory.setItem(SLOT_SAVE_TEMPLATE, new ItemBuilder(Material.WRITABLE_BOOK)
            .name("&6Save as Template")
            .lore(
                "&7Save this stage layout",
                "&7as a custom template.",
                "",
                "&eComing soon"
            )
            .build());

        // Decorator configuration button
        int enabledDecorators = countEnabledDecorators();
        inventory.setItem(SLOT_DECORATORS, new ItemBuilder(Material.PAINTING)
            .name("&d\u26A1 Stage Decorators")
            .lore(
                "&7Configure visual effects:",
                "&7DJ Billboard, Spotlights,",
                "&7Floor Tiles, Crowd FX, etc.",
                "",
                "&7Active: &f" + enabledDecorators + "/" + DecoratorType.values().length,
                "",
                "&eClick to configure"
            )
            .glow(enabledDecorators > 0)
            .build());

        inventory.setItem(SLOT_DELETE, new ItemBuilder(Material.BARRIER)
            .name("&cDelete Stage")
            .lore(
                "&7Permanently delete this",
                "&7stage and all its zones.",
                "",
                "&c&lShift-click to confirm"
            )
            .build());
    }

    private void placeZoneItem(Inventory inventory, StageZoneRole role, int slot) {
        String zoneName = stage.getZoneName(role);
        StageZoneConfig config = stage.getZoneConfigs().get(role);

        if (zoneName == null) {
            // Role not present - show placeholder
            inventory.setItem(slot, new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name("&8" + role.getDisplayName())
                .lore(
                    "&7This zone is not active.",
                    "",
                    "&eClick to add"
                )
                .build());
            return;
        }

        // Zone exists - show its info
        int entityCount = plugin.getEntityPoolManager().getEntityCount(zoneName);
        boolean hasEntities = entityCount > 0;
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);

        Material material = hasEntities ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE;

        List<String> lore = new ArrayList<>();
        lore.add("&7Role: &f" + role.getDisplayName());
        lore.add("&7Zone: &f" + zoneName);
        if (zone != null) {
            lore.add("&7Size: &f" + (int) zone.getSize().getX() + "x" +
                (int) zone.getSize().getY() + "x" + (int) zone.getSize().getZ());
        }
        lore.add("");
        if (config != null) {
            lore.add("&7Pattern: &f" + config.getPattern());
            lore.add("&7Entities: &f" + entityCount + "/" + config.getEntityCount());
            lore.add("&7Render: &f" + config.getRenderMode());
            lore.add("&7Block: &f" + config.getBlockType());
        }
        lore.add("");
        lore.add("&eLeft-click to edit");
        lore.add("&eRight-click to teleport");
        lore.add("&cShift-click to remove");

        ItemBuilder builder = new ItemBuilder(material)
            .name("&e" + role.getDisplayName())
            .lore(lore);

        if (hasEntities) {
            builder.glow();
        }

        inventory.setItem(slot, builder.build());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case SLOT_BACK -> {
                playClickSound(player);
                menuManager.openMenu(player, new StageListMenu(plugin, menuManager));
            }

            case SLOT_MOVE -> {
                playClickSound(player);
                plugin.getStageManager().moveStage(stage, player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Stage moved to your location.");
                menuManager.refreshMenu(player);
            }

            case SLOT_ROTATE -> {
                playClickSound(player);
                float delta = click.isShiftClick() ? 45f : 15f;
                if (click.isRightClick()) delta = -delta;
                plugin.getStageManager().rotateStage(stage, stage.getRotation() + delta);
                player.sendMessage(ChatColor.GREEN + "Stage rotated to " + (int) stage.getRotation() + "\u00B0");
                menuManager.refreshMenu(player);
            }

            case SLOT_TOGGLE_ACTIVE -> {
                playClickSound(player);
                if (stage.isActive()) {
                    plugin.getStageManager().deactivateStage(stage);
                    player.sendMessage(ChatColor.YELLOW + "Stage deactivated.");
                } else {
                    plugin.getStageManager().activateStage(stage);
                    player.sendMessage(ChatColor.GREEN + "Stage activated! Entities spawned.");
                }
                menuManager.refreshMenu(player);
            }

            case SLOT_VJ_MENU -> {
                playClickSound(player);
                menuManager.openMenu(player, new StageVJMenu(plugin, menuManager, stage));
            }

            case SLOT_DECORATORS -> {
                playClickSound(player);
                menuManager.openMenu(player, new StageDecoratorMenu(plugin, menuManager, stage));
            }

            case SLOT_ADD_ZONE -> {
                // Find missing roles and add the first one
                playClickSound(player);
                for (StageZoneRole role : StageZoneRole.values()) {
                    if (!stage.getRoleToZone().containsKey(role)) {
                        if (plugin.getStageManager().addRoleToStage(stage, role)) {
                            player.sendMessage(ChatColor.GREEN + "Added " + role.getDisplayName() + " zone.");
                        } else {
                            player.sendMessage(ChatColor.RED + "Failed to add zone.");
                        }
                        menuManager.refreshMenu(player);
                        return;
                    }
                }
                player.sendMessage(ChatColor.YELLOW + "All zone roles are already in this stage.");
            }

            case SLOT_PIN -> {
                playClickSound(player);
                stage.setPinned(!stage.isPinned());
                plugin.getStageManager().saveStages();
                String msg = stage.isPinned()
                    ? ChatColor.GOLD + "\u2605 " + ChatColor.GREEN + "Stage pinned!"
                    : ChatColor.GRAY + "Stage unpinned.";
                player.sendMessage(msg);
                menuManager.refreshMenu(player);
            }

            case SLOT_TAG -> {
                if (click.isRightClick()) {
                    playClickSound(player);
                    stage.setTag("");
                    plugin.getStageManager().saveStages();
                    player.sendMessage(ChatColor.GREEN + "Tag cleared.");
                    menuManager.refreshMenu(player);
                } else {
                    playClickSound(player);
                    player.closeInventory();
                    requestTag(player);
                }
            }

            case SLOT_DELETE -> {
                if (click.isShiftClick()) {
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 1f);
                    player.closeInventory();
                    player.sendMessage(ChatColor.RED + "Deleting stage: " + stage.getName());
                    plugin.getStageManager().deleteStage(stage.getName());
                    player.sendMessage(ChatColor.GREEN + "Stage and all zones deleted.");
                } else {
                    player.sendMessage(ChatColor.YELLOW + "Shift-click to confirm deletion.");
                }
            }

            default -> {
                // Check zone grid clicks
                StageZoneRole role = getRoleAtSlot(slot);
                if (role != null) {
                    handleZoneClick(player, role, click);
                }
            }
        }
    }

    private void handleZoneClick(Player player, StageZoneRole role, ClickType click) {
        String zoneName = stage.getZoneName(role);

        if (zoneName == null) {
            // Add the missing role
            playClickSound(player);
            if (plugin.getStageManager().addRoleToStage(stage, role)) {
                player.sendMessage(ChatColor.GREEN + "Added " + role.getDisplayName() + " zone.");
            } else {
                player.sendMessage(ChatColor.RED + "Failed to add zone.");
            }
            menuManager.refreshMenu(player);
            return;
        }

        if (click.isShiftClick()) {
            // Remove role
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 1f);
            plugin.getStageManager().removeRoleFromStage(stage, role);
            player.sendMessage(ChatColor.YELLOW + "Removed " + role.getDisplayName() + " zone.");
            menuManager.refreshMenu(player);
        } else if (click.isRightClick()) {
            // Teleport
            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone != null) {
                playClickSound(player);
                player.teleport(zone.getCenter());
                player.sendMessage(ChatColor.GREEN + "Teleported to " + role.getDisplayName());
            }
        } else {
            // Open zone editor
            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone != null) {
                playClickSound(player);
                menuManager.openMenu(player, new ZoneEditorMenu(plugin, menuManager, zone));
            }
        }
    }

    private void requestTag(Player player) {
        java.util.Set<String> existingTags = plugin.getStageManager().getAllTags();

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== Set Stage Tag ===");
        player.sendMessage(ChatColor.YELLOW + "Enter a tag for this stage.");
        if (!existingTags.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "Existing tags: " + String.join(", ", existingTags));
        }
        player.sendMessage(ChatColor.GRAY + "Suggestions: venue, event, test, archive");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to cancel.");
        player.sendMessage("");

        plugin.getChatInputManager().requestInput(player, ChatInputManager.InputType.GENERAL, input -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (input != null) {
                    String tag = input.trim().toLowerCase();
                    if (tag.length() > 20) tag = tag.substring(0, 20);
                    stage.setTag(tag);
                    plugin.getStageManager().saveStages();
                    player.sendMessage(ChatColor.GREEN + "Tag set to: " + tag);
                }
                menuManager.openMenu(player, new StageEditorMenu(plugin, menuManager, stage));
            });
        });
    }

    private StageZoneRole getRoleAtSlot(int slot) {
        return switch (slot) {
            case SLOT_LEFT_WING -> StageZoneRole.LEFT_WING;
            case SLOT_MAIN_STAGE -> StageZoneRole.MAIN_STAGE;
            case SLOT_RIGHT_WING -> StageZoneRole.RIGHT_WING;
            case SLOT_BACKSTAGE -> StageZoneRole.BACKSTAGE;
            case SLOT_RUNWAY -> StageZoneRole.RUNWAY;
            case SLOT_AUDIENCE -> StageZoneRole.AUDIENCE;
            case SLOT_SKYBOX -> StageZoneRole.SKYBOX;
            case SLOT_BALCONY -> StageZoneRole.BALCONY;
            default -> null;
        };
    }

    private int countEnabledDecorators() {
        int count = 0;
        for (DecoratorType type : DecoratorType.values()) {
            DecoratorConfig decoConfig = stage.getDecoratorConfig(type.getId());
            if (decoConfig != null && decoConfig.isEnabled()) count++;
        }
        return count;
    }

    private String formatLocation(org.bukkit.Location loc) {
        return String.format("%.0f, %.0f, %.0f (%s)",
            loc.getX(), loc.getY(), loc.getZ(), loc.getWorld().getName());
    }

    private void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }
}
