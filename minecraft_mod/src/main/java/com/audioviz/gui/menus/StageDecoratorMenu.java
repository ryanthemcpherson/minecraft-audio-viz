package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.decorators.DecoratorConfig;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class StageDecoratorMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final String stageName;
    private final Runnable onBack;

    // Decorator definitions: id, display name, icon, description
    private static final Object[][] DECORATORS = {
        {"billboard",   "DJ Billboard",      Items.OAK_SIGN,        "DJ name above stage"},
        {"spotlight",   "Spotlights",        Items.SEA_LANTERN,     "Sweeping light beams"},
        {"floor_tiles", "Floor Tiles",       Items.PURPLE_CONCRETE, "Reactive dance floor"},
        {"crowd_fx",    "Crowd FX",          Items.FIREWORK_ROCKET, "Audience particles"},
        {"beat_text",   "Beat Text FX",      Items.WRITABLE_BOOK,   "Hype text on beats"},
        {"banner",      "DJ Banner",         Items.WHITE_BANNER,    "DJ branding display"},
        {"transition",  "DJ Transitions",    Items.ENDER_EYE,       "Blackout/flash on DJ switch"},
    };

    public StageDecoratorMenu(ServerPlayerEntity player, MenuManager menuManager,
                              AudioVizMod mod, String stageName, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X5, player, menuManager);
        this.mod = mod;
        this.stageName = stageName;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Decorators: " + stageName).formatted(Formatting.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        Stage stage = mod.getStageManager().getStage(stageName);
        if (stage == null) {
            setSlot(slot(2, 4), new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("Stage not found!").formatted(Formatting.RED)));
            setBackButton(slot(4, 0), onBack);
            fillBackground();
            return;
        }

        setSlot(slot(0, 4), new GuiElementBuilder(Items.FIREWORK_STAR)
            .setName(Text.literal("Stage Decorators").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
            .addLoreLine(Text.literal("Left: Toggle on/off").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Right: Edit config (key=value)").formatted(Formatting.GRAY)));

        // Row 1: decorators 0-3 (slots 10, 12, 14, 16)
        // Row 2: decorators 4-6 (slots 19, 21, 23)
        int[] slots = {slot(1, 1), slot(1, 3), slot(1, 5), slot(1, 7),
                       slot(2, 1), slot(2, 3), slot(2, 5)};

        for (int i = 0; i < DECORATORS.length; i++) {
            Object[] d = DECORATORS[i];
            String decoId = (String) d[0];
            String displayName = (String) d[1];
            Item icon = (Item) d[2];
            String desc = (String) d[3];

            DecoratorConfig config = stage.getDecoratorConfig(decoId);
            boolean enabled = config != null && config.isEnabled();

            var builder = new GuiElementBuilder(enabled ? icon : Items.GRAY_DYE)
                .setName(Text.literal(displayName).formatted(enabled ? Formatting.GREEN : Formatting.RED))
                .addLoreLine(Text.literal(enabled ? "Enabled" : "Disabled")
                    .formatted(enabled ? Formatting.GREEN : Formatting.RED))
                .addLoreLine(Text.literal(desc).formatted(Formatting.GRAY));

            if (config != null && !config.getSettings().isEmpty()) {
                builder.addLoreLine(Text.empty());
                for (var entry : config.getSettings().entrySet()) {
                    builder.addLoreLine(Text.literal("  " + entry.getKey() + "=" + entry.getValue())
                        .formatted(Formatting.DARK_GRAY));
                }
            }

            builder.addLoreLine(Text.empty())
                .addLoreLine(Text.literal("Left: Toggle | Right: Config").formatted(Formatting.YELLOW));

            if (enabled) builder.glow();

            builder.setCallback((idx, type, act) -> {
                playClickSound();
                if (type == ClickType.MOUSE_RIGHT) {
                    promptTextInput("Config (" + decoId + "): key=value", "", input -> {
                        if (input.contains("=")) {
                            String[] parts = input.split("=", 2);
                            DecoratorConfig cfg = stage.getDecoratorConfig(decoId);
                            if (cfg == null) { cfg = new DecoratorConfig(); stage.setDecoratorConfig(decoId, cfg); }
                            cfg.set(parts[0].trim(), parts[1].trim());
                            mod.getStageManager().saveStages();
                            getPlayer().sendMessage(Text.literal("Set " + parts[0].trim() + "=" + parts[1].trim()).formatted(Formatting.GREEN));
                        }
                        menuManager.openMenu(getPlayer(),
                            new StageDecoratorMenu(getPlayer(), menuManager, mod, stageName, onBack));
                    }, () -> menuManager.openMenu(getPlayer(),
                        new StageDecoratorMenu(getPlayer(), menuManager, mod, stageName, onBack)));
                } else {
                    DecoratorConfig cfg = stage.getDecoratorConfig(decoId);
                    if (cfg == null) { cfg = new DecoratorConfig(); stage.setDecoratorConfig(decoId, cfg); }
                    cfg.setEnabled(!enabled);
                    mod.getStageManager().saveStages();
                    rebuild();
                }
            });

            setSlot(slots[i], builder);
        }

        // Row 3: Bulk controls
        setSlot(slot(3, 2), new GuiElementBuilder(Items.LIME_DYE)
            .setName(Text.literal("Enable All").formatted(Formatting.GREEN))
            .setCallback((i, t, a) -> {
                playClickSound();
                for (Object[] d : DECORATORS) {
                    DecoratorConfig cfg = stage.getDecoratorConfig((String) d[0]);
                    if (cfg == null) { cfg = new DecoratorConfig(); stage.setDecoratorConfig((String) d[0], cfg); }
                    cfg.setEnabled(true);
                }
                mod.getStageManager().saveStages();
                rebuild();
            }));

        setSlot(slot(3, 6), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("Disable All").formatted(Formatting.RED))
            .setCallback((i, t, a) -> {
                playClickSound();
                for (Object[] d : DECORATORS) {
                    DecoratorConfig cfg = stage.getDecoratorConfig((String) d[0]);
                    if (cfg != null) cfg.setEnabled(false);
                }
                mod.getStageManager().saveStages();
                rebuild();
            }));

        setBackButton(slot(4, 0), onBack);
        fillBackground();
    }
}
