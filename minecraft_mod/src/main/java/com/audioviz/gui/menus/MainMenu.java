package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Main hub menu for AudioViz.
 * Provides access to zone management, settings, and system status.
 *
 * <p>Ported from Paper: Material/ItemStack → Items + GuiElementBuilder,
 * InventoryClickEvent → SGUI click callbacks, Sound → SoundEvents.
 */
public class MainMenu extends AudioVizGui {

    private static final int SLOT_ZONES = 10;
    private static final int SLOT_STAGES = 11;
    private static final int SLOT_DJ_PANEL = 13;
    private static final int SLOT_SETTINGS = 15;
    private static final int SLOT_STATUS = 16;
    private static final int SLOT_HELP = 31;

    private final AudioVizMod mod;

    public MainMenu(ServerPlayerEntity player, MenuManager menuManager, AudioVizMod mod) {
        super(ScreenHandlerType.GENERIC_9X4, player, menuManager);
        this.mod = mod;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("AudioViz Control Panel").formatted(Formatting.DARK_BLUE, Formatting.BOLD);
    }

    @Override
    protected void build() {
        // Zone Management
        int zoneCount = mod.getZoneManager().getZoneCount();
        setSlot(SLOT_ZONES, new GuiElementBuilder(Items.CHEST)
            .setName(Text.literal("Zone Management").formatted(Formatting.GOLD))
            .addLoreLine(Text.literal("Manage visualization zones").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Zones: " + zoneCount).formatted(Formatting.YELLOW))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to manage zones").formatted(Formatting.YELLOW))
            .setCallback((index, type, action) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new ZoneManagementMenu(getPlayer(), menuManager, mod));
            }));

        // Stage Management
        int stageCount = mod.getStageManager().getStageCount();
        setSlot(SLOT_STAGES, new GuiElementBuilder(Items.NETHER_STAR)
            .setName(Text.literal("Stages").formatted(Formatting.GOLD))
            .addLoreLine(Text.literal("Manage visualization stages").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Stages: " + stageCount).formatted(Formatting.YELLOW))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to manage stages").formatted(Formatting.YELLOW))
            .setCallback((index, type, action) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new StageListMenu(getPlayer(), menuManager, mod, () ->
                        menuManager.openMenu(getPlayer(), new MainMenu(getPlayer(), menuManager, mod))));
            }));

        // DJ Control Panel
        setSlot(SLOT_DJ_PANEL, new GuiElementBuilder(Items.JUKEBOX)
            .setName(Text.literal("DJ Control Panel").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("Live visualization controls").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Adjust effects, intensity,").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("and visual parameters.").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to open DJ panel").formatted(Formatting.YELLOW))
            .glow()
            .setCallback((index, type, action) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new DJControlMenu(getPlayer(), menuManager, mod));
            }));

        // Settings
        setSlot(SLOT_SETTINGS, new GuiElementBuilder(Items.COMPARATOR)
            .setName(Text.literal("Settings").formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("Configure mod settings").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Performance, defaults,").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("and WebSocket options.").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to open settings").formatted(Formatting.YELLOW))
            .setCallback((index, type, action) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new SettingsMenu(getPlayer(), menuManager, mod));
            }));

        // Status Display
        int connectionCount = mod.getWebSocketServer() != null
            ? mod.getWebSocketServer().getConnectionCount() : 0;
        boolean connected = connectionCount > 0;

        setSlot(SLOT_STATUS, new GuiElementBuilder(connected ? Items.EMERALD : Items.REDSTONE)
            .setName(Text.literal("System Status").formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("Current system information").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("WebSocket: " + (connected ? "Connected" : "Disconnected"))
                .formatted(connected ? Formatting.GREEN : Formatting.RED))
            .addLoreLine(Text.literal("Clients: " + connectionCount).formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("Active Zones: " + zoneCount).formatted(Formatting.YELLOW))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to refresh").formatted(Formatting.YELLOW))
            .setCallback((index, type, action) -> {
                getPlayer().playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                    0.5f, 1.2f);
                rebuild();
            }));

        // Help
        setSlot(SLOT_HELP, new GuiElementBuilder(Items.BOOK)
            .setName(Text.literal("Help & Info").formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("AudioViz Fabric Mod").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Real-time audio visualization").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Version: 1.0.0").formatted(Formatting.WHITE))
            .addLoreLine(Text.literal("API: Fabric 1.21.11").formatted(Formatting.WHITE))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Use /audioviz help for").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("command information.").formatted(Formatting.GRAY))
            .setCallback((index, type, action) -> {
                playClickSound();
                close();
            }));

        fillBackground();
    }
}
