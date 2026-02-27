package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.ModConfig;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Settings menu — view and adjust mod configuration.
 */
public class SettingsMenu extends AudioVizGui {

    private static final int SLOT_MAP_TOGGLE = 10;
    private static final int SLOT_BUNDLE_TOGGLE = 12;
    private static final int SLOT_MAX_ENTITIES = 14;
    private static final int SLOT_MAX_PIXELS = 16;
    private static final int SLOT_INFO = 22;
    private static final int SLOT_BACK = 27;

    private final AudioVizMod mod;

    public SettingsMenu(ServerPlayerEntity player, MenuManager menuManager, AudioVizMod mod) {
        super(ScreenHandlerType.GENERIC_9X4, player, menuManager);
        this.mod = mod;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Settings").formatted(Formatting.AQUA);
    }

    @Override
    protected void build() {
        ModConfig config = mod.getConfig();

        // Map renderer toggle
        boolean mapEnabled = config.useMapRenderer;
        setSlot(SLOT_MAP_TOGGLE, new GuiElementBuilder(mapEnabled ? Items.FILLED_MAP : Items.MAP)
            .setName(Text.literal("Map Renderer").formatted(mapEnabled ? Formatting.GREEN : Formatting.RED))
            .addLoreLine(Text.literal(mapEnabled ? "Enabled" : "Disabled").formatted(
                mapEnabled ? Formatting.GREEN : Formatting.RED))
            .addLoreLine(Text.literal("Bitmap patterns rendered to map items").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Click to toggle (requires restart)").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                config.useMapRenderer = !config.useMapRenderer;
                try { config.save(mod.getConfigDir()); } catch (java.io.IOException ex) {
                    com.audioviz.AudioVizMod.LOGGER.error("Failed to save config", ex);
                }
                rebuild();
            }));

        // Bundle packets toggle
        boolean bundleEnabled = config.useBundlePackets;
        setSlot(SLOT_BUNDLE_TOGGLE, new GuiElementBuilder(bundleEnabled ? Items.ENDER_PEARL : Items.SNOWBALL)
            .setName(Text.literal("Bundle Packets").formatted(bundleEnabled ? Formatting.GREEN : Formatting.RED))
            .addLoreLine(Text.literal(bundleEnabled ? "Enabled" : "Disabled").formatted(
                bundleEnabled ? Formatting.GREEN : Formatting.RED))
            .addLoreLine(Text.literal("Atomic frame updates (no tearing)").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Click to toggle").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                config.useBundlePackets = !config.useBundlePackets;
                try { config.save(mod.getConfigDir()); } catch (java.io.IOException ex) {
                    com.audioviz.AudioVizMod.LOGGER.error("Failed to save config", ex);
                }
                rebuild();
            }));

        // Max entities info
        setSlot(SLOT_MAX_ENTITIES, new GuiElementBuilder(Items.ARMOR_STAND)
            .setName(Text.literal("Max Entities/Zone: " + config.maxEntitiesPerZone)
                .formatted(Formatting.WHITE))
            .addLoreLine(Text.literal("3D pattern entity limit per zone").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Edit config file to change").formatted(Formatting.DARK_GRAY)));

        // Max pixels info
        setSlot(SLOT_MAX_PIXELS, new GuiElementBuilder(Items.PAINTING)
            .setName(Text.literal("Max Pixels/Zone: " + config.maxPixelsPerZone)
                .formatted(Formatting.WHITE))
            .addLoreLine(Text.literal("Bitmap display pixel limit per zone").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Edit config file to change").formatted(Formatting.DARK_GRAY)));

        // Connection info
        setSlot(SLOT_INFO, new GuiElementBuilder(Items.OAK_SIGN)
            .setName(Text.literal("Connection Info").formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("WS Port: " + config.websocketPort).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("DJ Port: " + config.djPort).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Default Backend: " + config.defaultRendererBackend)
                .formatted(Formatting.GRAY)));

        // Back button
        setSlot(SLOT_BACK, new GuiElementBuilder(Items.ARROW)
            .setName(Text.literal("Back").formatted(Formatting.WHITE))
            .setCallback((i, t, a) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new MainMenu(getPlayer(), menuManager, mod));
            }));

        fillBackground();
    }
}
