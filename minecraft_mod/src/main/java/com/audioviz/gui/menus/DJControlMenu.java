package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * DJ control panel — live effects, brightness, and pattern controls.
 *
 * <p>Ported from Paper DJControlPanel. Uses SGUI click callbacks
 * to toggle effects in real-time.
 */
public class DJControlMenu extends AudioVizGui {

    private static final int SLOT_STROBE = 10;
    private static final int SLOT_FREEZE = 11;
    private static final int SLOT_BLACKOUT = 12;
    private static final int SLOT_BEAT_FLASH = 13;
    private static final int SLOT_RGB_SPLIT = 14;
    private static final int SLOT_BIT_CRUSH = 15;
    private static final int SLOT_EDGE_FLASH = 16;
    private static final int SLOT_BRIGHTNESS_UP = 19;
    private static final int SLOT_BRIGHTNESS_DOWN = 20;
    private static final int SLOT_BRIGHTNESS_DISPLAY = 22;
    private static final int SLOT_BACK = 35;

    private final AudioVizMod mod;

    public DJControlMenu(ServerPlayerEntity player, MenuManager menuManager, AudioVizMod mod) {
        super(ScreenHandlerType.GENERIC_9X4, player, menuManager);
        this.mod = mod;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("DJ Control Panel").formatted(Formatting.DARK_BLUE, Formatting.BOLD);
    }

    @Override
    protected void build() {
        EffectsProcessor effects = mod.getBitmapPatternManager() != null
            ? mod.getBitmapPatternManager().getEffectsProcessor() : null;

        boolean strobeOn = effects != null && effects.isStrobeEnabled();
        boolean frozenOn = effects != null && effects.isFrozen();
        boolean rgbOn = effects != null && effects.isRgbSplitEnabled();
        boolean bitCrushOn = effects != null && effects.isBitCrushEnabled();
        boolean edgeFlashOn = effects != null && effects.isEdgeFlashEnabled();
        double brightness = effects != null ? effects.getBrightness() : 1.0;
        boolean blackout = brightness < 0.01;

        // Strobe toggle
        setSlot(SLOT_STROBE, new GuiElementBuilder(strobeOn ? Items.GLOWSTONE : Items.COAL)
            .setName(Text.literal("Strobe").formatted(strobeOn ? Formatting.YELLOW : Formatting.GRAY))
            .addLoreLine(Text.literal(strobeOn ? "ON" : "OFF").formatted(
                strobeOn ? Formatting.GREEN : Formatting.RED))
            .addLoreLine(Text.literal("Click to toggle").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (effects != null) effects.setStrobeEnabled(!strobeOn);
                rebuild();
            }));

        // Freeze toggle
        setSlot(SLOT_FREEZE, new GuiElementBuilder(frozenOn ? Items.PACKED_ICE : Items.ICE)
            .setName(Text.literal("Freeze Frame").formatted(frozenOn ? Formatting.AQUA : Formatting.GRAY))
            .addLoreLine(Text.literal(frozenOn ? "FROZEN" : "LIVE").formatted(
                frozenOn ? Formatting.AQUA : Formatting.GREEN))
            .addLoreLine(Text.literal("Click to toggle").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (effects != null) {
                    if (frozenOn) {
                        effects.unfreeze();
                    } else if (mod.getBitmapPatternManager() != null) {
                        // Find the first active zone's frame buffer
                        var bpm = mod.getBitmapPatternManager();
                        for (String zoneName : mod.getMapRenderer().getActiveZones()) {
                            var buf = bpm.getFrameBuffer(zoneName);
                            if (buf != null) {
                                effects.freeze(buf);
                                break;
                            }
                        }
                    }
                }
                rebuild();
            }));

        // Blackout toggle
        setSlot(SLOT_BLACKOUT, new GuiElementBuilder(blackout ? Items.BLACK_WOOL : Items.WHITE_WOOL)
            .setName(Text.literal("Blackout").formatted(blackout ? Formatting.DARK_GRAY : Formatting.WHITE))
            .addLoreLine(Text.literal(blackout ? "BLACKOUT" : "NORMAL").formatted(
                blackout ? Formatting.RED : Formatting.GREEN))
            .addLoreLine(Text.literal("Click to toggle").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (effects != null) effects.blackout(!blackout);
                rebuild();
            }));

        // Beat Flash
        boolean beatFlashOn = effects != null && effects.isBeatFlashEnabled();
        setSlot(SLOT_BEAT_FLASH, new GuiElementBuilder(beatFlashOn ? Items.TORCH : Items.SOUL_TORCH)
            .setName(Text.literal("Beat Flash").formatted(beatFlashOn ? Formatting.GOLD : Formatting.GRAY))
            .addLoreLine(Text.literal(beatFlashOn ? "ON" : "OFF").formatted(
                beatFlashOn ? Formatting.GREEN : Formatting.RED))
            .addLoreLine(Text.literal("Click to toggle").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (effects != null) effects.setBeatFlashEnabled(!beatFlashOn);
                rebuild();
            }));

        // RGB Split
        setSlot(SLOT_RGB_SPLIT, new GuiElementBuilder(rgbOn ? Items.PRISMARINE_SHARD : Items.FLINT)
            .setName(Text.literal("RGB Split").formatted(rgbOn ? Formatting.LIGHT_PURPLE : Formatting.GRAY))
            .addLoreLine(Text.literal(rgbOn ? "ON" : "OFF").formatted(
                rgbOn ? Formatting.GREEN : Formatting.RED))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (effects != null) effects.setRgbSplitEnabled(!rgbOn);
                rebuild();
            }));

        // Bit Crush
        setSlot(SLOT_BIT_CRUSH, new GuiElementBuilder(bitCrushOn ? Items.MOSSY_COBBLESTONE : Items.COBBLESTONE)
            .setName(Text.literal("Bit Crush").formatted(bitCrushOn ? Formatting.DARK_GREEN : Formatting.GRAY))
            .addLoreLine(Text.literal(bitCrushOn ? "ON" : "OFF").formatted(
                bitCrushOn ? Formatting.GREEN : Formatting.RED))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (effects != null) effects.setBitCrushEnabled(!bitCrushOn);
                rebuild();
            }));

        // Edge Flash
        setSlot(SLOT_EDGE_FLASH, new GuiElementBuilder(edgeFlashOn ? Items.SEA_LANTERN : Items.GLASS)
            .setName(Text.literal("Edge Flash").formatted(edgeFlashOn ? Formatting.BLUE : Formatting.GRAY))
            .addLoreLine(Text.literal(edgeFlashOn ? "ON" : "OFF").formatted(
                edgeFlashOn ? Formatting.GREEN : Formatting.RED))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (effects != null) effects.setEdgeFlashEnabled(!edgeFlashOn);
                rebuild();
            }));

        // Brightness controls
        int pct = (int) (brightness * 100);
        setSlot(SLOT_BRIGHTNESS_UP, new GuiElementBuilder(Items.LIME_DYE)
            .setName(Text.literal("Brightness +10%").formatted(Formatting.GREEN))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (effects != null) effects.setBrightness(Math.min(1.0, brightness + 0.1));
                rebuild();
            }));

        setSlot(SLOT_BRIGHTNESS_DOWN, new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("Brightness -10%").formatted(Formatting.RED))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (effects != null) effects.setBrightness(Math.max(0.0, brightness - 0.1));
                rebuild();
            }));

        setSlot(SLOT_BRIGHTNESS_DISPLAY, new GuiElementBuilder(Items.SUNFLOWER)
            .setName(Text.literal("Brightness: " + pct + "%").formatted(Formatting.YELLOW)));

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
