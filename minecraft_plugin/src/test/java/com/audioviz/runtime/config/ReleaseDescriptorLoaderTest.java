package com.audioviz.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReleaseDescriptorLoaderTest {
    private ReleaseDescriptorLoader loader;
    private Properties valid;

    @BeforeEach
    void setUp() throws Exception {
        loader = new ReleaseDescriptorLoader(1);
        valid = validProperties();
    }

    @Test
    void loadsProductionDescriptorAndEd25519TrustKey() throws Exception {
        var descriptor = loader.load(valid);

        assertEquals("https", descriptor.manifestUri().getScheme());
        assertEquals("releases.mcav.live", descriptor.manifestUri().getHost());
        assertEquals(1, descriptor.runtimeApi());
        assertEquals(7, descriptor.minimumGeneration());
        assertEquals(
            java.util.Set.of("releases.mcav.live", "cdn.mcav.live"),
            descriptor.allowedHosts()
        );
        assertEquals("EdDSA", descriptor.trustedKeys().get("mcav-release-2026").getAlgorithm());
    }

    @Test
    void developmentResourceCannotEnableRemoteInstallation() {
        ReleaseDescriptorLoader.DescriptorException error = assertThrows(
            ReleaseDescriptorLoader.DescriptorException.class,
            () -> loader.load(getClass().getClassLoader())
        );
        assertEquals(
            ReleaseDescriptorLoader.FailureReason.DEVELOPMENT_DESCRIPTOR,
            error.reason()
        );
    }

    @Test
    void rejectsMissingInsecureTestOrIncompatibleProductionProperties() {
        assertInvalid("manifest.url", "");
        assertInvalid("manifest.url", "http://releases.mcav.live/manifest.json");
        assertInvalid("manifest.url", "https://user:pass@releases.mcav.live/manifest.json");
        assertInvalid("signing.key.id", "test-only-development");
        assertInvalid("signing.public.key", "not-base64");
        assertInvalid("allowed.hosts", "");
        assertInvalid("runtime.api", "2");
        assertInvalid("minimum.generation", "0");
        assertInvalid("release.mode", "false");
        Properties unknown = copy(valid);
        unknown.setProperty("surprise", "value");
        assertThrows(ReleaseDescriptorLoader.DescriptorException.class, () -> loader.load(unknown));
    }

    @Test
    void descriptorErrorsContainOnlyClosedReasonCodes() {
        valid.setProperty("signing.public.key", "secret-private-material");
        ReleaseDescriptorLoader.DescriptorException error = assertThrows(
            ReleaseDescriptorLoader.DescriptorException.class,
            () -> loader.load(valid)
        );
        assertEquals(error.reason().name(), error.getMessage());
        assertTrue(!error.getMessage().contains("secret-private-material"));
    }

    private void assertInvalid(String key, String value) {
        Properties properties = copy(valid);
        properties.setProperty(key, value);
        assertThrows(
            ReleaseDescriptorLoader.DescriptorException.class,
            () -> loader.load(properties),
            key
        );
    }

    private static Properties validProperties() throws Exception {
        byte[] publicKey = KeyPairGenerator.getInstance("Ed25519")
            .generateKeyPair()
            .getPublic()
            .getEncoded();
        Properties properties = new Properties();
        properties.setProperty("product.version", "1.2.0");
        properties.setProperty("runtime.api", "1");
        properties.setProperty(
            "manifest.url",
            "https://releases.mcav.live/runtime/stable/manifest.json"
        );
        properties.setProperty("minimum.generation", "7");
        properties.setProperty("signing.key.id", "mcav-release-2026");
        properties.setProperty(
            "signing.public.key",
            Base64.getEncoder().encodeToString(publicKey)
        );
        properties.setProperty("allowed.hosts", "releases.mcav.live,cdn.mcav.live");
        properties.setProperty("release.mode", "true");
        return properties;
    }

    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }
}
