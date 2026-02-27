package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.zones.VisualizationZone;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public class ZoneTemplateMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final Runnable onBack;

    /** Preset templates: name, sizeX, sizeY, sizeZ, entityCount, item */
    private static final Object[][] TEMPLATES = {
        {"Tiny (4x4x4)",       4, 4, 4,    16, Items.FLOWER_POT},
        {"Small (8x8x8)",      8, 8, 8,    32, Items.CHEST},
        {"Medium (16x12x16)", 16, 12, 16,  64, Items.CRAFTING_TABLE},
        {"Large (24x16x24)",  24, 16, 24, 128, Items.BEACON},
        {"Flat (16x1x16)",    16, 1, 16,   64, Items.WHITE_CARPET},
        {"Tower (4x24x4)",     4, 24, 4,   48, Items.LIGHTNING_ROD},
    };

    public ZoneTemplateMenu(ServerPlayerEntity player, MenuManager menuManager,
                            AudioVizMod mod, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X5, player, menuManager);
        this.mod = mod;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Zone Templates").formatted(Formatting.DARK_BLUE, Formatting.BOLD);
    }

    @Override
    protected void build() {
        // Header
        setSlot(slot(0, 4), new GuiElementBuilder(Items.ENDER_EYE)
            .setName(Text.literal("Choose Zone Size").formatted(Formatting.GOLD))
            .addLoreLine(Text.literal("Select a preset or custom size").formatted(Formatting.GRAY)));

        // Templates in rows 1-2 (slots 10,12,14 and 19,21,23)
        int[] slots = {slot(1, 1), slot(1, 3), slot(1, 5), slot(2, 1), slot(2, 3), slot(2, 5)};
        for (int i = 0; i < TEMPLATES.length; i++) {
            Object[] t = TEMPLATES[i];
            String name = (String) t[0];
            int sx = (int) t[1], sy = (int) t[2], sz = (int) t[3];
            int entities = (int) t[4];
            net.minecraft.item.Item icon = (net.minecraft.item.Item) t[5];

            setSlot(slots[i], new GuiElementBuilder(icon)
                .setName(Text.literal(name).formatted(Formatting.AQUA))
                .addLoreLine(Text.literal("Size: " + sx + "x" + sy + "x" + sz).formatted(Formatting.GRAY))
                .addLoreLine(Text.literal("Entities: " + entities).formatted(Formatting.GRAY))
                .addLoreLine(Text.empty())
                .addLoreLine(Text.literal("Click to create zone").formatted(Formatting.YELLOW))
                .setCallback((idx, type, action) -> {
                    playClickSound();
                    promptCreateZone(sx, sy, sz);
                }));
        }

        // Custom option
        setSlot(slot(3, 4), new GuiElementBuilder(Items.ANVIL)
            .setName(Text.literal("Custom Size").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("Create with default 10x10x10").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Edit size in Zone Editor after").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create").formatted(Formatting.YELLOW))
            .setCallback((idx, type, action) -> {
                playClickSound();
                promptCreateZone(10, 10, 10);
            }));

        // Back
        setBackButton(slot(4, 0), onBack);
        fillBackground();
    }

    private void promptCreateZone(int sizeX, int sizeY, int sizeZ) {
        promptTextInput("Zone Name", "", name -> {
            if (name.isEmpty()) {
                menuManager.openMenu(getPlayer(), new ZoneTemplateMenu(getPlayer(), menuManager, mod, onBack));
                return;
            }
            if (mod.getZoneManager().zoneExists(name)) {
                getPlayer().sendMessage(Text.literal("Zone '" + name + "' already exists!").formatted(Formatting.RED));
                menuManager.openMenu(getPlayer(), new ZoneTemplateMenu(getPlayer(), menuManager, mod, onBack));
                return;
            }
            BlockPos pos = getPlayer().getBlockPos();
            VisualizationZone zone = mod.getZoneManager().createZone(name, getPlayer().getEntityWorld(), pos);
            if (zone != null) {
                zone.setSize(sizeX, sizeY, sizeZ);
                mod.getZoneManager().saveZones();
                getPlayer().sendMessage(Text.literal("Created zone '" + name + "' (" + sizeX + "x" + sizeY + "x" + sizeZ + ")")
                    .formatted(Formatting.GREEN));
                // Open the zone editor for the new zone
                menuManager.openMenu(getPlayer(),
                    new ZoneEditorMenu(getPlayer(), menuManager, mod, name, onBack));
            }
        }, () -> menuManager.openMenu(getPlayer(), new ZoneTemplateMenu(getPlayer(), menuManager, mod, onBack)));
    }
}
