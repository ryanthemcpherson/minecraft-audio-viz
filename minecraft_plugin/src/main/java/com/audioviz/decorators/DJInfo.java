package com.audioviz.decorators;

/**
 * Immutable record holding current DJ information received from the Python processor.
 * Used by decorators to display DJ name, BPM, and trigger transition effects.
 */
public record DJInfo(
    String djName,
    String djId,
    double bpm,
    boolean isActive,
    long timestamp
) {

    private static final DJInfo NONE = new DJInfo("", "", 0.0, false, 0L);

    /**
     * Returns a sentinel "no DJ" instance with empty fields.
     */
    public static DJInfo none() {
        return NONE;
    }

    /**
     * Check if this represents an actual DJ (not the "none" sentinel).
     */
    public boolean isPresent() {
        return !djName.isEmpty() || !djId.isEmpty();
    }

    /**
     * Check if this DJ is different from another (for transition detection).
     */
    public boolean isDifferentDJ(DJInfo other) {
        if (other == null) return isPresent();
        return !djId.equals(other.djId);
    }
}
