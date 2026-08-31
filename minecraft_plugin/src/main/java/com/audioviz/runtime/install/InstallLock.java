package com.audioviz.runtime.install;

import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.INSTALL_ALREADY_RUNNING;
import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.LOCK_FAILURE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class InstallLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private InstallLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static InstallLock acquire(Path path) throws RuntimeInstaller.InstallException {
        if (path == null) {
            throw failure(LOCK_FAILURE);
        }
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (
            parent == null ||
            Files.isSymbolicLink(parent) ||
            !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(normalized)
        ) {
            throw failure(LOCK_FAILURE);
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(
                normalized,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            );
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw failure(INSTALL_ALREADY_RUNNING);
            }
            return new InstallLock(channel, lock);
        } catch (OverlappingFileLockException error) {
            closeAfterFailure(channel, error);
            throw failure(INSTALL_ALREADY_RUNNING, error);
        } catch (RuntimeInstaller.InstallException error) {
            throw error;
        } catch (IOException | UnsupportedOperationException error) {
            closeAfterFailure(channel, error);
            throw failure(LOCK_FAILURE, error);
        }
    }

    @Override
    public void close() throws RuntimeInstaller.InstallException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            lock.release();
        } catch (IOException error) {
            failure = error;
        }
        try {
            channel.close();
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure(LOCK_FAILURE, failure);
        }
    }

    private static void closeAfterFailure(FileChannel channel, Throwable primary) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException cleanupError) {
            primary.addSuppressed(cleanupError);
        }
    }

    private static RuntimeInstaller.InstallException failure(
        RuntimeInstaller.InstallFailureReason reason
    ) {
        return new RuntimeInstaller.InstallException(reason);
    }

    private static RuntimeInstaller.InstallException failure(
        RuntimeInstaller.InstallFailureReason reason,
        Throwable cause
    ) {
        return new RuntimeInstaller.InstallException(reason, cause);
    }
}
