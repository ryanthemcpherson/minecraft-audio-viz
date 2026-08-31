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
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_ZIP;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.IO_FAILURE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.MEMBER_DIGEST_MISMATCH;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.MEMBER_SIZE_MISMATCH;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.MEMBER_TOO_LARGE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.MISSING_MEMBER;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.NON_REGULAR_MEMBER;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.PATH_COLLISION;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.PATH_ESCAPE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.PLATFORM_MISMATCH;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.STAGING_NOT_UNIQUE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.TOO_MANY_MEMBERS;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.TOTAL_TOO_LARGE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.UNDECLARED_MEMBER;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.UNSUPPORTED_COMPRESSION;

import com.audioviz.runtime.RuntimeLimits;
import com.audioviz.runtime.release.RuntimeArtifact;
import com.audioviz.runtime.release.RuntimePlatform;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

public final class RuntimeArchiveVerifier {
    private static final String FILES_MANIFEST_PATH = "files.json";
    private static final int UNIX_FILE_TYPE_MASK = 0170000;
    private static final int UNIX_REGULAR_FILE = 0100000;
    private static final Set<String> FILE_MANIFEST_FIELDS = Set.of("schema_version", "files");
    private static final Set<String> PACKAGED_FILE_FIELDS = Set.of(
        "path",
        "size",
        "sha256",
        "executable"
    );
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );
    private static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );

    private final RuntimeLimits limits;
    private final RuntimePaths paths;

    @FunctionalInterface
    public interface CancellationToken {
        boolean isCancelled();
    }

    public RuntimeArchiveVerifier(RuntimeLimits limits, RuntimePaths paths) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public VerifiedRuntimeLayout extract(
        Path archive,
        Path uniqueStaging,
        RuntimeArtifact artifact,
        RuntimePlatform platform
    ) throws RuntimeStoreException {
        return extract(archive, uniqueStaging, artifact, platform, () -> false);
    }

    public VerifiedRuntimeLayout extract(
        Path archive,
        Path uniqueStaging,
        RuntimeArtifact artifact,
        RuntimePlatform platform,
        CancellationToken cancellationToken
    ) throws RuntimeStoreException {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        checkCancelled(cancellationToken);
        if (artifact.platform() != platform) {
            throw failure(PLATFORM_MISMATCH);
        }
        Path validatedArchive = validateArchivePath(archive, artifact);
        Path validatedStaging = validateStagingPath(uniqueStaging);
        verifyArchiveDigest(validatedArchive, artifact.sha256(), cancellationToken);

        try (ZipFile zip = ZipFile.builder()
            .setPath(validatedArchive)
            .setCharset(StandardCharsets.UTF_8)
            .setUseUnicodeExtraFields(false)
            .setIgnoreLocalFileHeader(false)
            .get()) {
            ArchiveIndex index = inspectCentralDirectory(zip, artifact, cancellationToken);
            byte[] filesManifestBytes = readFilesManifest(
                zip,
                index.filesManifestEntry(),
                cancellationToken
            );
            verifyDigest(
                filesManifestBytes,
                artifact.filesManifestSha256(),
                FILES_MANIFEST_DIGEST
            );
            Map<String, PackagedFile> packagedFiles = parseFilesManifest(filesManifestBytes);
            validateInventory(index.members(), packagedFiles, artifact.entrypoint());
            extractVerifiedFiles(
                zip,
                index.members(),
                packagedFiles,
                filesManifestBytes,
                artifact.entrypoint(),
                validatedStaging,
                cancellationToken
            );
            Path entrypoint = resolveUnder(validatedStaging, artifact.entrypoint());
            return new VerifiedRuntimeLayout(
                validatedStaging,
                entrypoint,
                packagedFiles,
                filesManifestBytes,
                artifact.filesManifestSha256(),
                artifact.uncompressedSize()
            );
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (ZipException error) {
            throw failure(INVALID_ZIP, error);
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    public VerifiedRuntimeLayout verifyStaging(Path staging, String filesManifestSha256)
        throws RuntimeStoreException {
        return verifyExtractedLayout(staging, paths.staging(), filesManifestSha256);
    }

    public VerifiedRuntimeLayout verifyVersion(Path version, String filesManifestSha256)
        throws RuntimeStoreException {
        return verifyExtractedLayout(version, paths.versions(), filesManifestSha256);
    }

    private VerifiedRuntimeLayout verifyExtractedLayout(
        Path root,
        Path expectedParent,
        String filesManifestSha256
    ) throws RuntimeStoreException {
        if (root == null) {
            throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path rootParent = normalizedRoot.getParent();
        if (
            rootParent == null ||
            !rootParent.equals(expectedParent) ||
            Files.isSymbolicLink(normalizedRoot) ||
            !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT);
        }
        Path filesManifest = normalizedRoot.resolve(FILES_MANIFEST_PATH);
        if (
            Files.isSymbolicLink(filesManifest) ||
            !Files.isRegularFile(filesManifest, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw failure(FILES_MANIFEST_MISSING);
        }
        try {
            long manifestSize = Files.size(filesManifest);
            if (manifestSize <= 0 || manifestSize > limits.maximumManifestBytes()) {
                throw failure(FILES_MANIFEST_INVALID);
            }
            byte[] manifestBytes;
            try (InputStream input = Files.newInputStream(filesManifest, StandardOpenOption.READ)) {
                manifestBytes = readBounded(
                    input,
                    limits.maximumManifestBytes(),
                    manifestSize
                );
            }
            verifyDigest(manifestBytes, filesManifestSha256, FILES_MANIFEST_DIGEST);
            Map<String, PackagedFile> packagedFiles = parseFilesManifest(manifestBytes);
            PackagedFile entrypointFile = packagedFiles.values()
                .stream()
                .filter(PackagedFile::executable)
                .reduce((left, right) -> {
                    throw new IllegalArgumentException("multiple entrypoints");
                })
                .orElseThrow(() -> new IllegalArgumentException("missing entrypoint"));
            Set<Path> expectedDirectories = new HashSet<>();
            long total = manifestSize;
            for (PackagedFile file : packagedFiles.values()) {
                Path destination = resolveUnder(normalizedRoot, file.path());
                Path parent = destination.getParent();
                while (parent != null && !parent.equals(normalizedRoot)) {
                    if (
                        Files.isSymbolicLink(parent) ||
                        !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT);
                    }
                    expectedDirectories.add(parent);
                    parent = parent.getParent();
                }
                verifyExtractedFile(destination, file);
                total = addSize(total, file.size());
                if (total > limits.maximumExtractedBytes()) {
                    throw failure(TOTAL_TOO_LARGE);
                }
            }
            long maximumEntries = 2L + packagedFiles.size() + expectedDirectories.size();
            try (var entries = Files.walk(normalizedRoot)) {
                var iterator = entries.iterator();
                long observedEntries = 0;
                while (iterator.hasNext()) {
                    Path entry = iterator.next();
                    observedEntries++;
                    if (observedEntries > maximumEntries) {
                        throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT);
                    }
                    if (entry.equals(normalizedRoot)) {
                        continue;
                    }
                    if (Files.isSymbolicLink(entry)) {
                        throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT);
                    }
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        if (!expectedDirectories.contains(entry)) {
                            throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT);
                        }
                        continue;
                    }
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT);
                    }
                    Path relative = normalizedRoot.relativize(entry);
                    String portable = relative.toString().replace(java.io.File.separatorChar, '/');
                    if (
                        !FILES_MANIFEST_PATH.equals(portable) &&
                        !packagedFiles.containsKey(portable)
                    ) {
                        throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT);
                    }
                }
            }
            return new VerifiedRuntimeLayout(
                normalizedRoot,
                resolveUnder(normalizedRoot, entrypointFile.path()),
                packagedFiles,
                manifestBytes,
                filesManifestSha256,
                total
            );
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (UncheckedIOException error) {
            throw failure(IO_FAILURE, error.getCause());
        } catch (IOException | IllegalArgumentException error) {
            throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT, error);
        }
    }

    private void verifyExtractedFile(Path path, PackagedFile file)
        throws RuntimeStoreException {
        if (
            Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw failure(RuntimeStoreException.FailureReason.VERSION_CORRUPT);
        }
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            while (true) {
                int read = input.read(buffer, 0, boundedReadLength(file.size(), total, buffer.length));
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw failure(IO_FAILURE);
                }
                total += read;
                if (total > file.size()) {
                    throw failure(MEMBER_SIZE_MISMATCH);
                }
                digest.update(buffer, 0, read);
            }
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
        if (total != file.size()) {
            throw failure(MEMBER_SIZE_MISMATCH);
        }
        byte[] expected = parseDigest(file.sha256(), MEMBER_DIGEST_MISMATCH);
        if (!MessageDigest.isEqual(expected, digest.digest())) {
            throw failure(MEMBER_DIGEST_MISMATCH);
        }
    }

    private Path validateArchivePath(Path archive, RuntimeArtifact artifact)
        throws RuntimeStoreException {
        if (archive == null) {
            throw failure(ARCHIVE_NOT_FOUND);
        }
        Path normalized = archive.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (
            parent == null ||
            !parent.equals(paths.downloads()) ||
            !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw failure(ARCHIVE_NOT_FOUND);
        }
        try {
            long size = Files.size(normalized);
            if (
                size != artifact.archiveSize() ||
                size <= 0 ||
                size > limits.maximumArchiveBytes()
            ) {
                throw failure(ARCHIVE_SIZE_MISMATCH);
            }
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
        return normalized;
    }

    private Path validateStagingPath(Path staging) throws RuntimeStoreException {
        if (staging == null) {
            throw failure(STAGING_NOT_UNIQUE);
        }
        Path normalized = staging.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (
            parent == null ||
            normalized.getFileName() == null ||
            !parent.equals(paths.staging()) ||
            Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw failure(STAGING_NOT_UNIQUE);
        }
        return normalized;
    }

    private void verifyArchiveDigest(
        Path archive,
        String expectedSha256,
        CancellationToken cancellationToken
    )
        throws RuntimeStoreException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = Files.newInputStream(archive, StandardOpenOption.READ)) {
            while (true) {
                checkCancelled(cancellationToken);
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw failure(IO_FAILURE);
                }
                digest.update(buffer, 0, read);
            }
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
        byte[] expected = parseDigest(expectedSha256, ARCHIVE_DIGEST_MISMATCH);
        if (!MessageDigest.isEqual(expected, digest.digest())) {
            throw failure(ARCHIVE_DIGEST_MISMATCH);
        }
    }

    private ArchiveIndex inspectCentralDirectory(
        ZipFile zip,
        RuntimeArtifact artifact,
        CancellationToken cancellationToken
    )
        throws RuntimeStoreException {
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        Map<String, ZipArchiveEntry> members = new LinkedHashMap<>();
        PathRegistry registry = new PathRegistry();
        ZipArchiveEntry filesManifestEntry = null;
        long totalUncompressed = 0;
        int count = 0;
        while (entries.hasMoreElements()) {
            checkCancelled(cancellationToken);
            ZipArchiveEntry entry = entries.nextElement();
            count++;
            if (count > limits.maximumArchiveMembers()) {
                throw failure(TOO_MANY_MEMBERS);
            }
            String path = validateRawEntryName(entry);
            registry.register(path);
            validateEntryMetadata(zip, entry);
            totalUncompressed = addSize(totalUncompressed, entry.getSize());
            if (totalUncompressed > limits.maximumExtractedBytes()) {
                throw failure(TOTAL_TOO_LARGE);
            }
            if (FILES_MANIFEST_PATH.equals(path)) {
                filesManifestEntry = entry;
            }
            members.put(path, entry);
        }
        if (filesManifestEntry == null) {
            throw failure(FILES_MANIFEST_MISSING);
        }
        if (totalUncompressed != artifact.uncompressedSize()) {
            throw failure(ARCHIVE_SIZE_MISMATCH);
        }
        return new ArchiveIndex(Map.copyOf(members), filesManifestEntry);
    }

    private String validateRawEntryName(ZipArchiveEntry entry) throws RuntimeStoreException {
        byte[] rawName = entry.getRawName();
        if (rawName == null) {
            throw failure(INVALID_MEMBER_PATH);
        }
        String decoded;
        try {
            decoded = decodeUtf8(rawName);
        } catch (CharacterCodingException error) {
            throw failure(INVALID_MEMBER_PATH, error);
        }
        if (
            !decoded.equals(entry.getName()) ||
            entry.getExtraField(UnicodePathExtraField.UPATH_ID) != null
        ) {
            throw failure(INVALID_MEMBER_PATH);
        }
        return normalizeMemberPath(decoded, limits);
    }

    private void validateEntryMetadata(ZipFile zip, ZipArchiveEntry entry)
        throws RuntimeStoreException {
        if (entry.getGeneralPurposeBit().usesEncryption()) {
            throw failure(ENCRYPTED_MEMBER);
        }
        if (
            entry.getMethod() != ZipEntry.STORED &&
            entry.getMethod() != ZipEntry.DEFLATED
        ) {
            throw failure(UNSUPPORTED_COMPRESSION);
        }
        if (!zip.canReadEntryData(entry)) {
            throw failure(UNSUPPORTED_COMPRESSION);
        }
        int unixType = entry.getUnixMode() & UNIX_FILE_TYPE_MASK;
        if (
            entry.isDirectory() ||
            entry.isUnixSymlink() ||
            (unixType != 0 && unixType != UNIX_REGULAR_FILE)
        ) {
            throw failure(NON_REGULAR_MEMBER);
        }
        long size = entry.getSize();
        long compressedSize = entry.getCompressedSize();
        if (size < 0 || compressedSize < 0) {
            throw failure(INVALID_ZIP);
        }
        long memberLimit = FILES_MANIFEST_PATH.equals(entry.getName())
            ? Math.min(limits.maximumMemberBytes(), limits.maximumManifestBytes())
            : limits.maximumMemberBytes();
        if (size > memberLimit) {
            throw failure(MEMBER_TOO_LARGE);
        }
        long ratioDenominator = Math.max(1, compressedSize);
        if (size > ratioDenominator * limits.maximumCompressionRatio()) {
            throw failure(COMPRESSION_RATIO);
        }
    }

    private byte[] readFilesManifest(
        ZipFile zip,
        ZipArchiveEntry entry,
        CancellationToken cancellationToken
    )
        throws RuntimeStoreException {
        try (InputStream input = zip.getInputStream(entry)) {
            return readBounded(
                input,
                limits.maximumManifestBytes(),
                entry.getSize(),
                cancellationToken
            );
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (IOException error) {
            throw failure(INVALID_ZIP, error);
        }
    }

    private void validateInventory(
        Map<String, ZipArchiveEntry> members,
        Map<String, PackagedFile> packagedFiles,
        String entrypoint
    ) throws RuntimeStoreException {
        String normalizedEntrypoint = normalizeMemberPath(entrypoint, limits);
        PackagedFile entrypointFile = packagedFiles.get(normalizedEntrypoint);
        if (entrypointFile == null || !entrypointFile.executable()) {
            throw failure(ENTRYPOINT_INVALID);
        }
        for (PackagedFile file : packagedFiles.values()) {
            if (file.executable() != file.path().equals(normalizedEntrypoint)) {
                throw failure(ENTRYPOINT_INVALID);
            }
            ZipArchiveEntry entry = members.get(file.path());
            if (entry == null) {
                throw failure(MISSING_MEMBER);
            }
            if (entry.getSize() != file.size()) {
                throw failure(MEMBER_SIZE_MISMATCH);
            }
        }
        for (String member : members.keySet()) {
            if (!FILES_MANIFEST_PATH.equals(member) && !packagedFiles.containsKey(member)) {
                throw failure(UNDECLARED_MEMBER);
            }
        }
    }

    private void extractVerifiedFiles(
        ZipFile zip,
        Map<String, ZipArchiveEntry> members,
        Map<String, PackagedFile> packagedFiles,
        byte[] filesManifestBytes,
        String entrypoint,
        Path staging,
        CancellationToken cancellationToken
    ) throws RuntimeStoreException {
        List<Path> created = new ArrayList<>();
        RuntimeStoreException primaryFailure = null;
        try {
            checkCancelled(cancellationToken);
            Files.createDirectory(staging);
            created.add(staging);
            boolean posix = Files.getFileStore(staging)
                .supportsFileAttributeView(PosixFileAttributeView.class);
            if (posix) {
                Files.setPosixFilePermissions(staging, DIRECTORY_PERMISSIONS);
            }
            List<PackagedFile> sortedFiles = packagedFiles.values()
                .stream()
                .sorted(Comparator.comparing(PackagedFile::path))
                .toList();
            for (PackagedFile file : sortedFiles) {
                checkCancelled(cancellationToken);
                Path destination = resolveUnder(staging, file.path());
                createOwnedParents(staging, destination.getParent(), created, posix);
                created.add(destination);
                extractFile(
                    zip,
                    members.get(file.path()),
                    file,
                    destination,
                    posix,
                    cancellationToken
                );
            }
            checkCancelled(cancellationToken);
            Path manifestDestination = resolveUnder(staging, FILES_MANIFEST_PATH);
            created.add(manifestDestination);
            writeForcedFile(manifestDestination, filesManifestBytes, false, posix);
            Path resolvedEntrypoint = resolveUnder(staging, entrypoint);
            if (!Files.isRegularFile(resolvedEntrypoint, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(ENTRYPOINT_INVALID);
            }
        } catch (RuntimeStoreException error) {
            primaryFailure = error;
            throw error;
        } catch (IOException error) {
            primaryFailure = failure(IO_FAILURE, error);
            throw primaryFailure;
        } finally {
            if (primaryFailure != null) {
                cleanupCreated(created, primaryFailure);
            }
        }
    }

    private void extractFile(
        ZipFile zip,
        ZipArchiveEntry entry,
        PackagedFile file,
        Path destination,
        boolean posix,
        CancellationToken cancellationToken
    ) throws IOException, RuntimeStoreException {
        MessageDigest digest = sha256Digest();
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (
            InputStream input = zip.getInputStream(entry);
            FileChannel output = FileChannel.open(
                destination,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        ) {
            while (true) {
                checkCancelled(cancellationToken);
                int read;
                try {
                    read = input.read(buffer, 0, boundedReadLength(file.size(), total, buffer.length));
                } catch (IOException error) {
                    throw failure(INVALID_ZIP, error);
                }
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw failure(IO_FAILURE);
                }
                total += read;
                if (total > file.size()) {
                    throw failure(MEMBER_SIZE_MISMATCH);
                }
                digest.update(buffer, 0, read);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
            }
            if (total != file.size()) {
                throw failure(MEMBER_SIZE_MISMATCH);
            }
            byte[] expectedDigest = parseDigest(file.sha256(), MEMBER_DIGEST_MISMATCH);
            if (!MessageDigest.isEqual(expectedDigest, digest.digest())) {
                throw failure(MEMBER_DIGEST_MISMATCH);
            }
            output.force(true);
        }
        if (posix) {
            Files.setPosixFilePermissions(
                destination,
                file.executable() ? EXECUTABLE_PERMISSIONS : FILE_PERMISSIONS
            );
        }
    }

    private static void writeForcedFile(
        Path destination,
        byte[] bytes,
        boolean executable,
        boolean posix
    ) throws IOException {
        try (FileChannel output = FileChannel.open(
            destination,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                output.write(buffer);
            }
            output.force(true);
        }
        if (posix) {
            Files.setPosixFilePermissions(
                destination,
                executable ? EXECUTABLE_PERMISSIONS : FILE_PERMISSIONS
            );
        }
    }

    private static void createOwnedParents(
        Path staging,
        Path parent,
        List<Path> created,
        boolean posix
    ) throws IOException, RuntimeStoreException {
        Path relative = staging.relativize(parent);
        Path current = staging;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
                created.add(current);
                if (posix) {
                    Files.setPosixFilePermissions(current, DIRECTORY_PERMISSIONS);
                }
            } else if (
                Files.isSymbolicLink(current) ||
                !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw failure(PATH_ESCAPE);
            }
        }
    }

    private static void cleanupCreated(List<Path> created, RuntimeStoreException primaryFailure) {
        for (int index = created.size() - 1; index >= 0; index--) {
            try {
                Files.deleteIfExists(created.get(index));
            } catch (IOException cleanupError) {
                primaryFailure.addSuppressed(cleanupError);
            }
        }
    }

    private static Path resolveUnder(Path staging, String memberPath)
        throws RuntimeStoreException {
        Path resolved = staging.resolve(memberPath.replace('/', java.io.File.separatorChar))
            .toAbsolutePath()
            .normalize();
        if (!resolved.startsWith(staging) || resolved.equals(staging)) {
            throw failure(PATH_ESCAPE);
        }
        return resolved;
    }

    private static byte[] readBounded(InputStream input, int maximumBytes, long expectedBytes)
        throws IOException, RuntimeStoreException {
        return readBounded(input, maximumBytes, expectedBytes, () -> false);
    }

    private static byte[] readBounded(
        InputStream input,
        int maximumBytes,
        long expectedBytes,
        CancellationToken cancellationToken
    )
        throws IOException, RuntimeStoreException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
            Math.min(maximumBytes, 16 * 1024)
        );
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        while (true) {
            checkCancelled(cancellationToken);
            int read = input.read(buffer, 0, boundedReadLength(maximumBytes, total, buffer.length));
            if (read < 0) {
                break;
            }
            if (read == 0) {
                throw failure(IO_FAILURE);
            }
            total += read;
            if (total > maximumBytes || total > expectedBytes) {
                throw failure(MEMBER_TOO_LARGE);
            }
            output.write(buffer, 0, read);
        }
        if (total != expectedBytes) {
            throw failure(MEMBER_SIZE_MISMATCH);
        }
        return output.toByteArray();
    }

    private static void checkCancelled(CancellationToken cancellationToken)
        throws RuntimeStoreException {
        if (cancellationToken.isCancelled()) {
            throw failure(RuntimeStoreException.FailureReason.CANCELLED);
        }
    }

    private static int boundedReadLength(long maximumBytes, long bytesRead, int bufferLength) {
        long remaining = maximumBytes - bytesRead;
        if (remaining >= bufferLength) {
            return bufferLength;
        }
        return Math.toIntExact(remaining + 1);
    }

    private static long addSize(long current, long addition) throws RuntimeStoreException {
        try {
            return Math.addExact(current, addition);
        } catch (ArithmeticException error) {
            throw failure(TOTAL_TOO_LARGE, error);
        }
    }

    private static void verifyDigest(
        byte[] bytes,
        String expectedSha256,
        RuntimeStoreException.FailureReason reason
    ) throws RuntimeStoreException {
        byte[] expected = parseDigest(expectedSha256, reason);
        byte[] actual = sha256Digest().digest(bytes);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw failure(reason);
        }
    }

    private static byte[] parseDigest(
        String digest,
        RuntimeStoreException.FailureReason reason
    ) throws RuntimeStoreException {
        if (digest == null || digest.length() != 64) {
            throw failure(reason);
        }
        try {
            return HexFormat.of().parseHex(digest);
        } catch (IllegalArgumentException error) {
            throw failure(reason, error);
        }
    }

    private static MessageDigest sha256Digest() throws RuntimeStoreException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    public record VerifiedRuntimeLayout(
        Path root,
        Path entrypoint,
        Map<String, PackagedFile> files,
        byte[] filesManifestBytes,
        String filesManifestSha256,
        long extractedBytes
    ) {
        public VerifiedRuntimeLayout {
            root = root.toAbsolutePath().normalize();
            entrypoint = entrypoint.toAbsolutePath().normalize();
            files = Map.copyOf(files);
            filesManifestBytes = filesManifestBytes.clone();
            Objects.requireNonNull(filesManifestSha256, "filesManifestSha256");
        }

        @Override
        public byte[] filesManifestBytes() {
            return filesManifestBytes.clone();
        }
    }

    private record ArchiveIndex(
        Map<String, ZipArchiveEntry> members,
        ZipArchiveEntry filesManifestEntry
    ) {}

    private Map<String, PackagedFile> parseFilesManifest(byte[] bytes)
        throws RuntimeStoreException {
        String decoded;
        try {
            decoded = decodeUtf8(bytes);
        } catch (CharacterCodingException error) {
            throw failure(FILES_MANIFEST_INVALID, error);
        }
        try (JsonReader reader = new JsonReader(new StringReader(decoded))) {
            reader.setStrictness(Strictness.STRICT);
            requireToken(reader, JsonToken.BEGIN_OBJECT);
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            Integer schemaVersion = null;
            Map<String, PackagedFile> files = null;
            while (reader.hasNext()) {
                String field = nextField(reader, seen, FILE_MANIFEST_FIELDS);
                switch (field) {
                    case "schema_version" -> schemaVersion = exactInteger(reader, 1);
                    case "files" -> files = readPackagedFiles(reader);
                    default -> throw failure(FILES_MANIFEST_INVALID);
                }
            }
            reader.endObject();
            requireFields(seen, FILE_MANIFEST_FIELDS);
            if (reader.peek() != JsonToken.END_DOCUMENT || schemaVersion != 1) {
                throw failure(FILES_MANIFEST_INVALID);
            }
            return Map.copyOf(files);
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (IOException | IllegalStateException | NumberFormatException error) {
            throw failure(FILES_MANIFEST_INVALID, error);
        }
    }

    private Map<String, PackagedFile> readPackagedFiles(JsonReader reader)
        throws IOException, RuntimeStoreException {
        requireToken(reader, JsonToken.BEGIN_ARRAY);
        reader.beginArray();
        Map<String, PackagedFile> files = new LinkedHashMap<>();
        PathRegistry registry = new PathRegistry();
        while (reader.hasNext()) {
            if (files.size() >= limits.maximumArchiveMembers() - 1) {
                throw failure(TOO_MANY_MEMBERS);
            }
            PackagedFile file = readPackagedFile(reader);
            registry.register(file.path());
            if (FILES_MANIFEST_PATH.equals(file.path())) {
                throw failure(FILES_MANIFEST_INVALID);
            }
            files.put(file.path(), file);
        }
        reader.endArray();
        return files;
    }

    private PackagedFile readPackagedFile(JsonReader reader)
        throws IOException, RuntimeStoreException {
        requireToken(reader, JsonToken.BEGIN_OBJECT);
        reader.beginObject();
        Set<String> seen = new HashSet<>();
        String path = null;
        Long size = null;
        String sha256 = null;
        Boolean executable = null;
        while (reader.hasNext()) {
            String field = nextField(reader, seen, PACKAGED_FILE_FIELDS);
            switch (field) {
                case "path" -> path = boundedString(reader, limits.maximumMemberPathBytes());
                case "size" -> size = exactLong(reader, limits.maximumMemberBytes());
                case "sha256" -> sha256 = digestString(reader);
                case "executable" -> executable = exactBoolean(reader);
                default -> throw failure(FILES_MANIFEST_INVALID);
            }
        }
        reader.endObject();
        requireFields(seen, PACKAGED_FILE_FIELDS);
        String normalizedPath = normalizeMemberPath(path, limits);
        return new PackagedFile(normalizedPath, size, sha256, executable);
    }

    private static String nextField(
        JsonReader reader,
        Set<String> seen,
        Set<String> expected
    ) throws IOException, RuntimeStoreException {
        String field = reader.nextName();
        if (!seen.add(field) || !expected.contains(field)) {
            throw failure(FILES_MANIFEST_INVALID);
        }
        return field;
    }

    private static void requireFields(Set<String> seen, Set<String> expected)
        throws RuntimeStoreException {
        if (!seen.containsAll(expected)) {
            throw failure(FILES_MANIFEST_INVALID);
        }
    }

    private static int exactInteger(JsonReader reader, int maximum)
        throws IOException, RuntimeStoreException {
        long value = exactLong(reader, maximum);
        return Math.toIntExact(value);
    }

    private static long exactLong(JsonReader reader, long maximum)
        throws IOException, RuntimeStoreException {
        requireToken(reader, JsonToken.NUMBER);
        String raw = reader.nextString();
        if (!isCanonicalNonNegativeInteger(raw)) {
            throw failure(FILES_MANIFEST_INVALID);
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw failure(FILES_MANIFEST_INVALID, error);
        }
        if (value < 0 || value > maximum) {
            throw failure(MEMBER_TOO_LARGE);
        }
        return value;
    }

    private static boolean isCanonicalNonNegativeInteger(String value) {
        if (value.isEmpty()) {
            return false;
        }
        if (value.length() > 1 && value.charAt(0) == '0') {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static String boundedString(JsonReader reader, int maximumCharacters)
        throws IOException, RuntimeStoreException {
        requireToken(reader, JsonToken.STRING);
        String value = reader.nextString();
        if (value.isEmpty() || value.length() > maximumCharacters) {
            throw failure(FILES_MANIFEST_INVALID);
        }
        return value;
    }

    private static String digestString(JsonReader reader)
        throws IOException, RuntimeStoreException {
        String value = boundedString(reader, 64);
        if (value.length() != 64) {
            throw failure(FILES_MANIFEST_INVALID);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (
                (character < '0' || character > '9') &&
                (character < 'a' || character > 'f')
            ) {
                throw failure(FILES_MANIFEST_INVALID);
            }
        }
        return value;
    }

    private static boolean exactBoolean(JsonReader reader)
        throws IOException, RuntimeStoreException {
        requireToken(reader, JsonToken.BOOLEAN);
        return reader.nextBoolean();
    }

    private static void requireToken(JsonReader reader, JsonToken expected)
        throws IOException, RuntimeStoreException {
        if (reader.peek() != expected) {
            throw failure(FILES_MANIFEST_INVALID);
        }
    }

    static String normalizeMemberPath(String rawPath, RuntimeLimits limits)
        throws RuntimeStoreException {
        if (
            rawPath == null ||
            rawPath.isEmpty() ||
            rawPath.startsWith("/") ||
            rawPath.indexOf('\\') >= 0 ||
            rawPath.indexOf('%') >= 0 ||
            !Normalizer.normalize(rawPath, Normalizer.Form.NFC).equals(rawPath)
        ) {
            throw failure(INVALID_MEMBER_PATH);
        }
        try {
            if (encodedUtf8Length(rawPath) > limits.maximumMemberPathBytes()) {
                throw failure(INVALID_MEMBER_PATH);
            }
        } catch (CharacterCodingException error) {
            throw failure(INVALID_MEMBER_PATH, error);
        }
        String[] segments = rawPath.split("/", -1);
        if (segments.length == 0) {
            throw failure(INVALID_MEMBER_PATH);
        }
        for (String segment : segments) {
            validateSegment(segment);
        }
        return String.join("/", segments);
    }

    private static void validateSegment(String segment) throws RuntimeStoreException {
        if (
            segment.isEmpty() ||
            ".".equals(segment) ||
            "..".equals(segment) ||
            segment.endsWith(".") ||
            segment.endsWith(" ")
        ) {
            throw failure(INVALID_MEMBER_PATH);
        }
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (
                character < 0x20 ||
                character == 0x7f ||
                character == '<' ||
                character == '>' ||
                character == ':' ||
                character == '"' ||
                character == '|' ||
                character == '?' ||
                character == '*'
            ) {
                throw failure(INVALID_MEMBER_PATH);
            }
        }
        String baseName = segment;
        int extension = segment.indexOf('.');
        if (extension >= 0) {
            baseName = segment.substring(0, extension);
        }
        String uppercase = baseName.toUpperCase(Locale.ROOT);
        if (
            Set.of("CON", "PRN", "AUX", "NUL", "CLOCK$").contains(uppercase) ||
            isNumberedDevice(uppercase, "COM") ||
            isNumberedDevice(uppercase, "LPT")
        ) {
            throw failure(INVALID_MEMBER_PATH);
        }
    }

    private static boolean isNumberedDevice(String value, String prefix) {
        return value.length() == 4 &&
            value.startsWith(prefix) &&
            value.charAt(3) >= '1' &&
            value.charAt(3) <= '9';
    }

    private static int encodedUtf8Length(String value) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value));
        return encoded.remaining();
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    private static final class PathRegistry {
        private final Map<String, String> originalByPortableKey = new HashMap<>();
        private final Set<String> fileKeys = new HashSet<>();
        private final Set<String> directoryKeys = new HashSet<>();

        void register(String path) throws RuntimeStoreException {
            String[] segments = path.split("/", -1);
            StringBuilder prefix = new StringBuilder();
            for (int index = 0; index < segments.length; index++) {
                if (index > 0) {
                    prefix.append('/');
                }
                prefix.append(segments[index]);
                String original = prefix.toString();
                String key = original.toLowerCase(Locale.ROOT);
                String existing = originalByPortableKey.putIfAbsent(key, original);
                if (existing != null && !existing.equals(original)) {
                    throw failure(CASE_COLLISION);
                }
                boolean complete = index == segments.length - 1;
                if (!complete) {
                    if (fileKeys.contains(key)) {
                        throw failure(PATH_COLLISION);
                    }
                    directoryKeys.add(key);
                } else {
                    if (directoryKeys.contains(key)) {
                        throw failure(PATH_COLLISION);
                    }
                    if (!fileKeys.add(key)) {
                        throw failure(DUPLICATE_MEMBER);
                    }
                }
            }
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
