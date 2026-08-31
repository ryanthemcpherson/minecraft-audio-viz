package com.audioviz.runtime.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.RuntimeState.HealthState;
import com.audioviz.runtime.store.RuntimeTransaction.TransactionPhase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeStoreValueTest {
    private static final RuntimeVersionId ID = RuntimeVersionId.of(
        "1.2.0",
        RuntimePlatform.LINUX_X86_64
    );
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void runtimeStateRejectsEveryInvalidFieldClass() {
        assertThrows(NullPointerException.class, () -> state(null, 1, 1, digest('a'), digest('b'), NOW, HealthState.CANDIDATE));
        assertThrows(NullPointerException.class, () -> state(ID, 1, 1, digest('a'), digest('b'), null, HealthState.CANDIDATE));
        assertThrows(NullPointerException.class, () -> state(ID, 1, 1, digest('a'), digest('b'), NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeState(2, ID, 1, digest('a'), digest('b'), 1, NOW, HealthState.CANDIDATE));
        assertThrows(IllegalArgumentException.class, () -> state(ID, -1, 1, digest('a'), digest('b'), NOW, HealthState.CANDIDATE));
        assertThrows(IllegalArgumentException.class, () -> state(ID, 1, 0, digest('a'), digest('b'), NOW, HealthState.CANDIDATE));
        for (String invalid : new String[]{null, "", "a".repeat(63), "g".repeat(64), "A".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> state(ID, 1, 1, invalid, digest('b'), NOW, HealthState.CANDIDATE));
            assertThrows(IllegalArgumentException.class, () -> state(ID, 1, 1, digest('a'), invalid, NOW, HealthState.CANDIDATE));
        }
    }

    @Test
    void runtimeStateHealthTransitionsAndWireNamesAreExact() {
        RuntimeState candidate = state(
            ID,
            1,
            1,
            "0123456789abcdef".repeat(4),
            digest('f'),
            NOW,
            HealthState.CANDIDATE
        );

        assertEquals(HealthState.HEALTHY, candidate.withHealth(HealthState.HEALTHY).health());
        assertEquals(HealthState.CANDIDATE, HealthState.fromWireName("candidate"));
        assertEquals(HealthState.HEALTHY, HealthState.fromWireName("healthy"));
        assertThrows(IllegalArgumentException.class, () -> HealthState.fromWireName("unknown"));
    }

    @Test
    void transactionRejectsInvalidPhaseCombinationsAndIdentifiers() {
        RuntimeState candidate = validState();
        UUID operation = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String staging = "stage-" + operation;
        assertThrows(NullPointerException.class, () -> new RuntimeTransaction(1, null, TransactionPhase.CANDIDATE_ACTIVE, null, candidate, null));
        assertThrows(NullPointerException.class, () -> new RuntimeTransaction(1, operation, null, null, candidate, null));
        assertThrows(NullPointerException.class, () -> new RuntimeTransaction(1, operation, TransactionPhase.CANDIDATE_ACTIVE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeTransaction(2, operation, TransactionPhase.CANDIDATE_ACTIVE, null, candidate, null));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeTransaction(1, operation, TransactionPhase.PROMOTION_PREPARED, staging, candidate, candidate));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeTransaction(1, operation, TransactionPhase.CANDIDATE_ACTIVE, staging, candidate, null));
        for (String invalid : new String[]{null, "stage-short", "other-" + operation, "stage-00000000-0000-0000-0000-00000000000G", "stage-00000000-0000-0000-0000-00000000000A"}) {
            assertThrows(IllegalArgumentException.class, () -> new RuntimeTransaction(1, operation, TransactionPhase.PROMOTION_PREPARED, invalid, candidate, null));
        }

        RuntimeTransaction promotion = new RuntimeTransaction(
            1,
            operation,
            TransactionPhase.PROMOTION_PREPARED,
            staging,
            candidate,
            null
        );
        assertEquals(TransactionPhase.CANDIDATE_ACTIVE, promotion.withPhase(TransactionPhase.CANDIDATE_ACTIVE).phase());
        assertEquals(null, promotion.withPhase(TransactionPhase.CANDIDATE_ACTIVE).sourceStagingId());
        for (TransactionPhase phase : TransactionPhase.values()) {
            assertEquals(phase, TransactionPhase.fromWireName(phase.wireName()));
        }
        assertThrows(IllegalArgumentException.class, () -> TransactionPhase.fromWireName("unknown"));
    }

    @Test
    void packagedFileRejectsNullsAndNegativeSize() {
        assertThrows(NullPointerException.class, () -> new PackagedFile(null, 0, digest('a'), false));
        assertThrows(NullPointerException.class, () -> new PackagedFile("file", 0, null, false));
        assertThrows(IllegalArgumentException.class, () -> new PackagedFile("file", -1, digest('a'), false));
        assertEquals(0, new PackagedFile("file", 0, digest('a'), false).size());
    }

    private static RuntimeState validState() {
        return state(ID, 1, 1, digest('a'), digest('b'), NOW, HealthState.CANDIDATE);
    }

    private static RuntimeState state(
        RuntimeVersionId id,
        long generation,
        int runtimeApi,
        String archiveDigest,
        String filesDigest,
        Instant activatedAt,
        HealthState health
    ) {
        return new RuntimeState(
            1,
            id,
            generation,
            archiveDigest,
            filesDigest,
            runtimeApi,
            activatedAt,
            health
        );
    }

    private static String digest(char character) {
        return Character.toString(character).repeat(64);
    }
}
