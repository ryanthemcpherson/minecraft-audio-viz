package com.audioviz.runtime.store;

import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_RUNTIME;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_STATE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.NOT_CANDIDATE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.RETENTION_INVALID;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.TRANSACTION_CONFLICT;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.VERSION_COLLISION;

import com.audioviz.runtime.RuntimeLimits;
import com.audioviz.runtime.store.AtomicStateStore.Boundary;
import com.audioviz.runtime.store.AtomicStateStore.FaultInjector;
import com.audioviz.runtime.store.RuntimeArchiveVerifier.VerifiedRuntimeLayout;
import com.audioviz.runtime.store.RuntimeState.HealthState;
import com.audioviz.runtime.store.RuntimeTransaction.TransactionPhase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RuntimeStore {
    private final RuntimePaths paths;
    private final Clock clock;
    private final AtomicStateStore stateStore;
    private final RuntimeArchiveVerifier archiveVerifier;
    private final int maximumInstalledRecords;

    public RuntimeStore(
        RuntimePaths paths,
        RuntimeLimits limits,
        Clock clock,
        FaultInjector faultInjector
    ) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.clock = Objects.requireNonNull(clock, "clock");
        stateStore = new AtomicStateStore(paths, faultInjector);
        archiveVerifier = new RuntimeArchiveVerifier(limits, paths);
        maximumInstalledRecords = limits.maximumArchiveMembers();
    }

    public InstalledRuntime promote(
        VerifiedRuntimeLayout layout,
        RuntimeVersionId id,
        long manifestGeneration,
        String archiveSha256,
        int runtimeApi
    ) throws RuntimeStoreException {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(id, "id");
        if (stateStore.readTransaction().isPresent()) {
            throw failure(TRANSACTION_CONFLICT);
        }
        RuntimeState candidate = new RuntimeState(
            1,
            id,
            manifestGeneration,
            archiveSha256,
            layout.filesManifestSha256(),
            runtimeApi,
            clock.instant(),
            HealthState.CANDIDATE
        );
        VerifiedRuntimeLayout verified = archiveVerifier.verifyStaging(
            layout.root(),
            candidate.filesManifestSha256()
        );
        if (!verified.entrypoint().equals(layout.entrypoint())) {
            throw failure(INVALID_RUNTIME);
        }
        Path target = versionPath(id);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Optional<InstalledRuntime> existing = installed(id);
            if (
                existing.isEmpty() ||
                !existing.orElseThrow().state().filesManifestSha256()
                    .equals(candidate.filesManifestSha256())
            ) {
                throw failure(VERSION_COLLISION);
            }
            stateStore.deleteStaging(verified.root());
            return existing.orElseThrow();
        }
        Path stagingFileName = verified.root().getFileName();
        if (stagingFileName == null) {
            throw failure(INVALID_RUNTIME);
        }
        RuntimeTransaction transaction = new RuntimeTransaction(
            1,
            UUID.randomUUID(),
            TransactionPhase.PROMOTION_PREPARED,
            stagingFileName.toString(),
            candidate,
            null
        );
        stateStore.writeTransaction(transaction);
        return completePromotion(transaction);
    }

    public void activateCandidate(InstalledRuntime candidate) throws RuntimeStoreException {
        InstalledRuntime verifiedCandidate = requireInstalled(candidate);
        if (stateStore.readTransaction().isPresent()) {
            throw failure(TRANSACTION_CONFLICT);
        }
        Optional<InstalledRuntime> current = current();
        RuntimeState previous = current
            .filter(runtime -> !runtime.id().equals(candidate.id()))
            .map(InstalledRuntime::state)
            .orElse(null);
        RuntimeState activeCandidate = new RuntimeState(
            1,
            verifiedCandidate.id(),
            verifiedCandidate.state().manifestGeneration(),
            verifiedCandidate.state().archiveSha256(),
            verifiedCandidate.state().filesManifestSha256(),
            verifiedCandidate.state().runtimeApi(),
            clock.instant(),
            HealthState.CANDIDATE
        );
        RuntimeTransaction transaction = new RuntimeTransaction(
            1,
            UUID.randomUUID(),
            TransactionPhase.CANDIDATE_ACTIVATING,
            null,
            activeCandidate,
            previous
        );
        stateStore.writeTransaction(transaction);
        stateStore.writeState(
            paths.currentState(),
            activeCandidate,
            Boundary.CURRENT_TEMP_WRITE,
            Boundary.CURRENT_REPLACE
        );
        stateStore.writeTransaction(transaction.withPhase(TransactionPhase.CANDIDATE_ACTIVE));
    }

    public void markHealthy(InstalledRuntime candidate) throws RuntimeStoreException {
        requireInstalled(candidate);
        RuntimeTransaction transaction = stateStore.readTransaction()
            .orElseThrow(() -> failure(NOT_CANDIDATE));
        if (
            transaction.phase() != TransactionPhase.CANDIDATE_ACTIVE ||
            !transaction.candidate().id().equals(candidate.id())
        ) {
            throw failure(NOT_CANDIDATE);
        }
        validateActiveTransaction(transaction);
        RuntimeTransaction committing = transaction.withPhase(TransactionPhase.HEALTH_COMMITTING);
        stateStore.writeTransaction(committing);
        completeHealthCommit(committing);
    }

    public void recover() throws RuntimeStoreException {
        Optional<RuntimeTransaction> pending = stateStore.readTransaction();
        if (pending.isEmpty()) {
            return;
        }
        RuntimeTransaction transaction = pending.orElseThrow();
        switch (transaction.phase()) {
            case PROMOTION_PREPARED -> recoverPromotion(transaction);
            case CANDIDATE_ACTIVATING -> recoverActivation(transaction);
            case CANDIDATE_ACTIVE -> validateActiveTransaction(transaction);
            case HEALTH_COMMITTING -> completeHealthCommit(transaction);
        }
    }

    public Optional<InstalledRuntime> installed(RuntimeVersionId id)
        throws RuntimeStoreException {
        Objects.requireNonNull(id, "id");
        Optional<RuntimeState> metadata;
        try {
            metadata = stateStore.readState(paths.installedState(id));
        } catch (RuntimeStoreException error) {
            if (error.reason() == INVALID_STATE) {
                return Optional.empty();
            }
            throw error;
        }
        if (metadata.isEmpty() || !metadata.orElseThrow().id().equals(id)) {
            return Optional.empty();
        }
        RuntimeState state = metadata.orElseThrow();
        try {
            VerifiedRuntimeLayout layout = archiveVerifier.verifyVersion(
                versionPath(id),
                state.filesManifestSha256()
            );
            return Optional.of(new InstalledRuntime(
                id,
                layout.root(),
                layout.entrypoint(),
                layout.files(),
                state
            ));
        } catch (RuntimeStoreException error) {
            if (isCorruption(error.reason())) {
                return Optional.empty();
            }
            throw error;
        }
    }

    public Optional<InstalledRuntime> current() throws RuntimeStoreException {
        return resolvePointer(paths.currentState());
    }

    public Optional<InstalledRuntime> findCompatible(int runtimeApi)
        throws RuntimeStoreException {
        if (runtimeApi <= 0) {
            throw failure(INVALID_RUNTIME);
        }
        return allInstalled().stream()
            .filter(runtime -> runtime.state().runtimeApi() == runtimeApi)
            .max(Comparator
                .comparingLong((InstalledRuntime runtime) -> runtime.state().manifestGeneration())
                .thenComparing(runtime -> runtime.state().activatedAt()));
    }

    public Optional<InstalledRuntime> rollbackTarget(int runtimeApi)
        throws RuntimeStoreException {
        Optional<RuntimeTransaction> transaction = stateStore.readTransaction();
        if (
            transaction.isPresent() &&
            transaction.orElseThrow().phase() != TransactionPhase.PROMOTION_PREPARED
        ) {
            RuntimeState previous = transaction.orElseThrow().previous();
            Optional<InstalledRuntime> resolved = resolveExpected(previous, runtimeApi);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        Optional<InstalledRuntime> lastKnownGood = resolvePointer(paths.lastKnownGoodState());
        return lastKnownGood.filter(runtime -> runtime.state().runtimeApi() == runtimeApi);
    }

    public int prune(int retainVersions) throws RuntimeStoreException {
        return prune(retainVersions, Set.of());
    }

    public void discardStaging(Path staging) throws RuntimeStoreException {
        stateStore.deleteStaging(staging);
    }

    public int prune(int retainVersions, Set<RuntimeVersionId> pinned)
        throws RuntimeStoreException {
        if (retainVersions < 0) {
            throw failure(RETENTION_INVALID);
        }
        Set<RuntimeVersionId> protectedIds = new HashSet<>(Set.copyOf(pinned));
        stateStore.readState(paths.currentState()).ifPresent(state -> protectedIds.add(state.id()));
        stateStore.readState(paths.lastKnownGoodState()).ifPresent(state -> protectedIds.add(state.id()));
        stateStore.readTransaction().ifPresent(transaction -> {
            protectedIds.add(transaction.candidate().id());
            if (transaction.previous() != null) {
                protectedIds.add(transaction.previous().id());
            }
        });
        List<InstalledRuntime> unprotected = allInstalled().stream()
            .filter(runtime -> !protectedIds.contains(runtime.id()))
            .sorted(Comparator
                .comparingLong((InstalledRuntime runtime) -> runtime.state().manifestGeneration())
                .thenComparing(runtime -> runtime.state().activatedAt())
                .reversed())
            .toList();
        int removed = 0;
        for (int index = retainVersions; index < unprotected.size(); index++) {
            InstalledRuntime runtime = unprotected.get(index);
            stateStore.deleteVersion(runtime.root());
            stateStore.deleteInstalledState(runtime.id());
            removed++;
        }
        return removed;
    }

    AtomicStateStore atomicStateStore() {
        return stateStore;
    }

    private InstalledRuntime completePromotion(RuntimeTransaction transaction)
        throws RuntimeStoreException {
        if (transaction.phase() != TransactionPhase.PROMOTION_PREPARED) {
            throw failure(TRANSACTION_CONFLICT);
        }
        RuntimeState candidate = transaction.candidate();
        Path source = paths.staging().resolve(transaction.sourceStagingId()).normalize();
        Path target = versionPath(candidate.id());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            VerifiedRuntimeLayout existing;
            try {
                existing = archiveVerifier.verifyVersion(
                    target,
                    candidate.filesManifestSha256()
                );
            } catch (RuntimeStoreException error) {
                throw failure(VERSION_COLLISION, error);
            }
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                VerifiedRuntimeLayout staged = archiveVerifier.verifyStaging(
                    source,
                    candidate.filesManifestSha256()
                );
                if (!staged.filesManifestSha256().equals(existing.filesManifestSha256())) {
                    throw failure(VERSION_COLLISION);
                }
                stateStore.deleteStaging(source);
            }
        } else if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            archiveVerifier.verifyStaging(source, candidate.filesManifestSha256());
            stateStore.moveVersion(source, target);
        } else {
            stateStore.deleteTransaction();
            throw failure(INVALID_RUNTIME);
        }
        Optional<RuntimeState> existingMetadata = stateStore.readState(
            paths.installedState(candidate.id())
        );
        if (existingMetadata.isPresent()) {
            RuntimeState existing = existingMetadata.orElseThrow();
            if (
                !existing.id().equals(candidate.id()) ||
                !existing.filesManifestSha256().equals(candidate.filesManifestSha256())
            ) {
                throw failure(VERSION_COLLISION);
            }
        } else {
            stateStore.writeState(
                paths.installedState(candidate.id()),
                candidate,
                null,
                Boundary.INSTALLED_STATE_REPLACE
            );
        }
        stateStore.deleteTransaction();
        return installed(candidate.id()).orElseThrow(() -> failure(INVALID_RUNTIME));
    }

    private void recoverPromotion(RuntimeTransaction transaction)
        throws RuntimeStoreException {
        Path source = paths.staging().resolve(transaction.sourceStagingId()).normalize();
        Path target = versionPath(transaction.candidate().id());
        if (
            !Files.exists(source, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        ) {
            stateStore.deleteTransaction();
            return;
        }
        completePromotion(transaction);
    }

    private void recoverActivation(RuntimeTransaction transaction)
        throws RuntimeStoreException {
        requireInstalledState(transaction.candidate());
        Optional<RuntimeState> current = stateStore.readState(paths.currentState());
        if (
            current.isPresent() &&
            !sameRuntime(current.orElseThrow(), transaction.candidate()) &&
            !sameRuntime(current.orElseThrow(), transaction.previous())
        ) {
            throw failure(TRANSACTION_CONFLICT);
        }
        stateStore.writeState(
            paths.currentState(),
            transaction.candidate(),
            Boundary.CURRENT_TEMP_WRITE,
            Boundary.CURRENT_REPLACE
        );
        stateStore.writeTransaction(transaction.withPhase(TransactionPhase.CANDIDATE_ACTIVE));
    }

    private void validateActiveTransaction(RuntimeTransaction transaction)
        throws RuntimeStoreException {
        requireInstalledState(transaction.candidate());
        Optional<RuntimeState> current = stateStore.readState(paths.currentState());
        if (current.isEmpty() || !sameRuntime(current.orElseThrow(), transaction.candidate())) {
            throw failure(TRANSACTION_CONFLICT);
        }
    }

    private void completeHealthCommit(RuntimeTransaction transaction)
        throws RuntimeStoreException {
        if (transaction.phase() != TransactionPhase.HEALTH_COMMITTING) {
            throw failure(TRANSACTION_CONFLICT);
        }
        requireInstalledState(transaction.candidate());
        Optional<RuntimeState> current = stateStore.readState(paths.currentState());
        if (current.isEmpty() || !sameRuntime(current.orElseThrow(), transaction.candidate())) {
            throw failure(TRANSACTION_CONFLICT);
        }
        RuntimeState previous = transaction.previous();
        if (previous != null) {
            requireInstalledState(previous);
            stateStore.writeState(
                paths.lastKnownGoodState(),
                previous.withHealth(HealthState.HEALTHY),
                null,
                Boundary.LAST_KNOWN_GOOD_REPLACE
            );
        }
        RuntimeState healthy = transaction.candidate().withHealth(HealthState.HEALTHY);
        stateStore.writeState(
            paths.currentState(),
            healthy,
            Boundary.CURRENT_TEMP_WRITE,
            Boundary.CURRENT_REPLACE
        );
        stateStore.deleteTransaction();
    }

    private InstalledRuntime requireInstalled(InstalledRuntime candidate)
        throws RuntimeStoreException {
        Objects.requireNonNull(candidate, "candidate");
        InstalledRuntime installed = installed(candidate.id())
            .orElseThrow(() -> failure(INVALID_RUNTIME));
        if (!sameRuntime(installed.state(), candidate.state())) {
            throw failure(INVALID_RUNTIME);
        }
        return installed;
    }

    private void requireInstalledState(RuntimeState state) throws RuntimeStoreException {
        InstalledRuntime installed = installed(state.id())
            .orElseThrow(() -> failure(INVALID_RUNTIME));
        if (!sameRuntime(installed.state(), state)) {
            throw failure(INVALID_RUNTIME);
        }
    }

    private Optional<InstalledRuntime> resolvePointer(Path statePath)
        throws RuntimeStoreException {
        Optional<RuntimeState> pointer = stateStore.readState(statePath);
        if (pointer.isEmpty()) {
            return Optional.empty();
        }
        RuntimeState state = pointer.orElseThrow();
        Optional<InstalledRuntime> installed = installed(state.id());
        if (installed.isEmpty() || !sameRuntime(installed.orElseThrow().state(), state)) {
            return Optional.empty();
        }
        return Optional.of(installed.orElseThrow().withState(state));
    }

    private Optional<InstalledRuntime> resolveExpected(RuntimeState state, int runtimeApi)
        throws RuntimeStoreException {
        if (state == null || state.runtimeApi() != runtimeApi) {
            return Optional.empty();
        }
        Optional<InstalledRuntime> installed = installed(state.id());
        if (installed.isEmpty() || !sameRuntime(installed.orElseThrow().state(), state)) {
            return Optional.empty();
        }
        return Optional.of(installed.orElseThrow().withState(state));
    }

    private List<InstalledRuntime> allInstalled() throws RuntimeStoreException {
        List<InstalledRuntime> installed = new ArrayList<>();
        try (var entries = Files.list(paths.installedState())) {
            var iterator = entries.iterator();
            int observedRecords = 0;
            while (iterator.hasNext()) {
                Path metadata = iterator.next();
                observedRecords++;
                if (observedRecords > maximumInstalledRecords) {
                    throw failure(INVALID_STATE);
                }
                Path fileName = metadata.getFileName();
                if (
                    fileName == null ||
                    Files.isSymbolicLink(metadata) ||
                    !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) ||
                    !fileName.toString().endsWith(".json")
                ) {
                    continue;
                }
                Optional<RuntimeState> state;
                try {
                    state = stateStore.readState(metadata);
                } catch (RuntimeStoreException error) {
                    if (error.reason() == INVALID_STATE) {
                        continue;
                    }
                    throw error;
                }
                if (state.isEmpty()) {
                    continue;
                }
                RuntimeState value = state.orElseThrow();
                if (!metadata.equals(paths.installedState(value.id()))) {
                    continue;
                }
                Optional<InstalledRuntime> runtime = installed(value.id());
                runtime.ifPresent(installed::add);
            }
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (UncheckedIOException error) {
            throw failure(RuntimeStoreException.FailureReason.IO_FAILURE, error.getCause());
        } catch (IOException error) {
            throw failure(RuntimeStoreException.FailureReason.IO_FAILURE, error);
        }
        return List.copyOf(installed);
    }

    private Path versionPath(RuntimeVersionId id) {
        return paths.versions().resolve(id.directoryName());
    }

    private static boolean sameRuntime(RuntimeState left, RuntimeState right) {
        return right != null &&
            left.id().equals(right.id()) &&
            left.manifestGeneration() == right.manifestGeneration() &&
            left.archiveSha256().equals(right.archiveSha256()) &&
            left.filesManifestSha256().equals(right.filesManifestSha256()) &&
            left.runtimeApi() == right.runtimeApi();
    }

    private static boolean isCorruption(RuntimeStoreException.FailureReason reason) {
        return reason != RuntimeStoreException.FailureReason.IO_FAILURE &&
            reason != RuntimeStoreException.FailureReason.PATH_ESCAPE;
    }

    public record InstalledRuntime(
        RuntimeVersionId id,
        Path root,
        Path entrypoint,
        Map<String, PackagedFile> files,
        RuntimeState state
    ) {
        public InstalledRuntime {
            Objects.requireNonNull(id, "id");
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            entrypoint = Objects.requireNonNull(entrypoint, "entrypoint")
                .toAbsolutePath()
                .normalize();
            files = Map.copyOf(files);
            Objects.requireNonNull(state, "state");
        }

        InstalledRuntime withState(RuntimeState nextState) {
            return new InstalledRuntime(id, root, entrypoint, files, nextState);
        }
    }

    private static RuntimeStoreException failure(RuntimeStoreException.FailureReason reason) {
        return new RuntimeStoreException(reason);
    }

    private static RuntimeStoreException failure(
        RuntimeStoreException.FailureReason reason,
        Throwable cause
    ) {
        return new RuntimeStoreException(reason, cause);
    }
}
