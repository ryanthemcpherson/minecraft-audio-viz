package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class StageTemplateMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final Runnable onBack;

    public StageTemplateMenu(ServerPlayerEntity player, MenuManager menuManager,
                             AudioVizMod mod, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X5, player, menuManager);
        this.mod = mod;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("New Stage - Choose Template").formatted(Formatting.DARK_BLUE, Formatting.BOLD);
    }

    @Override
    protected void build() {
        setSlot(slot(0, 4), new GuiElementBuilder(Items.NETHER_STAR)
            .setName(Text.literal("Stage Templates").formatted(Formatting.GOLD, Formatting.BOLD))
            .addLoreLine(Text.literal("Choose a layout for your stage").formatted(Formatting.GRAY)));

        // Small stage
        setSlot(slot(1, 1), new GuiElementBuilder(Items.OAK_SIGN)
            .setName(Text.literal("Small Stage").formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("2 zones: Main Stage + Audience").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Compact 60% scale, good for small servers").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); promptCreate("small"); }));

        // Medium stage
        setSlot(slot(1, 3), new GuiElementBuilder(Items.OAK_HANGING_SIGN)
            .setName(Text.literal("Medium Stage").formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("5 zones: Stage, Wings, Audience, Skybox").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Recommended for most setups").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .glow()
            .setCallback((i, t, a) -> { playClickSound(); promptCreate("medium"); }));

        // Large stage
        setSlot(slot(1, 5), new GuiElementBuilder(Items.BEACON)
            .setName(Text.literal("Large Stage").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("All 8 zones, 150% scale").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Full venue with backstage & balcony").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); promptCreate("large"); }));

        // Custom
        setSlot(slot(1, 7), new GuiElementBuilder(Items.COMMAND_BLOCK)
            .setName(Text.literal("Custom").formatted(Formatting.GOLD))
            .addLoreLine(Text.literal("All 8 zones, default sizes").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Fully customize after creation").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); promptCreate("custom"); }));

        setBackButton(slot(4, 0), onBack);
        fillBackground();
    }

    private void promptCreate(String template) {
        promptTextInput("Stage Name", "", name -> {
            if (name.isEmpty()) {
                reopen();
                return;
            }
            if (mod.getStageManager().stageExists(name)) {
                getPlayer().sendMessage(Text.literal("Stage '" + name + "' already exists!").formatted(Formatting.RED));
                reopen();
                return;
            }
            // Open zone layout wizard instead of creating immediately
            menuManager.openMenu(getPlayer(),
                new StageZoneLayoutMenu(getPlayer(), menuManager, mod, name, template, onBack));
        }, this::reopen);
    }

    private void reopen() {
        menuManager.openMenu(getPlayer(), new StageTemplateMenu(getPlayer(), menuManager, mod, onBack));
    }
}
