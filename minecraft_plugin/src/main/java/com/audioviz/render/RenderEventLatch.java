package com.audioviz.render;

import com.audioviz.protocol.MessageQueue;

import java.util.Objects;

/**
 * Fixed-memory accumulator for transient render events between tick drains.
 */
public final class RenderEventLatch {

    private final long[] beatSequences;
    private final byte[] beatFlags;
    private final double[] beatIntensities;
    private final MessageQueue.MessageGuard[] beatGuards;
    private final RenderParticleEvent[] particles;
    private final MessageQueue.MessageGuard[] particleGuards;

    private int beatCount;
    private int particleCount;
    private long rejectedBeatEvents;
    private long rejectedParticleEvents;
    private long deduplicatedBeatEvents;
    private long deduplicatedParticleEvents;

    public RenderEventLatch(int capacity) {
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

    public synchronized void latchBeat(
            long eventSequence,
            boolean beat,
            boolean kick,
            double intensity,
            MessageQueue.MessageGuard guard
    ) {
        Objects.requireNonNull(guard, "guard");
        for (int index = 0; index < beatCount; index++) {
            if (beatSequences[index] == eventSequence && beatGuards[index] == guard) {
                deduplicatedBeatEvents++;
                return;
            }
        }
        if (beatCount == beatSequences.length) {
            rejectedBeatEvents++;
            return;
        }
        int slot = beatCount++;
        beatSequences[slot] = eventSequence;
        beatFlags[slot] = (byte) ((beat ? 1 : 0) | (kick ? 2 : 0));
        beatIntensities[slot] = intensity;
        beatGuards[slot] = guard;
    }

    public synchronized boolean offerParticle(
            RenderParticleEvent particle,
            MessageQueue.MessageGuard guard
    ) {
        Objects.requireNonNull(particle, "particle");
        Objects.requireNonNull(guard, "guard");
        for (int index = 0; index < particleCount; index++) {
            if (particles[index].eventId() == particle.eventId()
                    && particleGuards[index] == guard) {
                deduplicatedParticleEvents++;
                return false;
            }
        }
        if (particleCount == particles.length) {
            rejectedParticleEvents++;
            return false;
        }
        int slot = particleCount++;
        particles[slot] = particle;
        particleGuards[slot] = guard;
        return true;
    }

    public synchronized void drainInto(DrainedRenderEvents drained) {
        Objects.requireNonNull(drained, "drained");
        drained.requireCapacity(beatCount, particleCount);
        drained.reset();
        for (int index = 0; index < beatCount; index++) {
            drained.addBeat(
                    beatSequences[index],
                    beatFlags[index],
                    beatIntensities[index],
                    beatGuards[index]);
            beatGuards[index] = null;
        }
        for (int index = 0; index < particleCount; index++) {
            drained.addParticle(particles[index], particleGuards[index]);
            particles[index] = null;
            particleGuards[index] = null;
        }
        beatCount = 0;
        particleCount = 0;
    }

    public synchronized long rejectedBeatEvents() {
        return rejectedBeatEvents;
    }

    public synchronized long rejectedParticleEvents() {
        return rejectedParticleEvents;
    }

    public synchronized long deduplicatedBeatEvents() {
        return deduplicatedBeatEvents;
    }

    public synchronized long deduplicatedParticleEvents() {
        return deduplicatedParticleEvents;
    }
}
