package com.audioviz.runtime.release;

import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.API_INCOMPATIBLE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.DUPLICATE_FIELD;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.EXPIRED;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.GENERATION_ROLLBACK;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.INVALID_SIGNATURE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.INVALID_TYPE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.INVALID_URL;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.INVALID_UTF8;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.KEY_ID_MISMATCH;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MALFORMED_JSON;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MALFORMED_SIGNATURE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MANIFEST_TOO_LARGE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MISSING_PLATFORM;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.NOT_YET_VALID;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.OUT_OF_RANGE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.UNKNOWN_FIELD;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.UNKNOWN_KEY;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.UNSUPPORTED_ALGORITHM;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.audioviz.runtime.RuntimeLimits;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuntimeManifestVerifierTest {

    private static final String KEY_ID = "test-only-2026";
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Path FIXTURES = Path.of(
        "..",
        "protocol",
        "fixtures",
        "runtime-release"
    ).toAbsolutePath().normalize();

    private static byte[] validManifest;
    private static byte[] validEnvelope;
    private static PublicKey publicKey;
    private static PrivateKey privateKey;

    private final RuntimeManifestVerifier verifier = new RuntimeManifestVerifier(
        RuntimeLimits.releaseDefaults()
    );

    @BeforeAll
    static void loadFixtures() throws Exception {
        validManifest = Files.readAllBytes(FIXTURES.resolve("valid-manifest.json"));
        validEnvelope = Files.readAllBytes(FIXTURES.resolve("valid-manifest.sig.json"));
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        publicKey = keyFactory.generatePublic(
            new X509EncodedKeySpec(readBase64Fixture("test-public-key.der.b64"))
        );
        privateKey = keyFactory.generatePrivate(
            new PKCS8EncodedKeySpec(readBase64Fixture("test-private-key.pk8.b64"))
        );
    }

    @Test
    void verifiesGoldenManifestExactBytes() throws Exception {
        RuntimeManifest manifest = verifier.verify(validManifest, validEnvelope, descriptor(1, 1), NOW);

        assertEquals(1L, manifest.generation());
        assertEquals("1.2.0", manifest.releaseVersion());
        assertEquals(Instant.parse("2026-08-31T00:00:00Z"), manifest.publishedAt());
        assertEquals(Instant.parse("2026-09-30T00:00:00Z"), manifest.expiresAt());
        assertEquals(3, manifest.artifacts().size());
        assertEquals(
            URI.create("https://releases.mcav.live/runtime/1.2.0/windows-x86_64.zip"),
            manifest.artifacts().get(RuntimePlatform.WINDOWS_X86_64).url()
        );
        assertEquals("bin/audioviz-vj.exe", manifest
            .artifacts()
            .get(RuntimePlatform.WINDOWS_X86_64)
            .entrypoint());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSignedDocuments")
    void rejectsStrictlyInvalidSignedManifest(
        String caseName,
        byte[] payload,
        ManifestVerificationException.FailureReason reason
    ) throws Exception {
        byte[] envelope = signedEnvelope(payload, KEY_ID, "Ed25519");

        ManifestVerificationException failure = assertThrows(
            ManifestVerificationException.class,
            () -> verifier.verify(payload, envelope, descriptor(1, 1), NOW)
        );

        assertEquals(reason, failure.reason());
        assertFalse(failure.getMessage().contains("releases.mcav.live"));
    }

    @Test
    void rejectsExpiredManifest() {
        assertReason(EXPIRED, validManifest, validEnvelope, descriptor(1, 1), Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void rejectsManifestPublishedInFuture() {
        assertReason(
            NOT_YET_VALID,
            validManifest,
            validEnvelope,
            descriptor(1, 1),
            Instant.parse("2026-08-30T23:59:59Z")
        );
    }

    @Test
    void rejectsGenerationBelowEmbeddedMinimum() {
        assertReason(GENERATION_ROLLBACK, validManifest, validEnvelope, descriptor(1, 2), NOW);
    }

    @Test
    void rejectsIncompatibleRuntimeApi() {
        assertReason(API_INCOMPATIBLE, validManifest, validEnvelope, descriptor(2, 1), NOW);
    }

    @Test
    void rejectsUnknownEnvelopeKeyBeforeParsingManifest() throws Exception {
        byte[] envelope = signedEnvelope(validManifest, "unknown-test-key", "Ed25519");

        assertReason(UNKNOWN_KEY, validManifest, envelope, descriptor(1, 1), NOW);
    }

    @Test
    void rejectsWrongEnvelopeAlgorithm() throws Exception {
        byte[] envelope = signedEnvelope(validManifest, KEY_ID, "RSA-PSS");

        assertReason(UNSUPPORTED_ALGORITHM, validManifest, envelope, descriptor(1, 1), NOW);
    }

    @Test
    void rejectsMalformedSignatureBase64() {
        byte[] envelope = ("{\"algorithm\":\"Ed25519\",\"key_id\":\"" + KEY_ID
            + "\",\"schema_version\":1,\"signature\":\"%%%\"}\n")
            .getBytes(StandardCharsets.UTF_8);

        assertReason(MALFORMED_SIGNATURE, validManifest, envelope, descriptor(1, 1), NOW);
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        byte[] envelope = signedEnvelope("different\n".getBytes(StandardCharsets.UTF_8), KEY_ID, "Ed25519");

        assertReason(INVALID_SIGNATURE, validManifest, envelope, descriptor(1, 1), NOW);
    }

    @Test
    void rejectsOversizedManifestBeforeSignatureVerification() {
        byte[] oversized = new byte[RuntimeLimits.releaseDefaults().maximumManifestBytes() + 1];

        assertReason(MANIFEST_TOO_LARGE, oversized, validEnvelope, descriptor(1, 1), NOW);
    }

    @Test
    void verifiesSignatureOverExactBytesRatherThanReserializedJson() throws Exception {
        byte[] reserialized = new String(validManifest, StandardCharsets.UTF_8)
            .replace("{\"artifacts\"", "{ \"artifacts\"")
            .getBytes(StandardCharsets.UTF_8);

        assertReason(INVALID_SIGNATURE, reserialized, validEnvelope, descriptor(1, 1), NOW);
    }

    @Test
    void rejectsManifestAndEnvelopeKeyIdMismatch() throws Exception {
        byte[] payload = replace(validJson(), "\"signing_key_id\":\"test-only-2026\"", "\"signing_key_id\":\"other-key\"");
        byte[] envelope = signedEnvelope(payload, KEY_ID, "Ed25519");

        assertReason(KEY_ID_MISMATCH, payload, envelope, descriptor(1, 1), NOW);
    }

    @Test
    void signatureEnvelopeDefensivelyCopiesSignatureBytes() {
        byte[] source = {1, 2, 3};
        SignatureEnvelope envelope = new SignatureEnvelope(1, "Ed25519", KEY_ID, source);
        source[0] = 9;
        byte[] returned = envelope.signature();
        returned[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, envelope.signature());
    }

    @Test
    void manifestArtifactMapIsImmutable() throws Exception {
        RuntimeManifest manifest = verifier.verify(validManifest, validEnvelope, descriptor(1, 1), NOW);

        assertThrows(
            UnsupportedOperationException.class,
            () -> manifest.artifacts().clear()
        );
    }

    @Test
    void releaseDescriptorDefensivelyCopiesAllowedHosts() {
        Set<String> source = new HashSet<>(Set.of("RELEASES.MCAV.LIVE"));
        ReleaseDescriptor releaseDescriptor = new ReleaseDescriptor(
            URI.create("https://releases.mcav.live/runtime/manifest-v1.json"),
            Map.of(KEY_ID, publicKey),
            source,
            1,
            1
        );
        source.clear();

        assertEquals(Set.of("releases.mcav.live"), releaseDescriptor.allowedHosts());
        assertThrows(
            UnsupportedOperationException.class,
            () -> releaseDescriptor.allowedHosts().add("example.com")
        );
    }

    private void assertReason(
        ManifestVerificationException.FailureReason reason,
        byte[] payload,
        byte[] envelope,
        ReleaseDescriptor descriptor,
        Instant now
    ) {
        ManifestVerificationException failure = assertThrows(
            ManifestVerificationException.class,
            () -> verifier.verify(payload, envelope, descriptor, now)
        );
        assertEquals(reason, failure.reason());
    }

    private static Stream<Arguments> invalidSignedDocuments() throws Exception {
        String valid = validJson();
        String withoutWindows = removeWindowsArtifact(valid);
        byte[] invalidUtf8 = validManifest.clone();
        invalidUtf8[invalidUtf8.length - 2] = (byte) 0x80;
        return Stream.of(
            Arguments.of(
                "duplicate field",
                replace(valid, "\"generation\":1", "\"generation\":1,\"generation\":2"),
                DUPLICATE_FIELD
            ),
            Arguments.of("unknown field", ("{\"surprise\":true," + valid.substring(1)).getBytes(StandardCharsets.UTF_8), UNKNOWN_FIELD),
            Arguments.of("trailing JSON", (valid + "{}\n").getBytes(StandardCharsets.UTF_8), MALFORMED_JSON),
            Arguments.of("invalid UTF-8", invalidUtf8, INVALID_UTF8),
            Arguments.of("float integer", replace(valid, "\"generation\":1", "\"generation\":1.5"), INVALID_TYPE),
            Arguments.of("negative size", replaceFirst(valid, "\"archive_size\":4096", "\"archive_size\":-1"), OUT_OF_RANGE),
            Arguments.of("schema-oversized archive", replaceFirst(valid, "\"archive_size\":4096", "\"archive_size\":1073741825"), OUT_OF_RANGE),
            Arguments.of("schema-oversized extraction", replaceFirst(valid, "\"uncompressed_size\":8192", "\"uncompressed_size\":2147483649"), OUT_OF_RANGE),
            Arguments.of("schema-oversized version", replace(valid, "\"release_version\":\"1.2.0\"", "\"release_version\":\"1.2.3-" + "a".repeat(59) + "\""), OUT_OF_RANGE),
            Arguments.of("schema-oversized key ID", replace(valid, "\"signing_key_id\":\"test-only-2026\"", "\"signing_key_id\":\"a" + "b".repeat(64) + "\""), OUT_OF_RANGE),
            Arguments.of("malformed version", replace(valid, "\"release_version\":\"1.2.0\"", "\"release_version\":\"1.2\""), INVALID_TYPE),
            Arguments.of("empty entrypoint segment", replaceFirst(valid, "bin/audioviz-vj", "bin//audioviz-vj"), INVALID_TYPE),
            Arguments.of("bad timestamp", replace(valid, "2026-09-30T00:00:00Z", "September 30"), INVALID_TYPE),
            Arguments.of("missing platform", withoutWindows.getBytes(StandardCharsets.UTF_8), MISSING_PLATFORM),
            Arguments.of("HTTP URL", replaceFirst(valid, "https://", "http://"), INVALID_URL),
            Arguments.of("URL userinfo", replaceFirst(valid, "https://", "https://user@"), INVALID_URL),
            Arguments.of("URL fragment", replaceFirst(valid, "linux-aarch64.zip", "linux-aarch64.zip#fragment"), INVALID_URL)
        );
    }

    private static ReleaseDescriptor descriptor(int runtimeApi, long minimumGeneration) {
        return new ReleaseDescriptor(
            URI.create("https://releases.mcav.live/runtime/manifest-v1.json"),
            Map.of(KEY_ID, publicKey),
            Set.of("releases.mcav.live"),
            runtimeApi,
            minimumGeneration
        );
    }

    private static byte[] signedEnvelope(byte[] payload, String keyId, String algorithm) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payload);
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        return ("{\"algorithm\":\"" + algorithm + "\",\"key_id\":\"" + keyId
            + "\",\"schema_version\":1,\"signature\":\"" + signature + "\"}\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] replace(String source, String target, String replacement) {
        return source.replace(target, replacement).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] replaceFirst(String source, String target, String replacement) {
        int index = source.indexOf(target);
        if (index < 0) {
            throw new IllegalArgumentException("target missing from fixture");
        }
        return (source.substring(0, index) + replacement + source.substring(index + target.length()))
            .getBytes(StandardCharsets.UTF_8);
    }

    private static String removeWindowsArtifact(String source) {
        int start = source.indexOf(",\"windows-x86_64\":{");
        int end = source.indexOf("}},\"expires_at\"", start);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("Windows artifact missing from fixture");
        }
        return source.substring(0, start) + source.substring(end + 1);
    }

    private static String validJson() {
        return new String(validManifest, StandardCharsets.UTF_8);
    }

    private static byte[] readBase64Fixture(String name) throws Exception {
        return Base64.getDecoder().decode(Files.readString(FIXTURES.resolve(name)).trim());
    }
}
