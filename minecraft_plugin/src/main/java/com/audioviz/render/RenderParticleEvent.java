package com.audioviz.render;

/** Immutable normalized particle event retained independently of state snapshots. */
public record RenderParticleEvent(
        long eventId,
        int particleTypeId,
        String particleName,
        float x,
        float y,
        float z,
        int count
) {

    public RenderParticleEvent {
        if (particleTypeId < 0 || particleTypeId > 0xFFFF) {
            throw new IllegalArgumentException("particleTypeId must be unsigned 16-bit");
        }
        if (particleTypeId == 0 && (particleName == null || particleName.isEmpty())) {
            throw new IllegalArgumentException("JSON particle events require a particle name");
        }
    }

    /** Numeric dictionary event used by the binary path. */
    public RenderParticleEvent(
            long eventId,
            int particleTypeId,
            float x,
            float y,
            float z,
            int count
    ) {
        this(eventId, particleTypeId, null, x, y, z, count);
    }
}
