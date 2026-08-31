package com.audioviz.runtime.net;

import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.CANCELLED;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.DESTINATION_EXISTS;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.HASH_MISMATCH;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.HOST_NOT_ALLOWED;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.HTTP_STATUS;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.INSECURE_URI;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.INVALID_EXPECTATION;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.INVALID_URI;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.IO_FAILURE;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.MISSING_REDIRECT_LOCATION;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.OVERSIZED;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.REDIRECT_LIMIT;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.REDIRECT_LOOP;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.TIMEOUT;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.TRUNCATED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.audioviz.runtime.net.RuntimeHttpSource.CancellationToken;
import com.audioviz.runtime.net.RuntimeHttpSource.DownloadResult;
import com.audioviz.runtime.net.RuntimeHttpSource.Transport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuntimeHttpSourceTest {

    private static final URI POLICY_ORIGIN = URI.create("https://releases.mcav.live/");
    private static final Set<String> ALLOWED_HOSTS = Set.of("releases.mcav.live");
    private static final CancellationToken NEVER_CANCEL = () -> false;

    @TempDir
    Path temp;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private RuntimeHttpSource source;
    private URI localOrigin;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            0
        );
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        localOrigin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        source = new RuntimeHttpSource(
            localTransport(),
            temp.resolve("downloads"),
            Duration.ofMillis(250),
            2
        );
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void downloadsExactBytesAndAtomicallyPublishesDestination() throws Exception {
        byte[] payload = "verified runtime archive".getBytes(StandardCharsets.UTF_8);
        respond("/payload", 200, payload, Map.of());
        Path destination = temp.resolve("downloads/candidate.zip");

        DownloadResult result = source.download(
            policyUri("/payload"),
            destination,
            payload.length,
            sha256(payload),
            ALLOWED_HOSTS,
            NEVER_CANCEL
        );

        assertEquals(payload.length, result.bytesWritten());
        assertEquals(sha256(payload), result.sha256());
        assertArrayEquals(payload, Files.readAllBytes(destination));
        assertNoPartFiles(destination);
    }

    @Test
    void fetchesBoundedManifestBytes() throws Exception {
        byte[] payload = "{\"schema_version\":1}\n".getBytes(StandardCharsets.UTF_8);
        respond("/manifest", 200, payload, Map.of());

        assertArrayEquals(
            payload,
            source.fetchBytes(policyUri("/manifest"), payload.length, ALLOWED_HOSTS)
        );
    }

    @Test
    void boundedFetchRejectsChunkedOversize() {
        respondChunked("/large-manifest", 200, new byte[21], Map.of());

        assertFailure(
            OVERSIZED,
            () -> source.fetchBytes(policyUri("/large-manifest"), 20, ALLOWED_HOSTS)
        );
    }

    @Test
    void truncatedDownloadNeverPublishesDestination() throws Exception {
        byte[] expected = new byte[20];
        respondWithDeclaredLength("/short", 200, new byte[15], 20, Map.of());
        Path destination = temp.resolve("downloads/candidate.zip");

        assertFailure(
            TRUNCATED,
            () -> source.download(
                policyUri("/short"),
                destination,
                expected.length,
                sha256(expected),
                ALLOWED_HOSTS,
                NEVER_CANCEL
            )
        );

        assertFalse(Files.exists(destination));
        assertNoPartFiles(destination);
    }

    @Test
    void rejectsDeclaredOversizeBeforePublishingDestination() throws Exception {
        byte[] payload = new byte[21];
        respond("/large", 200, payload, Map.of());
        Path destination = temp.resolve("downloads/candidate.zip");

        assertFailure(
            OVERSIZED,
            () -> source.download(
                policyUri("/large"),
                destination,
                20,
                sha256(new byte[20]),
                ALLOWED_HOSTS,
                NEVER_CANCEL
            )
        );

        assertFalse(Files.exists(destination));
        assertNoPartFiles(destination);
    }

    @Test
    void rejectsChunkedOversizeAfterBoundedRead() throws Exception {
        byte[] payload = new byte[21];
        respondChunked("/chunked-large", 200, payload, Map.of());
        Path destination = temp.resolve("downloads/candidate.zip");

        assertFailure(
            OVERSIZED,
            () -> source.download(
                policyUri("/chunked-large"),
                destination,
                20,
                sha256(new byte[20]),
                ALLOWED_HOSTS,
                NEVER_CANCEL
            )
        );

        assertFalse(Files.exists(destination));
        assertNoPartFiles(destination);
    }

    @Test
    void streamedOversizeReadsAtMostExpectedBytesPlusOne() throws Exception {
        byte[] payload = new byte[1024];
        AtomicInteger largestRequestedRead = new AtomicInteger();
        InputStream trackingBody = new ByteArrayInputStream(payload) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                largestRequestedRead.accumulateAndGet(length, Math::max);
                return super.read(target, offset, length);
            }
        };
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(
            HttpHeaders.of(Map.of(), (name, value) -> true)
        );
        when(response.body()).thenReturn(trackingBody);
        RuntimeHttpSource trackingSource = new RuntimeHttpSource(
            (uri, timeout) -> response,
            temp.resolve("downloads"),
            Duration.ofSeconds(1),
            2
        );
        Path destination = temp.resolve("downloads/candidate.zip");

        assertFailure(
            OVERSIZED,
            () -> trackingSource.download(
                policyUri("/payload"),
                destination,
                20,
                sha256(new byte[20]),
                ALLOWED_HOSTS,
                NEVER_CANCEL
            )
        );

        assertEquals(21, largestRequestedRead.get());
        assertFalse(Files.exists(destination));
        assertNoPartFiles(destination);
    }

    @Test
    void hashMismatchNeverPublishesDestination() throws Exception {
        byte[] payload = new byte[20];
        payload[0] = 1;
        respond("/bad-hash", 200, payload, Map.of());
        Path destination = temp.resolve("downloads/candidate.zip");

        assertFailure(
            HASH_MISMATCH,
            () -> source.download(
                policyUri("/bad-hash"),
                destination,
                payload.length,
                sha256(new byte[payload.length]),
                ALLOWED_HOSTS,
                NEVER_CANCEL
            )
        );

        assertFalse(Files.exists(destination));
        assertNoPartFiles(destination);
    }

    @Test
    void responseCloseFailureHappensBeforeAtomicPublication() throws Exception {
        byte[] payload = new byte[]{1, 2, 3};
        InputStream failingCloseBody = new ByteArrayInputStream(payload) {
            @Override
            public void close() throws IOException {
                throw new IOException("simulated close failure");
            }
        };
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(
            HttpHeaders.of(
                Map.of("Content-Length", List.of(Integer.toString(payload.length))),
                (name, value) -> true
            )
        );
        when(response.body()).thenReturn(failingCloseBody);
        RuntimeHttpSource failingCloseSource = new RuntimeHttpSource(
            (uri, timeout) -> response,
            temp.resolve("downloads"),
            Duration.ofSeconds(1),
            2
        );
        Path destination = temp.resolve("downloads/candidate.zip");

        assertFailure(
            IO_FAILURE,
            () -> failingCloseSource.download(
                policyUri("/payload"),
                destination,
                payload.length,
                sha256(payload),
                ALLOWED_HOSTS,
                NEVER_CANCEL
            )
        );

        assertFalse(Files.exists(destination));
        assertNoPartFiles(destination);
    }

    @Test
    void existingDestinationIsNeverOverwritten() throws Exception {
        byte[] existing = "keep me".getBytes(StandardCharsets.UTF_8);
        Path destination = temp.resolve("downloads/candidate.zip");
        Files.createDirectories(destination.getParent());
        Files.write(destination, existing);

        assertFailure(
            DESTINATION_EXISTS,
            () -> source.download(
                policyUri("/not-requested"),
                destination,
                existing.length,
                sha256(existing),
                ALLOWED_HOSTS,
                NEVER_CANCEL
            )
        );

        assertArrayEquals(existing, Files.readAllBytes(destination));
        assertNoPartFiles(destination);
    }

    @Test
    void rejectsDestinationOutsideOwnedDownloadDirectory() throws Exception {
        Path destination = temp.resolve("outside.zip");

        assertFailure(
            INVALID_EXPECTATION,
            () -> source.download(
                policyUri("/not-requested"),
                destination,
                1,
                sha256(new byte[]{1}),
                ALLOWED_HOSTS,
                NEVER_CANCEL
            )
        );

        assertFalse(Files.exists(destination));
    }

    @Test
    void cancellationDeletesPartFile() throws Exception {
        byte[] payload = new byte[32 * 1024];
        respond("/cancel", 200, payload, Map.of());
        Path destination = temp.resolve("downloads/candidate.zip");
        AtomicInteger checks = new AtomicInteger();
        CancellationToken cancellation = () -> checks.incrementAndGet() > 1;

        assertFailure(
            CANCELLED,
            () -> source.download(
                policyUri("/cancel"),
                destination,
                payload.length,
                sha256(payload),
                ALLOWED_HOSTS,
                cancellation
            )
        );

        assertFalse(Files.exists(destination));
        assertNoPartFiles(destination);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUris")
    void rejectsInvalidPolicyUris(
        String caseName,
        URI uri,
        RuntimeDownloadException.FailureReason expectedReason
    ) {
        assertFailure(
            expectedReason,
            () -> source.fetchBytes(uri, 1024, ALLOWED_HOSTS)
        );
    }

    @Test
    void followsAtMostTwoRelativeHttpsRedirects() throws Exception {
        byte[] payload = "redirected".getBytes(StandardCharsets.UTF_8);
        redirect("/first", "/second");
        redirect("/second", "/payload");
        respond("/payload", 200, payload, Map.of());

        assertArrayEquals(
            payload,
            source.fetchBytes(policyUri("/first"), payload.length, ALLOWED_HOSTS)
        );
    }

    @Test
    void rejectsRedirectLoop() {
        redirect("/loop", "/loop");

        assertFailure(
            REDIRECT_LOOP,
            () -> source.fetchBytes(policyUri("/loop"), 1024, ALLOWED_HOSTS)
        );
    }

    @Test
    void rejectsMoreThanTwoRedirects() {
        redirect("/one", "/two");
        redirect("/two", "/three");
        redirect("/three", "/payload");
        respond("/payload", 200, new byte[]{1}, Map.of());

        assertFailure(
            REDIRECT_LIMIT,
            () -> source.fetchBytes(policyUri("/one"), 1024, ALLOWED_HOSTS)
        );
    }

    @Test
    void rejectsHttpsToHttpRedirect() {
        redirect("/downgrade", "http://releases.mcav.live/payload");

        assertFailure(
            INSECURE_URI,
            () -> source.fetchBytes(policyUri("/downgrade"), 1024, ALLOWED_HOSTS)
        );
    }

    @Test
    void rejectsRedirectToNonAllowlistedHost() {
        redirect("/other-host", "https://example.com/payload");

        assertFailure(
            HOST_NOT_ALLOWED,
            () -> source.fetchBytes(policyUri("/other-host"), 1024, ALLOWED_HOSTS)
        );
    }

    @Test
    void rejectsRedirectWithoutExactlyOneLocation() {
        respond("/missing-location", 302, new byte[0], Map.of());

        assertFailure(
            MISSING_REDIRECT_LOCATION,
            () -> source.fetchBytes(policyUri("/missing-location"), 1024, ALLOWED_HOSTS)
        );
    }

    @Test
    void rejectsNonSuccessStatus() {
        respond("/unavailable", 503, new byte[0], Map.of());

        assertFailure(
            HTTP_STATUS,
            () -> source.fetchBytes(policyUri("/unavailable"), 1024, ALLOWED_HOSTS)
        );
    }

    @Test
    void mapsRequestTimeoutWithoutPublishingDestination() throws Exception {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
                writeResponse(exchange, 200, new byte[]{1}, 1, Map.of());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        Path destination = temp.resolve("downloads/candidate.zip");

        assertFailure(
            TIMEOUT,
            () -> source.download(
                policyUri("/slow"),
                destination,
                1,
                sha256(new byte[]{1}),
                ALLOWED_HOSTS,
                NEVER_CANCEL
            )
        );

        assertFalse(Files.exists(destination));
        assertNoPartFiles(destination);
    }

    private Transport localTransport() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return (uri, timeout) -> {
            URI localUri = localOrigin.resolve(uri.getRawPath());
            HttpRequest request = HttpRequest.newBuilder(localUri)
                .GET()
                .timeout(timeout)
                .build();
            return client.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
            );
        };
    }

    private static Stream<Arguments> invalidUris() {
        return Stream.of(
            Arguments.of("HTTP origin", URI.create("http://releases.mcav.live/file"), INSECURE_URI),
            Arguments.of("userinfo", URI.create("https://user@releases.mcav.live/file"), INVALID_URI),
            Arguments.of("fragment", URI.create("https://releases.mcav.live/file#part"), INVALID_URI),
            Arguments.of("query", URI.create("https://releases.mcav.live/file?token=x"), INVALID_URI),
            Arguments.of("nonallowlisted host", URI.create("https://example.com/file"), HOST_NOT_ALLOWED),
            Arguments.of("non-default port", URI.create("https://releases.mcav.live:8443/file"), INVALID_URI)
        );
    }

    private void respond(String path, int status, byte[] body, Map<String, String> headers) {
        respondWithDeclaredLength(path, status, body, body.length, headers);
    }

    private void respondChunked(String path, int status, byte[] body, Map<String, String> headers) {
        respondWithDeclaredLength(path, status, body, 0, headers);
    }

    private void redirect(String path, String location) {
        respond(path, 302, new byte[0], Map.of("Location", location));
    }

    private void respondWithDeclaredLength(
        String path,
        int status,
        byte[] body,
        long declaredLength,
        Map<String, String> headers
    ) {
        server.createContext(
            path,
            exchange -> writeResponse(exchange, status, body, declaredLength, headers)
        );
    }

    private static void writeResponse(
        HttpExchange exchange,
        int status,
        byte[] body,
        long declaredLength,
        Map<String, String> headers
    ) throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(status, declaredLength);
        try (exchange; var responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    private static URI policyUri(String path) {
        return POLICY_ORIGIN.resolve(path);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertFailure(
        RuntimeDownloadException.FailureReason reason,
        ThrowingOperation operation
    ) {
        RuntimeDownloadException failure = assertThrows(
            RuntimeDownloadException.class,
            operation::run
        );
        assertEquals(reason, failure.reason());
        assertFalse(failure.getMessage().contains("releases.mcav.live"));
    }

    private static void assertNoPartFiles(Path destination) throws IOException {
        Path parent = destination.getParent();
        if (!Files.exists(parent)) {
            return;
        }
        try (Stream<Path> children = Files.list(parent)) {
            assertTrue(
                children.noneMatch(path -> path.getFileName().toString().endsWith(".part"))
            );
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
