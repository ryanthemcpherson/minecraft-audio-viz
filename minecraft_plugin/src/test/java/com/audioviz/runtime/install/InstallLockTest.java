package com.audioviz.runtime.install;

import static com.audioviz.runtime.install.RuntimeInstaller.InstallFailureReason.LOCK_FAILURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class InstallLockTest {
    @TempDir
    Path temp;

    @Test
    void lockFilePersistsAndCanBeReacquiredAfterIdempotentClose() throws Exception {
        Path path = temp.resolve("install.lock");
        InstallLock lock = InstallLock.acquire(path);
        lock.close();
        lock.close();

        assertTrue(Files.isRegularFile(path));
        try (InstallLock ignored = InstallLock.acquire(path)) {
            assertTrue(Files.isRegularFile(path));
        }
    }

    @Test
    void rejectsInvalidLockTargets() throws Exception {
        assertReason(() -> InstallLock.acquire(null));
        assertReason(() -> InstallLock.acquire(temp.resolve("missing/install.lock")));
        Path directory = Files.createDirectory(temp.resolve("directory"));
        assertReason(() -> InstallLock.acquire(directory));
        Path parentFile = Files.writeString(temp.resolve("parent-file"), "not a directory");
        assertReason(() -> InstallLock.acquire(parentFile.resolve("install.lock")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsSymlinkLockFile() throws Exception {
        Path target = Files.writeString(temp.resolve("target"), "target");
        Path link = temp.resolve("install.lock");
        Files.createSymbolicLink(link, target);

        assertReason(() -> InstallLock.acquire(link));
    }

    private static void assertReason(ThrowingAcquire acquire) {
        RuntimeInstaller.InstallException failure = assertThrows(
            RuntimeInstaller.InstallException.class,
            acquire::run
        );
        assertEquals(LOCK_FAILURE, failure.reason());
    }

    @FunctionalInterface
    private interface ThrowingAcquire {
        void run() throws Exception;
    }
}
