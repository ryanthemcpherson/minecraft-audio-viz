package com.audioviz.runtime.store;

import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_STATE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.PATH_ESCAPE;
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

        Files.write(paths.currentState(), new byte[0]);
        RuntimeStoreException empty = assertThrows(
            RuntimeStoreException.class,
            () -> store.readState(paths.currentState())
        );
        assertEquals(INVALID_STATE, empty.reason());

        Files.delete(paths.currentState());
        Files.createDirectory(paths.currentState());
        RuntimeStoreException directory = assertThrows(
            RuntimeStoreException.class,
            () -> store.readState(paths.currentState())
        );
        assertEquals(INVALID_STATE, directory.reason());
    }

    @Test
    void rejectsMalformedStateFieldValues() throws Exception {
        String valid = stateJson("1.2.0");
        String[] invalid = {
            "[]",
            valid.replace("\"schema_version\":1", "\"schema_version\":2"),
            valid.replace("\"id\":{", "\"id\":[] ,\"ignored\":{"),
            valid.replace(",\"platform\":\"linux-x86_64\"", ""),
            valid.replace("\"release_version\":\"1.2.0\"", "\"release_version\":\"\""),
            valid.replace("\"release_version\":\"1.2.0\"", "\"release_version\":\"" + "a".repeat(65) + "\""),
            valid.replace("\"manifest_generation\":12", "\"manifest_generation\":-1"),
            valid.replace("\"manifest_generation\":12", "\"manifest_generation\":1.5"),
            valid.replace("\"manifest_generation\":12", "\"manifest_generation\":01"),
            valid.replace("\"manifest_generation\":12", "\"manifest_generation\":999999999999999999999"),
            valid.replace("\"runtime_api\":1", "\"runtime_api\":0"),
            valid.replace("\"runtime_api\":1", "\"runtime_api\":\"1\""),
            valid.replace("\"runtime_api\":1", "\"runtime_api\":2147483648"),
            valid.replace("\"archive_sha256\":\"" + ARCHIVE_DIGEST + "\"", "\"archive_sha256\":\"A" + "a".repeat(63) + "\""),
            valid.replace("\"archive_sha256\":\"" + ARCHIVE_DIGEST + "\"", "\"archive_sha256\":\":" + "a".repeat(63) + "\""),
            valid.replace("\"archive_sha256\":\"" + ARCHIVE_DIGEST + "\"", "\"archive_sha256\":\"g" + "a".repeat(63) + "\""),
            valid.replace("\"files_manifest_sha256\":\"" + FILES_DIGEST + "\"", "\"files_manifest_sha256\":\"short\""),
            valid.replace("\"activated_at\":\"2026-08-31T12:00:00Z\"", "\"activated_at\":\"2026-08-31T12:00:00.000Z\""),
            valid.replace("\"activated_at\":\"2026-08-31T12:00:00Z\"", "\"activated_at\":\"not-an-instant\""),
            valid.replace("\"health\":\"candidate\"", "\"health\":\"unknown\""),
            valid.replace("\"health\":\"candidate\"", "\"health\":\"\""),
            valid.replace("\"platform\":\"linux-x86_64\"", "\"platform\":\"solaris\""),
            valid.replace("\"platform\":\"linux-x86_64\"", "\"platform\":1"),
            valid.replace("\"id\":{", "\"id\":{\"extra\":1,"),
            valid.replace(",\"health\":\"candidate\"", "")
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
    void rejectsMalformedTransactionFieldValues() throws Exception {
        RuntimeState candidate = state("1.2.0", 12, HealthState.CANDIDATE);
        RuntimeTransaction transaction = new RuntimeTransaction(
            1,
            UUID.fromString("abcdef00-0000-0000-0000-000000000012"),
            TransactionPhase.CANDIDATE_ACTIVE,
            null,
            candidate,
            null
        );
        store.writeTransaction(transaction);
        String valid = Files.readString(paths.transactionJournal());
        String[] invalid = {
            "[]",
            valid.replace("\"schema_version\":1", "\"schema_version\":2"),
            valid.replace("abcdef00-", "ABCDEF00-"),
            valid.replace("abcdef00-0000-0000-0000-000000000012", "not-a-uuid"),
            valid.replace("\"candidate_active\"", "\"unknown\""),
            valid.replace("\"source_staging_id\":null", "\"source_staging_id\":\"stage-bad\""),
            valid.replace("\"source_staging_id\":null", "\"source_staging_id\":true"),
            valid.replace("\"candidate\":{", "\"candidate\":[] ,\"ignored\":{"),
            valid.replace("\"candidate\":{", "\"candidate\":null,\"discarded\":{") ,
            valid.replace("\"previous\":null", "\"previous\":{}"),
            valid.replace("\"previous\":null", "\"previous\":[]"),
            valid.replace("\"phase\":\"candidate_active\"", "\"phase\":\"candidate_active\",\"phase\":\"candidate_active\""),
            valid.replace("\"schema_version\":1", "\"unknown\":1,\"schema_version\":1"),
            valid.replace("\"source_staging_id\":null,", ""),
            valid.replace(",\"previous\":null", ""),
            valid + "{}"
        };

        for (String document : invalid) {
            Files.writeString(paths.transactionJournal(), document, StandardCharsets.UTF_8);
            RuntimeStoreException failure = assertThrows(
                RuntimeStoreException.class,
                store::readTransaction
            );
            assertEquals(INVALID_STATE, failure.reason());
        }
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
    void nonAtomicFallbackCanCreateANewStateWithoutJournal() throws Exception {
        RuntimeState candidate = state("1.2.0", 12, HealthState.CANDIDATE);

        unsupportedAtomicMoves().writeState(
            paths.currentState(),
            candidate,
            Boundary.CURRENT_TEMP_WRITE,
            Boundary.CURRENT_REPLACE
        );

        assertEquals(candidate, store.readState(paths.currentState()).orElseThrow());
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

    @Test
    void rejectsUnownedStateMoveAndDeleteTargets() throws Exception {
        RuntimeState candidate = state("1.2.0", 12, HealthState.CANDIDATE);
        Path outside = temp.resolve("outside.json");
        assertReason(PATH_ESCAPE, () -> store.readState(outside));
        assertReason(
            PATH_ESCAPE,
            () -> store.writeState(
                outside,
                candidate,
                Boundary.CURRENT_TEMP_WRITE,
                Boundary.CURRENT_REPLACE
            )
        );
        assertReason(
            PATH_ESCAPE,
            () -> store.moveVersion(
                temp.resolve("outside-stage"),
                paths.versions().resolve("1.2.0--linux-x86_64")
            )
        );
        assertReason(
            PATH_ESCAPE,
            () -> store.moveVersion(
                paths.staging().resolve("missing"),
                paths.versions().resolve("1.2.0--linux-x86_64")
            )
        );

        Path missingStaging = paths.staging().resolve("missing-delete");
        store.deleteStaging(missingStaging);
        Path stagingFile = Files.writeString(paths.staging().resolve("file"), "file");
        assertReason(PATH_ESCAPE, () -> store.deleteStaging(stagingFile));
        assertReason(PATH_ESCAPE, () -> store.deleteVersion(temp.resolve("outside-version")));
    }

    private static void assertReason(
        RuntimeStoreException.FailureReason reason,
        ThrowingStoreOperation operation
    ) {
        RuntimeStoreException failure = assertThrows(RuntimeStoreException.class, operation::run);
        assertEquals(reason, failure.reason());
    }

    @FunctionalInterface
    private interface ThrowingStoreOperation {
        void run() throws Exception;
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
