package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneConfig;
import com.audioviz.stages.StageZoneRole;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

public class StageVJMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final String stageName;
    private final Runnable onBack;

    private final Set<StageZoneRole> selectedZones = new HashSet<>();
    private int intensity = 4; // 1-7
    private int patternIndex = 0;

    private static final String[] EFFECT_NAMES = {
        "Blackout", "Freeze", "Flash", "Strobe", "Pulse", "Wave", "Spiral", "Explode"
    };
    private static final net.minecraft.item.Item[] EFFECT_ICONS = {
        Items.BLACK_WOOL, Items.PACKED_ICE, Items.GLOWSTONE, Items.REDSTONE_LAMP,
        Items.HEART_OF_THE_SEA, Items.PRISMARINE_SHARD, Items.NAUTILUS_SHELL, Items.TNT
    };

    public StageVJMenu(ServerPlayerEntity player, MenuManager menuManager,
                       AudioVizMod mod, String stageName, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X6, player, menuManager);
        this.mod = mod;
        this.stageName = stageName;
        this.onBack = onBack;
        // Select all zones by default
        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage != null) selectedZones.addAll(stage.getActiveRoles());
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("VJ: " + stageName).formatted(Formatting.GOLD, Formatting.BOLD);
    }

    @Override
    protected void build() {
        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage == null) {
            setSlot(slot(2, 4), new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("Stage not found!").formatted(Formatting.RED)));
            setBackButton(slot(5, 0), onBack);
            fillBackground();
            return;
        }

        // Row 0: Header
        setSlot(slot(0, 4), new GuiElementBuilder(Items.NOTE_BLOCK)
            .setName(Text.literal("VJ Control Panel").formatted(Formatting.GOLD, Formatting.BOLD))
            .addLoreLine(Text.literal("Selected: " + selectedZones.size() + " zones").formatted(Formatting.GRAY)));

        // Row 1: Zone selectors
        List<StageZoneRole> roles = new ArrayList<>(stage.getActiveRoles());
        roles.sort(Comparator.comparingInt(Enum::ordinal));

        for (int i = 0; i < Math.min(roles.size(), 8); i++) {
            StageZoneRole role = roles.get(i);
            boolean selected = selectedZones.contains(role);
            setSlot(slot(1, i), new GuiElementBuilder(selected ? Items.LIME_DYE : Items.GRAY_DYE)
                .setName(Text.literal(role.getDisplayName()).formatted(selected ? Formatting.GREEN : Formatting.GRAY))
                .addLoreLine(Text.literal(selected ? "Selected" : "Unselected")
                    .formatted(selected ? Formatting.GREEN : Formatting.RED))
                .setCallback((idx, type, act) -> {
                    playClickSound();
                    if (selectedZones.contains(role)) selectedZones.remove(role);
                    else selectedZones.add(role);
                    rebuild();
                }));
        }

        // Select All toggle
        boolean allSelected = selectedZones.containsAll(stage.getActiveRoles());
        setSlot(slot(1, 8), new GuiElementBuilder(allSelected ? Items.LIME_WOOL : Items.WHITE_WOOL)
            .setName(Text.literal(allSelected ? "Deselect All" : "Select All").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (allSelected) selectedZones.clear();
                else selectedZones.addAll(stage.getActiveRoles());
                rebuild();
            }));

        // Row 2: Intensity slider (7 levels)
        net.minecraft.item.Item[] woolColors = {
            Items.BLACK_WOOL, Items.GRAY_WOOL, Items.LIGHT_GRAY_WOOL, Items.WHITE_WOOL,
            Items.YELLOW_WOOL, Items.ORANGE_WOOL, Items.RED_WOOL
        };
        for (int level = 1; level <= 7; level++) {
            boolean active = level <= intensity;
            int finalLevel = level;
            setSlot(slot(2, level), new GuiElementBuilder(active ? woolColors[level - 1] : Items.BLACK_STAINED_GLASS_PANE)
                .setName(Text.literal("Intensity " + level).formatted(active ? Formatting.YELLOW : Formatting.DARK_GRAY))
                .setCallback((i, t, a) -> {
                    playClickSound();
                    intensity = finalLevel;
                    applyIntensity(stage);
                    rebuild();
                }));
        }

        // Row 3: Pattern controls
        List<String> patterns = mod.getBitmapPatternManager() != null
            ? mod.getBitmapPatternManager().getPatternIds() : List.of();
        if (!patterns.isEmpty() && patternIndex >= patterns.size()) patternIndex = 0;
        String currentPattern = patterns.isEmpty() ? "none" : patterns.get(patternIndex);

        setSlot(slot(3, 2), new GuiElementBuilder(Items.ARROW)
            .setName(Text.literal("\u2190 Prev Pattern").formatted(Formatting.WHITE))
            .setCallback((i, t, a) -> {
                playClickSound();
                patternIndex = (patternIndex - 1 + patterns.size()) % Math.max(1, patterns.size());
                rebuild();
            }));

        setSlot(slot(3, 4), new GuiElementBuilder(Items.PAINTING)
            .setName(Text.literal("Pattern: " + currentPattern).formatted(Formatting.AQUA, Formatting.BOLD))
            .addLoreLine(Text.literal("Click to apply to selected zones").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                applyPattern(stage, currentPattern);
                getPlayer().sendMessage(Text.literal("Applied '" + currentPattern + "' to " +
                    selectedZones.size() + " zones").formatted(Formatting.GREEN));
            }));

        setSlot(slot(3, 6), new GuiElementBuilder(Items.ARROW)
            .setName(Text.literal("Next Pattern \u2192").formatted(Formatting.WHITE))
            .setCallback((i, t, a) -> {
                playClickSound();
                patternIndex = (patternIndex + 1) % Math.max(1, patterns.size());
                rebuild();
            }));

        // Render mode toggle: switch between map (bitmap) and entity backends
        setSlot(slot(3, 8), new GuiElementBuilder(Items.FILLED_MAP)
            .setName(Text.literal("Toggle: Map \u2194 Entity").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("Switch render backend for selected zones").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Map = high-res, Entity = LED wall").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                toggleRenderBackend(stage);
            }));

        // Row 4: Effect triggers
        for (int i = 0; i < EFFECT_NAMES.length; i++) {
            String effectName = EFFECT_NAMES[i];
            setSlot(slot(4, i), new GuiElementBuilder(EFFECT_ICONS[i])
                .setName(Text.literal(effectName).formatted(Formatting.GOLD))
                .addLoreLine(Text.literal("Click to trigger").formatted(Formatting.YELLOW))
                .setCallback((idx, type, act) -> {
                    playClickSound();
                    triggerEffect(effectName.toLowerCase());
                }));
        }

        // Row 5: Back + presets
        setBackButton(slot(5, 0), onBack);

        setSlot(slot(5, 3), new GuiElementBuilder(Items.BLUE_DYE)
            .setName(Text.literal("Chill").formatted(Formatting.BLUE))
            .setCallback((i, t, a) -> { playClickSound(); intensity = 2; applyIntensity(stage); rebuild(); }));
        setSlot(slot(5, 4), new GuiElementBuilder(Items.YELLOW_DYE)
            .setName(Text.literal("Party").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); intensity = 5; applyIntensity(stage); rebuild(); }));
        setSlot(slot(5, 5), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("Rave").formatted(Formatting.RED))
            .setCallback((i, t, a) -> { playClickSound(); intensity = 7; applyIntensity(stage); rebuild(); }));

        // Apply button
        setSlot(slot(5, 8), new GuiElementBuilder(Items.LIME_CONCRETE)
            .setName(Text.literal("Apply All").formatted(Formatting.GREEN, Formatting.BOLD))
            .addLoreLine(Text.literal("Apply pattern + intensity to selected zones").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                applyIntensity(stage);
                applyPattern(stage, patterns.isEmpty() ? "none" : patterns.get(patternIndex));
                getPlayer().sendMessage(Text.literal("Applied settings to " +
                    selectedZones.size() + " zones").formatted(Formatting.GREEN));
            }));

        fillBackground();
    }

    private void toggleRenderBackend(Stage stage) {
        for (StageZoneRole role : selectedZones) {
            String zoneName = stage.getZoneName(role);
            if (zoneName == null) continue;

            StageZoneConfig config = stage.getOrCreateConfig(role);
            boolean isEntity = "entity".equalsIgnoreCase(config.getRenderMode());
            String newMode = isEntity ? "map" : "entity";
            config.setRenderMode(newMode);

            // Re-initialize the appropriate renderer for this zone
            var vizZone = mod.getZoneManager().getZone(zoneName);
            if (vizZone != null && vizZone.getWorld() != null) {
                var bpm = mod.getBitmapPatternManager();
                int width = bpm != null && bpm.getFrameBuffer(zoneName) != null
                    ? bpm.getFrameBuffer(zoneName).getWidth() : 16;
                int height = bpm != null && bpm.getFrameBuffer(zoneName) != null
                    ? bpm.getFrameBuffer(zoneName).getHeight() : 16;

                net.minecraft.util.math.Direction facing =
                    com.audioviz.protocol.MessageHandler.directionFromRotation(vizZone.getRotation());

                if ("entity".equals(newMode)) {
                    mod.getMapRenderer().destroyDisplay(zoneName);
                    mod.getBitmapToEntityBridge().initializeWall(zoneName, vizZone, width, height,
                        vizZone.getWorld(), facing);
                } else {
                    mod.getBitmapToEntityBridge().destroyWall(zoneName);
                    mod.getMapRenderer().initializeDisplay(zoneName, vizZone, width, height,
                        vizZone.getWorld(), facing);
                }
            }
        }
        mod.getStageManager().saveStages();
        getPlayer().sendMessage(Text.literal("Toggled render mode for " +
            selectedZones.size() + " zones").formatted(Formatting.LIGHT_PURPLE));
        rebuild();
    }

    private void applyIntensity(Stage stage) {
        float multiplier = intensity / 4.0f; // range 0.25 to 1.75
        for (StageZoneRole role : selectedZones) {
            StageZoneConfig config = stage.getOrCreateConfig(role);
            config.setIntensityMultiplier(multiplier);
        }
        mod.getStageManager().saveStages();
    }

    private void applyPattern(Stage stage, String patternId) {
        var bpm = mod.getBitmapPatternManager();
        for (StageZoneRole role : selectedZones) {
            String zoneName = stage.getZoneName(role);
            if (zoneName != null && bpm != null && bpm.isActive(zoneName)) {
                bpm.setPattern(zoneName, patternId);
            }
            StageZoneConfig config = stage.getOrCreateConfig(role);
            config.setPattern(patternId);
        }
        mod.getStageManager().saveStages();
    }

    private void triggerEffect(String effect) {
        var bpm = mod.getBitmapPatternManager();
        var effects = bpm != null ? bpm.getEffectsProcessor() : null;
        if (effects == null) return;

        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage == null) return;

        switch (effect) {
            case "blackout" -> effects.blackout(effects.getBrightness() != 0.0);
            case "freeze" -> {
                if (effects.isFrozen()) {
                    effects.unfreeze();
                } else {
                    // Freeze using the first selected zone's frame buffer
                    for (StageZoneRole role : selectedZones) {
                        String zoneName = stage.getZoneName(role);
                        if (zoneName != null) {
                            var buf = bpm.getFrameBuffer(zoneName);
                            if (buf != null) {
                                effects.freeze(buf);
                                break;
                            }
                        }
                    }
                }
            }
            case "flash" -> effects.setBeatFlashEnabled(true);
            case "strobe" -> effects.setStrobeEnabled(!effects.isStrobeEnabled());
            default -> getPlayer().sendMessage(
                Text.literal("Effect '" + effect + "' triggered").formatted(Formatting.YELLOW));
        }
    }
}
