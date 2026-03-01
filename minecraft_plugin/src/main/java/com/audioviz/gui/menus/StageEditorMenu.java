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
import com.audioviz.stages.StageZonePlacementSession;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import com.audioviz.zones.ZoneBoundaryRenderer;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main stage configuration screen with a spatial zone grid layout.
 * Shows zones in their approximate spatial arrangement and provides
 * controls for move, rotate, activate/deactivate, VJ access, and
 * live 3D stage preview with zone role labels.
 */
public class StageEditorMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;
    private final Stage stage;

    // Preview state
    private boolean previewActive = false;
    private final List<TextDisplay> roleLabels = new ArrayList<>();

    // Move/rotate preview state
    private boolean pendingMove = false;
    private Location pendingMoveLocation = null;
    private boolean pendingRotate = false;
    private float pendingRotation = 0f;

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

    // Preview button (header row)
    private static final int SLOT_PREVIEW = 6;

    // Organization controls (row 4, embedded in separator)
    private static final int SLOT_PIN = 38;
    private static final int SLOT_RELOCATE_ZONES = 39;
    private static final int SLOT_TAG = 40;

    // Confirm/cancel slots (row 4, shown during pending move/rotate)
    private static final int SLOT_CONFIRM = 42;
    private static final int SLOT_CANCEL = 43;

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

        // Preview Stage button
        inventory.setItem(SLOT_PREVIEW, new ItemBuilder(Material.ENDER_EYE)
            .name(previewActive ? "&a\u25C9 Stage Preview: ON" : "&7\u25CB Stage Preview: OFF")
            .lore(
                "&7Toggle live 3D rendering of",
                "&7all zones with distinct colors",
                "&7and role name labels.",
                "",
                previewActive ? "&eClick to hide preview" : "&eClick to show preview"
            )
            .glow(previewActive)
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

        // Row 4 - separator with pin/tag controls (or confirm/cancel during pending ops)
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

        // Relocate Zones
        inventory.setItem(SLOT_RELOCATE_ZONES, new ItemBuilder(Material.COMPASS)
            .name("&bRelocate Zones")
            .lore(
                "&7Walk to each zone's location",
                "&7and confirm placement one-by-one.",
                "",
                "&eClick to start placement wizard"
            )
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

        // Confirm/cancel buttons (visible during pending move or rotate)
        if (pendingMove || pendingRotate) {
            String action = pendingMove ? "Move" : "Rotation to " + (int) pendingRotation + "\u00B0";
            inventory.setItem(SLOT_CONFIRM, new ItemBuilder(Material.LIME_CONCRETE)
                .name("&a\u2714 Confirm " + action)
                .lore(
                    pendingMove
                        ? "&7Apply move to your location"
                        : "&7Apply rotation to " + (int) pendingRotation + "\u00B0",
                    "",
                    "&aClick to confirm"
                )
                .glow()
                .build());

            inventory.setItem(SLOT_CANCEL, new ItemBuilder(Material.RED_CONCRETE)
                .name("&c\u2718 Cancel")
                .lore(
                    "&7Discard the pending " + (pendingMove ? "move" : "rotation"),
                    "",
                    "&cClick to cancel"
                )
                .build());
        }

        // Control buttons (row 5)
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());

        // Move button - shows pending state if active
        if (pendingMove) {
            inventory.setItem(SLOT_MOVE, new ItemBuilder(Material.ENDER_PEARL)
                .name("&b\u21BB Move Stage &7(Pending)")
                .lore(
                    "&7Preview location set.",
                    "&7Use &aConfirm &7or &cCancel",
                    "&7in the row above.",
                    "",
                    "&eClick to update preview location"
                )
                .glow()
                .build());
        } else {
            inventory.setItem(SLOT_MOVE, new ItemBuilder(Material.ENDER_PEARL)
                .name("&bMove Stage")
                .lore(
                    "&7Move the stage anchor to",
                    "&7your current location.",
                    "&7Shows preview before applying.",
                    "",
                    "&eClick to preview move"
                )
                .build());
        }

        // Rotate button - shows pending state if active
        if (pendingRotate) {
            inventory.setItem(SLOT_ROTATE, new ItemBuilder(Material.COMPASS)
                .name("&b\u21BB Rotate Stage &7(Pending: " + (int) pendingRotation + "\u00B0)")
                .lore(
                    "&7Preview rotation: &f" + (int) pendingRotation + "\u00B0",
                    "&7Current: &f" + (int) stage.getRotation() + "\u00B0",
                    "",
                    "&7Use &aConfirm &7or &cCancel above.",
                    "",
                    "&eLeft-click: +15\u00B0",
                    "&eRight-click: -15\u00B0",
                    "&eShift-click: +/-45\u00B0"
                )
                .glow()
                .build());
        } else {
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
        }

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
                "&eClick to save"
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

        // Active zone indicator: green wool for zones with entities, yellow concrete otherwise
        Material material;
        if (hasEntities) {
            material = Material.LIME_WOOL;
        } else {
            material = Material.YELLOW_CONCRETE;
        }

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

        // Show active pattern name for zones with entities
        if (hasEntities && config != null) {
            lore.add("");
            lore.add("&a\u25B6 Active: &f" + config.getPattern());
        }

        lore.add("");
        lore.add("&eLeft-click to edit");
        lore.add("&eRight-click to highlight in world");
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
                cleanupPreview();
                menuManager.openMenu(player, new StageListMenu(plugin, menuManager));
            }

            case SLOT_PREVIEW -> {
                playClickSound(player);
                togglePreview(player);
                menuManager.refreshMenu(player);
            }

            case SLOT_MOVE -> {
                playClickSound(player);
                handleMoveClick(player);
                menuManager.refreshMenu(player);
            }

            case SLOT_ROTATE -> {
                playClickSound(player);
                handleRotateClick(player, click);
                menuManager.refreshMenu(player);
            }

            case SLOT_CONFIRM -> {
                if (pendingMove || pendingRotate) {
                    playClickSound(player);
                    confirmPendingAction(player);
                    menuManager.refreshMenu(player);
                }
            }

            case SLOT_CANCEL -> {
                if (pendingMove || pendingRotate) {
                    playClickSound(player);
                    cancelPendingAction(player);
                    menuManager.refreshMenu(player);
                }
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

            case SLOT_RELOCATE_ZONES -> {
                playClickSound(player);
                cleanupPreview();
                player.closeInventory();
                StageZonePlacementSession session =
                    plugin.getZonePlacementManager().startSession(player, stage);
                if (session == null) {
                    // Couldn't start - reopen menu
                    menuManager.openMenu(player, new StageEditorMenu(plugin, menuManager, stage));
                }
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

            case SLOT_SAVE_TEMPLATE -> {
                playClickSound(player);
                player.closeInventory();
                requestTemplateName(player);
            }

            case SLOT_DELETE -> {
                if (click.isShiftClick()) {
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 1f);
                    cleanupPreview();
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

    @Override
    public void onClose(Player player) {
        // Clean up pending actions but keep preview if explicitly toggled
        if (pendingMove || pendingRotate) {
            cancelPendingAction(player);
        }
        plugin.getZoneBoundaryRenderer().clearSelection();
    }

    // ==================== Preview ====================

    private void togglePreview(Player player) {
        ZoneBoundaryRenderer renderer = plugin.getZoneBoundaryRenderer();

        if (previewActive) {
            // Turn off preview
            renderer.hideStage(stage.getName());
            cleanupRoleLabels();
            previewActive = false;
            player.sendMessage(ChatColor.YELLOW + "Stage preview hidden.");
        } else {
            // Turn on preview - show all zones with distinct colors and floor planes
            renderer.showStage(stage.getName());
            spawnRoleLabels();
            previewActive = true;
            player.sendMessage(ChatColor.GREEN + "Stage preview active. All zones visible with labels.");
        }
    }

    private void spawnRoleLabels() {
        cleanupRoleLabels();

        for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
            StageZoneRole role = entry.getKey();
            String zoneName = entry.getValue();
            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            // Spawn a Text Display entity above the zone center
            Location labelLoc = zone.getCenter().add(0, zone.getSize().getY() * 0.5 + 1.5, 0);

            labelLoc.getWorld().spawn(labelLoc, TextDisplay.class, textDisplay -> {
                textDisplay.setText(ChatColor.GOLD + role.getDisplayName());
                textDisplay.setBillboard(Display.Billboard.CENTER);
                textDisplay.setBackgroundColor(Color.fromARGB(160, 0, 0, 0));
                textDisplay.setShadowed(true);
                textDisplay.setSeeThrough(true);

                // Make the text visible from a distance
                textDisplay.setViewRange(64.0f);

                roleLabels.add(textDisplay);
            });
        }
    }

    private void cleanupRoleLabels() {
        for (TextDisplay label : roleLabels) {
            if (label != null && !label.isDead()) {
                label.remove();
            }
        }
        roleLabels.clear();
    }

    private void cleanupPreview() {
        if (previewActive) {
            plugin.getZoneBoundaryRenderer().hideStage(stage.getName());
            previewActive = false;
        }
        cleanupRoleLabels();
        plugin.getZoneBoundaryRenderer().clearSelection();
    }

    // ==================== Move Preview ====================

    private void handleMoveClick(Player player) {
        Location newLoc = player.getLocation();

        if (pendingRotate) {
            // Cancel pending rotation if starting a move
            pendingRotate = false;
            pendingRotation = 0f;
        }

        pendingMove = true;
        pendingMoveLocation = newLoc;

        // Show preview: render stage boundaries at the new position as a green ghost
        // We show the current boundaries in dim yellow and describe the new position
        if (previewActive) {
            // Re-show to refresh positions
            plugin.getZoneBoundaryRenderer().showStage(stage.getName());
        }

        player.sendMessage(ChatColor.AQUA + "Move preview set to your location.");
        player.sendMessage(ChatColor.GRAY + "  New anchor: " + formatLocation(newLoc));
        player.sendMessage(ChatColor.GRAY + "  Use " + ChatColor.GREEN + "Confirm" +
            ChatColor.GRAY + " or " + ChatColor.RED + "Cancel" + ChatColor.GRAY + " in the menu.");
    }

    // ==================== Rotate Preview ====================

    private void handleRotateClick(Player player, ClickType click) {
        if (pendingMove) {
            // Cancel pending move if starting a rotation
            pendingMove = false;
            pendingMoveLocation = null;
        }

        float delta = click.isShiftClick() ? 45f : 15f;
        if (click.isRightClick()) delta = -delta;

        if (!pendingRotate) {
            // Start a new rotation preview from current rotation
            pendingRotate = true;
            pendingRotation = stage.getRotation() + delta;
        } else {
            // Adjust the pending rotation
            pendingRotation = pendingRotation + delta;
        }

        // Normalize to 0-360
        pendingRotation = ((pendingRotation % 360) + 360) % 360;

        player.sendMessage(ChatColor.AQUA + "Rotation preview: " + (int) pendingRotation + "\u00B0" +
            ChatColor.GRAY + " (current: " + (int) stage.getRotation() + "\u00B0)");
        player.sendMessage(ChatColor.GRAY + "  Use " + ChatColor.GREEN + "Confirm" +
            ChatColor.GRAY + " or " + ChatColor.RED + "Cancel" + ChatColor.GRAY + " in the menu.");
    }

    // ==================== Confirm / Cancel ====================

    private void confirmPendingAction(Player player) {
        if (pendingMove && pendingMoveLocation != null) {
            plugin.getStageManager().moveStage(stage, pendingMoveLocation);
            player.sendMessage(ChatColor.GREEN + "Stage moved to " + formatLocation(pendingMoveLocation) + ".");

            // Refresh preview if active
            if (previewActive) {
                plugin.getZoneBoundaryRenderer().hideStage(stage.getName());
                plugin.getZoneBoundaryRenderer().showStage(stage.getName());
                cleanupRoleLabels();
                spawnRoleLabels();
            }
        } else if (pendingRotate) {
            plugin.getStageManager().rotateStage(stage, pendingRotation);
            player.sendMessage(ChatColor.GREEN + "Stage rotated to " + (int) stage.getRotation() + "\u00B0.");

            // Refresh preview if active
            if (previewActive) {
                plugin.getZoneBoundaryRenderer().hideStage(stage.getName());
                plugin.getZoneBoundaryRenderer().showStage(stage.getName());
                cleanupRoleLabels();
                spawnRoleLabels();
            }
        }

        pendingMove = false;
        pendingMoveLocation = null;
        pendingRotate = false;
        pendingRotation = 0f;
    }

    private void cancelPendingAction(Player player) {
        if (pendingMove) {
            player.sendMessage(ChatColor.YELLOW + "Move cancelled.");
        } else if (pendingRotate) {
            player.sendMessage(ChatColor.YELLOW + "Rotation cancelled.");
        }
        pendingMove = false;
        pendingMoveLocation = null;
        pendingRotate = false;
        pendingRotation = 0f;
    }

    // ==================== Zone Click Handling ====================

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
            // Quick-select: highlight this zone in the world
            playClickSound(player);
            ZoneBoundaryRenderer renderer = plugin.getZoneBoundaryRenderer();
            renderer.setSelected(zoneName);

            // Also show the zone if not already visible
            if (!renderer.isShowing(zoneName)) {
                renderer.show(zoneName, Long.MAX_VALUE);
                renderer.setPersistent(zoneName, true);
            }

            player.sendMessage(ChatColor.GREEN + "\u2192 Highlighting " + ChatColor.GOLD +
                role.getDisplayName() + ChatColor.GREEN + " in world.");
        } else {
            // Open zone editor
            VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
            if (zone != null) {
                playClickSound(player);
                menuManager.openMenu(player, new ZoneEditorMenu(plugin, menuManager, zone));
            }
        }
    }

    // ==================== Tag Input ====================

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

    // ==================== Template Save ====================

    private void requestTemplateName(Player player) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== Save as Template ===");
        player.sendMessage(ChatColor.YELLOW + "Enter a name for this template.");
        player.sendMessage(ChatColor.GRAY + "Use letters, numbers, underscores, and hyphens.");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to cancel.");
        player.sendMessage("");

        plugin.getChatInputManager().requestInput(player, ChatInputManager.InputType.GENERAL, input -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (input != null) {
                    String templateName = input.trim().toLowerCase()
                        .replaceAll("[^a-z0-9_-]", "_");
                    if (templateName.isEmpty() || templateName.length() > 32) {
                        player.sendMessage(ChatColor.RED + "Invalid template name. Must be 1-32 characters.");
                    } else if (plugin.getStageManager().saveAsTemplate(stage, templateName)) {
                        player.sendMessage(ChatColor.GREEN + "Template saved as: " + templateName);
                    } else {
                        player.sendMessage(ChatColor.RED + "Failed to save template. Check console for details.");
                    }
                }
                menuManager.openMenu(player, new StageEditorMenu(plugin, menuManager, stage));
            });
        });
    }

    // ==================== Helpers ====================

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
