package com.audioviz.runtime.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.audioviz.runtime.RuntimeLimits;
import com.audioviz.runtime.net.RuntimeHttpSource;
import com.audioviz.runtime.release.ReleaseDescriptor;
import com.audioviz.runtime.release.RuntimeManifestVerifier;
import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.RuntimeArchiveVerifier;
import com.audioviz.runtime.store.RuntimePaths;
import com.audioviz.runtime.store.RuntimeStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeInstallerProductionPathTest {
    private static final String KEY_ID = "test-only-2026";
    private static final String ENTRYPOINT = "bin/audioviz-vj";
    private static final URI MANIFEST_URI = URI.create(
        "https://releases.mcav.live/mcav-runtime-manifest-v1.json"
    );
    private static final URI SIGNATURE_URI = URI.create(
        "https://releases.mcav.live/mcav-runtime-manifest-v1.sig.json"
    );
    private static final URI ARCHIVE_URI = URI.create(
        "https://releases.mcav.live/runtime/1.2.0/linux-x86_64.zip"
    );
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-31T12:00:00Z"),
        ZoneOffset.UTC
    );

    @TempDir
    Path temp;

    @Test
    void productionAdaptersInstallSignedArchiveEndToEnd() throws Exception {
        byte[] executable = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8);
        byte[] filesManifest = filesManifest(executable);
        byte[] archive = archive(executable, filesManifest);
        byte[] manifest = manifest(archive, filesManifest, executable.length);
        KeyPairFixture keys = keys();
        byte[] signature = signatureEnvelope(manifest, keys.privateKey());
        Map<URI, byte[]> responses = new HashMap<>();
        responses.put(MANIFEST_URI, manifest);
        responses.put(SIGNATURE_URI, signature);
        responses.put(ARCHIVE_URI, archive);
        AtomicInteger requests = new AtomicInteger();

        RuntimePaths paths = RuntimePaths.create(temp.resolve("runtime"));
        RuntimeLimits limits = RuntimeLimits.releaseDefaults();
        RuntimeHttpSource httpSource = new RuntimeHttpSource(
            (uri, timeout) -> {
                requests.incrementAndGet();
                byte[] body = responses.get(uri);
                if (body == null) {
                    throw new java.io.IOException("unexpected test URI");
                }
                return response(uri, body);
            },
            paths.downloads(),
            Duration.ofSeconds(1),
            2
        );
        RuntimeStore store = new RuntimeStore(paths, limits, CLOCK, boundary -> {});
        List<RuntimeInstallEvent.Stage> events = new ArrayList<>();
        RuntimeInstaller installer = new RuntimeInstaller(
            paths,
            store,
            limits,
            CLOCK,
            httpSource,
            new RuntimeManifestVerifier(limits),
            new RuntimeArchiveVerifier(limits, paths),
            event -> events.add(event.stage())
        );
        ReleaseDescriptor descriptor = new ReleaseDescriptor(
            MANIFEST_URI,
            Map.of(KEY_ID, keys.publicKey()),
            Set.of("releases.mcav.live"),
            1,
            1
        );

        RuntimeInstaller.InstallResult result = installer.install(
            new RuntimeInstaller.InstallRequest(
                descriptor,
                RuntimePlatform.LINUX_X86_64,
                1,
                42
            ),
            new CancellationToken(42)
        );

        assertEquals(RuntimeInstaller.InstallOutcome.INSTALLED, result.outcome());
        assertArrayEquals(executable, Files.readAllBytes(result.runtime().orElseThrow().entrypoint()));
        assertTrue(store.current().isEmpty());
        assertEquals(3, requests.get());
        assertEquals(RuntimeInstallEvent.Stage.INSTALLED, events.getLast());
    }

    private static byte[] filesManifest(byte[] executable) throws Exception {
        String digest = sha256(executable);
        return ("{\"schema_version\":1,\"files\":[{\"path\":\"" + ENTRYPOINT +
            "\",\"size\":" + executable.length + ",\"sha256\":\"" + digest +
            "\",\"executable\":true}]}\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] archive(byte[] executable, byte[] filesManifest) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            zip.setEncoding(StandardCharsets.UTF_8.name());
            zip.setUseLanguageEncodingFlag(true);
            zip.setUseZip64(Zip64Mode.AsNeeded);
            writeEntry(zip, ENTRYPOINT, executable, 0100700);
            writeEntry(zip, "files.json", filesManifest, 0100600);
        }
        return bytes.toByteArray();
    }

    private static void writeEntry(
        ZipArchiveOutputStream zip,
        String path,
        byte[] bytes,
        int unixMode
    ) throws Exception {
        ZipArchiveEntry entry = new ZipArchiveEntry(path);
        entry.setUnixMode(unixMode);
        zip.putArchiveEntry(entry);
        zip.write(bytes);
        zip.closeArchiveEntry();
    }

    private static byte[] manifest(
        byte[] archive,
        byte[] filesManifest,
        int executableSize
    ) throws Exception {
        String artifact = "{\"archive_size\":" + archive.length +
            ",\"entrypoint\":\"" + ENTRYPOINT + "\",\"files_manifest_sha256\":\"" +
            sha256(filesManifest) + "\",\"sha256\":\"" + sha256(archive) +
            "\",\"uncompressed_size\":" + (filesManifest.length + executableSize) +
            ",\"url\":\"" + ARCHIVE_URI + "\"}";
        String json = "{\"artifacts\":{\"linux-aarch64\":" + artifact +
            ",\"linux-x86_64\":" + artifact + ",\"windows-x86_64\":" + artifact +
            "},\"expires_at\":\"2026-09-30T00:00:00Z\",\"generation\":1," +
            "\"published_at\":\"2026-08-31T00:00:00Z\",\"release_version\":\"1.2.0\"," +
            "\"runtime_api_max\":1,\"runtime_api_min\":1,\"schema_version\":1," +
            "\"signing_key_id\":\"" + KEY_ID + "\"}\n";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] signatureEnvelope(byte[] manifest, PrivateKey privateKey)
        throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(manifest);
        String encoded = Base64.getEncoder().encodeToString(signer.sign());
        return ("{\"algorithm\":\"Ed25519\",\"key_id\":\"" + KEY_ID +
            "\",\"schema_version\":1,\"signature\":\"" + encoded + "\"}\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static KeyPairFixture keys() throws Exception {
        Path fixtures = Path.of("..", "protocol", "fixtures", "runtime-release");
        Base64.Decoder decoder = Base64.getDecoder();
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
            decoder.decode(Files.readString(fixtures.resolve("test-private-key.pk8.b64")).trim())
        ));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
            decoder.decode(Files.readString(fixtures.resolve("test-public-key.der.b64")).trim())
        ));
        return new KeyPairFixture(privateKey, publicKey);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<java.io.InputStream> response(URI uri, byte[] body) {
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        HttpRequest request = HttpRequest.newBuilder(uri).build();
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(
            Map.of("Content-Length", List.of(Integer.toString(body.length))),
            (name, value) -> true
        ));
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        when(response.request()).thenReturn(request);
        when(response.uri()).thenReturn(uri);
        return response;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record KeyPairFixture(PrivateKey privateKey, PublicKey publicKey) {}
}
