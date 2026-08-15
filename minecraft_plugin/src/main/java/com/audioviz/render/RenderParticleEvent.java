package com.audioviz.render;

/** Immutable normalized particle event retained independently of state snapshots. */
public record RenderParticleEvent(
        long eventId,
        int particleTypeId,
        float x,
        float y,
        float z,
        int count
) {
}
