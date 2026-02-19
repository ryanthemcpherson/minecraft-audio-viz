package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneConfig;
import com.audioviz.stages.StageZoneRole;
import com.google.gson.JsonObject;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Live VJ control panel for a stage.
 * Allows selecting zones and changing patterns, render modes, effects in bulk.
 */
public class StageVJMenu implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;
    private final Stage stage;

    // Per-player VJ state
    private final Set<StageZoneRole> selectedZones = new LinkedHashSet<>();
    private int pendingIntensity = 4; // 1-7
    private int patternIndex = 0;
    private String pendingRenderMode = "entities";

    // Available patterns for cycling
    private static final String[] PATTERNS = {
        "spectrum", "ring", "wave", "explode", "columns", "orbit", "matrix", "heartbeat",
        "mushroom", "skull", "sacred", "vortex", "pyramid", "galaxy", "laser",
        "mandala", "tesseract", "crystal",
        "blackhole", "nebula", "wormhole", "aurora", "ocean", "fireflies",
        "bars", "tubes", "circle"
    };

    // Zone selector slots (row 1, up to 8 zones + select-all)
    private static final int ZONE_SLOT_START = 9;
    private static final int SLOT_SELECT_ALL = 17;

    // Intensity slider (row 2)
    private static final int INTENSITY_START = 18;
    private static final int INTENSITY_COUNT = 7;
    private static final int SLOT_INTENSITY_LABEL = 26;

    // Pattern/mode controls (row 3)
    private static final int SLOT_PATTERN_PREV = 27;
    private static final int SLOT_PATTERN_DISPLAY = 28;
    private static final int SLOT_PATTERN_NEXT = 29;
    private static final int SLOT_RENDER_MODE = 31;
    private static final int SLOT_ENTITY_COUNT = 33;
    private static final int SLOT_APPLY = 35;

    // Effect triggers (row 4)
    private static final int EFFECT_START = 36;
    private static final String[] EFFECTS = {
        "blackout", "freeze", "flash", "strobe", "pulse", "wave_effect", "spiral", "explode_effect"
    };
    private static final Material[] EFFECT_MATERIALS = {
        Material.BLACK_CONCRETE, Material.BLUE_ICE, Material.GLOWSTONE,
        Material.REDSTONE_LAMP, Material.HEART_OF_THE_SEA, Material.PRISMARINE_SHARD,
        Material.ENDER_EYE, Material.FIRE_CHARGE
    };

    // Bottom row
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PRESET_CHILL = 47;
    private static final int SLOT_PRESET_PARTY = 48;
    private static final int SLOT_PRESET_RAVE = 49;
    private static final int SLOT_SAVE = 53;

    public StageVJMenu(AudioVizPlugin plugin, MenuManager menuManager, Stage stage) {
        this.plugin = plugin;
        this.menuManager = menuManager;
        this.stage = stage;

        // Select all zones by default
        selectedZones.addAll(stage.getActiveRoles());

        // Initialize pattern index from first zone's config
        if (!stage.getActiveRoles().isEmpty()) {
            StageZoneRole firstRole = stage.getActiveRoles().iterator().next();
            StageZoneConfig config = stage.getZoneConfigs().get(firstRole);
            if (config != null) {
                pendingRenderMode = config.getRenderMode();
                for (int i = 0; i < PATTERNS.length; i++) {
                    if (PATTERNS[i].equals(config.getPattern())) {
                        patternIndex = i;
                        break;
                    }
                }
            }
        }
    }

    @Override
    public String getTitle() {
        return "\u00A7d\u00A7lVJ: " + stage.getName();
    }

    @Override
    public int getSize() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();

        // Header row
        ItemStack purpleFiller = ItemBuilder.glassPane(DyeColor.PURPLE);
        for (int i = 0; i < 9; i++) inventory.setItem(i, purpleFiller);

        inventory.setItem(4, new ItemBuilder(Material.JUKEBOX)
            .name("&d&lVJ: " + stage.getName())
            .lore(
                "&7Live stage control panel",
                "&7Select zones and apply changes",
                "",
                "&7Selected: &f" + selectedZones.size() + "/" + stage.getActiveRoles().size() + " zones"
            )
            .glow()
            .build());

        // Row 1: Zone selectors
        List<StageZoneRole> roles = new ArrayList<>(stage.getActiveRoles());
        for (int i = 0; i < Math.min(roles.size(), 8); i++) {
            StageZoneRole role = roles.get(i);
            boolean selected = selectedZones.contains(role);
            String zoneName = stage.getZoneName(role);
            StageZoneConfig config = stage.getZoneConfigs().get(role);
            int entities = plugin.getEntityPoolManager().getEntityCount(zoneName);

            List<String> lore = new ArrayList<>();
            lore.add("&7" + role.getDisplayName());
            if (config != null) {
                lore.add("&7Pattern: &f" + config.getPattern());
                lore.add("&7Entities: &f" + entities);
                lore.add("&7Render: &f" + config.getRenderMode());
            }
            lore.add("");
            lore.add(selected ? "&aSelected" : "&7Not selected");
            lore.add("");
            lore.add("&eLeft-click to toggle");
            lore.add("&eRight-click to solo");

            ItemBuilder builder = new ItemBuilder(role.getIcon())
                .name((selected ? "&a" : "&7") + role.getDisplayName())
                .lore(lore);
            if (selected) builder.glow();

            inventory.setItem(ZONE_SLOT_START + i, builder.build());
        }

        // Select all toggle
        boolean allSelected = selectedZones.size() == roles.size();
        inventory.setItem(SLOT_SELECT_ALL, new ItemBuilder(
                allSelected ? Material.LIME_DYE : Material.GRAY_DYE)
            .name(allSelected ? "&aDeselect All" : "&aSelect All")
            .lore("&7Toggle all zone selection")
            .build());

        // Row 2: Intensity slider
        for (int i = 0; i < INTENSITY_COUNT; i++) {
            int level = i + 1;
            boolean active = level <= pendingIntensity;
            Material mat = active ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            inventory.setItem(INTENSITY_START + i, new ItemBuilder(mat)
                .name("&fIntensity: " + level)
                .lore(active ? "&aActive" : "&7Inactive", "", "&eClick to set")
                .build());
        }
        inventory.setItem(SLOT_INTENSITY_LABEL, new ItemBuilder(Material.BLAZE_ROD)
            .name("&6Intensity")
            .lore("&7Current: &f" + pendingIntensity + "/7")
            .build());

        // Row 3: Pattern and mode controls
        inventory.setItem(SLOT_PATTERN_PREV, new ItemBuilder(Material.ARROW)
            .name("&e< Previous Pattern")
            .build());

        inventory.setItem(SLOT_PATTERN_DISPLAY, new ItemBuilder(Material.PAINTING)
            .name("&e&l" + PATTERNS[patternIndex])
            .lore(
                "&7Current pattern selection",
                "",
                "&7Click Apply to set on",
                "&7all selected zones"
            )
            .glow()
            .build());

        inventory.setItem(SLOT_PATTERN_NEXT, new ItemBuilder(Material.ARROW)
            .name("&eNext Pattern >")
            .build());

        // Render mode
        Material renderMat = switch (pendingRenderMode) {
            case "particles" -> Material.CAMPFIRE;
            case "hybrid" -> Material.SOUL_CAMPFIRE;
            default -> Material.SEA_LANTERN;
        };
        inventory.setItem(SLOT_RENDER_MODE, new ItemBuilder(renderMat)
            .name("&bRender: " + pendingRenderMode)
            .lore(
                "&7Click to cycle:",
                "&f entities > particles > hybrid",
                "",
                "&eClick to change"
            )
            .build());

        // Entity count (shows current for first selected zone)
        int displayCount = 16;
        if (!selectedZones.isEmpty()) {
            StageZoneRole firstSelected = selectedZones.iterator().next();
            StageZoneConfig config = stage.getZoneConfigs().get(firstSelected);
            if (config != null) displayCount = config.getEntityCount();
        }
        inventory.setItem(SLOT_ENTITY_COUNT, new ItemBuilder(Material.ARMOR_STAND)
            .name("&bEntities: " + displayCount)
            .lore(
                "&eLeft: +1 | Shift: +16",
                "&eRight: -1 | Shift: -16"
            )
            .build());

        // Apply button
        inventory.setItem(SLOT_APPLY, new ItemBuilder(Material.EMERALD)
            .name("&a&lApply to Selected")
            .lore(
                "&7Apply pending changes to",
                "&7all " + selectedZones.size() + " selected zone(s).",
                "",
                "&7Pattern: &f" + PATTERNS[patternIndex],
                "&7Render: &f" + pendingRenderMode,
                "&7Intensity: &f" + pendingIntensity,
                "",
                "&eClick to apply"
            )
            .glow()
            .build());

        // Row 4: Effect triggers
        for (int i = 0; i < Math.min(EFFECTS.length, 8); i++) {
            inventory.setItem(EFFECT_START + i, new ItemBuilder(EFFECT_MATERIALS[i])
                .name("&c" + formatEffectName(EFFECTS[i]))
                .lore(
                    "&7Trigger this effect",
                    "",
                    "&eLeft: selected zones",
                    "&eRight: all zones"
                )
                .build());
        }

        // Bottom row
        ItemStack grayFiller = ItemBuilder.glassPane(DyeColor.GRAY);
        for (int i = 45; i < 54; i++) inventory.setItem(i, grayFiller);

        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());

        inventory.setItem(SLOT_PRESET_CHILL, new ItemBuilder(Material.LIGHT_BLUE_DYE)
            .name("&bPreset: Chill")
            .lore("&7Low intensity, gentle patterns")
            .build());

        inventory.setItem(SLOT_PRESET_PARTY, new ItemBuilder(Material.YELLOW_DYE)
            .name("&ePreset: Party")
            .lore("&7Medium intensity, dynamic patterns")
            .build());

        inventory.setItem(SLOT_PRESET_RAVE, new ItemBuilder(Material.MAGENTA_DYE)
            .name("&dPreset: Rave")
            .lore("&7Max intensity, all effects")
            .build());

        inventory.setItem(SLOT_SAVE, new ItemBuilder(Material.WRITABLE_BOOK)
            .name("&6Save Configuration")
            .lore("&7Save current zone configs")
            .build());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        // Zone selection (row 1)
        List<StageZoneRole> roles = new ArrayList<>(stage.getActiveRoles());
        if (slot >= ZONE_SLOT_START && slot < ZONE_SLOT_START + Math.min(roles.size(), 8)) {
            int index = slot - ZONE_SLOT_START;
            StageZoneRole role = roles.get(index);

            if (click.isRightClick()) {
                // Solo - select only this zone
                selectedZones.clear();
                selectedZones.add(role);
            } else {
                // Toggle
                if (selectedZones.contains(role)) {
                    selectedZones.remove(role);
                } else {
                    selectedZones.add(role);
                }
            }
            playClickSound(player);
            menuManager.refreshMenu(player);
            return;
        }

        switch (slot) {
            case SLOT_SELECT_ALL -> {
                playClickSound(player);
                if (selectedZones.size() == roles.size()) {
                    selectedZones.clear();
                } else {
                    selectedZones.clear();
                    selectedZones.addAll(roles);
                }
                menuManager.refreshMenu(player);
            }

            // Intensity slider
            case 18, 19, 20, 21, 22, 23, 24 -> {
                pendingIntensity = slot - INTENSITY_START + 1;
                playClickSound(player);
                menuManager.refreshMenu(player);
            }

            // Pattern cycling
            case SLOT_PATTERN_PREV -> {
                patternIndex = (patternIndex - 1 + PATTERNS.length) % PATTERNS.length;
                playClickSound(player);
                menuManager.refreshMenu(player);
            }
            case SLOT_PATTERN_NEXT -> {
                patternIndex = (patternIndex + 1) % PATTERNS.length;
                playClickSound(player);
                menuManager.refreshMenu(player);
            }

            // Render mode cycle
            case SLOT_RENDER_MODE -> {
                pendingRenderMode = switch (pendingRenderMode) {
                    case "entities" -> "particles";
                    case "particles" -> "hybrid";
                    default -> "entities";
                };
                playClickSound(player);
                menuManager.refreshMenu(player);
            }

            // Entity count
            case SLOT_ENTITY_COUNT -> {
                int delta = click.isShiftClick() ? 16 : 1;
                if (click.isRightClick()) delta = -delta;
                for (StageZoneRole role : selectedZones) {
                    StageZoneConfig config = stage.getOrCreateConfig(role);
                    config.setEntityCount(config.getEntityCount() + delta);
                }
                playClickSound(player);
                menuManager.refreshMenu(player);
            }

            // Apply to selected
            case SLOT_APPLY -> {
                applyToSelected(player);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);
                menuManager.refreshMenu(player);
            }

            // Back
            case SLOT_BACK -> {
                playClickSound(player);
                menuManager.openMenu(player, new StageEditorMenu(plugin, menuManager, stage));
            }

            // Presets
            case SLOT_PRESET_CHILL -> {
                applyPreset("chill", player);
            }
            case SLOT_PRESET_PARTY -> {
                applyPreset("party", player);
            }
            case SLOT_PRESET_RAVE -> {
                applyPreset("rave", player);
            }

            // Save
            case SLOT_SAVE -> {
                plugin.getStageManager().saveStages();
                player.sendMessage(ChatColor.GREEN + "Stage configuration saved.");
                playClickSound(player);
            }

            default -> {
                // Effect triggers (row 4)
                if (slot >= EFFECT_START && slot < EFFECT_START + EFFECTS.length) {
                    int effectIndex = slot - EFFECT_START;
                    String effect = EFFECTS[effectIndex];
                    triggerEffect(player, effect, click.isRightClick());
                }
            }
        }
    }

    private void applyToSelected(Player player) {
        if (selectedZones.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No zones selected.");
            return;
        }

        String pattern = PATTERNS[patternIndex];
        int appliedCount = 0;

        for (StageZoneRole role : selectedZones) {
            String zoneName = stage.getZoneName(role);
            if (zoneName == null) continue;

            StageZoneConfig config = stage.getOrCreateConfig(role);
            config.setPattern(pattern);
            config.setRenderMode(pendingRenderMode);

            // Send pattern change via WebSocket
            if (plugin.getWebSocketServer() != null) {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "set_pattern");
                msg.addProperty("pattern", pattern);
                msg.addProperty("zone", zoneName);
                plugin.getWebSocketServer().broadcast(msg.toString());

                // Send zone config
                JsonObject configMsg = new JsonObject();
                configMsg.addProperty("type", "set_zone_config");
                configMsg.addProperty("zone", zoneName);
                JsonObject configObj = new JsonObject();
                configObj.addProperty("entity_count", config.getEntityCount());
                configObj.addProperty("block_type", config.getBlockType());
                configObj.addProperty("brightness", config.getBrightness());
                configMsg.add("config", configObj);
                plugin.getWebSocketServer().broadcast(configMsg.toString());

                // Send render mode
                JsonObject renderMsg = new JsonObject();
                renderMsg.addProperty("type", "set_render_mode");
                renderMsg.addProperty("zone", zoneName);
                renderMsg.addProperty("mode", pendingRenderMode);
                plugin.getWebSocketServer().broadcast(renderMsg.toString());
            }

            // Re-init entity pool with new count
            Material material = Material.matchMaterial(config.getBlockType());
            if (material == null) material = Material.SEA_LANTERN;
            plugin.getEntityPoolManager().initializeBlockPool(zoneName, config.getEntityCount(), material);

            appliedCount++;
        }

        plugin.getStageManager().saveStages();
        player.sendMessage(ChatColor.GREEN + "Applied to " + appliedCount + " zone(s): " +
            "pattern=" + pattern + ", render=" + pendingRenderMode);
    }

    private void applyPreset(String preset, Player player) {
        playClickSound(player);
        switch (preset) {
            case "chill" -> {
                pendingIntensity = 3;
                patternIndex = findPatternIndex("aurora");
                pendingRenderMode = "entities";
            }
            case "party" -> {
                pendingIntensity = 5;
                patternIndex = findPatternIndex("spectrum");
                pendingRenderMode = "entities";
            }
            case "rave" -> {
                pendingIntensity = 7;
                patternIndex = findPatternIndex("laser");
                pendingRenderMode = "hybrid";
            }
        }
        player.sendMessage(ChatColor.AQUA + "Preset '" + preset + "' loaded. Click Apply to activate.");
        menuManager.refreshMenu(player);
    }

    private void triggerEffect(Player player, String effect, boolean allZones) {
        playClickSound(player);
        Collection<StageZoneRole> targets = allZones ? stage.getActiveRoles() : selectedZones;

        for (StageZoneRole role : targets) {
            String zoneName = stage.getZoneName(role);
            if (zoneName == null) continue;

            if (plugin.getWebSocketServer() != null) {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "trigger_effect");
                msg.addProperty("zone", zoneName);
                msg.addProperty("effect", effect);
                plugin.getWebSocketServer().broadcast(msg.toString());
            }
        }

        player.sendMessage(ChatColor.LIGHT_PURPLE + "Triggered " + formatEffectName(effect) +
            " on " + targets.size() + " zone(s)");
    }

    private int findPatternIndex(String pattern) {
        for (int i = 0; i < PATTERNS.length; i++) {
            if (PATTERNS[i].equals(pattern)) return i;
        }
        return 0;
    }

    private String formatEffectName(String effect) {
        return effect.replace("_", " ").substring(0, 1).toUpperCase() +
            effect.replace("_", " ").substring(1);
    }

    private void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }
}
