package com.audioviz.decorators;

import com.audioviz.AudioVizMod;
import com.audioviz.patterns.AudioState;
import com.audioviz.stages.Stage;
import net.minecraft.util.math.Vec3d;

/**
 * Abstract base class for stage decorators.
 *
 * <p>Ported from Paper: AudioVizPlugin → AudioVizMod,
 * Location → Vec3d, Material → block string reference.
 * Entity pool management via Polymer virtual entities.
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
