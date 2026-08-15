package com.audioviz.render;

import org.junit.jupiter.api.Test;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneSnapshotMailboxTest {

    @Test
    void newerPublicationSupersedesWithoutOverwritingReadingSlot() {
        ZoneSnapshotMailbox mailbox = new ZoneSnapshotMailbox("main", 8, 0, 3);
        ZoneRenderSnapshot first = requireNonNull(mailbox.tryClaim(10));
        first.entityCount(1);
        first.x()[0] = 0.25f;
        assertTrue(mailbox.publish(first));

        ZoneRenderSnapshot reading = requireNonNull(mailbox.takeLatest());
        ZoneRenderSnapshot second = requireNonNull(mailbox.tryClaim(11));
        second.entityCount(1);
        second.x()[0] = 0.75f;
        assertTrue(mailbox.publish(second));

        assertEquals(0.25f, reading.x()[0]);
        mailbox.releaseAfterRead(reading);
        assertSame(second, mailbox.takeLatest());
    }

    @Test
    void staleCompletionCannotReplaceNewerPublication() {
        ZoneSnapshotMailbox mailbox = new ZoneSnapshotMailbox("main", 8, 0, 4);
        ZoneRenderSnapshot slow = requireNonNull(mailbox.tryClaim(20));
        ZoneRenderSnapshot fast = requireNonNull(mailbox.tryClaim(21));
        assertTrue(mailbox.publish(fast));
        assertFalse(mailbox.publish(slow));
        assertSame(fast, mailbox.takeLatest());
    }

    @Test
    void supersededPendingSlotReturnsToTheClaimablePool() {
        ZoneSnapshotMailbox mailbox = new ZoneSnapshotMailbox("main", 1, 0, 2);
        ZoneRenderSnapshot first = requireNonNull(mailbox.tryClaim(30));
        ZoneRenderSnapshot second = requireNonNull(mailbox.tryClaim(31));

        assertTrue(mailbox.publish(first));
        assertTrue(mailbox.publish(second));

        assertSame(first, mailbox.tryClaim(32));
        assertSame(second, mailbox.takeLatest());
    }

    @Test
    void failedClaimLeavesReadingAndWritingSlotsOwnedByTheirCallers() {
        ZoneSnapshotMailbox mailbox = new ZoneSnapshotMailbox("main", 1, 0, 2);
        ZoneRenderSnapshot reading = requireNonNull(mailbox.tryClaim(40));
        reading.x()[0] = 0.4f;
        assertTrue(mailbox.publish(reading));
        assertSame(reading, mailbox.takeLatest());

        ZoneRenderSnapshot writing = requireNonNull(mailbox.tryClaim(41));
        writing.x()[0] = 0.8f;
        assertNull(mailbox.tryClaim(42));
        assertEquals(0.4f, reading.x()[0]);

        assertTrue(mailbox.publish(writing));
        mailbox.releaseAfterRead(reading);
        assertSame(writing, mailbox.takeLatest());
    }

    @Test
    void rejectedStalePublicationReturnsItsSlotToTheClaimablePool() {
        ZoneSnapshotMailbox mailbox = new ZoneSnapshotMailbox("main", 1, 0, 3);
        ZoneRenderSnapshot slow = requireNonNull(mailbox.tryClaim(50));
        ZoneRenderSnapshot fast = requireNonNull(mailbox.tryClaim(51));

        assertTrue(mailbox.publish(fast));
        assertFalse(mailbox.publish(slow));

        assertSame(slow, mailbox.tryClaim(52));
        assertSame(fast, mailbox.takeLatest());
    }
}
