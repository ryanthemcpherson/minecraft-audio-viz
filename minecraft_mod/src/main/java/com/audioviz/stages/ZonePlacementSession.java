package com.audioviz.stages;

import com.audioviz.AudioVizMod;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.menus.StageEditorMenu;
import com.audioviz.zones.VisualizationZone;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player in-world placement session for positioning stage zones one-by-one.
 * Each zone is defined by two opposite corners (like WorldEdit selections).
 *
 * <p>Ported from Paper: StageZonePlacementSession.
 * Bukkit events → method calls from ZonePlacementManager,
 * BukkitTask → tick-based rendering, Location → Vec3d/BlockPos,
 * Particle.DustOptions → DustParticleEffect, Component → Text.
 *
 * <p>Controls:
 *   Left-click:  Set corner 1, then corner 2
 *   Left-click (after both set): Re-pick corner 1
 *   Sneak+Left-click: Rotate front face (once corner 1 is set)
 *   Right-click: Confirm placement, advance to next zone
 *   Sneak+Right-click: Skip zone (keep template default)
 */
public class ZonePlacementSession {

    private static final Logger LOGGER = LoggerFactory.getLogger("audioviz");

    private final AudioVizMod mod;
    private final ServerPlayerEntity player;
    private final Stage stage;
    private final List<StageZoneRole> rolesToPlace;
    private final Runnable onComplete;

    private int currentIndex = 0;
    private int tickCounter = 0;
    private boolean stopped = false;

    // Two-corner placement state
    enum Phase { CORNER1, CORNER2, CONFIRMING }
    private Phase phase = Phase.CORNER1;
    private BlockPos corner1 = null;
    private BlockPos corner2 = null;
    private float currentRotation = 0f; // 0, 90, 180, 270

    // Particle colors (packed RGB for DustParticleEffect)
    private static final int COLOR_CORNER1 = 0xFFC800;      // Gold
    private static final int COLOR_PREVIEW = 0x00FF64;       // Green
    private static final int COLOR_CONFIRMED = 0x64FF32;     // Bright green
    private static final int COLOR_NOT_PLACED = 0x505050;    // Gray
    private static final int COLOR_FRONT = 0x00C8FF;         // Cyan
    private static final int COLOR_FRONT_ARROW = 0x00FFFF;   // Bright cyan

    // Color palette for already-placed zones
    private static final int[] ZONE_COLORS = {
        0xFF3C3C, 0x3C78FF, 0x3CFF3C, 0xFFA500,
        0xB43CFF, 0xFFFF3C, 0xFF69B4, 0xFFFFFF,
    };

    // Box corners for a unit cube [0,1]^3
    private static final double[][] CORNERS = {
        {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1},
        {0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}
    };
    private static final int[][] EDGES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0},
        {4, 5}, {5, 6}, {6, 7}, {7, 4},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };
    // Front face edges per rotation (which face of the unrotated AABB is "front")
    // 0°: Z=0 (south), 90°: X=1 (east), 180°: Z=1 (north), 270°: X=0 (west)
    private static final int[][][] FRONT_EDGES_BY_ROTATION = {
        {{0, 1}, {4, 5}, {0, 4}, {1, 5}},  // 0°:   Z=0 face
        {{1, 2}, {5, 6}, {1, 5}, {2, 6}},  // 90°:  X=1 face
        {{2, 3}, {6, 7}, {2, 6}, {3, 7}},  // 180°: Z=1 face
        {{0, 3}, {4, 7}, {0, 4}, {3, 7}},  // 270°: X=0 face
    };

    public ZonePlacementSession(AudioVizMod mod, ServerPlayerEntity player,
                                 Stage stage, Runnable onComplete) {
        this.mod = mod;
        this.player = player;
        this.stage = stage;
        this.onComplete = onComplete;
        this.rolesToPlace = new ArrayList<>(stage.getActiveRoles());
    }

    // ==================== Lifecycle ====================

    public void start() {
        if (rolesToPlace.isEmpty()) {
            player.sendMessage(Text.literal("No zones to place!").formatted(Formatting.RED));
            stop(true);
            return;
        }

        player.sendMessage(Text.empty());
        player.sendMessage(Text.literal("=== Zone Placement Wizard ===")
            .formatted(Formatting.GOLD, Formatting.BOLD));
        player.sendMessage(Text.literal("Define each zone by selecting two opposite corners.")
            .formatted(Formatting.YELLOW));
        player.sendMessage(Text.empty());
        player.sendMessage(Text.literal("Controls:").formatted(Formatting.YELLOW));
        player.sendMessage(Text.literal("  Left-click: ").formatted(Formatting.WHITE)
            .append(Text.literal("Set corner (1st, then 2nd)").formatted(Formatting.GREEN)));
        player.sendMessage(Text.literal("  Sneak+Left-click: ").formatted(Formatting.WHITE)
            .append(Text.literal("Rotate front face").formatted(Formatting.AQUA)));
        player.sendMessage(Text.literal("  Right-click: ").formatted(Formatting.WHITE)
            .append(Text.literal("Confirm zone & advance").formatted(Formatting.GREEN)));
        player.sendMessage(Text.literal("  Sneak+Right-click: ").formatted(Formatting.WHITE)
            .append(Text.literal("Skip zone (keep default)").formatted(Formatting.GRAY)));
        player.sendMessage(Text.empty());
        player.sendMessage(Text.literal("  The ").formatted(Formatting.GRAY)
            .append(Text.literal("cyan").formatted(Formatting.AQUA))
            .append(Text.literal(" face with arrow is the FRONT of the zone.").formatted(Formatting.GRAY)));
        player.sendMessage(Text.empty());

        StageZoneRole firstRole = rolesToPlace.get(currentIndex);
        player.sendMessage(Text.literal("Placing: ").formatted(Formatting.GREEN)
            .append(Text.literal(firstRole.getDisplayName()).formatted(Formatting.GOLD)));

        player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1f, 1.5f);
        updateActionBar();
    }

    public void stop(boolean cancelled) {
        if (stopped) return;
        stopped = true;

        mod.getZonePlacementManager().removeSession(player.getUuid());

        if (cancelled) {
            player.sendMessage(Text.literal("Zone placement cancelled.").formatted(Formatting.YELLOW));
            player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.5f, 0.8f);
        }
    }

    public boolean isStopped() { return stopped; }
    public ServerPlayerEntity getPlayer() { return player; }

    // ==================== Tick ====================

    public void tick() {
        if (stopped) return;
        if (!player.isAlive() || player.isDisconnected()) {
            stop(true);
            return;
        }

        tickCounter++;
        if (tickCounter % 5 == 0) {
            render();
            updateActionBar();
        }
    }

    // ==================== Interaction Handling ====================

    /**
     * Handle a player interaction. Called by ZonePlacementManager from Fabric callbacks.
     *
     * @param isLeftClick true for left-click, false for right-click
     * @param isSneaking true if player is sneaking
     * @return true if the interaction was consumed (should cancel default behavior)
     */
    public boolean handleInteraction(boolean isLeftClick, boolean isSneaking) {
        if (stopped || currentIndex >= rolesToPlace.size()) return false;

        // Sneak+Left-click: Cycle front face direction (once corner 1 is set)
        if (isLeftClick && isSneaking && phase != Phase.CORNER1) {
            currentRotation = (currentRotation + 90) % 360;
            String facing = switch (((int) currentRotation) % 360) {
                case 90  -> "East (+X)";
                case 180 -> "North (+Z)";
                case 270 -> "West (-X)";
                default  -> "South (-Z)";
            };
            player.sendMessage(Text.literal("Front face: " + facing).formatted(Formatting.AQUA));
            player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                0.5f, 1.0f + currentRotation / 360f);
            return true;
        }

        // Sneak+Right-click: Skip zone (keep template default)
        if (!isLeftClick && isSneaking) {
            StageZoneRole role = rolesToPlace.get(currentIndex);
            player.sendMessage(Text.literal("Skipped " + role.getDisplayName() + " (keeping default).")
                .formatted(Formatting.GRAY));
            player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.8f);

            currentIndex++;
            if (currentIndex >= rolesToPlace.size()) {
                complete();
            } else {
                resetForNextZone();
                StageZoneRole nextRole = rolesToPlace.get(currentIndex);
                player.sendMessage(Text.literal("Now placing: ").formatted(Formatting.GREEN)
                    .append(Text.literal(nextRole.getDisplayName()).formatted(Formatting.GOLD)));
            }
            return true;
        }

        if (isLeftClick) {
            BlockPos blockLoc = player.getBlockPos();

            switch (phase) {
                case CORNER1 -> {
                    corner1 = blockLoc;
                    phase = Phase.CORNER2;
                    player.sendMessage(Text.literal("Corner 1 set at " + formatPos(corner1) +
                        ". Walk to the opposite corner and left-click.").formatted(Formatting.GREEN));
                    player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.5f, 1.5f);
                }
                case CORNER2 -> {
                    corner2 = blockLoc;
                    phase = Phase.CONFIRMING;
                    Vec3d boxSize = computePreviewSize(corner1, corner2);
                    player.sendMessage(Text.literal("Corner 2 set at " + formatPos(corner2) +
                        ". Size: " + formatSize(boxSize) +
                        ". Sneak+Left-click to rotate. Right-click to confirm.")
                        .formatted(Formatting.GREEN));
                    player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.5f, 2.0f);
                }
                case CONFIRMING -> {
                    // Redo: start over with corner 1
                    corner1 = blockLoc;
                    corner2 = null;
                    currentRotation = 0f;
                    phase = Phase.CORNER2;
                    player.sendMessage(Text.literal("Corner 1 reset to " + formatPos(corner1) +
                        ". Walk to the opposite corner and left-click.").formatted(Formatting.YELLOW));
                    player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.5f, 1.5f);
                }
            }
            return true;
        } else {
            // Right-click (not sneaking): Confirm placement
            if (phase == Phase.CONFIRMING) {
                advanceToNextZone();
            } else if (phase == Phase.CORNER2) {
                // Auto-set corner 2 at current position and confirm
                corner2 = player.getBlockPos();
                phase = Phase.CONFIRMING;
                advanceToNextZone();
            } else {
                player.sendMessage(Text.literal("Set corner 1 first (left-click).")
                    .formatted(Formatting.RED));
            }
            return true;
        }
    }

    // ==================== Zone Application ====================

    /**
     * Apply the two corners to the zone.
     * Rotation only changes which face is "front" — the AABB stays fixed in world space.
     * Origin and size are adjusted so localToWorld() maps correctly:
     *   0°:   origin=minCorner,           size=(aabbX, Y, aabbZ) → front is -Z (south)
     *   90°:  origin=(maxX, Y, minZ),     size=(aabbZ, Y, aabbX) → front is +X (east)
     *   180°: origin=(maxX, Y, maxZ),     size=(aabbX, Y, aabbZ) → front is +Z (north)
     *   270°: origin=(minX, Y, maxZ),     size=(aabbZ, Y, aabbX) → front is -X (west)
     */
    private void applyCorners(StageZoneRole role) {
        if (corner1 == null || corner2 == null) return;

        String zoneName = stage.getZoneName(role);
        if (zoneName == null) return;

        VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        // Compute AABB from two corners
        double minX = Math.min(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());

        double aabbX = Math.max(1, maxX - minX);
        double aabbY = Math.max(1, maxY - minY);
        double aabbZ = Math.max(1, maxZ - minZ);

        // Adjust origin and swap dimensions so the world-space AABB stays fixed
        int rot = ((int) currentRotation) % 360;
        double originX, originZ, storedSizeX, storedSizeZ;

        switch (rot) {
            case 90 -> {
                originX = minX + aabbX;
                originZ = minZ;
                storedSizeX = aabbZ;
                storedSizeZ = aabbX;
            }
            case 180 -> {
                originX = minX + aabbX;
                originZ = minZ + aabbZ;
                storedSizeX = aabbX;
                storedSizeZ = aabbZ;
            }
            case 270 -> {
                originX = minX;
                originZ = minZ + aabbZ;
                storedSizeX = aabbZ;
                storedSizeZ = aabbX;
            }
            default -> { // 0°
                originX = minX;
                originZ = minZ;
                storedSizeX = aabbX;
                storedSizeZ = aabbZ;
            }
        }

        zone.setOrigin(new BlockPos((int) originX, (int) minY, (int) originZ));
        zone.setSize((float) storedSizeX, (float) aabbY, (float) storedSizeZ);
        zone.setRotation(currentRotation);
        mod.getZoneManager().saveZones();
    }

    private void advanceToNextZone() {
        StageZoneRole currentRole = rolesToPlace.get(currentIndex);
        applyCorners(currentRole);

        player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);

        currentIndex++;
        if (currentIndex >= rolesToPlace.size()) {
            complete();
        } else {
            resetForNextZone();
            StageZoneRole nextRole = rolesToPlace.get(currentIndex);
            player.sendMessage(Text.literal("Now placing: ").formatted(Formatting.GREEN)
                .append(Text.literal(nextRole.getDisplayName()).formatted(Formatting.GOLD)));
        }
    }

    private void resetForNextZone() {
        phase = Phase.CORNER1;
        corner1 = null;
        corner2 = null;
        currentRotation = 0f;
    }

    private void complete() {
        mod.getStageManager().saveStages();
        stop(false);

        player.sendMessage(Text.empty());
        player.sendMessage(Text.literal("All zones placed!")
            .formatted(Formatting.GREEN, Formatting.BOLD));
        player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);

        // Open the stage editor menu
        if (onComplete != null) {
            onComplete.run();
        } else {
            mod.getMenuManager().openMenu(player,
                new StageEditorMenu(player, mod.getMenuManager(), mod, stage.getName(),
                    () -> mod.getMenuManager().openMenu(player,
                        new com.audioviz.gui.menus.StageListMenu(player, mod.getMenuManager(), mod,
                            () -> mod.getMenuManager().openMenu(player,
                                new com.audioviz.gui.menus.MainMenu(player, mod.getMenuManager(), mod))))));
        }
    }

    // ==================== Rendering ====================

    private void render() {
        for (int i = 0; i < rolesToPlace.size(); i++) {
            StageZoneRole role = rolesToPlace.get(i);
            String zoneName = stage.getZoneName(role);
            if (zoneName == null) continue;

            VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
            if (zone == null) continue;

            if (i == currentIndex) {
                renderCurrentZone(zone);
            } else if (i < currentIndex) {
                // Already placed — distinct color per zone
                int color = ZONE_COLORS[i % ZONE_COLORS.length];
                Vec3d origin = zone.getOriginVec3d();
                Vec3d size = vec3FromVector3f(zone.getSize());
                renderBox(origin, size, zone.getRotation(), color, 1.0f, 0.7);
            } else {
                // Not yet placed — gray
                Vec3d origin = zone.getOriginVec3d();
                Vec3d size = vec3FromVector3f(zone.getSize());
                renderBox(origin, size, zone.getRotation(), COLOR_NOT_PLACED, 0.5f, 1.0);
            }
        }
    }

    private void renderCurrentZone(VisualizationZone zone) {
        BlockPos playerBlock = player.getBlockPos();

        switch (phase) {
            case CORNER1 -> {
                // Small marker at player's feet
                sendDustParticle(playerBlock.getX() + 0.5, playerBlock.getY() + 0.05,
                    playerBlock.getZ() + 0.5, COLOR_CORNER1, 1.5f);
                // Show existing zone dimly
                Vec3d origin = zone.getOriginVec3d();
                Vec3d size = vec3FromVector3f(zone.getSize());
                renderBox(origin, size, zone.getRotation(), COLOR_NOT_PLACED, 0.4f, 1.2);
            }
            case CORNER2 -> {
                // Corner 1 is set, stretch preview to player position
                Vec3d previewOrigin = computePreviewOrigin(corner1, playerBlock);
                Vec3d previewSize = computePreviewSize(corner1, playerBlock);

                renderBox(previewOrigin, previewSize, 0f, COLOR_PREVIEW, 1.2f, 0.5);
                renderFloor(previewOrigin, previewSize, COLOR_PREVIEW);
                renderFrontFace(previewOrigin, previewSize, currentRotation, 1.5f);
                renderFrontArrow(previewOrigin, previewSize, currentRotation);
                renderCornerMarker(corner1, COLOR_CORNER1);
            }
            case CONFIRMING -> {
                // Both corners set, show finalized box
                Vec3d previewOrigin = computePreviewOrigin(corner1, corner2);
                Vec3d previewSize = computePreviewSize(corner1, corner2);

                renderBox(previewOrigin, previewSize, 0f, COLOR_CONFIRMED, 1.2f, 0.5);
                renderFloor(previewOrigin, previewSize, COLOR_CONFIRMED);
                renderFrontFace(previewOrigin, previewSize, currentRotation, 1.5f);
                renderFrontArrow(previewOrigin, previewSize, currentRotation);
                renderCornerMarker(corner1, COLOR_CORNER1);
                renderCornerMarker(corner2, COLOR_FRONT);
            }
        }
    }

    // ==================== Particle Helpers ====================

    private void renderBox(Vec3d origin, Vec3d size, float rotation,
                            int color, float particleSize, double spacing) {
        Vec3d[] worldCorners = computeWorldCorners(origin, size, rotation);
        for (int[] edge : EDGES) {
            drawLine(worldCorners[edge[0]], worldCorners[edge[1]], color, particleSize, spacing);
        }
    }

    private void renderFrontFace(Vec3d origin, Vec3d size, float rotation, float particleSize) {
        Vec3d[] worldCorners = computeWorldCorners(origin, size, 0f);
        int faceIndex = (((int) rotation) % 360) / 90;
        int[][] frontEdges = FRONT_EDGES_BY_ROTATION[faceIndex];

        for (int[] edge : frontEdges) {
            drawLine(worldCorners[edge[0]], worldCorners[edge[1]], COLOR_FRONT, particleSize, 0.4);
        }
    }

    private void renderFrontArrow(Vec3d origin, Vec3d size, float rotation) {
        // Front face center and outward direction based on rotation
        double cx, cz, dirX, dirZ;
        int rot = ((int) rotation) % 360;

        switch (rot) {
            case 90  -> { cx = 1;   cz = 0.5; dirX = 1;  dirZ = 0;  }
            case 180 -> { cx = 0.5; cz = 1;   dirX = 0;  dirZ = 1;  }
            case 270 -> { cx = 0;   cz = 0.5; dirX = -1; dirZ = 0;  }
            default  -> { cx = 0.5; cz = 0;   dirX = 0;  dirZ = -1; }
        }

        Vec3d frontCenter = localToWorld(origin, size, 0f, cx, 0.5, cz);
        double arrowLength = Math.min(3.0, Math.max(size.x, size.z) * 0.3);
        Vec3d arrowEnd = frontCenter.add(dirX * arrowLength, 0, dirZ * arrowLength);
        drawLine(frontCenter, arrowEnd, COLOR_FRONT_ARROW, 1.5f, 0.3);

        // Arrowhead
        double headLength = arrowLength * 0.3;
        double headAngle = Math.toRadians(30);
        for (int side = -1; side <= 1; side += 2) {
            double hdx = -dirX * Math.cos(headAngle) - side * dirZ * Math.sin(headAngle);
            double hdz = -dirZ * Math.cos(headAngle) + side * dirX * Math.sin(headAngle);
            Vec3d headEnd = arrowEnd.add(hdx * headLength, 0, hdz * headLength);
            drawLine(arrowEnd, headEnd, COLOR_FRONT_ARROW, 1.5f, 0.2);
        }
    }

    private void renderCornerMarker(BlockPos corner, int color) {
        if (corner == null) return;
        double cx = corner.getX() + 0.5;
        double cy = corner.getY() + 0.1;
        double cz = corner.getZ() + 0.5;
        // Cross pattern
        for (double d = -0.5; d <= 0.5; d += 0.2) {
            sendDustParticle(cx + d, cy, cz, color, 1.5f);
            sendDustParticle(cx, cy, cz + d, color, 1.5f);
        }
    }

    private void renderFloor(Vec3d origin, Vec3d size, int color) {
        int darkenedColor = darkenColor(color, 0.3);
        double floorSpacing = 1.0;
        double stepX = floorSpacing / Math.max(1, size.x);
        double stepZ = floorSpacing / Math.max(1, size.z);
        for (double x = 0; x <= 1.0; x += stepX) {
            for (double z = 0; z <= 1.0; z += stepZ) {
                Vec3d point = localToWorld(origin, size, 0f, x, 0, z);
                sendDustParticle(point.x, point.y, point.z, darkenedColor, 0.5f);
            }
        }
    }

    // ==================== Geometry Helpers ====================

    private Vec3d computePreviewOrigin(BlockPos c1, BlockPos c2) {
        return new Vec3d(
            Math.min(c1.getX(), c2.getX()),
            Math.min(c1.getY(), c2.getY()),
            Math.min(c1.getZ(), c2.getZ()));
    }

    private Vec3d computePreviewSize(BlockPos c1, BlockPos c2) {
        return new Vec3d(
            Math.max(1, Math.abs(c2.getX() - c1.getX())),
            Math.max(1, Math.abs(c2.getY() - c1.getY())),
            Math.max(1, Math.abs(c2.getZ() - c1.getZ())));
    }

    private Vec3d[] computeWorldCorners(Vec3d origin, Vec3d size, float rotation) {
        Vec3d[] worldCorners = new Vec3d[8];
        double radians = Math.toRadians(rotation);
        double cosR = Math.cos(radians);
        double sinR = Math.sin(radians);

        for (int i = 0; i < 8; i++) {
            double scaledX = CORNERS[i][0] * size.x;
            double scaledY = CORNERS[i][1] * size.y;
            double scaledZ = CORNERS[i][2] * size.z;
            double rotatedX = scaledX * cosR - scaledZ * sinR;
            double rotatedZ = scaledX * sinR + scaledZ * cosR;
            worldCorners[i] = origin.add(rotatedX, scaledY, rotatedZ);
        }
        return worldCorners;
    }

    private Vec3d localToWorld(Vec3d origin, Vec3d size, float rotation,
                                double localX, double localY, double localZ) {
        double scaledX = localX * size.x;
        double scaledY = localY * size.y;
        double scaledZ = localZ * size.z;
        double radians = Math.toRadians(rotation);
        double rotatedX = scaledX * Math.cos(radians) - scaledZ * Math.sin(radians);
        double rotatedZ = scaledX * Math.sin(radians) + scaledZ * Math.cos(radians);
        return origin.add(rotatedX, scaledY, rotatedZ);
    }

    // ==================== Particle Sending ====================

    private void sendDustParticle(double x, double y, double z, int color, float size) {
        player.networkHandler.sendPacket(new ParticleS2CPacket(
            new DustParticleEffect(color, size),
            true, true, x, y, z, 0f, 0f, 0f, 0f, 1));
    }

    private void drawLine(Vec3d start, Vec3d end, int color, float particleSize, double spacing) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.01) return;

        double nx = dx / length;
        double ny = dy / length;
        double nz = dz / length;

        for (double d = 0; d <= length; d += spacing) {
            sendDustParticle(
                start.x + nx * d,
                start.y + ny * d,
                start.z + nz * d,
                color, particleSize);
        }
    }

    // ==================== Action Bar ====================

    private void updateActionBar() {
        if (currentIndex >= rolesToPlace.size()) return;

        StageZoneRole role = rolesToPlace.get(currentIndex);
        String progress = (currentIndex + 1) + "/" + rolesToPlace.size();
        String rotStr = (int) currentRotation + "\u00B0";

        String status;
        String hint;
        Formatting color;

        switch (phase) {
            case CORNER1 -> {
                status = "Set Corner 1";
                hint = "Left-click to place";
                color = Formatting.AQUA;
            }
            case CORNER2 -> {
                status = "Set Corner 2 [" + rotStr + "]";
                hint = "Left-click | Sneak+L=Face";
                color = Formatting.YELLOW;
            }
            case CONFIRMING -> {
                status = "Ready [" + rotStr + "]";
                hint = "R-click=Confirm | Sneak+L=Face | L-click=Redo";
                color = Formatting.GREEN;
            }
            default -> {
                status = "";
                hint = "";
                color = Formatting.WHITE;
            }
        }

        Text actionBar = Text.literal(
            role.getDisplayName() + " (" + progress + ") [" + status + "] " + hint)
            .formatted(color);
        // Send as overlay (action bar)
        player.sendMessage(actionBar, true);
    }

    // ==================== Utility ====================

    private static Vec3d vec3FromVector3f(org.joml.Vector3f v) {
        return new Vec3d(v.x, v.y, v.z);
    }

    private static int darkenColor(int color, double factor) {
        int r = (int) (((color >> 16) & 0xFF) * (1 - factor));
        int g = (int) (((color >> 8) & 0xFF) * (1 - factor));
        int b = (int) ((color & 0xFF) * (1 - factor));
        return (r << 16) | (g << 8) | b;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String formatSize(Vec3d size) {
        return (int) size.x + "x" + (int) size.y + "x" + (int) size.z;
    }
}
