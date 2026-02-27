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

import java.util.List;

/**
 * Zone management menu — list, inspect, and delete visualization zones.
 *
 * <p>Ported from Paper: InventoryClickEvent → SGUI callbacks,
 * Material/ItemStack → Items + GuiElementBuilder.
 */
public class ZoneManagementMenu extends AudioVizGui {

    private static final int SLOT_BACK = 27;

    private final AudioVizMod mod;

    public ZoneManagementMenu(ServerPlayerEntity player, MenuManager menuManager, AudioVizMod mod) {
        super(ScreenHandlerType.GENERIC_9X4, player, menuManager);
        this.mod = mod;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Zone Management").formatted(Formatting.DARK_BLUE, Formatting.BOLD);
    }

    @Override
    protected void build() {
        List<String> zoneNames = new java.util.ArrayList<>(mod.getZoneManager().getZoneNames());

        int slot = 0;
        for (String zoneName : zoneNames) {
            if (slot >= 27) break; // Max 3 rows of zones

            VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            String rendererType = mod.getMapRenderer().hasDisplay(zoneName) ? "MAP"
                : mod.getVirtualRenderer().hasPool(zoneName) ? "VIRTUAL_ENTITY" : "none";
            String activePattern = mod.getBitmapPatternManager() != null
                && mod.getBitmapPatternManager().isActive(zoneName)
                ? mod.getBitmapPatternManager().getActivePatternId(zoneName)
                : "none";

            setSlot(slot, new GuiElementBuilder(Items.ENDER_EYE)
                .setName(Text.literal(zoneName).formatted(Formatting.AQUA))
                .addLoreLine(Text.literal("Renderer: " + rendererType).formatted(Formatting.GRAY))
                .addLoreLine(Text.literal("Pattern: " + activePattern).formatted(Formatting.GRAY))
                .addLoreLine(Text.literal("Size: " + (int) zone.getSize().x + "x" + (int) zone.getSize().y
                    + "x" + (int) zone.getSize().z).formatted(Formatting.GRAY))
                .addLoreLine(Text.empty())
                .addLoreLine(Text.literal("Left-click: edit zone").formatted(Formatting.YELLOW))
                .addLoreLine(Text.literal("Right-click: teleport").formatted(Formatting.AQUA))
                .setCallback((index, type, action) -> {
                    playClickSound();
                    if (type == eu.pb4.sgui.api.ClickType.MOUSE_RIGHT) {
                        if (zone.getWorld() == null) {
                            getPlayer().sendMessage(net.minecraft.text.Text.literal("Zone world not loaded").formatted(net.minecraft.util.Formatting.RED));
                            return;
                        }
                        close();
                        getPlayer().teleport(
                            zone.getWorld(),
                            zone.getOrigin().getX() + 0.5,
                            (double) zone.getOrigin().getY(),
                            zone.getOrigin().getZ() + 0.5,
                            java.util.Set.of(),
                            getPlayer().getYaw(),
                            getPlayer().getPitch(),
                            false
                        );
                    } else {
                        menuManager.openMenu(getPlayer(),
                            new ZoneEditorMenu(getPlayer(), menuManager, mod, zoneName, () ->
                                menuManager.openMenu(getPlayer(),
                                    new ZoneManagementMenu(getPlayer(), menuManager, mod))));
                    }
                }));
            slot++;
        }

        if (zoneNames.isEmpty()) {
            setSlot(13, new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("No Zones").formatted(Formatting.RED))
                .addLoreLine(Text.literal("Use /audioviz zone create <name>").formatted(Formatting.GRAY))
                .addLoreLine(Text.literal("to create a zone.").formatted(Formatting.GRAY)));
        }

        // Row 3: Actions
        setSlot(SLOT_BACK, new GuiElementBuilder(Items.ARROW)
            .setName(Text.literal("Back").formatted(Formatting.WHITE))
            .setCallback((index, type, action) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new MainMenu(getPlayer(), menuManager, mod));
            }));

        setSlot(31, new GuiElementBuilder(Items.EMERALD)
            .setName(Text.literal("Create Zone").formatted(Formatting.GREEN, Formatting.BOLD))
            .addLoreLine(Text.literal("Choose a template or custom size").formatted(Formatting.GRAY))
            .glow()
            .setCallback((index, type, action) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new ZoneTemplateMenu(getPlayer(), menuManager, mod, () ->
                        menuManager.openMenu(getPlayer(),
                            new ZoneManagementMenu(getPlayer(), menuManager, mod))));
            }));

        setSlot(35, new GuiElementBuilder(Items.SUNFLOWER)
            .setName(Text.literal("Refresh").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> { playClickSound(); rebuild(); }));

        fillBackground();
    }
}
