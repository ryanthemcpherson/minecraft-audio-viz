package com.audioviz.runtime.install;

import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.API_MISMATCH;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.ARCHIVE_INVALID;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.INSTALL_ALREADY_RUNNING;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.MANIFEST_INVALID;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.DOWNLOAD_FAILED;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.EVENT_FAILURE;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.MANIFEST_FETCH_FAILED;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.PLATFORM_MISMATCH;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.STALE_OPERATION;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.STORE_FAILURE;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallOutcome.CANCELLED;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallOutcome.INSTALLED;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallOutcome.REUSED_CURRENT;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallOutcome.REUSED_INSTALLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.audioviz.runtime.RuntimeLimits;
import com.audioviz.runtime.net.RuntimeDownloadException;
import com.audioviz.runtime.release.ManifestVerificationException;
import com.audioviz.runtime.release.ReleaseDescriptor;
import com.audioviz.runtime.release.RuntimeArtifact;
import com.audioviz.runtime.release.RuntimeManifest;
import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.PackagedFile;
import com.audioviz.runtime.store.RuntimeArchiveVerifier.VerifiedRuntimeLayout;
import com.audioviz.runtime.store.RuntimePaths;
import com.audioviz.runtime.store.RuntimeStore;
import com.audioviz.runtime.store.RuntimeStore.InstalledRuntime;
import com.audioviz.runtime.store.RuntimeStoreException;
import com.audioviz.runtime.store.RuntimeVersionId;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeInstallerTest {
    private static final RuntimePlatform PLATFORM = RuntimePlatform.LINUX_X86_64;
    private static final String ENTRYPOINT = "bin/audioviz-vj";
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-31T12:00:00Z"),
        ZoneOffset.UTC
    );

    @TempDir
    Path temp;

    private RuntimePaths paths;
    private RuntimeStore store;
    private FakeSource source;
    private FakeVerifier verifier;
    private FakeExtractor extractor;
    private List<RuntimeInstallEvent.Stage> events;
    private ReleaseDescriptor descriptor;

    @BeforeEach
    void setUp() throws Exception {
        paths = RuntimePaths.create(temp.resolve("runtime"));
        store = new RuntimeStore(
            paths,
            RuntimeLimits.releaseDefaults(),
            CLOCK,
            boundary -> {}
        );
        source = new FakeSource();
        verifier = new FakeVerifier(manifest("1.2.0", 12));
        extractor = new FakeExtractor();
        events = new ArrayList<>();
        descriptor = new ReleaseDescriptor(
            URI.create("https://releases.mcav.live/mcav-runtime-manifest-v1.json"),
            Map.of(),
            Set.of("releases.mcav.live"),
            1,
            1
        );
    }

    @Test
    void installsVerifiedCandidateWithoutActivatingIt() throws Exception {
        RuntimeInstaller installer = installer();

        RuntimeInstaller.InstallResult result = installer.install(
            request(1),
            new CancellationToken(1)
        );

        assertEquals(INSTALLED, result.outcome());
        assertEquals(id("1.2.0"), result.runtime().orElseThrow().id());
        assertEquals(id("1.2.0"), store.installed(id("1.2.0")).orElseThrow().id());
        assertTrue(store.current().isEmpty());
        assertEquals(1, source.downloads.get());
        assertEquals(1, extractor.extractions.get());
        assertEquals(
            List.of(
                RuntimeInstallEvent.Stage.CHECKING,
                RuntimeInstallEvent.Stage.DOWNLOADING,
                RuntimeInstallEvent.Stage.VERIFYING,
                RuntimeInstallEvent.Stage.STAGING,
                RuntimeInstallEvent.Stage.INSTALLED
            ),
            events
        );
    }

    @Test
    void reusesExactVerifiedInstalledVersionWithoutDownload() throws Exception {
        promote("1.2.0", 12);

        RuntimeInstaller.InstallResult result = installer().install(
            request(2),
            new CancellationToken(2)
        );

        assertEquals(REUSED_INSTALLED, result.outcome());
        assertEquals(0, source.downloads.get());
        assertEquals(0, extractor.extractions.get());
    }

    @Test
    void reportsCurrentReuseWhenSignedManifestStillSelectsCurrent() throws Exception {
        InstalledRuntime current = prepareCurrent("1.2.0", 12);

        RuntimeInstaller.InstallResult result = installer().install(
            request(3),
            new CancellationToken(3)
        );

        assertEquals(REUSED_CURRENT, result.outcome());
        assertEquals(current.id(), result.runtime().orElseThrow().id());
    }

    @Test
    void verifiedCurrentRemainsUsableWhenManifestRefreshFails() throws Exception {
        InstalledRuntime current = prepareCurrent("1.1.0", 11);
        source.fetchFailure = new RuntimeDownloadException(
            RuntimeDownloadException.FailureReason.TIMEOUT
        );

        RuntimeInstaller.InstallResult result = installer().install(
            request(4),
            new CancellationToken(4)
        );

        assertEquals(REUSED_CURRENT, result.outcome());
        assertEquals(current.id(), result.runtime().orElseThrow().id());
    }

    @Test
    void manifestFetchFailureWithoutCurrentIsFatal() {
        source.fetchFailure = new RuntimeDownloadException(
            RuntimeDownloadException.FailureReason.TIMEOUT
        );

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(20), new CancellationToken(20))
        );

        assertEquals(MANIFEST_FETCH_FAILED, failure.reason());
    }

    @Test
    void expiredManifestAllowsCurrentButNeverActivatesAnotherInstalledVersion()
        throws Exception {
        InstalledRuntime current = prepareCurrent("1.1.0", 11);
        promote("1.2.0", 12);
        verifier.failure = new ManifestVerificationException(
            ManifestVerificationException.FailureReason.EXPIRED
        );

        RuntimeInstaller.InstallResult result = installer().install(
            request(5),
            new CancellationToken(5)
        );

        assertEquals(REUSED_CURRENT, result.outcome());
        assertEquals(current.id(), result.runtime().orElseThrow().id());

        Files.delete(paths.currentState());
        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(6), new CancellationToken(6))
        );
        assertEquals(MANIFEST_INVALID, failure.reason());
    }

    @Test
    void cancellationAfterDownloadDoesNotExtractOrPromoteCandidate() throws Exception {
        CancellationToken token = new CancellationToken(7);
        source.afterDownload = token::cancel;

        RuntimeInstaller.InstallResult result = installer().install(request(7), token);

        assertEquals(CANCELLED, result.outcome());
        assertTrue(store.installed(id("1.2.0")).isEmpty());
        assertEquals(0, extractor.extractions.get());
        assertDirectoryEmpty(paths.staging());
    }

    @Test
    void cancellationDuringExtractionCleansUniqueStagingAndDoesNotPromote()
        throws Exception {
        CancellationToken token = new CancellationToken(8);
        extractor.afterExtraction = token::cancel;

        RuntimeInstaller.InstallResult result = installer().install(request(8), token);

        assertEquals(CANCELLED, result.outcome());
        assertTrue(store.installed(id("1.2.0")).isEmpty());
        assertDirectoryEmpty(paths.staging());
    }

    @Test
    void extractorCancellationReasonReturnsCancelled() throws Exception {
        extractor.failure = new RuntimeStoreException(
            RuntimeStoreException.FailureReason.CANCELLED
        );

        RuntimeInstaller.InstallResult result = installer().install(
            request(21),
            new CancellationToken(21)
        );

        assertEquals(CANCELLED, result.outcome());
        assertTrue(store.installed(id("1.2.0")).isEmpty());
    }

    @Test
    void operationGenerationMismatchFailsBeforeNetworkOrFilesystemMutation() {
        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(10), new CancellationToken(9))
        );

        assertEquals(STALE_OPERATION, failure.reason());
        assertEquals(0, source.fetches.get());
        assertTrue(events.isEmpty());
    }

    @Test
    void installLockContentionIsReasonCoded() throws Exception {
        try (InstallLock ignored = InstallLock.acquire(paths.installLock())) {
            RuntimeInstaller.InstallException failure = assertThrows(
                RuntimeInstaller.InstallException.class,
                () -> installer().install(request(11), new CancellationToken(11))
            );
            assertEquals(INSTALL_ALREADY_RUNNING, failure.reason());
        }
    }

    @Test
    void requestRuntimeApiMustMatchEmbeddedDescriptor() {
        RuntimeInstaller.InstallRequest mismatched = new RuntimeInstaller.InstallRequest(
            descriptor,
            PLATFORM,
            2,
            12
        );

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(mismatched, new CancellationToken(12))
        );

        assertEquals(API_MISMATCH, failure.reason());
        assertEquals(0, source.fetches.get());
    }

    @Test
    void signedManifestMustContainRequestedPlatform() throws Exception {
        RuntimeManifest selected = manifest("1.2.0", 12);
        verifier = new FakeVerifier(new RuntimeManifest(
            selected.schemaVersion(),
            selected.generation(),
            selected.releaseVersion(),
            selected.runtimeApiMin(),
            selected.runtimeApiMax(),
            selected.publishedAt(),
            selected.expiresAt(),
            selected.signingKeyId(),
            Map.of()
        ));

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(16), new CancellationToken(16))
        );

        assertEquals(PLATFORM_MISMATCH, failure.reason());
        assertEquals(0, source.downloads.get());
    }

    @Test
    void signedManifestRuntimeApiMismatchFailsBeforeDownload() throws Exception {
        RuntimeManifest selected = manifest("1.2.0", 12);
        verifier = new FakeVerifier(new RuntimeManifest(
            selected.schemaVersion(),
            selected.generation(),
            selected.releaseVersion(),
            2,
            2,
            selected.publishedAt(),
            selected.expiresAt(),
            selected.signingKeyId(),
            selected.artifacts()
        ));

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(17), new CancellationToken(17))
        );

        assertEquals(API_MISMATCH, failure.reason());
        assertEquals(0, source.downloads.get());
    }

    @Test
    void manifestSignatureFailureNeverIntroducesCandidateBytes() throws Exception {
        InstalledRuntime current = prepareCurrent("1.1.0", 11);
        verifier.failure = new ManifestVerificationException(
            ManifestVerificationException.FailureReason.INVALID_SIGNATURE
        );

        RuntimeInstaller.InstallResult result = installer().install(
            request(13),
            new CancellationToken(13)
        );

        assertEquals(REUSED_CURRENT, result.outcome());
        assertEquals(current.id(), result.runtime().orElseThrow().id());
        assertTrue(store.installed(id("1.2.0")).isEmpty());
        assertEquals(0, source.downloads.get());
    }

    @Test
    void archiveFailurePreservesPreviouslyHealthyCurrent() throws Exception {
        InstalledRuntime current = prepareCurrent("1.1.0", 11);
        extractor.failure = new RuntimeStoreException(
            RuntimeStoreException.FailureReason.INVALID_ZIP
        );

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(14), new CancellationToken(14))
        );

        assertEquals(ARCHIVE_INVALID, failure.reason());
        assertEquals(current.id(), store.current().orElseThrow().id());
        assertTrue(store.installed(id("1.2.0")).isEmpty());
    }

    @Test
    void downloadFailurePreservesPreviouslyHealthyCurrent() throws Exception {
        InstalledRuntime current = prepareCurrent("1.1.0", 11);
        source.downloadFailure = new RuntimeDownloadException(
            RuntimeDownloadException.FailureReason.TRUNCATED
        );

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(18), new CancellationToken(18))
        );

        assertEquals(DOWNLOAD_FAILED, failure.reason());
        assertEquals(current.id(), store.current().orElseThrow().id());
        assertTrue(store.installed(id("1.2.0")).isEmpty());
    }

    @Test
    void downloadMustPublishExactlyTheRequestedOwnedPath() {
        source.returnedPath = temp.resolve("outside.zip");

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(22), new CancellationToken(22))
        );

        assertEquals(DOWNLOAD_FAILED, failure.reason());
        assertTrue(storeInstalledEmpty("1.2.0"));
    }

    @Test
    void cachedArchiveIsReverifiedWithoutAnotherDownload() throws Exception {
        Path cached = paths.downloads().resolve(
            "1.2.0--linux-x86_64--aaaaaaaaaaaaaaaa.zip"
        );
        Files.write(cached, new byte[]{1});

        RuntimeInstaller.InstallResult result = installer().install(
            request(23),
            new CancellationToken(23)
        );

        assertEquals(INSTALLED, result.outcome());
        assertEquals(0, source.downloads.get());
        assertEquals(1, extractor.extractions.get());
    }

    @Test
    void eventSinkFailureIsReasonCodedBeforeMutation() {
        RuntimeInstaller failing = new RuntimeInstaller(
            paths,
            store,
            CLOCK,
            source,
            verifier,
            extractor,
            event -> {
                throw new IllegalStateException("injected");
            }
        );

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> failing.install(request(24), new CancellationToken(24))
        );

        assertEquals(EVENT_FAILURE, failure.reason());
        assertEquals(0, source.fetches.get());
    }

    @Test
    void cancellationAfterImmutablePromotionKeepsReusableCandidateInactive()
        throws Exception {
        CancellationToken token = new CancellationToken(19);
        store = new RuntimeStore(
            paths,
            RuntimeLimits.releaseDefaults(),
            CLOCK,
            boundary -> {
                if (boundary == com.audioviz.runtime.store.AtomicStateStore.Boundary.VERSION_MOVE) {
                    token.cancel();
                }
            }
        );

        RuntimeInstaller.InstallResult result = installer().install(request(19), token);

        assertEquals(CANCELLED, result.outcome());
        assertTrue(store.installed(id("1.2.0")).isPresent());
        assertTrue(store.current().isEmpty());
    }

    @Test
    void cancellationReportedByManifestFetchReturnsCancelled() throws Exception {
        source.fetchFailure = new RuntimeDownloadException(
            RuntimeDownloadException.FailureReason.CANCELLED
        );

        RuntimeInstaller.InstallResult result = installer().install(
            request(26),
            new CancellationToken(26)
        );

        assertEquals(CANCELLED, result.outcome());
    }

    @Test
    void cancellationImmediatelyAfterManifestFetchReturnsCancelled() throws Exception {
        CancellationToken token = new CancellationToken(27);
        source.afterFetch = token::cancel;

        RuntimeInstaller.InstallResult result = installer().install(request(27), token);

        assertEquals(CANCELLED, result.outcome());
        assertEquals(0, source.downloads.get());
    }

    @Test
    void cancellationAfterExactInstalledCheckReturnsCancelled() throws Exception {
        CancellationToken token = new CancellationToken(28);
        verifier.afterVerify = token::cancel;

        RuntimeInstaller.InstallResult result = installer().install(
            request(28),
            token
        );

        assertEquals(CANCELLED, result.outcome());
        assertEquals(0, source.downloads.get());
    }

    @Test
    void installedVersionWithDifferentRuntimeApiIsNotReused() throws Exception {
        RuntimeManifest selected = manifest("1.2.0", 12);
        RuntimeArtifact artifact = selected.artifacts().get(PLATFORM);
        store.promote(layout("1.2.0"), id("1.2.0"), 12, artifact.sha256(), 2);
        CancellationToken token = new CancellationToken(34);
        verifier.afterVerify = token::cancel;

        RuntimeInstaller.InstallResult result = installer().install(request(34), token);

        assertEquals(CANCELLED, result.outcome());
        assertEquals(0, source.downloads.get());
    }

    @Test
    void artifactValueMustMatchRequestedPlatform() throws Exception {
        RuntimeManifest selected = manifest("1.2.0", 12);
        RuntimeArtifact base = selected.artifacts().get(PLATFORM);
        RuntimeArtifact mismatched = new RuntimeArtifact(
            RuntimePlatform.WINDOWS_X86_64,
            base.url(),
            base.archiveSize(),
            base.uncompressedSize(),
            base.sha256(),
            base.entrypoint(),
            base.filesManifestSha256()
        );
        verifier = new FakeVerifier(new RuntimeManifest(
            selected.schemaVersion(),
            selected.generation(),
            selected.releaseVersion(),
            selected.runtimeApiMin(),
            selected.runtimeApiMax(),
            selected.publishedAt(),
            selected.expiresAt(),
            selected.signingKeyId(),
            Map.of(PLATFORM, mismatched)
        ));

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(35), new CancellationToken(35))
        );

        assertEquals(PLATFORM_MISMATCH, failure.reason());
    }

    @Test
    void malformedVerifiedVersionIsReasonCoded() throws Exception {
        verifier = new FakeVerifier(manifest(".invalid", 12));

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(29), new CancellationToken(29))
        );

        assertEquals(MANIFEST_INVALID, failure.reason());
    }

    @Test
    void nullDownloadResultIsRejected() {
        source.returnNull = true;

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(30), new CancellationToken(30))
        );

        assertEquals(DOWNLOAD_FAILED, failure.reason());
    }

    @Test
    void cancellationReportedByDownloadReturnsCancelled() throws Exception {
        source.downloadFailure = new RuntimeDownloadException(
            RuntimeDownloadException.FailureReason.CANCELLED
        );

        RuntimeInstaller.InstallResult result = installer().install(
            request(31),
            new CancellationToken(31)
        );

        assertEquals(CANCELLED, result.outcome());
    }

    @Test
    void cancellationObservedAlongsideExtractionFailureReturnsCancelled() throws Exception {
        CancellationToken token = new CancellationToken(32);
        extractor.beforeFailure = token::cancel;
        extractor.failure = new RuntimeStoreException(
            RuntimeStoreException.FailureReason.IO_FAILURE
        );

        RuntimeInstaller.InstallResult result = installer().install(request(32), token);

        assertEquals(CANCELLED, result.outcome());
    }

    @Test
    void promotionFailureIsReasonCodedAndLeavesNoInstalledVersion() throws Exception {
        store = new RuntimeStore(
            paths,
            RuntimeLimits.releaseDefaults(),
            CLOCK,
            boundary -> {
                if (boundary == com.audioviz.runtime.store.AtomicStateStore.Boundary.VERSION_MOVE) {
                    throw new java.io.IOException("injected promotion failure");
                }
            }
        );

        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            () -> installer().install(request(33), new CancellationToken(33))
        );

        assertEquals(STORE_FAILURE, failure.reason());
        assertTrue(store.installed(id("1.2.0")).isEmpty());
    }

    @Test
    void cancelledTokenReturnsWithoutFetching() throws Exception {
        CancellationToken token = new CancellationToken(15);
        token.cancel();

        RuntimeInstaller.InstallResult result = installer().install(request(15), token);

        assertEquals(CANCELLED, result.outcome());
        assertEquals(0, source.fetches.get());
    }

    private RuntimeInstaller installer() {
        return new RuntimeInstaller(
            paths,
            store,
            CLOCK,
            source,
            verifier,
            extractor,
            event -> events.add(event.stage())
        );
    }

    private boolean storeInstalledEmpty(String version) {
        try {
            return store.installed(id(version)).isEmpty();
        } catch (RuntimeStoreException error) {
            throw new AssertionError(error);
        }
    }

    private RuntimeInstaller.InstallRequest request(long generation) {
        return new RuntimeInstaller.InstallRequest(descriptor, PLATFORM, 1, generation);
    }

    private InstalledRuntime prepareCurrent(String version, long generation) throws Exception {
        InstalledRuntime installed = promote(version, generation);
        store.activateCandidate(installed);
        store.markHealthy(installed);
        return store.current().orElseThrow();
    }

    private InstalledRuntime promote(String version, long generation) throws Exception {
        RuntimeManifest selected = manifest(version, generation);
        RuntimeArtifact artifact = selected.artifacts().get(PLATFORM);
        return store.promote(
            layout(version),
            id(version),
            generation,
            artifact.sha256(),
            1
        );
    }

    private VerifiedRuntimeLayout layout(String version) throws Exception {
        Path staging = paths.newStaging();
        Path entrypoint = staging.resolve(ENTRYPOINT.replace('/', java.io.File.separatorChar));
        Files.createDirectories(entrypoint.getParent());
        byte[] executable = ("payload-" + version).getBytes(StandardCharsets.UTF_8);
        Files.write(entrypoint, executable);
        String fileDigest = sha256(executable);
        byte[] manifestBytes = filesManifest(executable.length, fileDigest);
        Files.write(staging.resolve("files.json"), manifestBytes);
        PackagedFile file = new PackagedFile(ENTRYPOINT, executable.length, fileDigest, true);
        return new VerifiedRuntimeLayout(
            staging,
            entrypoint,
            Map.of(ENTRYPOINT, file),
            manifestBytes,
            sha256(manifestBytes),
            executable.length + manifestBytes.length
        );
    }

    private static RuntimeManifest manifest(String version, long generation) throws Exception {
        byte[] executable = ("payload-" + version).getBytes(StandardCharsets.UTF_8);
        String filesDigest = sha256(filesManifest(executable.length, sha256(executable)));
        RuntimeArtifact artifact = new RuntimeArtifact(
            PLATFORM,
            URI.create("https://releases.mcav.live/runtime/" + version + "/linux-x86_64.zip"),
            1,
            executable.length,
            "a".repeat(64),
            ENTRYPOINT,
            filesDigest
        );
        return new RuntimeManifest(
            1,
            generation,
            version,
            1,
            1,
            CLOCK.instant().minusSeconds(60),
            CLOCK.instant().plusSeconds(3_600),
            "test-only-2026",
            Map.of(PLATFORM, artifact)
        );
    }

    private static byte[] filesManifest(long size, String digest) {
        return ("{\"schema_version\":1,\"files\":[{\"path\":\"" + ENTRYPOINT +
            "\",\"size\":" + size + ",\"sha256\":\"" + digest +
            "\",\"executable\":true}]}\n").getBytes(StandardCharsets.UTF_8);
    }

    private static RuntimeVersionId id(String version) {
        return RuntimeVersionId.of(version, PLATFORM);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertDirectoryEmpty(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            assertFalse(entries.findAny().isPresent());
        }
    }

    private static final class FakeSource implements RuntimeInstaller.ReleaseSource {
        private final AtomicInteger fetches = new AtomicInteger();
        private final AtomicInteger downloads = new AtomicInteger();
        private RuntimeDownloadException fetchFailure;
        private RuntimeDownloadException downloadFailure;
        private Path returnedPath;
        private boolean returnNull;
        private Runnable afterFetch = () -> {};
        private Runnable afterDownload = () -> {};

        @Override
        public RuntimeInstaller.SignedManifestBytes fetch(
            RuntimeInstaller.InstallRequest request,
            CancellationToken token
        ) throws RuntimeDownloadException {
            fetches.incrementAndGet();
            if (fetchFailure != null) {
                throw fetchFailure;
            }
            afterFetch.run();
            return new RuntimeInstaller.SignedManifestBytes(new byte[]{1}, new byte[]{2});
        }

        @Override
        public Path download(
            RuntimeInstaller.InstallRequest request,
            RuntimeArtifact artifact,
            Path destination,
            CancellationToken token
        ) throws RuntimeDownloadException {
            downloads.incrementAndGet();
            if (downloadFailure != null) {
                throw downloadFailure;
            }
            try {
                Files.write(destination, new byte[]{1});
            } catch (java.io.IOException error) {
                throw new RuntimeDownloadException(
                    RuntimeDownloadException.FailureReason.IO_FAILURE,
                    error
                );
            }
            afterDownload.run();
            if (returnNull) {
                return null;
            }
            return returnedPath == null ? destination : returnedPath;
        }
    }

    private static final class FakeVerifier implements RuntimeInstaller.ManifestReader {
        private final RuntimeManifest manifest;
        private ManifestVerificationException failure;
        private Runnable afterVerify = () -> {};

        private FakeVerifier(RuntimeManifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public RuntimeManifest verify(
            RuntimeInstaller.SignedManifestBytes signed,
            ReleaseDescriptor descriptor,
            Instant now
        ) throws ManifestVerificationException {
            if (failure != null) {
                throw failure;
            }
            afterVerify.run();
            return manifest;
        }
    }

    private final class FakeExtractor implements RuntimeInstaller.ArchiveExtractor {
        private final AtomicInteger extractions = new AtomicInteger();
        private RuntimeStoreException failure;
        private Runnable beforeFailure = () -> {};
        private Runnable afterExtraction = () -> {};

        @Override
        public VerifiedRuntimeLayout extract(
            Path archive,
            Path staging,
            RuntimeArtifact artifact,
            RuntimePlatform platform,
            CancellationToken token
        ) throws RuntimeStoreException {
            extractions.incrementAndGet();
            if (failure != null) {
                beforeFailure.run();
                throw failure;
            }
            try {
                VerifiedRuntimeLayout layout = layout("1.2.0");
                afterExtraction.run();
                return layout;
            } catch (RuntimeStoreException error) {
                throw error;
            } catch (Exception error) {
                throw new RuntimeStoreException(RuntimeStoreException.FailureReason.IO_FAILURE, error);
            }
        }
    }
}
