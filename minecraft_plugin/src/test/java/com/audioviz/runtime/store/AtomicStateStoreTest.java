package com.audioviz.runtime.store;

import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.AtomicStateStore.Boundary;
import com.audioviz.runtime.store.RuntimeState.HealthState;
import com.audioviz.runtime.store.RuntimeTransaction.TransactionPhase;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicStateStoreTest {

    private static final String ARCHIVE_DIGEST = "a".repeat(64);
    private static final String FILES_DIGEST = "b".repeat(64);

    @TempDir
    Path temp;

    private RuntimePaths paths;
    private AtomicStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        paths = RuntimePaths.create(temp.resolve("runtime"));
        store = new AtomicStateStore(paths, boundary -> {});
    }

    @Test
    void roundTripsStrictStateAndTransactionRecords() throws Exception {
        RuntimeState state = state("1.2.0", 12, HealthState.CANDIDATE);
        RuntimeTransaction transaction = new RuntimeTransaction(
            1,
            UUID.fromString("00000000-0000-0000-0000-000000000012"),
            TransactionPhase.CANDIDATE_ACTIVE,
            null,
            state,
            state("1.1.0", 11, HealthState.HEALTHY)
        );

        store.writeTransaction(transaction);
        store.writeState(
            paths.currentState(),
            state,
            Boundary.CURRENT_TEMP_WRITE,
            Boundary.CURRENT_REPLACE
        );

        assertEquals(transaction, store.readTransaction().orElseThrow());
        assertEquals(state, store.readState(paths.currentState()).orElseThrow());
        assertFalse(Files.readString(paths.currentState()).contains("\\"));
    }

    @Test
    void rejectsUnknownDuplicateTrailingAndAbsolutePathState() throws Exception {
        String valid = stateJson("1.2.0");
        String[] invalid = {
            valid.replace("\"schema_version\":1", "\"schema_version\":1,\"extra\":1"),
            valid.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"),
            valid + "{}",
            valid.replace("\"release_version\":\"1.2.0\"", "\"release_version\":\"C:/escape\"")
        };

        for (String document : invalid) {
            Files.writeString(paths.currentState(), document, StandardCharsets.UTF_8);
            RuntimeStoreException failure = assertThrows(
                RuntimeStoreException.class,
                () -> store.readState(paths.currentState())
            );
            assertEquals(INVALID_STATE, failure.reason());
        }
    }

    @Test
    void rejectsInvalidUtf8AndOversizedState() throws Exception {
        Files.write(paths.currentState(), new byte[]{(byte) 0xc3, (byte) 0x28});
        RuntimeStoreException invalidUtf8 = assertThrows(
            RuntimeStoreException.class,
            () -> store.readState(paths.currentState())
        );
        assertEquals(INVALID_STATE, invalidUtf8.reason());

        Files.write(paths.currentState(), new byte[AtomicStateStore.MAXIMUM_STATE_BYTES + 1]);
        RuntimeStoreException oversized = assertThrows(
            RuntimeStoreException.class,
            () -> store.readState(paths.currentState())
        );
        assertEquals(INVALID_STATE, oversized.reason());
    }

    @Test
    void failedReplaceLeavesPreviousStateReadableAndCleansTemporaryFile() throws Exception {
        RuntimeState previous = state("1.1.0", 11, HealthState.HEALTHY);
        store.writeState(
            paths.currentState(),
            previous,
            Boundary.CURRENT_TEMP_WRITE,
            Boundary.CURRENT_REPLACE
        );
        AtomicStateStore failing = new AtomicStateStore(paths, boundary -> {
            if (boundary == Boundary.CURRENT_TEMP_WRITE) {
                throw new java.io.IOException("injected");
            }
        });

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> failing.writeState(
                paths.currentState(),
                state("1.2.0", 12, HealthState.CANDIDATE),
                Boundary.CURRENT_TEMP_WRITE,
                Boundary.CURRENT_REPLACE
            )
        );

        assertEquals(RuntimeStoreException.FailureReason.IO_FAILURE, failure.reason());
        assertEquals(previous, store.readState(paths.currentState()).orElseThrow());
        try (var entries = Files.list(paths.state())) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void nonAtomicReplacementRequiresAnExistingJournal() throws Exception {
        RuntimeState previous = state("1.1.0", 11, HealthState.HEALTHY);
        RuntimeState candidate = state("1.2.0", 12, HealthState.CANDIDATE);
        store.writeState(
            paths.currentState(),
            previous,
            Boundary.CURRENT_TEMP_WRITE,
            Boundary.CURRENT_REPLACE
        );
        AtomicStateStore withoutJournal = unsupportedAtomicMoves();

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> withoutJournal.writeState(
                paths.currentState(),
                candidate,
                Boundary.CURRENT_TEMP_WRITE,
                Boundary.CURRENT_REPLACE
            )
        );

        assertEquals(RuntimeStoreException.FailureReason.ATOMIC_MOVE_UNSUPPORTED, failure.reason());
        assertEquals(previous, store.readState(paths.currentState()).orElseThrow());
    }

    @Test
    void journalAllowsRecoverableFallbackWhenAtomicReplaceIsUnavailable() throws Exception {
        RuntimeState previous = state("1.1.0", 11, HealthState.HEALTHY);
        RuntimeState candidate = state("1.2.0", 12, HealthState.CANDIDATE);
        store.writeState(
            paths.currentState(),
            previous,
            Boundary.CURRENT_TEMP_WRITE,
            Boundary.CURRENT_REPLACE
        );
        store.writeTransaction(new RuntimeTransaction(
            1,
            UUID.fromString("00000000-0000-0000-0000-000000000013"),
            TransactionPhase.CANDIDATE_ACTIVATING,
            null,
            candidate,
            previous
        ));

        unsupportedAtomicMoves().writeState(
            paths.currentState(),
            candidate,
            Boundary.CURRENT_TEMP_WRITE,
            Boundary.CURRENT_REPLACE
        );

        assertEquals(candidate, store.readState(paths.currentState()).orElseThrow());
    }

    @Test
    void rejectsTraversalInPromotionJournalStagingIdentifier() throws Exception {
        RuntimeState candidate = state("1.2.0", 12, HealthState.CANDIDATE);
        RuntimeTransaction transaction = new RuntimeTransaction(
            1,
            UUID.fromString("00000000-0000-0000-0000-000000000014"),
            TransactionPhase.PROMOTION_PREPARED,
            "stage-00000000-0000-0000-0000-000000000014",
            candidate,
            null
        );
        store.writeTransaction(transaction);
        String malicious = Files.readString(paths.transactionJournal())
            .replace("stage-00000000-0000-0000-0000-000000000014", "../escape");
        Files.writeString(paths.transactionJournal(), malicious, StandardCharsets.UTF_8);

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            store::readTransaction
        );

        assertEquals(INVALID_STATE, failure.reason());
    }

    private AtomicStateStore unsupportedAtomicMoves() {
        return new AtomicStateStore(paths, boundary -> {}, (source, destination, options) -> {
            throw new AtomicMoveNotSupportedException(
                source.toString(),
                destination.toString(),
                "injected"
            );
        });
    }

    private static RuntimeState state(String version, long generation, HealthState health) {
        return new RuntimeState(
            1,
            RuntimeVersionId.of(version, RuntimePlatform.LINUX_X86_64),
            generation,
            ARCHIVE_DIGEST,
            FILES_DIGEST,
            1,
            Instant.parse("2026-08-31T12:00:00Z"),
            health
        );
    }

    private static String stateJson(String version) {
        return "{\"schema_version\":1,\"id\":{\"release_version\":\"" + version +
            "\",\"platform\":\"linux-x86_64\"},\"manifest_generation\":12," +
            "\"archive_sha256\":\"" + ARCHIVE_DIGEST + "\",\"files_manifest_sha256\":\"" +
            FILES_DIGEST + "\",\"runtime_api\":1,\"activated_at\":\"2026-08-31T12:00:00Z\"," +
            "\"health\":\"candidate\"}\n";
    }
}
