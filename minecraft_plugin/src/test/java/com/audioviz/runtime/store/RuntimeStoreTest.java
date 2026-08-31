package com.audioviz.runtime.store;

import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.VERSION_COLLISION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.audioviz.runtime.RuntimeLimits;
import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.AtomicStateStore.Boundary;
import com.audioviz.runtime.store.RuntimeArchiveVerifier.VerifiedRuntimeLayout;
import com.audioviz.runtime.store.RuntimeState.HealthState;
import com.audioviz.runtime.store.RuntimeStore.InstalledRuntime;
import com.audioviz.runtime.store.RuntimeTransaction.TransactionPhase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RuntimeStoreTest {

    private static final RuntimePlatform PLATFORM = RuntimePlatform.LINUX_X86_64;
    private static final String ENTRYPOINT = "bin/audioviz-vj";
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-31T12:00:00Z"),
        ZoneOffset.UTC
    );

    @TempDir
    Path temp;

    private RuntimePaths paths;
    private Faults faults;
    private RuntimeStore store;

    @BeforeEach
    void setUp() throws Exception {
        paths = RuntimePaths.create(temp.resolve("runtime"));
        faults = new Faults();
        store = newStore(faults);
    }

    @Test
    void promotesImmutableVersionAndTreatsIdenticalCollisionAsIdempotent() throws Exception {
        InstalledRuntime first = promote("1.2.0", 12, 1);
        VerifiedRuntimeLayout duplicateLayout = layout("1.2.0", "payload-1.2.0");

        InstalledRuntime duplicate = store.promote(
            duplicateLayout,
            id("1.2.0"),
            12,
            "a".repeat(64),
            1
        );

        assertEquals(first, duplicate);
        assertFalse(Files.exists(duplicateLayout.root()));
        assertEquals(first, store.installed(id("1.2.0")).orElseThrow());
    }

    @Test
    void rejectsVersionCollisionWhenPayloadDigestDiffers() throws Exception {
        promote("1.2.0", 12, 1);
        VerifiedRuntimeLayout different = layout("1.2.0", "different");

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> store.promote(different, id("1.2.0"), 12, "a".repeat(64), 1)
        );

        assertEquals(VERSION_COLLISION, failure.reason());
        assertTrue(Files.exists(different.root()));
    }

    @Test
    void keepsPriorHealthyRuntimeAvailableUntilCandidateBecomesHealthy() throws Exception {
        InstalledRuntime previous = promote("1.1.0", 11, 1);
        store.activateCandidate(previous);
        store.markHealthy(previous);
        InstalledRuntime candidate = promote("1.2.0", 12, 1);

        store.activateCandidate(candidate);

        assertEquals(HealthState.CANDIDATE, store.current().orElseThrow().state().health());
        assertEquals(previous.id(), store.rollbackTarget(1).orElseThrow().id());

        store.markHealthy(candidate);

        assertEquals(candidate.id(), store.current().orElseThrow().id());
        assertEquals(HealthState.HEALTHY, store.current().orElseThrow().state().health());
        assertEquals(previous.id(), store.rollbackTarget(1).orElseThrow().id());
        assertTrue(store.atomicStateStore().readTransaction().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {
        "JOURNAL_WRITE", "VERSION_MOVE", "INSTALLED_STATE_REPLACE", "JOURNAL_CLEANUP"
    })
    void promotionRecoversFromEveryTransactionBoundary(Boundary boundary) throws Exception {
        VerifiedRuntimeLayout candidate = layout("1.2.0", "candidate");
        faults.failOnce(boundary);

        assertThrows(
            RuntimeStoreException.class,
            () -> store.promote(candidate, id("1.2.0"), 12, "a".repeat(64), 1)
        );

        RuntimeStore recovered = newStore(new Faults());
        recovered.recover();

        assertEquals(id("1.2.0"), recovered.installed(id("1.2.0")).orElseThrow().id());
        assertTrue(recovered.atomicStateStore().readTransaction().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {
        "CURRENT_TEMP_WRITE", "CURRENT_REPLACE"
    })
    void candidateActivationRecoversAtAtomicWriteBoundaries(Boundary boundary) throws Exception {
        InstalledRuntime previous = promote("1.1.0", 11, 1);
        store.activateCandidate(previous);
        store.markHealthy(previous);
        InstalledRuntime candidate = promote("1.2.0", 12, 1);
        faults.failOnce(boundary);

        assertThrows(RuntimeStoreException.class, () -> store.activateCandidate(candidate));

        RuntimeStore recovered = newStore(new Faults());
        recovered.recover();

        assertEquals(candidate.id(), recovered.current().orElseThrow().id());
        assertEquals(previous.id(), recovered.rollbackTarget(1).orElseThrow().id());
    }

    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {
        "JOURNAL_WRITE", "LAST_KNOWN_GOOD_REPLACE", "CURRENT_TEMP_WRITE",
        "CURRENT_REPLACE", "JOURNAL_CLEANUP"
    })
    void healthCommitRecoversAtEveryAtomicWriteBoundary(Boundary boundary) throws Exception {
        InstalledRuntime previous = promote("1.1.0", 11, 1);
        store.activateCandidate(previous);
        store.markHealthy(previous);
        InstalledRuntime candidate = promote("1.2.0", 12, 1);
        store.activateCandidate(candidate);
        faults.failOnce(boundary);

        assertThrows(RuntimeStoreException.class, () -> store.markHealthy(candidate));

        RuntimeStore recovered = newStore(new Faults());
        recovered.recover();

        assertEquals(candidate.id(), recovered.current().orElseThrow().id());
        assertEquals(HealthState.HEALTHY, recovered.current().orElseThrow().state().health());
        assertEquals(previous.id(), recovered.rollbackTarget(1).orElseThrow().id());
    }

    @Test
    void excludesCorruptAndIncompatibleInstalledVersions() throws Exception {
        InstalledRuntime compatible = promote("1.1.0", 11, 1);
        InstalledRuntime incompatible = promote("2.0.0", 20, 2);
        Files.writeString(compatible.entrypoint(), "tampered", StandardCharsets.UTF_8);

        assertTrue(store.installed(compatible.id()).isEmpty());
        assertTrue(store.findCompatible(1).isEmpty());
        assertEquals(incompatible.id(), store.findCompatible(2).orElseThrow().id());
        assertTrue(store.rollbackTarget(1).isEmpty());
    }

    @Test
    void rejectsUnexpectedFilesAddedAfterPromotion() throws Exception {
        InstalledRuntime runtime = promote("1.2.0", 12, 1);
        Files.writeString(runtime.root().resolve("unexpected.py"), "untrusted", StandardCharsets.UTF_8);

        assertTrue(store.installed(runtime.id()).isEmpty());
        assertTrue(store.findCompatible(1).isEmpty());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsSymlinkedDirectoryBeforeReadingInstalledPayload() throws Exception {
        InstalledRuntime runtime = promote("1.2.0", 12, 1);
        Path bin = runtime.root().resolve("bin");
        Path outside = temp.resolve("outside");
        Files.createDirectory(outside);
        Files.copy(runtime.entrypoint(), outside.resolve("audioviz-vj"));
        Files.delete(runtime.entrypoint());
        Files.delete(bin);
        Files.createSymbolicLink(bin, outside);

        assertTrue(store.installed(runtime.id()).isEmpty());
    }

    @Test
    void recoveryDiscardsPromotionJournalWhenNoCandidatePayloadExists() throws Exception {
        RuntimeState missing = new RuntimeState(
            1,
            id("1.2.0"),
            12,
            "a".repeat(64),
            "b".repeat(64),
            1,
            CLOCK.instant(),
            HealthState.CANDIDATE
        );
        store.atomicStateStore().writeTransaction(new RuntimeTransaction(
            1,
            UUID.fromString("00000000-0000-0000-0000-000000000015"),
            TransactionPhase.PROMOTION_PREPARED,
            "stage-00000000-0000-0000-0000-000000000015",
            missing,
            null
        ));

        store.recover();

        assertTrue(store.atomicStateStore().readTransaction().isEmpty());
        assertTrue(store.installed(missing.id()).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {
        "BEFORE_FILE_FORCE", "AFTER_FILE_FORCE"
    })
    void failedJournalForceKeepsPreviouslyHealthyRuntimeActive(Boundary boundary)
        throws Exception {
        InstalledRuntime previous = promote("1.1.0", 11, 1);
        store.activateCandidate(previous);
        store.markHealthy(previous);
        InstalledRuntime candidate = promote("1.2.0", 12, 1);
        faults.failOnce(boundary);

        assertThrows(RuntimeStoreException.class, () -> store.activateCandidate(candidate));

        RuntimeStore recovered = newStore(new Faults());
        recovered.recover();
        assertEquals(previous.id(), recovered.current().orElseThrow().id());
        assertTrue(recovered.atomicStateStore().readTransaction().isEmpty());
    }

    @Test
    void pruneKeepsCurrentLastKnownGoodJournalPinnedAndNewestAdditional() throws Exception {
        InstalledRuntime one = promote("1.0.0", 10, 1);
        InstalledRuntime two = promote("2.0.0", 20, 1);
        InstalledRuntime three = promote("3.0.0", 30, 1);
        InstalledRuntime four = promote("4.0.0", 40, 1);
        InstalledRuntime five = promote("5.0.0", 50, 1);
        InstalledRuntime six = promote("6.0.0", 60, 1);
        store.activateCandidate(one);
        store.markHealthy(one);
        store.activateCandidate(two);
        store.markHealthy(two);
        store.activateCandidate(three);
        Path unrelatedStaging = paths.newStaging();
        Files.createDirectory(unrelatedStaging);
        Files.writeString(unrelatedStaging.resolve("sentinel"), "keep", StandardCharsets.UTF_8);

        int removed = store.prune(1, Set.of(four.id()));

        assertEquals(1, removed);
        assertTrue(store.installed(one.id()).isPresent());
        assertTrue(store.installed(two.id()).isPresent());
        assertTrue(store.installed(three.id()).isPresent());
        assertTrue(store.installed(four.id()).isPresent());
        assertTrue(store.installed(six.id()).isPresent());
        assertTrue(store.installed(five.id()).isEmpty());
        assertTrue(Files.isRegularFile(unrelatedStaging.resolve("sentinel")));
    }

    @Test
    void pruneFailureNeverRemovesProtectedVersions() throws Exception {
        InstalledRuntime current = promote("1.0.0", 10, 1);
        promote("2.0.0", 20, 1);
        store.activateCandidate(current);
        store.markHealthy(current);
        faults.failOnce(Boundary.PRUNE);

        assertThrows(RuntimeStoreException.class, () -> store.prune(0, Set.of()));

        assertTrue(store.installed(current.id()).isPresent());
    }

    private InstalledRuntime promote(String version, long generation, int runtimeApi)
        throws Exception {
        return store.promote(
            layout(version, "payload-" + version),
            id(version),
            generation,
            "a".repeat(64),
            runtimeApi
        );
    }

    private VerifiedRuntimeLayout layout(String version, String payload) throws Exception {
        Path staging = paths.newStaging();
        Path entrypoint = staging.resolve(ENTRYPOINT.replace('/', java.io.File.separatorChar));
        Files.createDirectories(entrypoint.getParent());
        byte[] executable = payload.getBytes(StandardCharsets.UTF_8);
        Files.write(entrypoint, executable);
        String fileDigest = sha256(executable);
        String manifestText = "{\"schema_version\":1,\"files\":[{\"path\":\"" + ENTRYPOINT +
            "\",\"size\":" + executable.length + ",\"sha256\":\"" + fileDigest +
            "\",\"executable\":true}]}\n";
        byte[] manifest = manifestText.getBytes(StandardCharsets.UTF_8);
        Files.write(staging.resolve("files.json"), manifest);
        PackagedFile packaged = new PackagedFile(ENTRYPOINT, executable.length, fileDigest, true);
        return new VerifiedRuntimeLayout(
            staging,
            entrypoint,
            Map.of(ENTRYPOINT, packaged),
            manifest,
            sha256(manifest),
            executable.length + manifest.length
        );
    }

    private RuntimeStore newStore(Faults injectedFaults) {
        return new RuntimeStore(
            paths,
            RuntimeLimits.releaseDefaults(),
            CLOCK,
            injectedFaults
        );
    }

    private static RuntimeVersionId id(String version) {
        return RuntimeVersionId.of(version, PLATFORM);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class Faults implements AtomicStateStore.FaultInjector {
        private Optional<Boundary> next = Optional.empty();

        void failOnce(Boundary boundary) {
            next = Optional.of(boundary);
        }

        @Override
        public void at(Boundary boundary) throws IOException {
            if (next.filter(boundary::equals).isPresent()) {
                next = Optional.empty();
                throw new IOException("injected " + boundary);
            }
        }
    }
}
