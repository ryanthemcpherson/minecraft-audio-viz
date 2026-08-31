package com.audioviz.runtime.install;

import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.API_MISMATCH;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.ARCHIVE_INVALID;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.DOWNLOAD_FAILED;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.EVENT_FAILURE;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.MANIFEST_FETCH_FAILED;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.MANIFEST_INVALID;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.PLATFORM_MISMATCH;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.STALE_OPERATION;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.STORE_FAILURE;

import com.audioviz.runtime.RuntimeLimits;
import com.audioviz.runtime.net.RuntimeDownloadException;
import com.audioviz.runtime.net.RuntimeHttpSource;
import com.audioviz.runtime.release.ManifestVerificationException;
import com.audioviz.runtime.release.ReleaseDescriptor;
import com.audioviz.runtime.release.RuntimeArtifact;
import com.audioviz.runtime.release.RuntimeManifest;
import com.audioviz.runtime.release.RuntimeManifestVerifier;
import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.RuntimeArchiveVerifier;
import com.audioviz.runtime.store.RuntimeArchiveVerifier.VerifiedRuntimeLayout;
import com.audioviz.runtime.store.RuntimePaths;
import com.audioviz.runtime.store.RuntimeStore;
import com.audioviz.runtime.store.RuntimeStore.InstalledRuntime;
import com.audioviz.runtime.store.RuntimeStoreException;
import com.audioviz.runtime.store.RuntimeVersionId;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class RuntimeInstaller {
    public enum InstallOutcome {
        REUSED_CURRENT,
        REUSED_INSTALLED,
        INSTALLED,
        CANCELLED
    }

    public enum InstallFailureReason {
        INSTALL_ALREADY_RUNNING,
        LOCK_FAILURE,
        STALE_OPERATION,
        API_MISMATCH,
        PLATFORM_MISMATCH,
        MANIFEST_FETCH_FAILED,
        MANIFEST_INVALID,
        DOWNLOAD_FAILED,
        ARCHIVE_INVALID,
        STORE_FAILURE,
        EVENT_FAILURE
    }

    public static final class InstallException extends Exception {
        private final InstallFailureReason reason;

        public InstallException(InstallFailureReason reason) {
            super("MCAV_RUNTIME_INSTALL_" + reason.name());
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public InstallException(InstallFailureReason reason, Throwable cause) {
            super("MCAV_RUNTIME_INSTALL_" + reason.name(), cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public InstallFailureReason reason() {
            return reason;
        }
    }

    public record InstallRequest(
        ReleaseDescriptor descriptor,
        RuntimePlatform platform,
        int pluginRuntimeApi,
        long operationGeneration
    ) {
        public InstallRequest {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(platform, "platform");
            if (pluginRuntimeApi <= 0 || operationGeneration < 0) {
                throw new IllegalArgumentException("invalid install request");
            }
        }
    }

    public record InstallResult(InstallOutcome outcome, InstalledRuntime selectedRuntime) {
        public InstallResult {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome == InstallOutcome.CANCELLED && selectedRuntime != null) {
                throw new IllegalArgumentException("cancelled result cannot select a runtime");
            }
            if (outcome != InstallOutcome.CANCELLED && selectedRuntime == null) {
                throw new IllegalArgumentException("successful result requires a runtime");
            }
        }

        public Optional<InstalledRuntime> runtime() {
            return Optional.ofNullable(selectedRuntime);
        }

        static InstallResult cancelled() {
            return new InstallResult(InstallOutcome.CANCELLED, null);
        }
    }

    public record SignedManifestBytes(byte[] payload, byte[] signature) {
        public SignedManifestBytes {
            payload = Objects.requireNonNull(payload, "payload").clone();
            signature = Objects.requireNonNull(signature, "signature").clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }

    public interface ReleaseSource {
        SignedManifestBytes fetch(InstallRequest request, CancellationToken token)
            throws RuntimeDownloadException;

        Path download(
            InstallRequest request,
            RuntimeArtifact artifact,
            Path destination,
            CancellationToken token
        ) throws RuntimeDownloadException;
    }

    @FunctionalInterface
    public interface ManifestReader {
        RuntimeManifest verify(
            SignedManifestBytes signed,
            ReleaseDescriptor descriptor,
            Instant now
        ) throws ManifestVerificationException;
    }

    @FunctionalInterface
    public interface ArchiveExtractor {
        VerifiedRuntimeLayout extract(
            Path archive,
            Path staging,
            RuntimeArtifact artifact,
            RuntimePlatform platform,
            CancellationToken token
        ) throws RuntimeStoreException;
    }

    private final RuntimePaths paths;
    private final RuntimeStore store;
    private final Clock clock;
    private final ReleaseSource source;
    private final ManifestReader manifestReader;
    private final ArchiveExtractor archiveExtractor;
    private final Consumer<RuntimeInstallEvent> eventSink;

    public RuntimeInstaller(
        RuntimePaths paths,
        RuntimeStore store,
        RuntimeLimits limits,
        Clock clock,
        RuntimeHttpSource httpSource,
        RuntimeManifestVerifier manifestVerifier,
        RuntimeArchiveVerifier archiveVerifier,
        Consumer<RuntimeInstallEvent> eventSink
    ) {
        this(
            paths,
            store,
            clock,
            new HttpReleaseSource(paths, limits, httpSource),
            (signed, descriptor, now) -> manifestVerifier.verify(
                signed.payload(),
                signed.signature(),
                descriptor,
                now
            ),
            (archive, staging, artifact, platform, token) -> archiveVerifier.extract(
                archive,
                staging,
                artifact,
                platform,
                token::isCancelled
            ),
            eventSink
        );
    }

    RuntimeInstaller(
        RuntimePaths paths,
        RuntimeStore store,
        Clock clock,
        ReleaseSource source,
        ManifestReader manifestReader,
        ArchiveExtractor archiveExtractor,
        Consumer<RuntimeInstallEvent> eventSink
    ) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
        this.manifestReader = Objects.requireNonNull(manifestReader, "manifestReader");
        this.archiveExtractor = Objects.requireNonNull(archiveExtractor, "archiveExtractor");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    public InstallResult install(InstallRequest request, CancellationToken token)
        throws InstallException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(token, "token");
        requireGeneration(request, token);
        if (token.isCancelled()) {
            return InstallResult.cancelled();
        }
        InstallLock lock = InstallLock.acquire(paths.installLock());
        try {
            InstallResult result = installLocked(request, token);
            lock.close();
            return result;
        } catch (RuntimeStoreException error) {
            closeAfterFailure(lock, error);
            throw failure(STORE_FAILURE, error);
        } catch (InstallException error) {
            closeAfterFailure(lock, error);
            throw error;
        } catch (RuntimeException | Error error) {
            closeAfterFailure(lock, error);
            throw error;
        }
    }

    private InstallResult installLocked(InstallRequest request, CancellationToken token)
        throws InstallException, RuntimeStoreException {
            store.recover();
            if (token.isCancelled()) {
                return InstallResult.cancelled();
            }
            if (request.pluginRuntimeApi() != request.descriptor().runtimeApi()) {
                throw failure(API_MISMATCH);
            }
            Optional<InstalledRuntime> verifiedCurrent = store.current()
                .filter(runtime -> runtime.state().runtimeApi() == request.pluginRuntimeApi());
            emit(RuntimeInstallEvent.Stage.CHECKING, "checking", request);

            SignedManifestBytes signed;
            try {
                signed = source.fetch(request, token);
            } catch (RuntimeDownloadException error) {
                if (error.reason() == RuntimeDownloadException.FailureReason.CANCELLED) {
                    return InstallResult.cancelled();
                }
                return offlineCurrentOrThrow(
                    verifiedCurrent,
                    request,
                    MANIFEST_FETCH_FAILED,
                    error
                );
            }
            if (token.isCancelled()) {
                return InstallResult.cancelled();
            }

            RuntimeManifest manifest;
            try {
                manifest = manifestReader.verify(signed, request.descriptor(), clock.instant());
            } catch (ManifestVerificationException error) {
                return offlineCurrentOrThrow(
                    verifiedCurrent,
                    request,
                    MANIFEST_INVALID,
                    error
                );
            }
            RuntimeArtifact artifact = selectArtifact(manifest, request);
            RuntimeVersionId id;
            try {
                id = RuntimeVersionId.of(manifest.releaseVersion(), request.platform());
            } catch (IllegalArgumentException error) {
                throw failure(MANIFEST_INVALID, error);
            }
            Optional<InstalledRuntime> exact = exactInstalled(id, manifest, artifact, request);
            if (exact.isPresent()) {
                InstalledRuntime selected = exact.orElseThrow();
                InstallOutcome outcome = verifiedCurrent
                    .filter(current -> current.id().equals(selected.id()))
                    .isPresent()
                    ? InstallOutcome.REUSED_CURRENT
                    : InstallOutcome.REUSED_INSTALLED;
                emit(
                    RuntimeInstallEvent.Stage.INSTALLED,
                    outcome == InstallOutcome.REUSED_CURRENT
                        ? "reused_current"
                        : "reused_installed",
                    request
                );
                return new InstallResult(outcome, selected);
            }
            if (token.isCancelled()) {
                return InstallResult.cancelled();
            }

            Path archive = archivePath(manifest, artifact);
            if (!Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                emit(RuntimeInstallEvent.Stage.DOWNLOADING, "downloading", request);
                try {
                    Path returned = source.download(request, artifact, archive, token);
                    if (returned == null) {
                        throw failure(DOWNLOAD_FAILED);
                    }
                    Path downloaded = returned.toAbsolutePath().normalize();
                    if (!downloaded.equals(archive)) {
                        throw failure(DOWNLOAD_FAILED);
                    }
                    archive = downloaded;
                } catch (RuntimeDownloadException error) {
                    if (error.reason() == RuntimeDownloadException.FailureReason.CANCELLED) {
                        return InstallResult.cancelled();
                    }
                    throw failure(DOWNLOAD_FAILED, error);
                }
            }
            if (token.isCancelled()) {
                return InstallResult.cancelled();
            }
            emit(RuntimeInstallEvent.Stage.VERIFYING, "verifying", request);
            emit(RuntimeInstallEvent.Stage.STAGING, "staging", request);
            VerifiedRuntimeLayout layout;
            try {
                layout = archiveExtractor.extract(
                    archive,
                    paths.newStaging(),
                    artifact,
                    request.platform(),
                    token
                );
            } catch (RuntimeStoreException error) {
                if (
                    error.reason() == RuntimeStoreException.FailureReason.CANCELLED ||
                    token.isCancelled()
                ) {
                    return InstallResult.cancelled();
                }
                throw failure(ARCHIVE_INVALID, error);
            }
            if (token.isCancelled()) {
                discard(layout);
                return InstallResult.cancelled();
            }
            InstalledRuntime installed;
            try {
                installed = store.promote(
                    layout,
                    id,
                    manifest.generation(),
                    artifact.sha256(),
                    request.pluginRuntimeApi()
                );
            } catch (RuntimeStoreException error) {
                throw failure(STORE_FAILURE, error);
            }
            if (token.isCancelled()) {
                return InstallResult.cancelled();
            }
            emit(RuntimeInstallEvent.Stage.INSTALLED, "installed", request);
            return new InstallResult(InstallOutcome.INSTALLED, installed);
    }

    private static void closeAfterFailure(InstallLock lock, Throwable primary) {
        try {
            lock.close();
        } catch (InstallException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private InstallResult offlineCurrentOrThrow(
        Optional<InstalledRuntime> current,
        InstallRequest request,
        InstallFailureReason failureReason,
        Throwable cause
    ) throws InstallException {
        if (current.isEmpty()) {
            throw failure(failureReason, cause);
        }
        emit(RuntimeInstallEvent.Stage.INSTALLED, "offline_current", request);
        return new InstallResult(InstallOutcome.REUSED_CURRENT, current.orElseThrow());
    }

    private RuntimeArtifact selectArtifact(RuntimeManifest manifest, InstallRequest request)
        throws InstallException {
        if (
            request.pluginRuntimeApi() < manifest.runtimeApiMin() ||
            request.pluginRuntimeApi() > manifest.runtimeApiMax()
        ) {
            throw failure(API_MISMATCH);
        }
        RuntimeArtifact artifact = manifest.artifacts().get(request.platform());
        if (artifact == null || artifact.platform() != request.platform()) {
            throw failure(PLATFORM_MISMATCH);
        }
        return artifact;
    }

    private Optional<InstalledRuntime> exactInstalled(
        RuntimeVersionId id,
        RuntimeManifest manifest,
        RuntimeArtifact artifact,
        InstallRequest request
    ) throws RuntimeStoreException {
        return store.installed(id).filter(runtime ->
            runtime.state().manifestGeneration() == manifest.generation() &&
            runtime.state().archiveSha256().equals(artifact.sha256()) &&
            runtime.state().filesManifestSha256().equals(artifact.filesManifestSha256()) &&
            runtime.state().runtimeApi() == request.pluginRuntimeApi()
        );
    }

    private Path archivePath(RuntimeManifest manifest, RuntimeArtifact artifact) {
        String name = manifest.releaseVersion() + "--" + artifact.platform().wireName() +
            "--" + artifact.sha256().substring(0, 16) + ".zip";
        return paths.downloads().resolve(name);
    }

    private void discard(VerifiedRuntimeLayout layout) throws InstallException {
        try {
            store.discardStaging(layout.root());
        } catch (RuntimeStoreException error) {
            throw failure(STORE_FAILURE, error);
        }
    }

    private void emit(
        RuntimeInstallEvent.Stage stage,
        String code,
        InstallRequest request
    ) throws InstallException {
        try {
            eventSink.accept(new RuntimeInstallEvent(stage, code, request.operationGeneration()));
        } catch (RuntimeException error) {
            throw failure(EVENT_FAILURE, error);
        }
    }

    private static void requireGeneration(InstallRequest request, CancellationToken token)
        throws InstallException {
        if (request.operationGeneration() != token.generation()) {
            throw failure(STALE_OPERATION);
        }
    }

    private static URI signatureUri(URI manifestUri) throws RuntimeDownloadException {
        String path = manifestUri.getRawPath();
        if (path == null || !path.endsWith(".json")) {
            throw new RuntimeDownloadException(
                RuntimeDownloadException.FailureReason.INVALID_URI
            );
        }
        String signaturePath = path.substring(0, path.length() - ".json".length()) +
            ".sig.json";
        try {
            return new URI(
                manifestUri.getScheme(),
                manifestUri.getRawAuthority(),
                signaturePath,
                null,
                null
            );
        } catch (URISyntaxException error) {
            throw new RuntimeDownloadException(
                RuntimeDownloadException.FailureReason.INVALID_URI,
                error
            );
        }
    }

    private static final class HttpReleaseSource implements ReleaseSource {
        private final RuntimePaths paths;
        private final RuntimeLimits limits;
        private final RuntimeHttpSource source;

        private HttpReleaseSource(
            RuntimePaths paths,
            RuntimeLimits limits,
            RuntimeHttpSource source
        ) {
            this.paths = Objects.requireNonNull(paths, "paths");
            this.limits = Objects.requireNonNull(limits, "limits");
            this.source = Objects.requireNonNull(source, "source");
        }

        @Override
        public SignedManifestBytes fetch(InstallRequest request, CancellationToken token)
            throws RuntimeDownloadException {
            byte[] payload = source.fetchBytes(
                request.descriptor().manifestUri(),
                limits.maximumManifestBytes(),
                request.descriptor().allowedHosts(),
                token
            );
            byte[] signature = source.fetchBytes(
                signatureUri(request.descriptor().manifestUri()),
                limits.maximumSignatureEnvelopeBytes(),
                request.descriptor().allowedHosts(),
                token
            );
            return new SignedManifestBytes(payload, signature);
        }

        @Override
        public Path download(
            InstallRequest request,
            RuntimeArtifact artifact,
            Path destination,
            CancellationToken token
        ) throws RuntimeDownloadException {
            Path normalized = destination.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent == null || !parent.equals(paths.downloads())) {
                throw new RuntimeDownloadException(
                    RuntimeDownloadException.FailureReason.INVALID_EXPECTATION
                );
            }
            source.download(
                artifact.url(),
                normalized,
                artifact.archiveSize(),
                artifact.sha256(),
                request.descriptor().allowedHosts(),
                token
            );
            return normalized;
        }
    }

    private static InstallException failure(InstallFailureReason reason) {
        return new InstallException(reason);
    }

    private static InstallException failure(InstallFailureReason reason, Throwable cause) {
        return new InstallException(reason, cause);
    }
}
