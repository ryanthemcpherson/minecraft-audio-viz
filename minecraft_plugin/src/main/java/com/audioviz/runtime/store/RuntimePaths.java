package com.audioviz.runtime.store;

import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_ROOT;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.IO_FAILURE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class RuntimePaths {
    private final Path root;
    private final Path downloads;
    private final Path staging;
    private final Path versions;
    private final Path state;
    private final Path installedState;
    private final Path currentState;
    private final Path lastKnownGoodState;
    private final Path transactionJournal;
    private final Path installLock;

    private RuntimePaths(Path root) {
        this.root = root;
        downloads = root.resolve("downloads");
        staging = root.resolve("staging");
        versions = root.resolve("versions");
        state = root.resolve("state");
        installedState = state.resolve("installed");
        currentState = state.resolve("current.json");
        lastKnownGoodState = state.resolve("last-known-good.json");
        transactionJournal = state.resolve("transaction.json");
        installLock = state.resolve("install.lock");
    }

    public static RuntimePaths create(Path root) throws RuntimeStoreException {
        Path normalized = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (normalized.getParent() == null || normalized.getFileName() == null) {
            throw failure(INVALID_ROOT);
        }
        RuntimePaths paths = new RuntimePaths(normalized);
        paths.initializeOwnedDirectories();
        return paths;
    }

    private void initializeOwnedDirectories() throws RuntimeStoreException {
        try {
            ensureDirectory(root);
            ensureDirectory(downloads);
            ensureDirectory(staging);
            ensureDirectory(versions);
            ensureDirectory(state);
            ensureDirectory(installedState);
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    private static void ensureDirectory(Path directory)
        throws IOException, RuntimeStoreException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(INVALID_ROOT);
            }
            return;
        }
        Files.createDirectory(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(INVALID_ROOT);
        }
    }

    public Path newStaging() {
        return staging.resolve("stage-" + UUID.randomUUID());
    }

    public Path root() {
        return root;
    }

    public Path downloads() {
        return downloads;
    }

    public Path staging() {
        return staging;
    }

    public Path versions() {
        return versions;
    }

    public Path state() {
        return state;
    }

    public Path currentState() {
        return currentState;
    }

    public Path installedState() {
        return installedState;
    }

    public Path installedState(RuntimeVersionId id) {
        Objects.requireNonNull(id, "id");
        return installedState.resolve(id.directoryName() + ".json");
    }

    public Path lastKnownGoodState() {
        return lastKnownGoodState;
    }

    public Path transactionJournal() {
        return transactionJournal;
    }

    public Path installLock() {
        return installLock;
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
