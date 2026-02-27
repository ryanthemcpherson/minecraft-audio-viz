package com.audioviz.decorators;

/**
 * Immutable record holding current DJ information.
 * Pure Java — no Bukkit or Fabric dependencies.
 */
public record DJInfo(
    String djName,
    String djId,
    double bpm,
    boolean isActive,
    long timestamp
) {
    private static final DJInfo NONE = new DJInfo("", "", 0.0, false, 0L);

    public static DJInfo none() { return NONE; }

    public boolean isPresent() {
        return !djName.isEmpty() || !djId.isEmpty();
    }

    public boolean isDifferentDJ(DJInfo other) {
        if (other == null) return isPresent();
        return !djId.equals(other.djId);
    }
}
