package com.audioviz.runtime.config;

import com.audioviz.runtime.release.ReleaseDescriptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

public final class ReleaseDescriptorLoader {
    public static final String RESOURCE = "runtime/release-descriptor.properties";
    private static final int MAX_RESOURCE_BYTES = 64 * 1024;
    private static final int MAX_ALLOWED_HOSTS = 8;
    private static final Set<String> FIELDS = Set.of(
        "product.version",
        "runtime.api",
        "manifest.url",
        "minimum.generation",
        "signing.key.id",
        "signing.public.key",
        "allowed.hosts",
        "release.mode"
    );
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final int supportedRuntimeApi;

    public ReleaseDescriptorLoader(int supportedRuntimeApi) {
        if (supportedRuntimeApi <= 0) {
            throw new IllegalArgumentException("supportedRuntimeApi must be positive");
        }
        this.supportedRuntimeApi = supportedRuntimeApi;
    }

    public ReleaseDescriptor load(ClassLoader classLoader) throws DescriptorException {
        Objects.requireNonNull(classLoader, "classLoader");
        byte[] bytes;
        try (InputStream input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw failure(FailureReason.RESOURCE_MISSING);
            }
            bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw failure(FailureReason.RESOURCE_TOO_LARGE);
            }
        } catch (IOException error) {
            throw failure(FailureReason.IO_FAILURE, error);
        }
        Properties properties = new Properties();
        try {
            properties.load(new ByteArrayInputStream(bytes));
        } catch (IOException | IllegalArgumentException error) {
            throw failure(FailureReason.RESOURCE_INVALID, error);
        }
        return load(properties);
    }

    ReleaseDescriptor load(Properties properties) throws DescriptorException {
        Objects.requireNonNull(properties, "properties");
        if (!FIELDS.equals(properties.stringPropertyNames())) {
            throw failure(FailureReason.RESOURCE_INVALID);
        }
        String releaseMode = value(properties, "release.mode", 5);
        if ("false".equals(releaseMode)) {
            throw failure(FailureReason.DEVELOPMENT_DESCRIPTOR);
        }
        if (!"true".equals(releaseMode)) {
            throw failure(FailureReason.RESOURCE_INVALID);
        }
        String productVersion = value(properties, "product.version", 64);
        if (productVersion.contains("${")) {
            throw failure(FailureReason.RESOURCE_INVALID);
        }
        int runtimeApi = integer(properties, "runtime.api", 1, 1_000_000);
        if (runtimeApi != supportedRuntimeApi) {
            throw failure(FailureReason.RUNTIME_API_MISMATCH);
        }
        URI manifest = manifestUri(value(properties, "manifest.url", 2_048));
        long generation = longValue(
            properties,
            "minimum.generation",
            1,
            Long.MAX_VALUE
        );
        String keyId = value(properties, "signing.key.id", 64);
        if (!IDENTIFIER.matcher(keyId).matches() || keyId.startsWith("test-only-")) {
            throw failure(FailureReason.TRUST_KEY_INVALID);
        }
        PublicKey publicKey = publicKey(value(properties, "signing.public.key", 4_096));
        Set<String> allowedHosts = allowedHosts(value(properties, "allowed.hosts", 2_048));
        if (!allowedHosts.contains(manifest.getHost().toLowerCase(Locale.ROOT))) {
            throw failure(FailureReason.ALLOWED_HOSTS_INVALID);
        }
        return new ReleaseDescriptor(
            manifest,
            Map.of(keyId, publicKey),
            allowedHosts,
            runtimeApi,
            generation
        );
    }

    private static URI manifestUri(String value) throws DescriptorException {
        try {
            URI uri = new URI(value);
            if (
                !"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.getHost() == null ||
                uri.getUserInfo() != null ||
                uri.getFragment() != null ||
                uri.getQuery() != null ||
                uri.getPath().isBlank()
            ) {
                throw failure(FailureReason.MANIFEST_URI_INVALID);
            }
            return uri;
        } catch (URISyntaxException error) {
            throw failure(FailureReason.MANIFEST_URI_INVALID, error);
        }
    }

    private static PublicKey publicKey(String value) throws DescriptorException {
        byte[] encoded;
        try {
            encoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw failure(FailureReason.TRUST_KEY_INVALID, error);
        }
        if (encoded.length < 32 || encoded.length > 512) {
            throw failure(FailureReason.TRUST_KEY_INVALID);
        }
        try {
            return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException error) {
            throw failure(FailureReason.TRUST_KEY_INVALID, error);
        }
    }

    private static Set<String> allowedHosts(String value) throws DescriptorException {
        String[] rawHosts = value.split(",", -1);
        if (rawHosts.length == 0 || rawHosts.length > MAX_ALLOWED_HOSTS) {
            throw failure(FailureReason.ALLOWED_HOSTS_INVALID);
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String raw : rawHosts) {
            String host = raw.strip().toLowerCase(Locale.ROOT);
            if (!validHost(host) || !hosts.add(host)) {
                throw failure(FailureReason.ALLOWED_HOSTS_INVALID);
            }
        }
        return Set.copyOf(hosts);
    }

    private static boolean validHost(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        int labelStart = 0;
        for (int index = 0; index <= host.length(); index++) {
            if (index < host.length() && host.charAt(index) != '.') {
                continue;
            }
            int labelLength = index - labelStart;
            if (labelLength < 1 || labelLength > 63) {
                return false;
            }
            if (
                !alphaNumeric(host.charAt(labelStart)) ||
                !alphaNumeric(host.charAt(index - 1))
            ) {
                return false;
            }
            for (int characterIndex = labelStart; characterIndex < index; characterIndex++) {
                char character = host.charAt(characterIndex);
                if (!alphaNumeric(character) && character != '-') {
                    return false;
                }
            }
            labelStart = index + 1;
        }
        return true;
    }

    private static boolean alphaNumeric(char character) {
        return (character >= 'a' && character <= 'z') ||
            (character >= '0' && character <= '9');
    }

    private static int integer(
        Properties properties,
        String key,
        int minimum,
        int maximum
    ) throws DescriptorException {
        return Math.toIntExact(longValue(properties, key, minimum, maximum));
    }

    private static long longValue(
        Properties properties,
        String key,
        long minimum,
        long maximum
    ) throws DescriptorException {
        String raw = value(properties, key, 32);
        if (!raw.matches("0|[1-9][0-9]*")) {
            throw failure(FailureReason.RESOURCE_INVALID);
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw failure(FailureReason.RESOURCE_INVALID, error);
        }
        if (parsed < minimum || parsed > maximum) {
            throw failure(FailureReason.RESOURCE_INVALID);
        }
        return parsed;
    }

    private static String value(Properties properties, String key, int maximum)
        throws DescriptorException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw failure(FailureReason.RESOURCE_INVALID);
        }
        return value.strip();
    }

    public enum FailureReason {
        RESOURCE_MISSING,
        RESOURCE_TOO_LARGE,
        RESOURCE_INVALID,
        DEVELOPMENT_DESCRIPTOR,
        RUNTIME_API_MISMATCH,
        MANIFEST_URI_INVALID,
        TRUST_KEY_INVALID,
        ALLOWED_HOSTS_INVALID,
        IO_FAILURE
    }

    public static final class DescriptorException extends Exception {
        private final FailureReason reason;

        DescriptorException(FailureReason reason) {
            super(reason.name());
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        DescriptorException(FailureReason reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public FailureReason reason() {
            return reason;
        }
    }

    private static DescriptorException failure(FailureReason reason) {
        return new DescriptorException(reason);
    }

    private static DescriptorException failure(FailureReason reason, Throwable cause) {
        return new DescriptorException(reason, cause);
    }
}
