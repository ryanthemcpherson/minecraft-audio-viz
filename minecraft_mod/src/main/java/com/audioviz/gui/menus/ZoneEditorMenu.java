package com.audioviz.gui.menus;

import com.audioviz.AudioVizMod;
import com.audioviz.effects.BeatEffectConfig;
import com.audioviz.effects.BeatType;
import com.audioviz.gui.AudioVizGui;
import com.audioviz.gui.MenuManager;
import com.audioviz.zones.VisualizationZone;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Formatting;
import org.joml.Vector3f;

import java.util.Set;

public class ZoneEditorMenu extends AudioVizGui {

    private final AudioVizMod mod;
    private final String zoneName;
    private final Runnable onBack;

    public ZoneEditorMenu(ServerPlayerEntity player, MenuManager menuManager,
                          AudioVizMod mod, String zoneName, Runnable onBack) {
        super(ScreenHandlerType.GENERIC_9X4, player, menuManager);
        this.mod = mod;
        this.zoneName = zoneName;
        this.onBack = onBack;
    }

    @Override
    protected Text getMenuTitle() {
        return Text.literal("Zone: " + zoneName).formatted(Formatting.DARK_BLUE, Formatting.BOLD);
    }

    @Override
    protected void build() {
        VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
        if (zone == null) {
            setSlot(slot(1, 4), new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("Zone not found!").formatted(Formatting.RED)));
            setBackButton(slot(3, 0), onBack);
            fillBackground();
            return;
        }

        Vector3f size = zone.getSize();
        float rotation = zone.getRotation();

        // Row 0: Info header
        setSlot(slot(0, 4), new GuiElementBuilder(Items.ENDER_EYE)
            .setName(Text.literal(zoneName).formatted(Formatting.AQUA, Formatting.BOLD))
            .addLoreLine(Text.literal("Origin: " + zone.getOrigin().getX() + ", " +
                zone.getOrigin().getY() + ", " + zone.getOrigin().getZ()).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Size: " + fmt(size.x) + "x" + fmt(size.y) + "x" + fmt(size.z)).formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Rotation: " + fmt(rotation) + "\u00B0").formatted(Formatting.GRAY)));

        // Row 1: Size X [-][val][+]  Size Y [-][val][+]  Size Z [-][val][+]
        buildSizeControl(1, 0, "X", size.x, Formatting.RED, () -> zone.getSize().x, (v) -> {
            zone.setSize(v, zone.getSize().y, zone.getSize().z);
            save();
        });
        buildSizeControl(1, 3, "Y", size.y, Formatting.GREEN, () -> zone.getSize().y, (v) -> {
            zone.setSize(zone.getSize().x, v, zone.getSize().z);
            save();
        });
        buildSizeControl(1, 6, "Z", size.z, Formatting.BLUE, () -> zone.getSize().z, (v) -> {
            zone.setSize(zone.getSize().x, zone.getSize().y, v);
            save();
        });

        // Row 2: Rotation [-][val][+], Init Pool, Cleanup, Teleport
        setSlot(slot(2, 0), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("-15\u00B0").formatted(Formatting.RED))
            .addLoreLine(Text.literal("Shift: -45\u00B0").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = type == ClickType.MOUSE_LEFT_SHIFT ? 45 : 15;
                zone.setRotation(zone.getRotation() - step);
                save();
            }));
        setSlot(slot(2, 1), new GuiElementBuilder(Items.COMPASS)
            .setName(Text.literal("Rotation: " + fmt(rotation) + "\u00B0").formatted(Formatting.YELLOW)));
        setSlot(slot(2, 2), new GuiElementBuilder(Items.LIME_DYE)
            .setName(Text.literal("+15\u00B0").formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("Shift: +45\u00B0").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = type == ClickType.MOUSE_LEFT_SHIFT ? 45 : 15;
                zone.setRotation(zone.getRotation() + step);
                save();
            }));

        // Init Pool
        setSlot(slot(2, 4), new GuiElementBuilder(Items.SPAWNER)
            .setName(Text.literal("Init Pool").formatted(Formatting.LIGHT_PURPLE))
            .addLoreLine(Text.literal("Left: 64 entities").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Right: 128 entities").formatted(Formatting.GRAY))
            .addLoreLine(Text.literal("Shift-Left: 16 entities").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                if (zone.getWorld() == null) {
                    getPlayer().sendMessage(Text.literal("Zone world not loaded").formatted(Formatting.RED));
                    return;
                }
                int count = switch (type) {
                    case MOUSE_LEFT_SHIFT -> 16;
                    case MOUSE_RIGHT -> 128;
                    default -> 64;
                };
                mod.getVirtualRenderer().initializePool(zoneName, zone, count, zone.getWorld());
                getPlayer().sendMessage(Text.literal("Pool initialized: " + count + " entities").formatted(Formatting.GREEN));
                rebuild();
            }));

        // Cleanup
        setSlot(slot(2, 5), new GuiElementBuilder(Items.TNT)
            .setName(Text.literal("Cleanup").formatted(Formatting.RED))
            .addLoreLine(Text.literal("Destroy entity pool & map display").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                mod.getVirtualRenderer().destroyPool(zoneName);
                mod.getMapRenderer().destroyDisplay(zoneName);
                getPlayer().sendMessage(Text.literal("Zone cleaned up").formatted(Formatting.YELLOW));
                rebuild();
            }));

        // Teleport
        setSlot(slot(2, 7), new GuiElementBuilder(Items.ENDER_PEARL)
            .setName(Text.literal("Teleport").formatted(Formatting.AQUA))
            .setCallback((i, type, a) -> {
                playClickSound();
                if (zone.getWorld() == null) {
                    getPlayer().sendMessage(Text.literal("Zone world not loaded").formatted(Formatting.RED));
                    return;
                }
                close();
                getPlayer().teleport(zone.getWorld(),
                    zone.getOrigin().getX() + 0.5, (double) zone.getOrigin().getY(),
                    zone.getOrigin().getZ() + 0.5, Set.of(),
                    getPlayer().getYaw(), getPlayer().getPitch(), false);
            }));

        // Row 3: Back, Particles, Lights, Beats, Boundaries, Delete
        setBackButton(slot(3, 0), onBack);

        // Particle effects toggle
        boolean particlesEnabled = mod.getParticleEffectManager() != null
            && !mod.getParticleEffectManager().getEnabledEffects(zoneName.toLowerCase()).isEmpty();
        setSlot(slot(3, 2), new GuiElementBuilder(particlesEnabled ? Items.BLAZE_POWDER : Items.GUNPOWDER)
            .setName(Text.literal(particlesEnabled ? "Disable Particles" : "Enable Particles")
                .formatted(particlesEnabled ? Formatting.GOLD : Formatting.GRAY))
            .addLoreLine(Text.literal("Audio-reactive particle effects").formatted(Formatting.DARK_GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                var pm = mod.getParticleEffectManager();
                if (pm == null) return;
                String key = zoneName.toLowerCase();
                if (pm.getEnabledEffects(key).isEmpty()) {
                    pm.enableDefaultEffects(key);
                    getPlayer().sendMessage(Text.literal("Particles enabled").formatted(Formatting.GREEN));
                } else {
                    for (String eid : new java.util.ArrayList<>(pm.getEnabledEffects(key))) {
                        pm.disableEffect(key, eid);
                    }
                    getPlayer().sendMessage(Text.literal("Particles disabled").formatted(Formatting.YELLOW));
                }
                rebuild();
            }));

        // Ambient lights toggle
        boolean lightsOn = mod.getAmbientLightManager() != null && mod.getAmbientLightManager().hasZone(zoneName);
        setSlot(slot(3, 3), new GuiElementBuilder(lightsOn ? Items.GLOWSTONE : Items.COAL)
            .setName(Text.literal(lightsOn ? "Remove Lights" : "Place Lights")
                .formatted(lightsOn ? Formatting.YELLOW : Formatting.GRAY))
            .addLoreLine(Text.literal("Audio-reactive light blocks").formatted(Formatting.DARK_GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                var lm = mod.getAmbientLightManager();
                if (lm == null) return;
                if (lm.hasZone(zoneName)) {
                    lm.teardownZone(zoneName);
                    getPlayer().sendMessage(Text.literal("Lights removed").formatted(Formatting.YELLOW));
                } else {
                    lm.initializeZone(zone);
                    getPlayer().sendMessage(Text.literal("Lights placed").formatted(Formatting.GREEN));
                }
                rebuild();
            }));

        // Beat effects toggle
        boolean beatsOn = mod.getBeatEventManager() != null
            && mod.getBeatEventManager().getZoneConfig(zoneName) != null;
        setSlot(slot(3, 5), new GuiElementBuilder(beatsOn ? Items.NOTE_BLOCK : Items.JUKEBOX)
            .setName(Text.literal(beatsOn ? "Disable Beats" : "Enable Beats")
                .formatted(beatsOn ? Formatting.LIGHT_PURPLE : Formatting.GRAY))
            .addLoreLine(Text.literal("Beat-triggered particle bursts").formatted(Formatting.DARK_GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                var bm = mod.getBeatEventManager();
                if (bm == null) return;
                if (bm.getZoneConfig(zoneName) != null) {
                    bm.removeZoneConfig(zoneName);
                    getPlayer().sendMessage(Text.literal("Beats disabled").formatted(Formatting.YELLOW));
                } else {
                    BeatEffectConfig config = new BeatEffectConfig.Builder()
                        .addEffect(BeatType.BEAT, bm.get("particle_burst"))
                        .build();
                    bm.setZoneConfig(zoneName, config);
                    getPlayer().sendMessage(Text.literal("Beats enabled").formatted(Formatting.GREEN));
                }
                rebuild();
            }));

        boolean bShowing = mod.getZoneBoundaryRenderer() != null
            && mod.getZoneBoundaryRenderer().isShowing(zoneName);
        setSlot(slot(3, 4), new GuiElementBuilder(bShowing ? Items.ENDER_EYE : Items.GLASS)
            .setName(Text.literal(bShowing ? "Hide Boundaries" : "Show Boundaries").formatted(Formatting.AQUA))
            .addLoreLine(Text.literal("Persistent particle outlines (30s)").formatted(Formatting.GRAY))
            .setCallback((i, t, a) -> {
                playClickSound();
                var renderer = mod.getZoneBoundaryRenderer();
                if (renderer != null) {
                    boolean nowShowing = renderer.toggle(zoneName);
                    getPlayer().sendMessage(Text.literal(nowShowing
                        ? "Zone boundaries shown (30s)" : "Zone boundaries hidden")
                        .formatted(nowShowing ? Formatting.AQUA : Formatting.YELLOW));
                } else {
                    showZoneBoundaries(zone);
                }
                rebuild();
            }));

        setSlot(slot(3, 8), new GuiElementBuilder(Items.BARRIER)
            .setName(Text.literal("Delete Zone").formatted(Formatting.RED, Formatting.BOLD))
            .addLoreLine(Text.literal("Shift-click to confirm").formatted(Formatting.DARK_RED))
            .setCallback((i, type, a) -> {
                if (type == ClickType.MOUSE_LEFT_SHIFT || type == ClickType.MOUSE_RIGHT_SHIFT) {
                    playClickSound();
                    mod.getVirtualRenderer().destroyPool(zoneName);
                    mod.getMapRenderer().destroyDisplay(zoneName);
                    if (mod.getBitmapToEntityBridge() != null) mod.getBitmapToEntityBridge().destroyWall(zoneName);
                    if (mod.getBitmapPatternManager() != null) mod.getBitmapPatternManager().deactivateZone(zoneName);
                    if (mod.getAmbientLightManager() != null) mod.getAmbientLightManager().teardownZone(zoneName);
                    if (mod.getBeatEventManager() != null) mod.getBeatEventManager().removeZoneConfig(zoneName);
                    mod.getZoneManager().deleteZone(zoneName);
                    getPlayer().sendMessage(Text.literal("Deleted zone '" + zoneName + "'").formatted(Formatting.RED));
                    onBack.run();
                }
            }));

        fillBackground();
    }

    private void buildSizeControl(int row, int startCol, String axis, float current,
                                   Formatting color, java.util.function.Supplier<Float> liveValue,
                                   java.util.function.Consumer<Float> setter) {
        setSlot(slot(row, startCol), new GuiElementBuilder(Items.RED_DYE)
            .setName(Text.literal("-").formatted(Formatting.RED))
            .addLoreLine(Text.literal("Click: -1 | Shift: -5").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = (type == ClickType.MOUSE_LEFT_SHIFT) ? 5 : 1;
                setter.accept(Math.max(1, liveValue.get() - step));
                rebuild();
            }));
        setSlot(slot(row, startCol + 1), new GuiElementBuilder(Items.PAPER)
            .setName(Text.literal(axis + ": " + fmt(current)).formatted(color)));
        setSlot(slot(row, startCol + 2), new GuiElementBuilder(Items.LIME_DYE)
            .setName(Text.literal("+").formatted(Formatting.GREEN))
            .addLoreLine(Text.literal("Click: +1 | Shift: +5").formatted(Formatting.GRAY))
            .setCallback((i, type, a) -> {
                playClickSound();
                float step = (type == ClickType.MOUSE_LEFT_SHIFT) ? 5 : 1;
                setter.accept(Math.min(100, liveValue.get() + step));
                rebuild();
            }));
    }

    private void save() {
        mod.getZoneManager().saveZones();
        rebuild();
    }

    /**
     * Visualize zone boundaries by spawning particles along the 12 edges of the zone box.
     * Respects zone rotation via localToWorld().
     */
    private void showZoneBoundaries(VisualizationZone zone) {
        if (zone.getWorld() == null) return;

        // Calculate 8 corners in world coordinates
        Vec3d[] corners = new Vec3d[8];
        for (int i = 0; i < 8; i++) {
            double lx = (i & 1) != 0 ? 1.0 : 0.0;
            double ly = (i & 2) != 0 ? 1.0 : 0.0;
            double lz = (i & 4) != 0 ? 1.0 : 0.0;
            corners[i] = zone.localToWorld(lx, ly, lz);
        }

        // 12 edges of the bounding box
        int[][] edges = {
            {0,1}, {2,3}, {4,5}, {6,7},  // along local X
            {0,2}, {1,3}, {4,6}, {5,7},  // along local Y
            {0,4}, {1,5}, {2,6}, {3,7},  // along local Z
        };

        var player = getPlayer();
        for (int[] edge : edges) {
            spawnEdgeParticles(player, corners[edge[0]], corners[edge[1]]);
        }

        player.sendMessage(Text.literal("Zone boundaries shown with particles").formatted(Formatting.AQUA));
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

    private static String fmt(float v) {
        return v == (int) v ? String.valueOf((int) v) : String.format("%.1f", v);
    }
}
