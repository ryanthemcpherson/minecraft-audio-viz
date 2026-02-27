package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public class StageEditorMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final String stageName;
    private final Runnable onBack;

    // Spatial grid: map each role to a fixed slot
    private static final Map<StageZoneRole, Integer> ROLE_SLOTS = Map.of(
        StageZoneRole.LEFT_WING,  slot(1, 1),
        StageZoneRole.MAIN_STAGE, slot(1, 4),
        StageZoneRole.RIGHT_WING, slot(1, 7),
        StageZoneRole.BACKSTAGE,  slot(2, 1),
        StageZoneRole.RUNWAY,     slot(2, 4),
        StageZoneRole.AUDIENCE,   slot(2, 7),
        StageZoneRole.SKYBOX,     slot(3, 1),
        StageZoneRole.BALCONY,    slot(3, 7)
    );

    private static final Map<StageZoneRole, Formatting> ROLE_COLORS = Map.of(
        StageZoneRole.MAIN_STAGE, Formatting.GOLD,
        StageZoneRole.LEFT_WING,  Formatting.BLUE,
        StageZoneRole.RIGHT_WING, Formatting.BLUE,
        StageZoneRole.SKYBOX,     Formatting.AQUA,
        StageZoneRole.AUDIENCE,   Formatting.GREEN,
        StageZoneRole.BACKSTAGE,  Formatting.GRAY,
        StageZoneRole.RUNWAY,     Formatting.LIGHT_PURPLE,
        StageZoneRole.BALCONY,    Formatting.YELLOW
    );

    public StageEditorMenu(ServerPlayerEntity player, MenuManager menuManager,
                           AudioVizMod mod, String stageName, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X6, player, menuManager);
        this.mod = mod;
        this.stageName = stageName;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Stage: " + stageName).formatted(Formatting.GOLD);
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
        boolean active = stage.isActive();
        setSlot(slot(0, 4), new GuiElementBuilder(active ? Items.NETHER_STAR : Items.COAL)
            .setName(Text.literal(stageName).formatted(Formatting.GOLD, Formatting.BOLD))
            .addLoreLine(Text.literal("Template: " + stage.getTemplateName()).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Anchor: " + stage.getAnchor().getX() + ", " +
                stage.getAnchor().getY() + ", " + stage.getAnchor().getZ()).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Rotation: " + (int) stage.getRotation() + "\u00B0").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Zones: " + stage.getRoleToZone().size() + "/8").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to toggle " + (active ? "OFF" : "ON"))
                .formatted(active ? Formatting.RED : Formatting.GREEN))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (active) mod.getStageManager().deactivateStage(stage);
                else mod.getStageManager().activateStage(stage);
                rebuild();
            }));

        // Rows 1-3: Spatial zone grid
        for (StageZoneRole role : StageZoneRole.values()) {
            Integer slotPos = ROLE_SLOTS.get(role);
            if (slotPos == null) continue;

            String zoneName = stage.getZoneName(role);
            Formatting color = ROLE_COLORS.getOrDefault(role, Formatting.WHITE);

            if (zoneName != null) {
                VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
                var builder = new GuiElementBuilder(Items.LIME_WOOL)
                    .setName(Text.literal(role.getDisplayName()).formatted(color, Formatting.BOLD))
                    .addLoreLine(Text.literal("Zone: " + zoneName).formatted(Formatting.GRAY));

                if (zone != null) {
                    builder.addLoreLine(Text.literal("Size: " + (int) zone.getSize().x + "x" +
                        (int) zone.getSize().y + "x" + (int) zone.getSize().z).formatted(Formatting.GRAY));
                }
                builder.addLoreLine(Text.empty())
                    .addLoreLine(Text.literal("Left: Edit zone").formatted(Formatting.YELLOW))
                    .addLoreLine(Text.literal("Right: Unassign role").formatted(Formatting.RED));

                String finalZoneName = zoneName;
                builder.setCallback((i, type, act) -> {
                    playClickSound();
                    if (type == ClickType.MOUSE_RIGHT) {
                        stage.getRoleToZone().remove(role);
                        mod.getStageManager().saveStages();
                        rebuild();
                    } else {
                        menuManager.openMenu(getPlayer(),
                            new ZoneEditorMenu(getPlayer(), menuManager, mod, finalZoneName, () ->
                                menuManager.openMenu(getPlayer(),
                                    new StageEditorMenu(getPlayer(), menuManager, mod, stageName, onBack))));
                    }
                });
                setSlot(slotPos, builder);
            } else {
                setSlot(slotPos, new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE)
                    .setName(Text.literal(role.getDisplayName()).formatted(Formatting.DARK_GRAY))
                    .addLoreLine(Text.literal("No zone assigned").formatted(Formatting.GRAY))
                    .addLoreLine(Text.literal("Click to assign").formatted(Formatting.YELLOW))
                    .setCallback((i, t, a) -> {
                        playClickSound();
                        // Auto-create a zone for this role
                        String autoName = stageName.toLowerCase() + "_" + role.name().toLowerCase();
                        if (!mod.getZoneManager().zoneExists(autoName)) {
                            var worldPos = stage.getWorldPositionForRole(role);
                            BlockPos zoneOrigin = new BlockPos((int) worldPos.x, (int) worldPos.y, (int) worldPos.z);
                            var world = findWorld(stage.getWorldName());
                            if (world != null) {
                                var zone = mod.getZoneManager().createZone(autoName, world, zoneOrigin);
                                if (zone != null) {
                                    var defaultSize = role.getDefaultSize();
                                    zone.setSize(defaultSize.x, defaultSize.y, defaultSize.z);
                                    mod.getZoneManager().saveZones();
                                }
                            }
                        }
                        stage.getRoleToZone().put(role, autoName);
                        mod.getStageManager().saveStages();
                        rebuild();
                    }));
            }
        }

        // Row 4: separator (just background)

        // Row 5: Actions
        setBackButton(slot(5, 0), onBack);

        // Move to player
        setSlot(slot(5, 1), new GuiElementBuilder(Items.COMPASS)
            .setName(Text.literal("Move Here").formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("Set anchor to your position").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                stage.setAnchor(getPlayer().getBlockPos());
                stage.setWorldName(getPlayer().getEntityWorld().getRegistryKey().getValue().toString());
                mod.getStageManager().saveStages();
                getPlayer().sendMessage(Text.literal("Stage moved to your position").formatted(Formatting.GREEN));
                rebuild();
            }));

        // Rotate -15°
        setSlot(slot(5, 2), new GuiElementBuilder(Items.RECOVERY_COMPASS)
            .setName(Text.literal("Rotate -15\u00B0").formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("Current: " + (int) stage.getRotation() + "\u00B0").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                stage.setRotation(stage.getRotation() - 15);
                mod.getStageManager().saveStages();
                rebuild();
            }));

        // Rotate +15°
        setSlot(slot(5, 3), new GuiElementBuilder(Items.COMPASS)
            .setName(Text.literal("Rotate +15\u00B0").formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("Current: " + (int) stage.getRotation() + "\u00B0").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                stage.setRotation(stage.getRotation() + 15);
                mod.getStageManager().saveStages();
                rebuild();
            }));

        // Decorators
        setSlot(slot(5, 5), new GuiElementBuilder(Items.FIREWORK_ROCKET)
            .setName(Text.literal("Decorators").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("Billboard, spotlights, effects...").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new StageDecoratorMenu(getPlayer(), menuManager, mod, stageName, () ->
                        menuManager.openMenu(getPlayer(),
                            new StageEditorMenu(getPlayer(), menuManager, mod, stageName, onBack))));
            }));

        // VJ Control
        setSlot(slot(5, 6), new GuiElementBuilder(Items.NOTE_BLOCK)
            .setName(Text.literal("VJ Control").formatted(Formatting.GOLD))
            .addLoreLine(Text.literal("Patterns, intensity, effects").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                menuManager.openMenu(getPlayer(),
                    new StageVJMenu(getPlayer(), menuManager, mod, stageName, () ->
                        menuManager.openMenu(getPlayer(),
                            new StageEditorMenu(getPlayer(), menuManager, mod, stageName, onBack))));
            }));

        // Delete
        setSlot(slot(5, 8), new GuiElementBuilder(Items.BARRIER)
            .setName(Text.literal("Delete Stage").formatted(Formatting.RED, Formatting.BOLD))
            .addLoreLine(Text.literal("Shift-click to confirm").formatted(Formatting.DARK_RED))
            .addLoreLine(Text.literal("Also deletes all zones!").formatted(Formatting.DARK_RED))
            .setCallback((i, type, a) -> {
                if (type == ClickType.MOUSE_LEFT_SHIFT || type == ClickType.MOUSE_RIGHT_SHIFT) {
                    playClickSound();
                    mod.getStageManager().deleteStage(stageName);
                    getPlayer().sendMessage(Text.literal("Deleted stage '" + stageName + "'").formatted(Formatting.RED));
                    onBack.run();
                }
            }));

        fillBackground();
    }

    private net.minecraft.server.world.ServerWorld findWorld(String worldName) {
        for (var w : mod.getServer().getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldName)) return w;
        }
        // Fallback to overworld only
        return mod.getServer().getOverworld();
    }
}
