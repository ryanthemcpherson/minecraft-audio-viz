package com.audioviz.beatsync;

/**
 * Per-zone configuration for beat sync overrides.
 * All fields have safe defaults (auto BPM, no offset, normal sensitivity).
 */
public class BeatSyncConfig {

    private double manualBpm = 0.0;              // 0 = auto (use DJ client value)
    private double phaseOffset = 0.0;            // -0.5 to 0.5
    private double beatThresholdMultiplier = 1.0; // 0.1 to 5.0
    private boolean projectionEnabled = true;

    public double getManualBpm() { return manualBpm; }
    public double getPhaseOffset() { return phaseOffset; }
    public double getBeatThresholdMultiplier() { return beatThresholdMultiplier; }
    public boolean isProjectionEnabled() { return projectionEnabled; }
    public boolean isAutoBpm() { return manualBpm <= 0; }

    public void setManualBpm(double bpm) {
        this.manualBpm = Math.max(0, Math.min(300, bpm));
    }

    public void setPhaseOffset(double offset) {
        this.phaseOffset = Math.max(-0.5, Math.min(0.5, offset));
    }

    public void setBeatThresholdMultiplier(double multiplier) {
        this.beatThresholdMultiplier = Math.max(0.1, Math.min(5.0, multiplier));
    }

    public void setProjectionEnabled(boolean enabled) {
        this.projectionEnabled = enabled;
    }

    /**
     * Apply a phase offset to a beat phase value, wrapping to [0, 1).
     */
    public static double applyPhaseOffset(double beatPhase, double offset) {
        double result = (beatPhase + offset) % 1.0;
        if (result < 0) result += 1.0;
        return result;
    }
}
