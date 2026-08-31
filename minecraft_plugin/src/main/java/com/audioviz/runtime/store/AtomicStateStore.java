package com.audioviz.runtime.store;

import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.ATOMIC_MOVE_UNSUPPORTED;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.INVALID_STATE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.IO_FAILURE;
import static com.audioviz.runtime.store.RuntimeStoreException.FailureReason.PATH_ESCAPE;

import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.RuntimeState.HealthState;
import com.audioviz.runtime.store.RuntimeTransaction.TransactionPhase;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.CopyOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AtomicStateStore {
    static final int MAXIMUM_STATE_BYTES = 64 * 1024;

    private static final Set<String> STATE_FIELDS = Set.of(
        "schema_version",
        "id",
        "manifest_generation",
        "archive_sha256",
        "files_manifest_sha256",
        "runtime_api",
        "activated_at",
        "health"
    );
    private static final Set<String> ID_FIELDS = Set.of("release_version", "platform");
    private static final Set<String> TRANSACTION_FIELDS = Set.of(
        "schema_version",
        "operation_id",
        "phase",
        "source_staging_id",
        "candidate",
        "previous"
    );

    public enum Boundary {
        BEFORE_FILE_FORCE,
        AFTER_FILE_FORCE,
        JOURNAL_WRITE,
        VERSION_MOVE,
        INSTALLED_STATE_REPLACE,
        CURRENT_TEMP_WRITE,
        CURRENT_REPLACE,
        LAST_KNOWN_GOOD_REPLACE,
        JOURNAL_CLEANUP,
        PRUNE
    }

    @FunctionalInterface
    public interface FaultInjector {
        void at(Boundary boundary) throws IOException;
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path destination, CopyOption... options) throws IOException;
    }

    private final RuntimePaths paths;
    private final FaultInjector faultInjector;
    private final AtomicMover atomicMover;

    public AtomicStateStore(RuntimePaths paths, FaultInjector faultInjector) {
        this(paths, faultInjector, (source, destination, options) ->
            Files.move(source, destination, options));
    }

    AtomicStateStore(
        RuntimePaths paths,
        FaultInjector faultInjector,
        AtomicMover atomicMover
    ) {
        this.paths = java.util.Objects.requireNonNull(paths, "paths");
        this.faultInjector = java.util.Objects.requireNonNull(faultInjector, "faultInjector");
        this.atomicMover = java.util.Objects.requireNonNull(atomicMover, "atomicMover");
    }

    Optional<RuntimeState> readState(Path path) throws RuntimeStoreException {
        validateStatePath(path);
        Optional<byte[]> bytes = readOptional(path);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        try (JsonReader reader = jsonReader(bytes.orElseThrow())) {
            RuntimeState state = readRuntimeState(reader);
            requireEnd(reader);
            return Optional.of(state);
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            throw failure(INVALID_STATE, error);
        }
    }

    Optional<RuntimeTransaction> readTransaction() throws RuntimeStoreException {
        Optional<byte[]> bytes = readOptional(paths.transactionJournal());
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        try (JsonReader reader = jsonReader(bytes.orElseThrow())) {
            requireToken(reader, JsonToken.BEGIN_OBJECT);
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            Integer schemaVersion = null;
            UUID operationId = null;
            TransactionPhase phase = null;
            String sourceStagingId = null;
            boolean sourceSeen = false;
            RuntimeState candidate = null;
            RuntimeState previous = null;
            boolean previousSeen = false;
            while (reader.hasNext()) {
                String field = nextField(reader, seen, TRANSACTION_FIELDS);
                switch (field) {
                    case "schema_version" -> schemaVersion = exactInteger(reader);
                    case "operation_id" -> operationId = uuid(reader);
                    case "phase" -> phase = TransactionPhase.fromWireName(string(reader, 32));
                    case "source_staging_id" -> {
                        sourceSeen = true;
                        sourceStagingId = nullableString(reader, 64);
                    }
                    case "candidate" -> candidate = readRuntimeState(reader);
                    case "previous" -> {
                        previousSeen = true;
                        previous = nullableState(reader);
                    }
                    default -> throw failure(INVALID_STATE);
                }
            }
            reader.endObject();
            requireFields(seen, TRANSACTION_FIELDS);
            requireEnd(reader);
            if (!sourceSeen || !previousSeen) {
                throw failure(INVALID_STATE);
            }
            return Optional.of(new RuntimeTransaction(
                schemaVersion,
                operationId,
                phase,
                sourceStagingId,
                candidate,
                previous
            ));
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            throw failure(INVALID_STATE, error);
        }
    }

    void writeState(
        Path destination,
        RuntimeState state,
        Boundary tempBoundary,
        Boundary replaceBoundary
    ) throws RuntimeStoreException {
        validateStatePath(destination);
        writeAtomically(
            destination,
            stateBytes(state),
            tempBoundary,
            replaceBoundary
        );
    }

    void writeTransaction(RuntimeTransaction transaction) throws RuntimeStoreException {
        writeAtomically(
            paths.transactionJournal(),
            transactionBytes(transaction),
            null,
            Boundary.JOURNAL_WRITE
        );
    }

    void deleteTransaction() throws RuntimeStoreException {
        try {
            Files.deleteIfExists(paths.transactionJournal());
            checkpoint(Boundary.JOURNAL_CLEANUP);
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    void moveVersion(Path staging, Path version) throws RuntimeStoreException {
        validateDirectChild(staging, paths.staging());
        validateDirectChild(version, paths.versions());
        if (
            !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(staging)
        ) {
            throw failure(PATH_ESCAPE);
        }
        try {
            try {
                Files.move(staging, version, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(staging, version);
            }
            checkpoint(Boundary.VERSION_MOVE);
        } catch (FileAlreadyExistsException error) {
            throw failure(RuntimeStoreException.FailureReason.VERSION_COLLISION, error);
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    void deleteStaging(Path staging) throws RuntimeStoreException {
        deleteOwnedTree(staging, paths.staging(), null);
    }

    void deleteVersion(Path version) throws RuntimeStoreException {
        deleteOwnedTree(version, paths.versions(), Boundary.PRUNE);
    }

    void deleteInstalledState(RuntimeVersionId id) throws RuntimeStoreException {
        Path metadata = paths.installedState(id);
        try {
            Files.deleteIfExists(metadata);
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    private void writeAtomically(
        Path destination,
        byte[] bytes,
        Boundary tempBoundary,
        Boundary replaceBoundary
    ) throws RuntimeStoreException {
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(PATH_ESCAPE);
        }
        Path temporary = parent.resolve(
            "." + destination.getFileName() + ".tmp-" + UUID.randomUUID()
        );
        boolean moved = false;
        RuntimeStoreException primaryFailure = null;
        try {
            try (FileChannel output = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                checkpoint(Boundary.BEFORE_FILE_FORCE);
                output.force(true);
                checkpoint(Boundary.AFTER_FILE_FORCE);
            }
            if (tempBoundary != null) {
                checkpoint(tempBoundary);
            }
            try {
                atomicMover.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException error) {
                if (
                    Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(paths.transactionJournal(), LinkOption.NOFOLLOW_LINKS)
                ) {
                    throw failure(ATOMIC_MOVE_UNSUPPORTED, error);
                }
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            if (replaceBoundary != null) {
                checkpoint(replaceBoundary);
            }
        } catch (RuntimeStoreException error) {
            primaryFailure = error;
            throw error;
        } catch (IOException error) {
            primaryFailure = failure(IO_FAILURE, error);
            throw primaryFailure;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupError) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupError);
                    } else {
                        throw failure(IO_FAILURE, cleanupError);
                    }
                }
            }
        }
    }

    private Optional<byte[]> readOptional(Path path) throws RuntimeStoreException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(INVALID_STATE);
        }
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAXIMUM_STATE_BYTES) {
                throw failure(INVALID_STATE);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
            byte[] buffer = new byte[8 * 1024];
            long total = 0;
            try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
                while (true) {
                    int allowed = (int) Math.min(buffer.length, MAXIMUM_STATE_BYTES - total + 1);
                    int read = input.read(buffer, 0, allowed);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        throw failure(IO_FAILURE);
                    }
                    total += read;
                    if (total > MAXIMUM_STATE_BYTES) {
                        throw failure(INVALID_STATE);
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (total != size) {
                throw failure(INVALID_STATE);
            }
            return Optional.of(output.toByteArray());
        } catch (RuntimeStoreException error) {
            throw error;
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    private void validateStatePath(Path path) throws RuntimeStoreException {
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (
            parent == null ||
            (!parent.equals(paths.state()) && !parent.equals(paths.installedState()))
        ) {
            throw failure(PATH_ESCAPE);
        }
    }

    private static void validateDirectChild(Path child, Path expectedParent)
        throws RuntimeStoreException {
        Path normalized = child.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || !parent.equals(expectedParent)) {
            throw failure(PATH_ESCAPE);
        }
    }

    private void deleteOwnedTree(Path root, Path expectedParent, Boundary boundary)
        throws RuntimeStoreException {
        validateDirectChild(root, expectedParent);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(PATH_ESCAPE);
        }
        try {
            if (boundary != null) {
                checkpoint(boundary);
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error)
                    throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    private void checkpoint(Boundary boundary) throws IOException {
        faultInjector.at(boundary);
    }

    private static byte[] stateBytes(RuntimeState state) throws RuntimeStoreException {
        try {
            StringWriter text = new StringWriter();
            try (JsonWriter writer = new JsonWriter(text)) {
                writeRuntimeState(writer, state);
            }
            return (text + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    private static byte[] transactionBytes(RuntimeTransaction transaction)
        throws RuntimeStoreException {
        try {
            StringWriter text = new StringWriter();
            try (JsonWriter writer = new JsonWriter(text)) {
                writer.beginObject();
                writer.name("schema_version").value(transaction.schemaVersion());
                writer.name("operation_id").value(transaction.operationId().toString());
                writer.name("phase").value(transaction.phase().wireName());
                writer.name("source_staging_id");
                if (transaction.sourceStagingId() == null) {
                    writer.nullValue();
                } else {
                    writer.value(transaction.sourceStagingId());
                }
                writer.name("candidate");
                writeRuntimeState(writer, transaction.candidate());
                writer.name("previous");
                if (transaction.previous() == null) {
                    writer.nullValue();
                } else {
                    writeRuntimeState(writer, transaction.previous());
                }
                writer.endObject();
            }
            return (text + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    private static void writeRuntimeState(JsonWriter writer, RuntimeState state)
        throws IOException {
        writer.beginObject();
        writer.name("schema_version").value(state.schemaVersion());
        writer.name("id").beginObject();
        writer.name("release_version").value(state.id().releaseVersion());
        writer.name("platform").value(state.id().platform().wireName());
        writer.endObject();
        writer.name("manifest_generation").value(state.manifestGeneration());
        writer.name("archive_sha256").value(state.archiveSha256());
        writer.name("files_manifest_sha256").value(state.filesManifestSha256());
        writer.name("runtime_api").value(state.runtimeApi());
        writer.name("activated_at").value(state.activatedAt().toString());
        writer.name("health").value(state.health().wireName());
        writer.endObject();
    }

    private static RuntimeState nullableState(JsonReader reader)
        throws IOException, RuntimeStoreException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return readRuntimeState(reader);
    }

    private static RuntimeState readRuntimeState(JsonReader reader)
        throws IOException, RuntimeStoreException {
        requireToken(reader, JsonToken.BEGIN_OBJECT);
        reader.beginObject();
        Set<String> seen = new HashSet<>();
        Integer schemaVersion = null;
        RuntimeVersionId id = null;
        Long manifestGeneration = null;
        String archiveSha256 = null;
        String filesManifestSha256 = null;
        Integer runtimeApi = null;
        Instant activatedAt = null;
        HealthState health = null;
        while (reader.hasNext()) {
            String field = nextField(reader, seen, STATE_FIELDS);
            switch (field) {
                case "schema_version" -> schemaVersion = exactInteger(reader);
                case "id" -> id = readId(reader);
                case "manifest_generation" -> manifestGeneration = exactLong(reader);
                case "archive_sha256" -> archiveSha256 = digest(reader);
                case "files_manifest_sha256" -> filesManifestSha256 = digest(reader);
                case "runtime_api" -> runtimeApi = exactInteger(reader);
                case "activated_at" -> activatedAt = instant(reader);
                case "health" -> health = HealthState.fromWireName(string(reader, 16));
                default -> throw failure(INVALID_STATE);
            }
        }
        reader.endObject();
        requireFields(seen, STATE_FIELDS);
        return new RuntimeState(
            schemaVersion,
            id,
            manifestGeneration,
            archiveSha256,
            filesManifestSha256,
            runtimeApi,
            activatedAt,
            health
        );
    }

    private static RuntimeVersionId readId(JsonReader reader)
        throws IOException, RuntimeStoreException {
        requireToken(reader, JsonToken.BEGIN_OBJECT);
        reader.beginObject();
        Set<String> seen = new HashSet<>();
        String releaseVersion = null;
        RuntimePlatform platform = null;
        while (reader.hasNext()) {
            String field = nextField(reader, seen, ID_FIELDS);
            switch (field) {
                case "release_version" -> releaseVersion = string(reader, 64);
                case "platform" -> platform = platform(string(reader, 32));
                default -> throw failure(INVALID_STATE);
            }
        }
        reader.endObject();
        requireFields(seen, ID_FIELDS);
        return RuntimeVersionId.of(releaseVersion, platform);
    }

    private static RuntimePlatform platform(String wireName) {
        for (RuntimePlatform platform : RuntimePlatform.values()) {
            if (platform.wireName().equals(wireName)) {
                return platform;
            }
        }
        throw new IllegalArgumentException("unsupported platform");
    }

    private static JsonReader jsonReader(byte[] bytes) throws RuntimeStoreException {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw failure(INVALID_STATE, error);
        }
        JsonReader reader = new JsonReader(new StringReader(decoded));
        reader.setStrictness(Strictness.STRICT);
        return reader;
    }

    private static String nextField(JsonReader reader, Set<String> seen, Set<String> expected)
        throws IOException, RuntimeStoreException {
        String field = reader.nextName();
        if (!seen.add(field) || !expected.contains(field)) {
            throw failure(INVALID_STATE);
        }
        return field;
    }

    private static void requireFields(Set<String> seen, Set<String> expected)
        throws RuntimeStoreException {
        if (!seen.containsAll(expected)) {
            throw failure(INVALID_STATE);
        }
    }

    private static String nullableString(JsonReader reader, int maximum)
        throws IOException, RuntimeStoreException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return string(reader, maximum);
    }

    private static String string(JsonReader reader, int maximum)
        throws IOException, RuntimeStoreException {
        requireToken(reader, JsonToken.STRING);
        String value = reader.nextString();
        if (value.isEmpty() || value.length() > maximum) {
            throw failure(INVALID_STATE);
        }
        return value;
    }

    private static String digest(JsonReader reader) throws IOException, RuntimeStoreException {
        String value = string(reader, 64);
        if (value.length() != 64) {
            throw failure(INVALID_STATE);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (
                (character < '0' || character > '9') &&
                (character < 'a' || character > 'f')
            ) {
                throw failure(INVALID_STATE);
            }
        }
        return value;
    }

    private static int exactInteger(JsonReader reader)
        throws IOException, RuntimeStoreException {
        long value = exactLong(reader);
        if (value > Integer.MAX_VALUE) {
            throw failure(INVALID_STATE);
        }
        return Math.toIntExact(value);
    }

    private static long exactLong(JsonReader reader) throws IOException, RuntimeStoreException {
        requireToken(reader, JsonToken.NUMBER);
        String value = reader.nextString();
        if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) {
            throw failure(INVALID_STATE);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw failure(INVALID_STATE);
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw failure(INVALID_STATE, error);
        }
    }

    private static Instant instant(JsonReader reader)
        throws IOException, RuntimeStoreException {
        String value = string(reader, 64);
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw failure(INVALID_STATE);
            }
            return parsed;
        } catch (DateTimeParseException error) {
            throw failure(INVALID_STATE, error);
        }
    }

    private static UUID uuid(JsonReader reader) throws IOException, RuntimeStoreException {
        String value = string(reader, 36);
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw failure(INVALID_STATE);
        }
        return parsed;
    }

    private static void requireToken(JsonReader reader, JsonToken expected)
        throws IOException, RuntimeStoreException {
        if (reader.peek() != expected) {
            throw failure(INVALID_STATE);
        }
    }

    private static void requireEnd(JsonReader reader)
        throws IOException, RuntimeStoreException {
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw failure(INVALID_STATE);
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
