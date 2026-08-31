package com.audioviz.runtime.release;

import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.DUPLICATE_FIELD;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.ENVELOPE_TOO_LARGE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.INVALID_TYPE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.INVALID_UTF8;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MALFORMED_JSON;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MALFORMED_SIGNATURE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MANIFEST_TOO_LARGE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MISSING_FIELD;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MISSING_PLATFORM;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.OUT_OF_RANGE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.UNKNOWN_FIELD;

import com.audioviz.runtime.RuntimeLimits;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class StrictJson {
    private static final int SCHEMA_MAXIMUM_IDENTIFIER_CHARACTERS = 64;
    private static final int SCHEMA_MAXIMUM_VERSION_CHARACTERS = 64;
    private static final int SCHEMA_MAXIMUM_ENTRYPOINT_CHARACTERS = 128;
    private static final long SCHEMA_MAXIMUM_ARCHIVE_BYTES = 1024L * 1024 * 1024;
    private static final long SCHEMA_MAXIMUM_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final Set<String> MANIFEST_FIELDS = Set.of(
        "schema_version",
        "generation",
        "release_version",
        "runtime_api_min",
        "runtime_api_max",
        "published_at",
        "expires_at",
        "signing_key_id",
        "artifacts"
    );
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
        "url",
        "archive_size",
        "uncompressed_size",
        "sha256",
        "entrypoint",
        "files_manifest_sha256"
    );
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        "algorithm",
        "key_id",
        "schema_version",
        "signature"
    );
    private static final Pattern INTEGER_PATTERN = Pattern.compile("(?:0|-[1-9][0-9]*|[1-9][0-9]*)");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
        "[0-9A-Za-z][0-9A-Za-z._-]*"
    );
    private static final Pattern DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern UTC_SECONDS_PATTERN = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"
    );

    private StrictJson() {}

    static SignatureEnvelope readSignatureEnvelope(byte[] bytes, RuntimeLimits limits)
        throws ManifestVerificationException {
        if (bytes == null || bytes.length == 0 || bytes.length > limits.maximumSignatureEnvelopeBytes()) {
            throw failure(ENVELOPE_TOO_LARGE);
        }
        try (JsonReader reader = reader(bytes)) {
            requireDepth(1, limits);
            requireToken(reader, JsonToken.BEGIN_OBJECT);
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            Integer schemaVersion = null;
            String algorithm = null;
            String keyId = null;
            String signature = null;
            while (reader.hasNext()) {
                String field = nextField(reader, seen, ENVELOPE_FIELDS);
                switch (field) {
                    case "schema_version" -> schemaVersion = exactInt(reader, "schema_version", 1, 1);
                    case "algorithm" -> algorithm = boundedString(
                        reader,
                        "algorithm",
                        limits.maximumIdentifierCharacters()
                    );
                    case "key_id" -> keyId = identifier(
                        reader,
                        "key_id",
                        Math.min(
                            limits.maximumIdentifierCharacters(),
                            SCHEMA_MAXIMUM_IDENTIFIER_CHARACTERS
                        )
                    );
                    case "signature" -> signature = boundedString(reader, "signature", 128);
                    default -> throw failure(UNKNOWN_FIELD);
                }
            }
            reader.endObject();
            requireEndDocument(reader);
            requireFields(seen, ENVELOPE_FIELDS);
            byte[] decodedSignature;
            try {
                decodedSignature = Base64.getDecoder().decode(signature);
            } catch (IllegalArgumentException error) {
                throw failure(MALFORMED_SIGNATURE, error);
            }
            if (decodedSignature.length != 64) {
                throw failure(MALFORMED_SIGNATURE);
            }
            return new SignatureEnvelope(schemaVersion, algorithm, keyId, decodedSignature);
        } catch (ManifestVerificationException error) {
            throw error;
        } catch (IOException | IllegalStateException | NumberFormatException error) {
            throw failure(MALFORMED_JSON, error);
        }
    }

    static RuntimeManifest readManifest(byte[] bytes, RuntimeLimits limits)
        throws ManifestVerificationException {
        if (bytes == null || bytes.length == 0 || bytes.length > limits.maximumManifestBytes()) {
            throw failure(MANIFEST_TOO_LARGE);
        }
        try (JsonReader reader = reader(bytes)) {
            requireDepth(1, limits);
            requireToken(reader, JsonToken.BEGIN_OBJECT);
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            Integer schemaVersion = null;
            Long generation = null;
            String releaseVersion = null;
            Integer runtimeApiMin = null;
            Integer runtimeApiMax = null;
            Instant publishedAt = null;
            Instant expiresAt = null;
            String signingKeyId = null;
            Map<RuntimePlatform, RuntimeArtifact> artifacts = null;
            while (reader.hasNext()) {
                String field = nextField(reader, seen, MANIFEST_FIELDS);
                switch (field) {
                    case "schema_version" -> schemaVersion = exactInt(reader, field, 1, 1);
                    case "generation" -> generation = boundedLong(reader, field, 1, Long.MAX_VALUE);
                    case "release_version" -> releaseVersion = version(reader, limits);
                    case "runtime_api_min" -> runtimeApiMin = exactInt(reader, field, 1, 65_535);
                    case "runtime_api_max" -> runtimeApiMax = exactInt(reader, field, 1, 65_535);
                    case "published_at" -> publishedAt = timestamp(reader, field);
                    case "expires_at" -> expiresAt = timestamp(reader, field);
                    case "signing_key_id" -> signingKeyId = identifier(
                        reader,
                        field,
                        Math.min(
                            limits.maximumIdentifierCharacters(),
                            SCHEMA_MAXIMUM_IDENTIFIER_CHARACTERS
                        )
                    );
                    case "artifacts" -> artifacts = readArtifacts(reader, limits, 2);
                    default -> throw failure(UNKNOWN_FIELD);
                }
            }
            reader.endObject();
            requireEndDocument(reader);
            requireFields(seen, MANIFEST_FIELDS);
            if (runtimeApiMin > runtimeApiMax) {
                throw failure(OUT_OF_RANGE);
            }
            return new RuntimeManifest(
                schemaVersion,
                generation,
                releaseVersion,
                runtimeApiMin,
                runtimeApiMax,
                publishedAt,
                expiresAt,
                signingKeyId,
                artifacts
            );
        } catch (ManifestVerificationException error) {
            throw error;
        } catch (CharacterCodingException error) {
            throw failure(INVALID_UTF8, error);
        } catch (IOException | IllegalStateException | NumberFormatException error) {
            throw failure(MALFORMED_JSON, error);
        }
    }

    private static Map<RuntimePlatform, RuntimeArtifact> readArtifacts(
        JsonReader reader,
        RuntimeLimits limits,
        int depth
    ) throws IOException, ManifestVerificationException {
        requireDepth(depth, limits);
        requireToken(reader, JsonToken.BEGIN_OBJECT);
        reader.beginObject();
        Set<String> seen = new HashSet<>();
        Map<RuntimePlatform, RuntimeArtifact> artifacts = new EnumMap<>(RuntimePlatform.class);
        while (reader.hasNext()) {
            String wireName = reader.nextName();
            if (!seen.add(wireName)) {
                throw failure(DUPLICATE_FIELD);
            }
            RuntimePlatform platform;
            try {
                platform = RuntimePlatform.fromWireName(wireName);
            } catch (IllegalArgumentException error) {
                throw failure(UNKNOWN_FIELD, error);
            }
            if (artifacts.size() >= limits.maximumArtifacts()) {
                throw failure(OUT_OF_RANGE);
            }
            artifacts.put(platform, readArtifact(reader, limits, depth + 1, platform));
        }
        reader.endObject();
        if (artifacts.size() != RuntimePlatform.values().length) {
            throw failure(MISSING_PLATFORM);
        }
        for (RuntimePlatform platform : RuntimePlatform.values()) {
            if (!artifacts.containsKey(platform)) {
                throw failure(MISSING_PLATFORM);
            }
        }
        return Map.copyOf(artifacts);
    }

    private static RuntimeArtifact readArtifact(
        JsonReader reader,
        RuntimeLimits limits,
        int depth,
        RuntimePlatform platform
    ) throws IOException, ManifestVerificationException {
        requireDepth(depth, limits);
        requireToken(reader, JsonToken.BEGIN_OBJECT);
        reader.beginObject();
        Set<String> seen = new HashSet<>();
        String url = null;
        Long archiveSize = null;
        Long uncompressedSize = null;
        String sha256 = null;
        String entrypoint = null;
        String filesManifestSha256 = null;
        while (reader.hasNext()) {
            String field = nextField(reader, seen, ARTIFACT_FIELDS);
            switch (field) {
                case "url" -> url = boundedString(reader, field, 2048);
                case "archive_size" -> archiveSize = boundedLong(
                    reader,
                    field,
                    1,
                    Math.min(limits.maximumArchiveBytes(), SCHEMA_MAXIMUM_ARCHIVE_BYTES)
                );
                case "uncompressed_size" -> uncompressedSize = boundedLong(
                    reader,
                    field,
                    1,
                    Math.min(
                        limits.maximumExtractedBytes(),
                        SCHEMA_MAXIMUM_UNCOMPRESSED_BYTES
                    )
                );
                case "sha256" -> sha256 = digest(reader, field);
                case "entrypoint" -> entrypoint = entrypoint(reader, limits);
                case "files_manifest_sha256" -> filesManifestSha256 = digest(reader, field);
                default -> throw failure(UNKNOWN_FIELD);
            }
        }
        reader.endObject();
        requireFields(seen, ARTIFACT_FIELDS);
        URI artifactUri;
        try {
            artifactUri = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw failure(ManifestVerificationException.FailureReason.INVALID_URL, error);
        }
        return new RuntimeArtifact(
            platform,
            artifactUri,
            archiveSize,
            uncompressedSize,
            sha256,
            entrypoint,
            filesManifestSha256
        );
    }

    private static JsonReader reader(byte[] bytes)
        throws CharacterCodingException, ManifestVerificationException {
        String decoded = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
        JsonReader reader = new JsonReader(new StringReader(decoded));
        reader.setStrictness(Strictness.STRICT);
        return reader;
    }

    private static String nextField(JsonReader reader, Set<String> seen, Set<String> expected)
        throws IOException, ManifestVerificationException {
        String field = reader.nextName();
        if (!seen.add(field)) {
            throw failure(DUPLICATE_FIELD);
        }
        if (!expected.contains(field)) {
            throw failure(UNKNOWN_FIELD);
        }
        return field;
    }

    private static void requireFields(Set<String> seen, Set<String> expected)
        throws ManifestVerificationException {
        if (!seen.containsAll(expected)) {
            throw failure(MISSING_FIELD);
        }
    }

    private static long boundedLong(JsonReader reader, String field, long minimum, long maximum)
        throws IOException, ManifestVerificationException {
        requireToken(reader, JsonToken.NUMBER);
        String raw = reader.nextString();
        if (!INTEGER_PATTERN.matcher(raw).matches()) {
            throw failure(INVALID_TYPE);
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw failure(OUT_OF_RANGE, error);
        }
        if (value < minimum || value > maximum) {
            throw failure(OUT_OF_RANGE);
        }
        return value;
    }

    private static int exactInt(JsonReader reader, String field, int minimum, int maximum)
        throws IOException, ManifestVerificationException {
        long value = boundedLong(reader, field, minimum, maximum);
        return Math.toIntExact(value);
    }

    private static String boundedString(JsonReader reader, String field, int maximumCharacters)
        throws IOException, ManifestVerificationException {
        requireToken(reader, JsonToken.STRING);
        String value = reader.nextString();
        if (value.isEmpty() || value.length() > maximumCharacters) {
            throw failure(OUT_OF_RANGE);
        }
        return value;
    }

    private static String identifier(JsonReader reader, String field, int maximumCharacters)
        throws IOException, ManifestVerificationException {
        String value = boundedString(reader, field, maximumCharacters);
        if (!IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw failure(INVALID_TYPE);
        }
        return value;
    }

    private static String version(JsonReader reader, RuntimeLimits limits)
        throws IOException, ManifestVerificationException {
        String value = boundedString(
            reader,
            "release_version",
            Math.min(limits.maximumVersionCharacters(), SCHEMA_MAXIMUM_VERSION_CHARACTERS)
        );
        if (!isVersion(value)) {
            throw failure(INVALID_TYPE);
        }
        return value;
    }

    private static String digest(JsonReader reader, String field)
        throws IOException, ManifestVerificationException {
        String value = boundedString(reader, field, 64);
        if (!DIGEST_PATTERN.matcher(value).matches()) {
            throw failure(INVALID_TYPE);
        }
        return value;
    }

    private static String entrypoint(JsonReader reader, RuntimeLimits limits)
        throws IOException, ManifestVerificationException {
        String value = boundedString(
            reader,
            "entrypoint",
            Math.min(
                limits.maximumEntrypointCharacters(),
                SCHEMA_MAXIMUM_ENTRYPOINT_CHARACTERS
            )
        );
        if (!isEntrypoint(value)) {
            throw failure(INVALID_TYPE);
        }
        return value;
    }

    private static boolean isVersion(String value) {
        int prereleaseSeparator = value.indexOf('-');
        int coreEnd = prereleaseSeparator < 0 ? value.length() : prereleaseSeparator;
        int firstDot = value.indexOf('.');
        int secondDot = firstDot < 0 ? -1 : value.indexOf('.', firstDot + 1);
        int extraCoreDot = secondDot < 0 ? -1 : value.indexOf('.', secondDot + 1);
        if (
            firstDot <= 0 ||
            secondDot <= firstDot + 1 ||
            secondDot >= coreEnd - 1 ||
            (extraCoreDot >= 0 && extraCoreDot < coreEnd)
        ) {
            return false;
        }
        if (
            !isAsciiDigits(value, 0, firstDot) ||
            !isAsciiDigits(value, firstDot + 1, secondDot) ||
            !isAsciiDigits(value, secondDot + 1, coreEnd)
        ) {
            return false;
        }
        if (prereleaseSeparator < 0) {
            return true;
        }
        if (prereleaseSeparator == value.length() - 1) {
            return false;
        }
        for (int index = prereleaseSeparator + 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isAsciiLetterOrDigit(character) && character != '.' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isEntrypoint(String value) {
        boolean segmentHasCharacter = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/') {
                if (!segmentHasCharacter) {
                    return false;
                }
                segmentHasCharacter = false;
                continue;
            }
            if (
                !isAsciiLetterOrDigit(character) &&
                character != '.' &&
                character != '_' &&
                character != '-'
            ) {
                return false;
            }
            segmentHasCharacter = true;
        }
        return segmentHasCharacter;
    }

    private static boolean isAsciiDigits(String value, int start, int end) {
        if (start >= end) {
            return false;
        }
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return (character >= '0' && character <= '9') ||
        (character >= 'A' && character <= 'Z') ||
        (character >= 'a' && character <= 'z');
    }

    private static Instant timestamp(JsonReader reader, String field)
        throws IOException, ManifestVerificationException {
        String value = boundedString(reader, field, 32);
        if (!UTC_SECONDS_PATTERN.matcher(value).matches()) {
            throw failure(INVALID_TYPE);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw failure(INVALID_TYPE, error);
        }
    }

    private static void requireToken(JsonReader reader, JsonToken expected)
        throws IOException, ManifestVerificationException {
        if (reader.peek() != expected) {
            throw failure(INVALID_TYPE);
        }
    }

    private static void requireEndDocument(JsonReader reader)
        throws IOException, ManifestVerificationException {
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw failure(MALFORMED_JSON);
        }
    }

    private static void requireDepth(int depth, RuntimeLimits limits)
        throws ManifestVerificationException {
        if (depth > limits.maximumJsonDepth()) {
            throw failure(OUT_OF_RANGE);
        }
    }

    private static ManifestVerificationException failure(
        ManifestVerificationException.FailureReason reason
    ) {
        return new ManifestVerificationException(reason);
    }

    private static ManifestVerificationException failure(
        ManifestVerificationException.FailureReason reason,
        Throwable cause
    ) {
        return new ManifestVerificationException(reason, cause);
    }
}
