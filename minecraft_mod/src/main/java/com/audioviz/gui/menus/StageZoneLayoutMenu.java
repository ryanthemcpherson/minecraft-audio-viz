package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageManager;
import com.audioviz.stages.StageZoneRole;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;

import java.util.*;

/**
 * Intermediate step in the stage creation wizard.
 * Lets the user configure zone layout before creating the stage:
 * - Toggle zones on/off
 * - Adjust overall scale
 * - Set facing direction (rotation)
 * - Preview zone sizes in the spatial grid
 */
public class StageZoneLayoutMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final String stageName;
    private final String templateName;
    private final Runnable onBack;

    // Mutable layout state
    private final Set<StageZoneRole> enabledRoles;
    private float scale;
    private float rotation;

    // Spatial grid: same layout as StageEditorMenu
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

    public StageZoneLayoutMenu(ServerPlayerEntity player, MenuManager menuManager,
                                AudioVizMod mod, String stageName, String templateName,
                                Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X6, player, menuManager);
        this.mod = mod;
        this.stageName = stageName;
        this.templateName = templateName;
        this.onBack = onBack;

        // Initialize from template defaults
        this.enabledRoles = EnumSet.copyOf(StageManager.getTemplateRolesPublic(templateName));
        this.scale = StageManager.getTemplateScalePublic(templateName);
        this.rotation = 0f;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Zone Layout: " + stageName).formatted(Formatting.DARK_BLUE, Formatting.BOLD);
    }

    @Override
    protected void build() {
        // Row 0: Header
        setSlot(slot(0, 4), new GuiElementBuilder(Items.NETHER_STAR)
            .setName(Text.literal(stageName).formatted(Formatting.GOLD, Formatting.BOLD))
            .addLoreLine(Text.literal("Template: " + templateName).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Zones: " + enabledRoles.size() + "/8").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Scale: " + fmt(scale) + "x").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Configure your stage layout below").formatted(Formatting.YELLOW)));

        // Rows 1-3: Spatial zone grid
        for (StageZoneRole role : StageZoneRole.values()) {
            Integer slotPos = ROLE_SLOTS.get(role);
            if (slotPos == null) continue;

            boolean enabled = enabledRoles.contains(role);
            Formatting color = ROLE_COLORS.getOrDefault(role, Formatting.WHITE);
            Vector3f size = role.getDefaultSize();
            float scaledX = size.x * scale;
            float scaledY = size.y * scale;
            float scaledZ = size.z * scale;

            var builder = new GuiElementBuilder(enabled ? Items.LIME_WOOL : Items.RED_WOOL)
                .setName(Text.literal(role.getDisplayName()).formatted(color, Formatting.BOLD))
                .addLoreLine(Text.literal(enabled ? "ENABLED" : "DISABLED")
                    .formatted(enabled ? Formatting.GREEN : Formatting.RED))
                .addLoreLine(Text.empty())
                .addLoreLine(Text.literal("Size: " + fmt(scaledX) + "x" + fmt(scaledY) + "x" + fmt(scaledZ))
                    .formatted(Formatting.GRAY))
                .addLoreLine(Text.literal("Pattern: " + role.getSuggestedPattern()).formatted(Formatting.GRAY))
                .addLoreLine(Text.literal("Offset: " + fmtOffset(role.getDefaultOffset(), scale)).formatted(Formatting.DARK_GRAY))
                .addLoreLine(Text.empty())
                .addLoreLine(Text.literal("Click to " + (enabled ? "disable" : "enable")).formatted(Formatting.YELLOW));

            builder.setCallback((i, type, act) -> {
                playClickSound();
                if (enabled) {
                    // Don't allow disabling the last zone
                    if (enabledRoles.size() <= 1) {
                        getPlayer().sendMessage(Text.literal("Must have at least one zone!")
                            .formatted(Formatting.RED));
                        return;
                    }
                    enabledRoles.remove(role);
                } else {
                    enabledRoles.add(role);
                }
                rebuild();
            });

            setSlot(slotPos, builder);
        }

        // Row 4: Scale and rotation controls
        // Scale -
        setSlot(slot(4, 1), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("-0.1x Scale").formatted(Formatting.RED))
            .addLoreLine(Text.literal("Shift: -0.5x").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = type == ClickType.MOUSE_LEFT_SHIFT ? 0.5f : 0.1f;
                scale = Math.max(0.3f, scale - step);
                rebuild();
            }));

        // Scale display
        setSlot(slot(4, 2), new GuiElementBuilder(Items.SPYGLASS)
            .setName(Text.literal("Scale: " + fmt(scale) + "x").formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("Controls overall zone sizes").formatted(Formatting.GRAY)));

        // Scale +
        setSlot(slot(4, 3), new GuiElementBuilder(Items.LIME_DYE)
            .setName(Text.literal("+0.1x Scale").formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("Shift: +0.5x").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = type == ClickType.MOUSE_LEFT_SHIFT ? 0.5f : 0.1f;
                scale = Math.min(3.0f, scale + step);
                rebuild();
            }));

        // Rotation -
        setSlot(slot(4, 5), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("-15\u00B0").formatted(Formatting.RED))
            .addLoreLine(Text.literal("Shift: -45\u00B0").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = type == ClickType.MOUSE_LEFT_SHIFT ? 45 : 15;
                rotation = (rotation - step) % 360;
                rebuild();
            }));

        // Rotation display
        setSlot(slot(4, 6), new GuiElementBuilder(Items.COMPASS)
            .setName(Text.literal("Facing: " + fmt(rotation) + "\u00B0").formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("Stage rotation").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("0\u00B0 = default orientation").formatted(Formatting.DARK_GRAY)));

        // Rotation +
        setSlot(slot(4, 7), new GuiElementBuilder(Items.LIME_DYE)
            .setName(Text.literal("+15\u00B0").formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("Shift: +45\u00B0").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = type == ClickType.MOUSE_LEFT_SHIFT ? 45 : 15;
                rotation = (rotation + step) % 360;
                rebuild();
            }));

        // Row 5: Back and Create
        setBackButton(slot(5, 0), onBack);

        // Quick presets
        setSlot(slot(5, 3), new GuiElementBuilder(Items.PAINTING)
            .setName(Text.literal("Reset to Template").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Restore default " + templateName + " layout").formatted(Formatting.DARK_GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                enabledRoles.clear();
                enabledRoles.addAll(StageManager.getTemplateRolesPublic(templateName));
                scale = StageManager.getTemplateScalePublic(templateName);
                rotation = 0f;
                rebuild();
            }));

        // All zones toggle
        setSlot(slot(5, 5), new GuiElementBuilder(Items.WRITABLE_BOOK)
            .setName(Text.literal(enabledRoles.size() == 8 ? "Disable Optional" : "Enable All")
                .formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("Toggle all zones on/off").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                if (enabledRoles.size() == StageZoneRole.values().length) {
                    // Reset to template defaults
                    enabledRoles.clear();
                    enabledRoles.addAll(StageManager.getTemplateRolesPublic(templateName));
                } else {
                    enabledRoles.addAll(EnumSet.allOf(StageZoneRole.class));
                }
                rebuild();
            }));

        // CREATE button
        setSlot(slot(5, 8), new GuiElementBuilder(Items.EMERALD)
            .setName(Text.literal("Create Stage").formatted(Formatting.GREEN, Formatting.BOLD))
            .glow()
            .addLoreLine(Text.literal(enabledRoles.size() + " zones at " + fmt(scale) + "x scale")
                .formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Rotation: " + fmt(rotation) + "\u00B0").formatted(Formatting.GRAY))
            .addLoreLine(Text.empty())
            .addLoreLine(Text.literal("Click to create!").formatted(Formatting.YELLOW))
            .setCallback((i, t, a) -> {
                playClickSound();
                createStage();
            }));

        fillBackground();
    }

    private void createStage() {
        BlockPos pos = getPlayer().getBlockPos();
        String worldName = getPlayer().getEntityWorld().getRegistryKey().getValue().toString();

        Stage stage = mod.getStageManager().createStageWithLayout(
            stageName, pos, worldName, templateName, enabledRoles, scale, rotation);

        if (stage != null) {
            getPlayer().sendMessage(Text.literal("Created stage '" + stageName + "' with " +
                stage.getRoleToZone().size() + " zones").formatted(Formatting.GREEN));

            // Close the menu and start the in-world zone placement wizard
            close();
            mod.getZonePlacementManager().startSession(getPlayer(), stage, () ->
                mod.getMenuManager().openMenu(getPlayer(),
                    new StageEditorMenu(getPlayer(), menuManager, mod, stage.getName(), onBack)));
        } else {
            getPlayer().sendMessage(Text.literal("Failed to create stage").formatted(Formatting.RED));
            onBack.run();
        }
    }

    private static String fmt(float v) {
        if (v == (int) v) return String.valueOf((int) v);
        return String.format("%.1f", v);
    }

    private static String fmtOffset(Vector3f offset, float scale) {
        return fmt(offset.x * scale) + ", " + fmt(offset.y * scale) + ", " + fmt(offset.z * scale);
    }
}
