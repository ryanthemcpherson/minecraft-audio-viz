package com.audioviz.render;

import com.audioviz.protocol.MessageQueue;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderEventLatchTest {

    private static final MessageQueue.MessageGuard ALLOW_ALL = operation -> {
        operation.run();
        return true;
    };

    @Test
    void beatAndParticlesSurviveSnapshotSupersessionAndDeduplicate() {
        RenderEventLatch latch = new RenderEventLatch(4);
        latch.latchBeat(7, true, true, 0.4, ALLOW_ALL);
        latch.latchBeat(8, true, false, 0.9, ALLOW_ALL);
        assertTrue(latch.offerParticle(
            new RenderParticleEvent(12, 0, "NOTE", .5f, .5f, .5f, 20), ALLOW_ALL));
        assertFalse(latch.offerParticle(
            new RenderParticleEvent(12, 0, "NOTE", .5f, .5f, .5f, 20), ALLOW_ALL));

        DrainedRenderEvents drained = new DrainedRenderEvents(4);
        latch.drainInto(drained);

        assertTrue(drained.beat());
        assertTrue(drained.kick());
        assertEquals(0.9, drained.beatIntensity(), 1e-9);
        assertEquals(1, drained.particleCount());
        assertEquals(0, drained.particle(0).particleTypeId());
        assertEquals("NOTE", drained.particle(0).particleName());
        latch.drainInto(drained);
        assertFalse(drained.beat());
        assertEquals(0, drained.particleCount());
    }

    @Test
    void identicalSequencesFromDifferentGuardsRemainIndependent() {
        MessageQueue.MessageGuard rejectedGuard = operation -> false;
        AtomicInteger validExecutions = new AtomicInteger();
        MessageQueue.MessageGuard validGuard = operation -> {
            operation.run();
            return true;
        };
        RenderEventLatch latch = new RenderEventLatch(2);
        latch.latchBeat(9, true, false, 0.3, rejectedGuard);
        latch.latchBeat(9, false, true, 0.8, validGuard);

        DrainedRenderEvents drained = new DrainedRenderEvents(2);
        latch.drainInto(drained);

        assertEquals(2, drained.beatCount());
        assertFalse(drained.beatGuard(0).runIfValid(validExecutions::incrementAndGet));
        assertTrue(drained.beatGuard(1).runIfValid(validExecutions::incrementAndGet));
        assertEquals(1, validExecutions.get());
        assertTrue(drained.beat());
        assertTrue(drained.kick());
        assertEquals(0.8, drained.beatIntensity(), 1e-9);
    }

    @Test
    void particleDeduplicationIsScopedToGuardIdentity() {
        MessageQueue.MessageGuard anotherGuard = operation -> {
            operation.run();
            return true;
        };
        RenderParticleEvent first = new RenderParticleEvent(14, 2, .1f, .2f, .3f, 5);
        RenderParticleEvent sameIdOtherConnection =
            new RenderParticleEvent(14, 3, .7f, .8f, .9f, 6);
        RenderEventLatch latch = new RenderEventLatch(2);

        assertTrue(latch.offerParticle(first, ALLOW_ALL));
        assertTrue(latch.offerParticle(sameIdOtherConnection, anotherGuard));

        DrainedRenderEvents drained = new DrainedRenderEvents(2);
        latch.drainInto(drained);
        assertEquals(2, drained.particleCount());
        assertSame(first, drained.particle(0));
        assertSame(ALLOW_ALL, drained.particleGuard(0));
        assertSame(sameIdOtherConnection, drained.particle(1));
        assertSame(anotherGuard, drained.particleGuard(1));
    }

    @Test
    void fixedCapacityRejectsAndCountsNewestUniqueEvents() {
        RenderEventLatch latch = new RenderEventLatch(1);
        latch.latchBeat(1, true, false, 0.2, ALLOW_ALL);
        latch.latchBeat(2, true, true, 0.7, ALLOW_ALL);
        RenderParticleEvent accepted = new RenderParticleEvent(20, 4, .1f, .2f, .3f, 1);

        assertTrue(latch.offerParticle(accepted, ALLOW_ALL));
        assertFalse(latch.offerParticle(
            new RenderParticleEvent(21, 4, .4f, .5f, .6f, 2), ALLOW_ALL));
        assertEquals(1, latch.rejectedBeatEvents());
        assertEquals(1, latch.rejectedParticleEvents());

        DrainedRenderEvents drained = new DrainedRenderEvents(1);
        latch.drainInto(drained);
        assertEquals(1, drained.beatCount());
        assertEquals(0.2, drained.beatIntensity(), 1e-9);
        assertEquals(1, drained.particleCount());
        assertSame(accepted, drained.particle(0));
    }
}
