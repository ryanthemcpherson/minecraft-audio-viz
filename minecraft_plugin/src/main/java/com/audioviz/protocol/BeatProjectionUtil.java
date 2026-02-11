package com.audioviz.protocol;

import java.util.Map;

/**
 * Shared beat projection logic used by both MessageQueue and MessageHandler.
 *
 * Determines whether a synthesized beat should fire based on tempo confidence,
 * BPM, beat phase, and per-zone cooldown tracking.
 */
public final class BeatProjectionUtil {

    private BeatProjectionUtil() {
        // Utility class
    }

    // Tuning constants for beat projection
    public static final double MIN_PHASE_ASSIST_CONFIDENCE = 0.60;
    public static final double MIN_PHASE_ASSIST_BPM = 60.0;
    public static final double PHASE_EDGE_WINDOW = 0.12;
    public static final double SYNTH_BEAT_MIN_INTENSITY = 0.25;
    public static final double SYNTH_BEAT_COOLDOWN_FRACTION = 0.60;
    public static final long SYNTH_BEAT_COOLDOWN_MIN_MS = 120L;

    /**
     * Result of a beat projection calculation.
     */
    public static final class BeatProjection {
        private final boolean isBeat;
        private final double beatIntensity;

        public BeatProjection(boolean isBeat, double beatIntensity) {
            this.isBeat = isBeat;
            this.beatIntensity = beatIntensity;
        }

        public boolean isBeat() {
            return isBeat;
        }

        public double beatIntensity() {
            return beatIntensity;
        }
    }

    /**
     * Project whether a beat should be triggered, using explicit beat data
     * or synthesizing one from tempo/phase information.
     *
     * @param zoneName                zone identifier for per-zone cooldown
     * @param explicitBeat            whether an explicit beat was detected upstream
     * @param explicitBeatIntensity   intensity of the explicit beat (0-1)
     * @param bpm                     current estimated BPM
     * @param tempoConfidence         confidence in the tempo estimate (0-1)
     * @param beatPhase               current beat phase (0-1)
     * @param lastBeatTimestampByZone mutable map tracking last beat time per zone
     * @return projection result
     */
    public static BeatProjection projectBeat(
        String zoneName,
        boolean explicitBeat,
        double explicitBeatIntensity,
        double bpm,
        double tempoConfidence,
        double beatPhase,
        Map<String, Long> lastBeatTimestampByZone
    ) {
        long nowMs = System.currentTimeMillis();
        double clampedExplicitIntensity = InputSanitizer.sanitizeDouble(explicitBeatIntensity, 0.0, 1.0, 0.0);

        if (explicitBeat) {
            lastBeatTimestampByZone.put(zoneName, nowMs);
            return new BeatProjection(true, clampedExplicitIntensity);
        }

        if (tempoConfidence < MIN_PHASE_ASSIST_CONFIDENCE || bpm < MIN_PHASE_ASSIST_BPM) {
            return new BeatProjection(false, 0.0);
        }

        boolean nearTickEdge = beatPhase <= PHASE_EDGE_WINDOW || beatPhase >= (1.0 - PHASE_EDGE_WINDOW);
        if (!nearTickEdge) {
            return new BeatProjection(false, 0.0);
        }

        double beatPeriodMs = 60000.0 / bpm;
        long minIntervalMs = (long) Math.max(
            SYNTH_BEAT_COOLDOWN_MIN_MS,
            beatPeriodMs * SYNTH_BEAT_COOLDOWN_FRACTION
        );
        long lastBeatMs = lastBeatTimestampByZone.getOrDefault(zoneName, 0L);
        if (nowMs - lastBeatMs < minIntervalMs) {
            return new BeatProjection(false, 0.0);
        }

        double projectedIntensity = Math.max(SYNTH_BEAT_MIN_INTENSITY, tempoConfidence * 0.65);
        projectedIntensity = InputSanitizer.sanitizeDouble(projectedIntensity, 0.0, 1.0, SYNTH_BEAT_MIN_INTENSITY);
        lastBeatTimestampByZone.put(zoneName, nowMs);
        return new BeatProjection(true, projectedIntensity);
    }
}
