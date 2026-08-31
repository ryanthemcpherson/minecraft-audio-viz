package com.audioviz.runtime.net;

import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.ATOMIC_MOVE_UNSUPPORTED;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.CANCELLED;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.DESTINATION_EXISTS;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.HASH_MISMATCH;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.HOST_NOT_ALLOWED;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.HTTP_STATUS;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.INSECURE_URI;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.INTERRUPTED;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.INVALID_CONTENT_LENGTH;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.INVALID_EXPECTATION;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.INVALID_URI;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.IO_FAILURE;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.MISSING_REDIRECT_LOCATION;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.OVERSIZED;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.REDIRECT_LIMIT;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.REDIRECT_LOOP;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.TIMEOUT;
import static com.audioviz.runtime.net.RuntimeDownloadException.FailureReason.TRUNCATED;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

public final class RuntimeHttpSource {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAXIMUM_REDIRECTS = 2;
    private static final int BUFFER_SIZE = 16 * 1024;

    private final Transport transport;
    private final Path downloadDirectory;
    private final Duration requestTimeout;
    private final int maximumRedirects;

    public RuntimeHttpSource(Executor executor, Path downloadDirectory) {
        this(
            new JdkTransport(
                HttpClient.newBuilder()
                    .executor(Objects.requireNonNull(executor, "executor"))
                    .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
            ),
            downloadDirectory,
            DEFAULT_REQUEST_TIMEOUT,
            DEFAULT_MAXIMUM_REDIRECTS
        );
    }

    RuntimeHttpSource(
        Transport transport,
        Path downloadDirectory,
        Duration requestTimeout,
        int maximumRedirects
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.downloadDirectory = normalizeDownloadDirectory(downloadDirectory);
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative() || maximumRedirects < 0) {
            throw new IllegalArgumentException("HTTP limits must be positive");
        }
        this.maximumRedirects = maximumRedirects;
    }

    @FunctionalInterface
    public interface CancellationToken {
        boolean isCancelled();
    }

    @FunctionalInterface
    public interface Transport {
        HttpResponse<InputStream> execute(URI uri, Duration timeout)
            throws IOException, InterruptedException;
    }

    public record DownloadResult(long bytesWritten, String sha256) {}

    private record JdkTransport(HttpClient client) implements Transport {
        @Override
        public HttpResponse<InputStream> execute(URI uri, Duration timeout)
            throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "MCAV-Paper-Runtime/1")
                .timeout(timeout)
                .build();
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
    }

    public byte[] fetchBytes(URI uri, int maximumBytes, Set<String> allowedHosts)
        throws RuntimeDownloadException {
        if (maximumBytes <= 0) {
            throw failure(INVALID_EXPECTATION);
        }
        HttpResponse<InputStream> response = openFinalResponse(uri, allowedHosts);
        try (InputStream input = response.body()) {
            requireIdentityEncoding(response.headers());
            long declaredLength = declaredContentLength(response.headers());
            if (declaredLength > maximumBytes) {
                throw failure(OVERSIZED);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(maximumBytes, BUFFER_SIZE)
            );
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            while (true) {
                int read;
                try {
                    read = input.read(
                        buffer,
                        0,
                        boundedReadLength(maximumBytes, total)
                    );
                } catch (IOException error) {
                    if (declaredLength >= 0 && total < declaredLength) {
                        throw failure(TRUNCATED, error);
                    }
                    throw failure(IO_FAILURE, error);
                }
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw failure(IO_FAILURE);
                }
                total += read;
                if (total > maximumBytes || (declaredLength >= 0 && total > declaredLength)) {
                    throw failure(OVERSIZED);
                }
                output.write(buffer, 0, read);
            }
            if (declaredLength >= 0 && total < declaredLength) {
                throw failure(TRUNCATED);
            }
            return output.toByteArray();
        } catch (RuntimeDownloadException error) {
            throw error;
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    public DownloadResult download(
        URI uri,
        Path destination,
        long expectedBytes,
        String expectedSha256,
        Set<String> allowedHosts,
        CancellationToken cancellationToken
    ) throws RuntimeDownloadException {
        byte[] expectedDigest = validateDownloadExpectation(
            destination,
            expectedBytes,
            expectedSha256,
            cancellationToken
        );
        if (cancellationToken.isCancelled()) {
            throw failure(CANCELLED);
        }

        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path parent = normalizedDestination.getParent();
        if (
            parent == null ||
            normalizedDestination.getFileName() == null ||
            !parent.equals(downloadDirectory)
        ) {
            throw failure(INVALID_EXPECTATION);
        }
        if (Files.exists(normalizedDestination, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(DESTINATION_EXISTS);
        }

        HttpResponse<InputStream> response = openFinalResponse(uri, allowedHosts);
        Path part = null;
        boolean published = false;
        RuntimeDownloadException primaryFailure = null;
        try {
            DownloadResult result;
            try (InputStream input = response.body()) {
                requireIdentityEncoding(response.headers());
                long declaredLength = declaredContentLength(response.headers());
                requireExpectedLength(declaredLength, expectedBytes);

                Files.createDirectories(downloadDirectory);
                if (Files.isSymbolicLink(downloadDirectory)) {
                    throw failure(INVALID_EXPECTATION);
                }
                if (Files.exists(normalizedDestination, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure(DESTINATION_EXISTS);
                }
                part = Files.createTempFile(
                    downloadDirectory,
                    ".mcav-runtime-",
                    ".part"
                );
                result = streamVerifiedDownload(
                    input,
                    part,
                    expectedBytes,
                    expectedDigest,
                    cancellationToken
                );
            }
            try {
                Files.move(part, normalizedDestination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw failure(ATOMIC_MOVE_UNSUPPORTED, error);
            } catch (FileAlreadyExistsException error) {
                throw failure(DESTINATION_EXISTS, error);
            }
            published = true;
            return result;
        } catch (RuntimeDownloadException error) {
            primaryFailure = error;
            throw error;
        } catch (IOException error) {
            primaryFailure = failure(IO_FAILURE, error);
            throw primaryFailure;
        } finally {
            if (!published && part != null) {
                try {
                    Files.deleteIfExists(part);
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

    private static DownloadResult streamVerifiedDownload(
        InputStream input,
        Path part,
        long expectedBytes,
        byte[] expectedDigest,
        CancellationToken cancellationToken
    ) throws IOException, RuntimeDownloadException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        try (FileChannel output = FileChannel.open(
            part,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        )) {
            while (true) {
                if (cancellationToken.isCancelled()) {
                    throw failure(CANCELLED);
                }
                int read;
                try {
                    read = input.read(
                        buffer,
                        0,
                        boundedReadLength(expectedBytes, total)
                    );
                } catch (IOException error) {
                    if (total < expectedBytes) {
                        throw failure(TRUNCATED, error);
                    }
                    throw error;
                }
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw failure(IO_FAILURE);
                }
                total += read;
                if (total > expectedBytes) {
                    throw failure(OVERSIZED);
                }
                digest.update(buffer, 0, read);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
            }
            if (total < expectedBytes) {
                throw failure(TRUNCATED);
            }
            byte[] actualDigest = digest.digest();
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                throw failure(HASH_MISMATCH);
            }
            output.force(true);
            return new DownloadResult(total, HexFormat.of().formatHex(actualDigest));
        }
    }

    private static byte[] validateDownloadExpectation(
        Path destination,
        long expectedBytes,
        String expectedSha256,
        CancellationToken cancellationToken
    ) throws RuntimeDownloadException {
        if (
            destination == null ||
            expectedBytes <= 0 ||
            expectedSha256 == null ||
            expectedSha256.length() != 64 ||
            cancellationToken == null
        ) {
            throw failure(INVALID_EXPECTATION);
        }
        for (int index = 0; index < expectedSha256.length(); index++) {
            char character = expectedSha256.charAt(index);
            if (
                (character < '0' || character > '9') &&
                (character < 'a' || character > 'f')
            ) {
                throw failure(INVALID_EXPECTATION);
            }
        }
        try {
            return HexFormat.of().parseHex(expectedSha256);
        } catch (IllegalArgumentException error) {
            throw failure(INVALID_EXPECTATION, error);
        }
    }

    private static Path normalizeDownloadDirectory(Path downloadDirectory) {
        Path normalized = Objects.requireNonNull(downloadDirectory, "downloadDirectory")
            .toAbsolutePath()
            .normalize();
        if (normalized.getParent() == null || normalized.getFileName() == null) {
            throw new IllegalArgumentException("download directory must not be a filesystem root");
        }
        return normalized;
    }

    private static void requireExpectedLength(long declaredLength, long expectedBytes)
        throws RuntimeDownloadException {
        if (declaredLength > expectedBytes) {
            throw failure(OVERSIZED);
        }
        if (declaredLength >= 0 && declaredLength < expectedBytes) {
            throw failure(TRUNCATED);
        }
    }

    private static int boundedReadLength(long maximumBytes, long bytesRead) {
        long remaining = maximumBytes - bytesRead;
        if (remaining >= BUFFER_SIZE) {
            return BUFFER_SIZE;
        }
        return Math.toIntExact(remaining + 1);
    }

    private static long declaredContentLength(HttpHeaders headers)
        throws RuntimeDownloadException {
        List<String> values = headers.allValues("Content-Length");
        if (values.isEmpty()) {
            return -1;
        }
        if (values.size() != 1) {
            throw failure(INVALID_CONTENT_LENGTH);
        }
        try {
            long value = Long.parseLong(values.getFirst());
            if (value < 0) {
                throw failure(INVALID_CONTENT_LENGTH);
            }
            return value;
        } catch (NumberFormatException error) {
            throw failure(INVALID_CONTENT_LENGTH, error);
        }
    }

    private static void requireIdentityEncoding(HttpHeaders headers)
        throws RuntimeDownloadException {
        List<String> encodings = headers.allValues("Content-Encoding");
        if (
            encodings.size() > 1 ||
            (encodings.size() == 1 && !"identity".equalsIgnoreCase(encodings.getFirst()))
        ) {
            throw failure(INVALID_CONTENT_LENGTH);
        }
    }

    private static MessageDigest sha256Digest() throws RuntimeDownloadException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    private HttpResponse<InputStream> openFinalResponse(URI uri, Set<String> allowedHosts)
        throws RuntimeDownloadException {
        Set<String> normalizedAllowedHosts = normalizeAllowedHosts(allowedHosts);
        URI current = validateUri(uri, normalizedAllowedHosts);
        Set<URI> visited = new HashSet<>();
        int redirects = 0;

        while (true) {
            URI loopKey = current.normalize();
            if (!visited.add(loopKey)) {
                throw failure(REDIRECT_LOOP);
            }
            HttpResponse<InputStream> response = execute(current);
            int statusCode = response.statusCode();
            if (!isRedirect(statusCode)) {
                if (statusCode != 200) {
                    closeRejectedBody(response.body());
                    throw failure(HTTP_STATUS);
                }
                return response;
            }

            closeRejectedBody(response.body());
            if (redirects >= maximumRedirects) {
                throw failure(REDIRECT_LIMIT);
            }
            URI next = redirectLocation(current, response.headers());
            current = validateUri(next, normalizedAllowedHosts);
            redirects++;
        }
    }

    private HttpResponse<InputStream> execute(URI uri) throws RuntimeDownloadException {
        try {
            HttpResponse<InputStream> response = transport.execute(uri, requestTimeout);
            if (response == null || response.body() == null) {
                throw failure(IO_FAILURE);
            }
            return response;
        } catch (HttpTimeoutException error) {
            throw failure(TIMEOUT, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(INTERRUPTED, error);
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    private static URI redirectLocation(URI current, HttpHeaders headers)
        throws RuntimeDownloadException {
        List<String> locations = headers.allValues("Location");
        if (locations.size() != 1 || locations.getFirst().isBlank()) {
            throw failure(MISSING_REDIRECT_LOCATION);
        }
        try {
            return current.resolve(URI.create(locations.getFirst()));
        } catch (IllegalArgumentException error) {
            throw failure(INVALID_URI, error);
        }
    }

    private static URI validateUri(URI uri, Set<String> normalizedAllowedHosts)
        throws RuntimeDownloadException {
        if (uri == null || !uri.isAbsolute() || uri.isOpaque()) {
            throw failure(INVALID_URI);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw failure(INSECURE_URI);
        }
        if (
            uri.getRawUserInfo() != null ||
            uri.getRawQuery() != null ||
            uri.getRawFragment() != null ||
            (uri.getPort() != -1 && uri.getPort() != 443)
        ) {
            throw failure(INVALID_URI);
        }
        String normalizedHost = normalizeHost(uri.getHost());
        if (!normalizedAllowedHosts.contains(normalizedHost)) {
            throw failure(HOST_NOT_ALLOWED);
        }
        return uri;
    }

    private static Set<String> normalizeAllowedHosts(Set<String> allowedHosts)
        throws RuntimeDownloadException {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw failure(INVALID_EXPECTATION);
        }
        Set<String> normalized = new HashSet<>();
        for (String host : allowedHosts) {
            normalized.add(normalizeHost(host));
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeHost(String host) throws RuntimeDownloadException {
        if (host == null || host.isBlank()) {
            throw failure(INVALID_URI);
        }
        try {
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
                .toLowerCase(Locale.ROOT);
            if (ascii.isEmpty() || ascii.endsWith(".")) {
                throw failure(INVALID_URI);
            }
            return ascii;
        } catch (IllegalArgumentException error) {
            throw failure(INVALID_URI, error);
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 ||
            statusCode == 302 ||
            statusCode == 303 ||
            statusCode == 307 ||
            statusCode == 308;
    }

    private static void closeRejectedBody(InputStream input) throws RuntimeDownloadException {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException error) {
            throw failure(IO_FAILURE, error);
        }
    }

    private static RuntimeDownloadException failure(
        RuntimeDownloadException.FailureReason reason
    ) {
        return new RuntimeDownloadException(reason);
    }

    private static RuntimeDownloadException failure(
        RuntimeDownloadException.FailureReason reason,
        Throwable cause
    ) {
        return new RuntimeDownloadException(reason, cause);
    }
}
