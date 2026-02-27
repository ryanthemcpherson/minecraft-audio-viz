package com.audioviz.decorators;

import com.audioviz.AudioVizMod;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import com.audioviz.zones.VisualizationZone;
import net.minecraft.block.BlockState;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * Abstract base class for stage decorators.
 *
 * <p>Ported from Paper: AudioVizPlugin -> AudioVizMod,
 * Location -> Vec3d, Material -> BlockState.
 * Entity pool management via Polymer virtual entities through DecoratorEntityManager.
 */
public abstract class StageDecorator {

    protected final String id;
    protected final String displayName;
    protected final Stage stage;
    protected final AudioVizMod mod;
    protected boolean enabled;
    protected DecoratorConfig config;
    protected long tickCount = 0;

    protected StageDecorator(String id, String displayName, Stage stage, AudioVizMod mod) {
        this.id = id;
        this.displayName = displayName;
        this.stage = stage;
        this.mod = mod;
        this.enabled = true;
        this.config = getDefaultConfig();
    }

    public abstract void onActivate();
    public abstract void onDeactivate();
    public abstract void tick(AudioState audio, DJInfo djInfo);
    public abstract DecoratorConfig getDefaultConfig();

    public String getDecoratorZoneName() {
        return stage.getName().toLowerCase() + "_deco_" + id;
    }

    // ========== Zone & Entity Helpers ==========

    /**
     * Calculate a world position relative to the stage anchor, applying rotation.
     */
    protected Vec3d getStageRelativePosition(double offsetX, double offsetY, double offsetZ) {
        double radians = Math.toRadians(stage.getRotation());
        double rotatedX = offsetX * Math.cos(radians) - offsetZ * Math.sin(radians);
        double rotatedZ = offsetX * Math.sin(radians) + offsetZ * Math.cos(radians);
        return new Vec3d(
            stage.getAnchor().getX() + rotatedX,
            stage.getAnchor().getY() + offsetY,
            stage.getAnchor().getZ() + rotatedZ
        );
    }

    /**
     * Create a visualization zone for this decorator at the given origin.
     * Registers the zone in ZoneManager so other code can find it.
     */
    protected VisualizationZone createDecoratorZone(Vec3d origin, Vector3f size) {
        String zoneName = getDecoratorZoneName();
        ServerWorld world = getStageWorld();
        BlockPos blockOrigin = BlockPos.ofFloored(origin.x, origin.y, origin.z);

        VisualizationZone zone = mod.getZoneManager().createZone(zoneName, world, blockOrigin);
        if (zone == null) {
            zone = mod.getZoneManager().getZone(zoneName);
        }
        if (zone != null) {
            zone.setSize(size);
            zone.setRotation(stage.getRotation());
        }
        return zone;
    }

    /**
     * Initialize a text display entity pool for this decorator.
     */
    protected void initTextPool(int count) {
        initTextPool(count, DisplayEntity.BillboardMode.CENTER);
    }

    /**
     * Initialize a text display entity pool with a specific billboard mode.
     */
    protected void initTextPool(int count, DisplayEntity.BillboardMode billboard) {
        String zoneName = getDecoratorZoneName();
        VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
        Vec3d origin = zone != null ? zone.getOriginVec3d() : getStageRelativePosition(0, 0, 0);
        ServerWorld world = getStageWorld();

        mod.getDecoratorEntityManager().initTextPool(zoneName, count, billboard, world, origin);
    }

    /**
     * Initialize a block display entity pool for this decorator.
     */
    protected void initBlockPool(int count, BlockState blockState) {
        String zoneName = getDecoratorZoneName();
        VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
        Vec3d origin = zone != null ? zone.getOriginVec3d() : getStageRelativePosition(0, 0, 0);
        ServerWorld world = getStageWorld();

        mod.getDecoratorEntityManager().initBlockPool(zoneName, count, blockState, world, origin);
    }

    /**
     * Clean up the entity pool and zone for this decorator.
     */
    protected void cleanupDecoratorZone() {
        String zoneName = getDecoratorZoneName();
        mod.getDecoratorEntityManager().cleanup(zoneName);
        mod.getZoneManager().deleteZone(zoneName);
    }

    /**
     * Resolve the ServerWorld for this stage.
     */
    protected ServerWorld getStageWorld() {
        String worldName = stage.getWorldName();
        if (worldName != null && mod.getServer() != null) {
            for (ServerWorld w : mod.getServer().getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(worldName)) {
                    return w;
                }
            }
        }
        return mod.getServer().getOverworld();
    }

    // ========== Audio Helpers ==========

    protected int getDominantBand(AudioState audio) {
        double[] bands = audio.getBands();
        int dominant = 0;
        double maxVal = 0;
        for (int i = 0; i < bands.length; i++) {
            if (bands[i] > maxVal) {
                maxVal = bands[i];
                dominant = i;
            }
        }
        return dominant;
    }

    protected double lerp(double a, double b, double t) {
        return a + (b - a) * Math.max(0, Math.min(1, t));
    }

    // ========== Lifecycle ==========

    public void incrementTick() { tickCount++; }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public Stage getStage() { return stage; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public DecoratorConfig getConfig() { return config; }
    public void setConfig(DecoratorConfig config) {
        this.config = config != null ? config : getDefaultConfig();
    }
}
