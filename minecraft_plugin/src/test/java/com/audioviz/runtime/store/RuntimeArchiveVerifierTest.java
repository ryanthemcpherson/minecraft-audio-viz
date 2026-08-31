package com.audioviz.runtime.store;

import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.ARCHIVE_DIGEST_MISMATCH;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.ARCHIVE_NOT_FOUND;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.ARCHIVE_SIZE_MISMATCH;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.CASE_COLLISION;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.COMPRESSION_RATIO;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.DUPLICATE_MEMBER;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.ENCRYPTED_MEMBER;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.ENTRYPOINT_INVALID;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.FILES_MANIFEST_DIGEST;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.FILES_MANIFEST_INVALID;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.FILES_MANIFEST_MISSING;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_MEMBER_PATH;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_ROOT;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_ZIP;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.MEMBER_DIGEST_MISMATCH;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.MEMBER_SIZE_MISMATCH;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.MEMBER_TOO_LARGE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.MISSING_MEMBER;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.NON_REGULAR_MEMBER;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.PATH_COLLISION;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.PLATFORM_MISMATCH;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.STAGING_NOT_UNIQUE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.TOO_MANY_MEMBERS;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.TOTAL_TOO_LARGE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.UNDECLARED_MEMBER;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.UNSUPPORTED_COMPRESSION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.audioviz.runtime.RuntimeLimits;
import com.audioviz.runtime.release.RuntimeArtifact;
import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.RuntimeArchiveVerifier.VerifiedRuntimeLayout;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeArchiveVerifierTest {

    private static final RuntimePlatform PLATFORM = RuntimePlatform.LINUX_X86_64;
    private static final String ENTRYPOINT = "bin/audioviz-vj";
    private static final byte[] EXECUTABLE = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    private RuntimeArchiveVerifier verifier;
    private Path archives;

    @BeforeEach
    void setUp() throws Exception {
        RuntimePaths paths = RuntimePaths.create(temp.resolve("runtime"));
        archives = paths.downloads();
        verifier = new RuntimeArchiveVerifier(RuntimeLimits.releaseDefaults(), paths);
    }

    @Test
    void extractsVerifiedFilesAndEntrypoint() throws Exception {
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file("patterns/default.lua", "return {}\n".getBytes(StandardCharsets.UTF_8), false)
            )
        );
        Path staging = temp.resolve("runtime/staging/candidate");

        VerifiedRuntimeLayout layout = verifier.extract(
            fixture.path(),
            staging,
            fixture.artifact(),
            PLATFORM
        );

        assertEquals(staging.toAbsolutePath().normalize(), layout.root());
        assertEquals(staging.resolve(ENTRYPOINT).toAbsolutePath().normalize(), layout.entrypoint());
        assertEquals(Set.of(ENTRYPOINT, "patterns/default.lua"), layout.files().keySet());
        assertArrayEquals(EXECUTABLE, Files.readAllBytes(layout.entrypoint()));
        assertTrue(Files.isRegularFile(staging.resolve("files.json")));
        assertEquals(fixture.artifact().filesManifestSha256(), layout.filesManifestSha256());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void appliesOwnerOnlyPermissionsOnPosixFileSystems() throws Exception {
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file("patterns/default.lua", "return {}\n".getBytes(StandardCharsets.UTF_8), false)
            )
        );
        Path staging = temp.resolve("runtime/staging/candidate");

        verifier.extract(fixture.path(), staging, fixture.artifact(), PLATFORM);

        assertEquals("rwx------", PosixFilePermissions.toString(
            Files.getPosixFilePermissions(staging)
        ));
        assertEquals("rwx------", PosixFilePermissions.toString(
            Files.getPosixFilePermissions(staging.resolve(ENTRYPOINT))
        ));
        assertEquals("rw-------", PosixFilePermissions.toString(
            Files.getPosixFilePermissions(staging.resolve("patterns/default.lua"))
        ));
        assertEquals("rw-------", PosixFilePermissions.toString(
            Files.getPosixFilePermissions(staging.resolve("files.json"))
        ));
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
        "/absolute",
        "C:/drive",
        "C:\\drive",
        "//server/share",
        "\\\\server\\share",
        "../sentinel.txt",
        "bin/../tool",
        "bin/%2e%2e/tool",
        "bin\\tool",
        "./tool",
        "bin//tool",
        "bin/NUL.txt",
        "bin/COM1",
        "bin/file:stream",
        "bin/trailing.",
        "bin/trailing ",
        "bin/nul\u0000byte",
        "bin/control\u0001"
    })
    void rejectsCrossPlatformHostilePath(String hostilePath) throws Exception {
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file(hostilePath, new byte[]{1}, false)
            )
        );

        assertRejectedWithoutEscape(fixture, INVALID_MEMBER_PATH);
    }

    @Test
    void rejectsCaseCollisionAcrossDirectorySegments() throws Exception {
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file("Assets/one.txt", new byte[]{1}, false),
                file("assets/two.txt", new byte[]{2}, false)
            )
        );

        assertRejectedWithoutEscape(fixture, CASE_COLLISION);
    }

    @Test
    void rejectsDirectoryFileCollision() throws Exception {
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file("assets", new byte[]{1}, false),
                file("assets/two.txt", new byte[]{2}, false)
            )
        );

        assertRejectedWithoutEscape(fixture, PATH_COLLISION);
    }

    @Test
    void rejectsUnixSymlinkEntry() throws Exception {
        ArchiveFile link = new ArchiveFile(
            "bin/link",
            "../outside".getBytes(StandardCharsets.UTF_8),
            false,
            0120777
        );
        ArchiveFixture fixture = archive(
            List.of(file(ENTRYPOINT, EXECUTABLE, true), link)
        );

        assertRejectedWithoutEscape(fixture, NON_REGULAR_MEMBER);
    }

    @Test
    void rejectsUnixDeviceEntry() throws Exception {
        ArchiveFile device = new ArchiveFile("bin/device", new byte[]{1}, false, 0020666);
        ArchiveFixture fixture = archive(
            List.of(file(ENTRYPOINT, EXECUTABLE, true), device)
        );

        assertRejectedWithoutEscape(fixture, NON_REGULAR_MEMBER);
    }

    @Test
    void rejectsDuplicateCentralDirectoryMember() throws Exception {
        ArchiveFile duplicate = file("assets/value.bin", new byte[]{1}, false);
        ArchiveFixture fixture = archive(
            List.of(file(ENTRYPOINT, EXECUTABLE, true), duplicate, duplicate)
        );

        assertRejectedWithoutEscape(fixture, DUPLICATE_MEMBER);
    }

    @Test
    void rejectsArchiveMemberNotDeclaredByFilesManifest() throws Exception {
        List<ArchiveFile> declared = List.of(file(ENTRYPOINT, EXECUTABLE, true));
        List<ArchiveFile> archived = List.of(
            file(ENTRYPOINT, EXECUTABLE, true),
            file("extra.txt", new byte[]{1}, false)
        );
        ArchiveFixture fixture = archive(archived, declared, ENTRYPOINT, null);

        assertRejectedWithoutEscape(fixture, UNDECLARED_MEMBER);
    }

    @Test
    void rejectsDeclaredFileMissingFromArchive() throws Exception {
        List<ArchiveFile> declared = List.of(
            file(ENTRYPOINT, EXECUTABLE, true),
            file("missing.txt", new byte[]{1}, false)
        );
        ArchiveFixture fixture = archive(
            List.of(file(ENTRYPOINT, EXECUTABLE, true)),
            declared,
            ENTRYPOINT,
            null
        );

        assertRejectedWithoutEscape(fixture, MISSING_MEMBER);
    }

    @Test
    void rejectsDeclaredDigestMismatch() throws Exception {
        ArchiveFile actual = file("assets/value.bin", new byte[]{1}, false);
        ArchiveFile declared = file("assets/value.bin", new byte[]{2}, false);
        ArchiveFixture fixture = archive(
            List.of(file(ENTRYPOINT, EXECUTABLE, true), actual),
            List.of(file(ENTRYPOINT, EXECUTABLE, true), declared),
            ENTRYPOINT,
            null
        );

        assertRejectedWithoutEscape(fixture, MEMBER_DIGEST_MISMATCH);
    }

    @Test
    void rejectsDeclaredSizeMismatch() throws Exception {
        ArchiveFile actual = file("assets/value.bin", new byte[]{1, 2}, false);
        ArchiveFile declared = new ArchiveFile(actual.path(), new byte[]{1}, false, 0100644);
        ArchiveFixture fixture = archive(
            List.of(file(ENTRYPOINT, EXECUTABLE, true), actual),
            List.of(file(ENTRYPOINT, EXECUTABLE, true), declared),
            ENTRYPOINT,
            null
        );

        assertRejectedWithoutEscape(fixture, MEMBER_SIZE_MISMATCH);
    }

    @Test
    void rejectsFilesManifestDigestMismatch() throws Exception {
        ArchiveFixture fixture = archive(List.of(file(ENTRYPOINT, EXECUTABLE, true)));
        RuntimeArtifact tampered = artifact(
            fixture.path(),
            fixture.artifact().uncompressedSize(),
            "0".repeat(64),
            ENTRYPOINT
        );

        assertRejectedWithoutEscape(
            new ArchiveFixture(fixture.path(), tampered),
            FILES_MANIFEST_DIGEST
        );
    }

    @Test
    void rejectsMissingFilesManifest() throws Exception {
        ArchiveFixture fixture = archiveWithoutFilesManifest(
            List.of(file(ENTRYPOINT, EXECUTABLE, true))
        );

        assertRejectedWithoutEscape(fixture, FILES_MANIFEST_MISSING);
    }

    @Test
    void rejectsStrictlyMalformedFilesManifest() throws Exception {
        byte[] malformed = ("{\"files\":[],\"schema_version\":1,"
            + "\"schema_version\":1}\n").getBytes(StandardCharsets.UTF_8);
        ArchiveFixture fixture = archiveWithFilesManifest(
            List.of(file(ENTRYPOINT, EXECUTABLE, true)),
            malformed,
            ENTRYPOINT
        );

        assertRejectedWithoutEscape(fixture, FILES_MANIFEST_INVALID);
    }

    @Test
    void rejectsFilesManifestUnknownTypesAndInvalidUtf8() throws Exception {
        List<byte[]> invalidManifests = List.of(
            "{\"files\":[],\"schema_version\":1,\"unknown\":true}\n"
                .getBytes(StandardCharsets.UTF_8),
            ("{\"files\":[{\"executable\":true,\"path\":\"bin/audioviz-vj\","
                + "\"sha256\":\"" + sha256(EXECUTABLE) + "\",\"size\":1.5}],"
                + "\"schema_version\":1}\n").getBytes(StandardCharsets.UTF_8),
            new byte[]{(byte) 0xc3, 0x28}
        );

        for (byte[] invalidManifest : invalidManifests) {
            ArchiveFixture fixture = archiveWithFilesManifest(
                List.of(file(ENTRYPOINT, EXECUTABLE, true)),
                invalidManifest,
                ENTRYPOINT
            );
            assertRejectedWithoutEscape(fixture, FILES_MANIFEST_INVALID);
        }
    }

    @Test
    void rejectsArchiveDigestMismatchBeforeOpeningZip() throws Exception {
        ArchiveFixture fixture = archive(List.of(file(ENTRYPOINT, EXECUTABLE, true)));
        byte[] bytes = Files.readAllBytes(fixture.path());
        bytes[bytes.length / 2] ^= 1;
        Files.write(fixture.path(), bytes);

        assertRejectedWithoutEscape(fixture, ARCHIVE_DIGEST_MISMATCH);
    }

    @Test
    void rejectsArchiveSizeMismatchBeforeOpeningZip() throws Exception {
        ArchiveFixture fixture = archive(List.of(file(ENTRYPOINT, EXECUTABLE, true)));
        Files.write(
            fixture.path(),
            new byte[]{0},
            StandardOpenOption.APPEND
        );

        assertRejectedWithoutEscape(fixture, ARCHIVE_SIZE_MISMATCH);
    }

    @Test
    void rejectsArchiveOutsideOwnedDownloadDirectory() throws Exception {
        ArchiveFixture fixture = archive(List.of(file(ENTRYPOINT, EXECUTABLE, true)));
        Path outsideArchive = temp.resolve("outside.zip");
        Files.copy(fixture.path(), outsideArchive);
        RuntimeArtifact outsideArtifact = artifact(
            outsideArchive,
            fixture.artifact().uncompressedSize(),
            fixture.artifact().filesManifestSha256(),
            fixture.artifact().entrypoint()
        );

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> verifier.extract(
                outsideArchive,
                temp.resolve("runtime/staging/outside"),
                outsideArtifact,
                PLATFORM
            )
        );

        assertEquals(ARCHIVE_NOT_FOUND, failure.reason());
    }

    @Test
    void existingStagingDirectoryIsNeverReusedOrRemoved() throws Exception {
        ArchiveFixture fixture = archive(List.of(file(ENTRYPOINT, EXECUTABLE, true)));
        Path staging = temp.resolve("runtime/staging/existing");
        Files.createDirectory(staging);
        Files.writeString(staging.resolve("owned.txt"), "keep", StandardCharsets.UTF_8);

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> verifier.extract(fixture.path(), staging, fixture.artifact(), PLATFORM)
        );

        assertEquals(STAGING_NOT_UNIQUE, failure.reason());
        assertEquals("keep", Files.readString(staging.resolve("owned.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsArtifactPlatformMismatch() throws Exception {
        ArchiveFixture fixture = archive(List.of(file(ENTRYPOINT, EXECUTABLE, true)));

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> verifier.extract(
                fixture.path(),
                temp.resolve("runtime/staging/platform"),
                fixture.artifact(),
                RuntimePlatform.WINDOWS_X86_64
            )
        );

        assertEquals(PLATFORM_MISMATCH, failure.reason());
    }

    @Test
    void rejectsEmptyAndDotOnlyMemberNames() {
        for (String path : List.of("", ".", "..")) {
            RuntimeStoreException failure = assertThrows(
                RuntimeStoreException.class,
                () -> RuntimeArchiveVerifier.normalizeMemberPath(
                    path,
                    RuntimeLimits.releaseDefaults()
                )
            );
            assertEquals(INVALID_MEMBER_PATH, failure.reason());
        }
    }

    @Test
    void rejectsMissingEntrypointDeclaration() throws Exception {
        ArchiveFixture fixture = archive(
            List.of(file(ENTRYPOINT, EXECUTABLE, true)),
            List.of(file(ENTRYPOINT, EXECUTABLE, true)),
            "bin/missing",
            null
        );

        assertRejectedWithoutEscape(fixture, ENTRYPOINT_INVALID);
    }

    @Test
    void rejectsExecutableBitOnNonEntrypoint() throws Exception {
        ArchiveFile extraExecutable = file("bin/helper", new byte[]{1}, true);
        ArchiveFixture fixture = archive(
            List.of(file(ENTRYPOINT, EXECUTABLE, true), extraExecutable)
        );

        assertRejectedWithoutEscape(fixture, ENTRYPOINT_INVALID);
    }

    @Test
    void rejectsEncryptedMemberFlag() throws Exception {
        ArchiveFixture fixture = archive(List.of(file(ENTRYPOINT, EXECUTABLE, true)));
        patchFirstEntryFlags(fixture.path(), 0x0001);
        ArchiveFixture corrupted = refreshedFixture(fixture);

        assertRejectedWithoutEscape(corrupted, ENCRYPTED_MEMBER);
    }

    @Test
    void rejectsUnsupportedCompressionMethod() throws Exception {
        ArchiveFixture fixture = archive(List.of(file(ENTRYPOINT, EXECUTABLE, true)));
        patchFirstEntryMethod(fixture.path(), 99);
        ArchiveFixture corrupted = refreshedFixture(fixture);

        assertRejectedWithoutEscape(corrupted, UNSUPPORTED_COMPRESSION);
    }

    @Test
    void rejectsInvalidUtf8RawMemberName() throws Exception {
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file("xy", new byte[]{1}, false)
            )
        );
        patchEntryName(fixture.path(), "xy", new byte[]{(byte) 0xc3, 0x28});
        ArchiveFixture corrupted = refreshedFixture(fixture);

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> verifier.extract(
                corrupted.path(),
                temp.resolve("runtime/staging/invalid-utf8"),
                corrupted.artifact(),
                PLATFORM
            )
        );

        assertTrue(Set.of(INVALID_MEMBER_PATH, INVALID_ZIP).contains(failure.reason()));
        assertFalse(Files.exists(temp.resolve("runtime/staging/invalid-utf8")));
    }

    @Test
    void rejectsExcessiveMemberCount() throws Exception {
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file("one", new byte[]{1}, false)
            )
        );
        RuntimeArchiveVerifier bounded = new RuntimeArchiveVerifier(
            limits(2, 512L * 1024 * 1024, 4L * 1024 * 1024 * 1024, 100),
            RuntimePaths.create(temp.resolve("runtime"))
        );

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> bounded.extract(
                fixture.path(),
                temp.resolve("runtime/staging/count"),
                fixture.artifact(),
                PLATFORM
            )
        );

        assertEquals(TOO_MANY_MEMBERS, failure.reason());
    }

    @Test
    void rejectsOversizedMember() throws Exception {
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file("large", new byte[64], false)
            )
        );
        RuntimeArchiveVerifier bounded = new RuntimeArchiveVerifier(
            limits(16_384, 32, 4L * 1024 * 1024 * 1024, 100),
            RuntimePaths.create(temp.resolve("runtime"))
        );

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> bounded.extract(
                fixture.path(),
                temp.resolve("runtime/staging/member-size"),
                fixture.artifact(),
                PLATFORM
            )
        );

        assertEquals(MEMBER_TOO_LARGE, failure.reason());
    }

    @Test
    void rejectsExcessiveTotalSize() throws Exception {
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file("large", new byte[64], false)
            )
        );
        RuntimeArchiveVerifier bounded = new RuntimeArchiveVerifier(
            limits(16_384, 512L * 1024 * 1024, 32, 100),
            RuntimePaths.create(temp.resolve("runtime"))
        );

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> bounded.extract(
                fixture.path(),
                temp.resolve("runtime/staging/total-size"),
                fixture.artifact(),
                PLATFORM
            )
        );

        assertEquals(TOTAL_TOO_LARGE, failure.reason());
    }

    @Test
    void rejectsRuntimeRootThatIsAFile() throws Exception {
        Path root = temp.resolve("not-a-directory");
        Files.write(root, new byte[]{1});

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> RuntimePaths.create(root)
        );

        assertEquals(INVALID_ROOT, failure.reason());
    }

    @Test
    void runtimeVersionIdAcceptsOnlyPortableBoundedNames() {
        assertEquals(
            "1.2.0-beta--linux-x86_64",
            RuntimeVersionId.of("1.2.0-beta", PLATFORM).directoryName()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> RuntimeVersionId.of("../escape", PLATFORM)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> RuntimeVersionId.of("a".repeat(65), PLATFORM)
        );
    }

    @Test
    void portablePathPolicyHoldsForTenThousandGeneratedCombinations() throws Exception {
        String[] prefixes = {"", "/", "C:", "..", "assets", "Ａssets", "e\u0301"};
        String[] separators = {"/", "\\", "//", "%2f"};
        String[] segments = {"file", ".", "..", "NUL", "name.", "Name", "name", "safe-bin"};
        int generated = 0;
        outer:
        for (String prefix : prefixes) {
            for (String firstSeparator : separators) {
                for (String first : segments) {
                    for (String secondSeparator : separators) {
                        for (String second : segments) {
                            String candidate = prefix + firstSeparator + first
                                + secondSeparator + second + generated;
                            assertPortableOutcome(candidate);
                            generated++;
                            if (generated == 10_000) {
                                break outer;
                            }
                        }
                    }
                }
            }
        }
        while (generated < 10_000) {
            String candidate = "safe/segment-" + generated;
            assertPortableOutcome(candidate);
            generated++;
        }
        assertEquals(10_000, generated);
    }

    private void assertPortableOutcome(String candidate) throws Exception {
        Path staging = temp.resolve("property-stage").toAbsolutePath().normalize();
        try {
            String normalized = RuntimeArchiveVerifier.normalizeMemberPath(
                candidate,
                RuntimeLimits.releaseDefaults()
            );
            Path windowsResolved = staging
                .resolve(normalized.replace('/', java.io.File.separatorChar))
                .normalize();
            assertTrue(windowsResolved.startsWith(staging));
            Deque<String> posixSegments = new ArrayDeque<>();
            for (String segment : normalized.split("/", -1)) {
                assertFalse(segment.isEmpty());
                assertFalse(".".equals(segment));
                assertFalse("..".equals(segment));
                posixSegments.addLast(segment);
            }
            assertFalse(posixSegments.isEmpty());
        } catch (RuntimeStoreException expected) {
            assertFalse(Files.exists(staging));
        }
    }

    @Test
    void rejectsExcessiveCompressionRatio() throws Exception {
        byte[] repeated = new byte[16 * 1024];
        ArchiveFixture fixture = archive(
            List.of(
                file(ENTRYPOINT, EXECUTABLE, true),
                file("assets/repeated.bin", repeated, false)
            )
        );
        RuntimePaths strictPaths = RuntimePaths.create(temp.resolve("strict-runtime"));
        Path strictArchive = strictPaths.downloads().resolve("fixture.zip");
        Files.copy(fixture.path(), strictArchive);
        RuntimeArtifact strictArtifact = artifact(
            strictArchive,
            fixture.artifact().uncompressedSize(),
            fixture.artifact().filesManifestSha256(),
            fixture.artifact().entrypoint()
        );
        RuntimeArchiveVerifier strictVerifier = new RuntimeArchiveVerifier(
            limits(16_384, 512L * 1024 * 1024, 4L * 1024 * 1024 * 1024, 2),
            strictPaths
        );

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> strictVerifier.extract(
                strictArchive,
                temp.resolve("strict-runtime/staging/candidate"),
                strictArtifact,
                PLATFORM
            )
        );

        assertEquals(COMPRESSION_RATIO, failure.reason());
    }

    private ArchiveFixture archiveWithoutFilesManifest(List<ArchiveFile> files)
        throws Exception {
        Files.createDirectories(archives);
        Path archive = Files.createTempFile(archives, "fixture-", ".zip");
        writeArchive(archive, files, null);
        long uncompressedSize = files.stream().mapToLong(file -> file.bytes().length).sum();
        return new ArchiveFixture(
            archive,
            artifact(archive, uncompressedSize, "0".repeat(64), ENTRYPOINT)
        );
    }

    private ArchiveFixture archiveWithFilesManifest(
        List<ArchiveFile> archivedFiles,
        byte[] filesManifest,
        String entrypoint
    ) throws Exception {
        Files.createDirectories(archives);
        Path archive = Files.createTempFile(archives, "fixture-", ".zip");
        writeArchive(archive, archivedFiles, filesManifest);
        long uncompressedSize = archivedFiles
            .stream()
            .mapToLong(file -> file.bytes().length)
            .sum() + filesManifest.length;
        return new ArchiveFixture(
            archive,
            artifact(archive, uncompressedSize, sha256(filesManifest), entrypoint)
        );
    }

    private static void writeArchive(
        Path archive,
        List<ArchiveFile> archivedFiles,
        byte[] filesManifest
    ) throws IOException {
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)) {
            output.setUseZip64(Zip64Mode.AsNeeded);
            output.setEncoding(StandardCharsets.UTF_8.name());
            for (ArchiveFile file : archivedFiles) {
                ZipArchiveEntry entry = new ZipArchiveEntry(file.path());
                entry.setMethod(ZipEntry.DEFLATED);
                entry.setUnixMode(file.unixMode());
                entry.setTime(0);
                output.putArchiveEntry(entry);
                output.write(file.bytes());
                output.closeArchiveEntry();
            }
            if (filesManifest != null) {
                ZipArchiveEntry manifestEntry = new ZipArchiveEntry("files.json");
                manifestEntry.setMethod(ZipEntry.DEFLATED);
                manifestEntry.setUnixMode(0100644);
                manifestEntry.setTime(0);
                output.putArchiveEntry(manifestEntry);
                output.write(filesManifest);
                output.closeArchiveEntry();
            }
            output.finish();
        }
    }

    private static ArchiveFixture refreshedFixture(ArchiveFixture fixture) throws Exception {
        RuntimeArtifact original = fixture.artifact();
        return new ArchiveFixture(
            fixture.path(),
            artifact(
                fixture.path(),
                original.uncompressedSize(),
                original.filesManifestSha256(),
                original.entrypoint()
            )
        );
    }

    private static void patchFirstEntryFlags(Path archive, int additionalFlags)
        throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        boolean patchedLocal = false;
        boolean patchedCentral = false;
        for (int offset = 0; offset <= bytes.length - 10; offset++) {
            int signature = littleEndianInt(bytes, offset);
            if (!patchedLocal && signature == 0x04034b50) {
                writeLittleEndianShort(
                    bytes,
                    offset + 6,
                    littleEndianShort(bytes, offset + 6) | additionalFlags
                );
                patchedLocal = true;
            } else if (!patchedCentral && signature == 0x02014b50) {
                writeLittleEndianShort(
                    bytes,
                    offset + 8,
                    littleEndianShort(bytes, offset + 8) | additionalFlags
                );
                patchedCentral = true;
            }
        }
        if (!patchedLocal || !patchedCentral) {
            throw new IOException("fixture ZIP headers missing");
        }
        Files.write(archive, bytes);
    }

    private static void patchFirstEntryMethod(Path archive, int method) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        boolean patchedLocal = false;
        boolean patchedCentral = false;
        for (int offset = 0; offset <= bytes.length - 12; offset++) {
            int signature = littleEndianInt(bytes, offset);
            if (!patchedLocal && signature == 0x04034b50) {
                writeLittleEndianShort(bytes, offset + 8, method);
                patchedLocal = true;
            } else if (!patchedCentral && signature == 0x02014b50) {
                writeLittleEndianShort(bytes, offset + 10, method);
                patchedCentral = true;
            }
        }
        if (!patchedLocal || !patchedCentral) {
            throw new IOException("fixture ZIP headers missing");
        }
        Files.write(archive, bytes);
    }

    private static void patchEntryName(Path archive, String currentName, byte[] replacement)
        throws IOException {
        byte[] current = currentName.getBytes(StandardCharsets.UTF_8);
        if (current.length != replacement.length) {
            throw new IllegalArgumentException("replacement name length must match");
        }
        byte[] bytes = Files.readAllBytes(archive);
        int patches = 0;
        for (int offset = 0; offset <= bytes.length - 46; offset++) {
            int signature = littleEndianInt(bytes, offset);
            int nameLengthOffset;
            int nameOffset;
            if (signature == 0x04034b50) {
                nameLengthOffset = offset + 26;
                nameOffset = offset + 30;
            } else if (signature == 0x02014b50) {
                nameLengthOffset = offset + 28;
                nameOffset = offset + 46;
            } else {
                continue;
            }
            int nameLength = littleEndianShort(bytes, nameLengthOffset);
            if (
                nameLength == current.length &&
                nameOffset + nameLength <= bytes.length &&
                Arrays.equals(
                    Arrays.copyOfRange(bytes, nameOffset, nameOffset + nameLength),
                    current
                )
            ) {
                System.arraycopy(replacement, 0, bytes, nameOffset, replacement.length);
                patches++;
            }
        }
        if (patches != 2) {
            throw new IOException("expected local and central fixture name headers");
        }
        Files.write(archive, bytes);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) |
            ((bytes[offset + 1] & 0xff) << 8) |
            ((bytes[offset + 2] & 0xff) << 16) |
            ((bytes[offset + 3] & 0xff) << 24);
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static void writeLittleEndianShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xff);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    private void assertRejectedWithoutEscape(
        ArchiveFixture fixture,
        RuntimeStoreException.FailureReason expectedReason
    ) throws Exception {
        Path outside = temp.resolve("sentinel.txt");
        Files.writeString(outside, "unchanged", StandardCharsets.UTF_8);
        Path staging = temp.resolve("runtime/staging/rejected");

        RuntimeStoreException failure = assertThrows(
            RuntimeStoreException.class,
            () -> verifier.extract(fixture.path(), staging, fixture.artifact(), PLATFORM)
        );

        assertEquals(expectedReason, failure.reason());
        assertEquals("unchanged", Files.readString(outside, StandardCharsets.UTF_8));
        assertFalse(Files.exists(staging));
    }

    private ArchiveFixture archive(List<ArchiveFile> files) throws Exception {
        return archive(files, files, ENTRYPOINT, null);
    }

    private ArchiveFixture archive(
        List<ArchiveFile> archivedFiles,
        List<ArchiveFile> declaredFiles,
        String entrypoint,
        String filesDigestOverride
    ) throws Exception {
        Files.createDirectories(archives);
        Path archive = Files.createTempFile(archives, "fixture-", ".zip");
        byte[] filesManifest = filesManifest(declaredFiles);
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)) {
            output.setUseZip64(Zip64Mode.AsNeeded);
            output.setEncoding(StandardCharsets.UTF_8.name());
            for (ArchiveFile file : archivedFiles) {
                ZipArchiveEntry entry = new ZipArchiveEntry(file.path());
                entry.setMethod(ZipEntry.DEFLATED);
                entry.setUnixMode(file.unixMode());
                entry.setTime(0);
                output.putArchiveEntry(entry);
                output.write(file.bytes());
                output.closeArchiveEntry();
            }
            ZipArchiveEntry manifestEntry = new ZipArchiveEntry("files.json");
            manifestEntry.setMethod(ZipEntry.DEFLATED);
            manifestEntry.setUnixMode(0100644);
            manifestEntry.setTime(0);
            output.putArchiveEntry(manifestEntry);
            output.write(filesManifest);
            output.closeArchiveEntry();
            output.finish();
        }
        long uncompressedSize = archivedFiles
            .stream()
            .mapToLong(file -> file.bytes().length)
            .sum() + filesManifest.length;
        String filesDigest = filesDigestOverride == null
            ? sha256(filesManifest)
            : filesDigestOverride;
        return new ArchiveFixture(
            archive,
            artifact(archive, uncompressedSize, filesDigest, entrypoint)
        );
    }

    private static RuntimeArtifact artifact(
        Path archive,
        long uncompressedSize,
        String filesManifestDigest,
        String entrypoint
    ) throws Exception {
        byte[] archiveBytes = Files.readAllBytes(archive);
        return new RuntimeArtifact(
            PLATFORM,
            URI.create("https://releases.mcav.live/runtime/test.zip"),
            archiveBytes.length,
            uncompressedSize,
            sha256(archiveBytes),
            entrypoint,
            filesManifestDigest
        );
    }

    private static byte[] filesManifest(List<ArchiveFile> files) throws Exception {
        List<ArchiveFile> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(ArchiveFile::path));
        StringBuilder json = new StringBuilder("{\"files\":[");
        for (int index = 0; index < sorted.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            ArchiveFile file = sorted.get(index);
            json.append("{\"executable\":")
                .append(file.executable())
                .append(",\"path\":\"")
                .append(escapeJson(file.path()))
                .append("\",\"sha256\":\"")
                .append(sha256(file.bytes()))
                .append("\",\"size\":")
                .append(file.bytes().length)
                .append('}');
        }
        json.append("],\"schema_version\":1}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static ArchiveFile file(String path, byte[] bytes, boolean executable) {
        return new ArchiveFile(path, bytes.clone(), executable, executable ? 0100755 : 0100644);
    }

    private static RuntimeLimits limits(
        int maximumMembers,
        long maximumMemberBytes,
        long maximumExtractedBytes,
        int maximumRatio
    ) {
        RuntimeLimits defaults = RuntimeLimits.releaseDefaults();
        return new RuntimeLimits(
            defaults.maximumManifestBytes(),
            defaults.maximumSignatureEnvelopeBytes(),
            defaults.maximumTrustedKeys(),
            defaults.maximumArtifacts(),
            defaults.maximumIdentifierCharacters(),
            defaults.maximumVersionCharacters(),
            defaults.maximumEntrypointCharacters(),
            defaults.maximumArchiveBytes(),
            maximumExtractedBytes,
            maximumMembers,
            defaults.maximumMemberPathBytes(),
            maximumMemberBytes,
            maximumRatio,
            defaults.maximumJsonDepth()
        );
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record ArchiveFile(
        String path,
        byte[] bytes,
        boolean executable,
        int unixMode
    ) {
        private ArchiveFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record ArchiveFixture(Path path, RuntimeArtifact artifact) {}
}
