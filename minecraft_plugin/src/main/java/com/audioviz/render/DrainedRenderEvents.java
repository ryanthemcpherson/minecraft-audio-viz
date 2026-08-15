package com.audioviz.render;

import com.audioviz.protocol.MessageQueue;

/**
 * Caller-owned fixed storage reused when draining a {@link RenderEventLatch}.
 */
public final class DrainedRenderEvents {

    private final long[] beatSequences;
    private final byte[] beatFlags;
    private final double[] beatIntensities;
    private final MessageQueue.MessageGuard[] beatGuards;
    private final RenderParticleEvent[] particles;
    private final MessageQueue.MessageGuard[] particleGuards;

    private int beatCount;
    private int particleCount;
    private boolean beat;
    private boolean kick;
    private double beatIntensity;

    public DrainedRenderEvents(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        beatSequences = new long[capacity];
        beatFlags = new byte[capacity];
        beatIntensities = new double[capacity];
        beatGuards = new MessageQueue.MessageGuard[capacity];
        particles = new RenderParticleEvent[capacity];
        particleGuards = new MessageQueue.MessageGuard[capacity];
    }

    public int beatCount() {
        return beatCount;
    }

    public boolean beat() {
        return beat;
    }

    public boolean kick() {
        return kick;
    }

    public double beatIntensity() {
        return beatIntensity;
    }

    public long beatSequence(int index) {
        checkIndex(index, beatCount, "beat");
        return beatSequences[index];
    }

    public boolean beat(int index) {
        checkIndex(index, beatCount, "beat");
        return (beatFlags[index] & 1) != 0;
    }

    public boolean kick(int index) {
        checkIndex(index, beatCount, "beat");
        return (beatFlags[index] & 2) != 0;
    }

    public double beatIntensity(int index) {
        checkIndex(index, beatCount, "beat");
        return beatIntensities[index];
    }

    public MessageQueue.MessageGuard beatGuard(int index) {
        checkIndex(index, beatCount, "beat");
        return beatGuards[index];
    }

    public int particleCount() {
        return particleCount;
    }

    public RenderParticleEvent particle(int index) {
        checkIndex(index, particleCount, "particle");
        return particles[index];
    }

    public MessageQueue.MessageGuard particleGuard(int index) {
        checkIndex(index, particleCount, "particle");
        return particleGuards[index];
    }

    void requireCapacity(int requiredBeats, int requiredParticles) {
        if (requiredBeats > beatSequences.length || requiredParticles > particles.length) {
            throw new IllegalArgumentException("drain target is smaller than the latched event set");
        }
    }

    void reset() {
        for (int index = 0; index < beatCount; index++) {
            beatGuards[index] = null;
        }
        for (int index = 0; index < particleCount; index++) {
            particles[index] = null;
            particleGuards[index] = null;
        }
        beatCount = 0;
        particleCount = 0;
        beat = false;
        kick = false;
        beatIntensity = 0;
    }

    void addBeat(
            long sequence,
            byte flags,
            double intensity,
            MessageQueue.MessageGuard guard
    ) {
        int slot = beatCount++;
        beatSequences[slot] = sequence;
        beatFlags[slot] = flags;
        beatIntensities[slot] = intensity;
        beatGuards[slot] = guard;
        beat |= (flags & 1) != 0;
        kick |= (flags & 2) != 0;
        beatIntensity = Math.max(beatIntensity, intensity);
    }

    void addParticle(RenderParticleEvent particle, MessageQueue.MessageGuard guard) {
        int slot = particleCount++;
        particles[slot] = particle;
        particleGuards[slot] = guard;
    }

    private static void checkIndex(int index, int count, String eventType) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(eventType + " event index: " + index);
        }
    }
}
