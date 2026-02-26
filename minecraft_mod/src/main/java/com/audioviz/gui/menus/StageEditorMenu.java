package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
            .addLoreLine(Text.literal("Zones: " + stage.getRoleToZone().size() + "/" + StageZoneRole.values().length).formatted(Formatting.GRAY))
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

        // Row 4: Stage preview controls
        setSlot(slot(4, 1), new GuiElementBuilder(Items.GLASS)
            .setName(Text.literal("Show All Zones").formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("Particle outlines for all zones").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                showAllZoneBoundaries(stage);
            }));

        setSlot(slot(4, 3), new GuiElementBuilder(Items.BRICKS)
            .setName(Text.literal("Build Zone Markers").formatted(Formatting.YELLOW))
            .addLoreLine(Text.literal("Place colored blocks at zone corners").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Helps visualize stage layout").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                buildZoneMarkers(stage);
            }));

        setSlot(slot(4, 5), new GuiElementBuilder(Items.TNT_MINECART)
            .setName(Text.literal("Clear Markers").formatted(Formatting.RED))
            .addLoreLine(Text.literal("Remove zone marker blocks").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                clearZoneMarkers(stage);
            }));

        setSlot(slot(4, 7), new GuiElementBuilder(Items.ENDER_PEARL)
            .setName(Text.literal("Teleport to Stage").formatted(Formatting.LIGHT_PURPLE))
            .setCallback((i, t, a) -> {
                playClickSound();
                close();
                var world = findWorld(stage.getWorldName());
                if (world != null) {
                    getPlayer().teleport(world,
                        stage.getAnchor().getX() + 0.5, (double) stage.getAnchor().getY(),
                        stage.getAnchor().getZ() + 0.5, java.util.Set.of(),
                        getPlayer().getYaw(), getPlayer().getPitch(), false);
                }
            }));

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

    /**
     * Show particle outlines for ALL zones in the stage simultaneously.
     */
    private void showAllZoneBoundaries(Stage stage) {
        int count = 0;
        for (StageZoneRole role : stage.getActiveRoles()) {
            String zoneName = stage.getZoneName(role);
            if (zoneName == null) continue;
            var zone = mod.getZoneManager().getZone(zoneName);
            if (zone == null || zone.getWorld() == null) continue;

            // 8 corners
            Vec3d[] corners = new Vec3d[8];
            for (int i = 0; i < 8; i++) {
                corners[i] = zone.localToWorld(
                    (i & 1) != 0 ? 1.0 : 0.0,
                    (i & 2) != 0 ? 1.0 : 0.0,
                    (i & 4) != 0 ? 1.0 : 0.0);
            }

            int[][] edges = {
                {0,1}, {2,3}, {4,5}, {6,7},
                {0,2}, {1,3}, {4,6}, {5,7},
                {0,4}, {1,5}, {2,6}, {3,7},
            };

            for (int[] edge : edges) {
                spawnEdgeParticles(getPlayer(), corners[edge[0]], corners[edge[1]]);
            }
            count++;
        }
        getPlayer().sendMessage(Text.literal("Showing boundaries for " + count + " zones")
            .formatted(Formatting.AQUA));
    }

    /**
     * Place colored concrete blocks at the 4 bottom corners and pillars of each zone.
     * Uses a different color per zone role for easy identification.
     */
    private void buildZoneMarkers(Stage stage) {
        var world = findWorld(stage.getWorldName());
        if (world == null) return;

        // Color mapping for each role
        Map<StageZoneRole, net.minecraft.block.Block> roleBlocks = Map.of(
            StageZoneRole.MAIN_STAGE, Blocks.GOLD_BLOCK,
            StageZoneRole.LEFT_WING,  Blocks.BLUE_CONCRETE,
            StageZoneRole.RIGHT_WING, Blocks.BLUE_CONCRETE,
            StageZoneRole.SKYBOX,     Blocks.CYAN_CONCRETE,
            StageZoneRole.AUDIENCE,   Blocks.LIME_CONCRETE,
            StageZoneRole.BACKSTAGE,  Blocks.GRAY_CONCRETE,
            StageZoneRole.RUNWAY,     Blocks.MAGENTA_CONCRETE,
            StageZoneRole.BALCONY,    Blocks.YELLOW_CONCRETE
        );

        int placed = 0;
        for (StageZoneRole role : stage.getActiveRoles()) {
            String zoneName = stage.getZoneName(role);
            if (zoneName == null) continue;
            var zone = mod.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            var block = roleBlocks.getOrDefault(role, Blocks.WHITE_CONCRETE);

            // Place corner pillars (3 blocks tall) at each bottom corner
            for (int cx = 0; cx <= 1; cx++) {
                for (int cz = 0; cz <= 1; cz++) {
                    Vec3d corner = zone.localToWorld(cx, 0, cz);
                    BlockPos base = BlockPos.ofFloored(corner.x, corner.y, corner.z);
                    for (int h = 0; h < 3; h++) {
                        world.setBlockState(base.up(h), block.getDefaultState());
                        placed++;
                    }
                }
            }
        }
        getPlayer().sendMessage(Text.literal("Placed " + placed + " marker blocks")
            .formatted(Formatting.YELLOW));
    }

    /**
     * Remove zone marker blocks by replacing 3-tall pillars at zone corners with air.
     */
    private void clearZoneMarkers(Stage stage) {
        var world = findWorld(stage.getWorldName());
        if (world == null) return;

        int removed = 0;
        for (StageZoneRole role : stage.getActiveRoles()) {
            String zoneName = stage.getZoneName(role);
            if (zoneName == null) continue;
            var zone = mod.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            for (int cx = 0; cx <= 1; cx++) {
                for (int cz = 0; cz <= 1; cz++) {
                    Vec3d corner = zone.localToWorld(cx, 0, cz);
                    BlockPos base = BlockPos.ofFloored(corner.x, corner.y, corner.z);
                    for (int h = 0; h < 3; h++) {
                        var currentState = world.getBlockState(base.up(h));
                        // Only remove concrete/gold blocks (our markers)
                        String blockId = net.minecraft.registry.Registries.BLOCK
                            .getId(currentState.getBlock()).getPath();
                        if (blockId.endsWith("_concrete") || blockId.equals("gold_block")) {
                            world.setBlockState(base.up(h), Blocks.AIR.getDefaultState());
                            removed++;
                        }
                    }
                }
            }
        }
        getPlayer().sendMessage(Text.literal("Cleared " + removed + " marker blocks")
            .formatted(Formatting.RED));
    }

    private void spawnEdgeParticles(net.minecraft.server.network.ServerPlayerEntity player, Vec3d a, Vec3d b) {
        double dist = a.distanceTo(b);
        int steps = Math.max(2, (int)(dist / 0.5));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double x = a.x + (b.x - a.x) * t;
            double y = a.y + (b.y - a.y) * t;
            double z = a.z + (b.z - a.z) * t;
            player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ParticleS2CPacket(
                net.minecraft.particle.ParticleTypes.END_ROD,
                true, true, x, y, z, 0f, 0f, 0f, 0f, 1
            ));
        }
    }

    private net.minecraft.server.world.ServerWorld findWorld(String worldName) {
        for (var w : mod.getServer().getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldName)) return w;
        }
        // Fallback to overworld only
        return mod.getServer().getOverworld();
    }
}
