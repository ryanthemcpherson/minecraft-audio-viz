package com.audioviz.runtime.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeConfigLoaderTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:34:56Z");

    @TempDir
    Path temporaryDirectory;

    private RuntimeConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new RuntimeConfigLoader(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void loadsExactReleaseDefaults() throws Exception {
        RuntimeConfig config = loader.load(defaultConfiguration(), temporaryDirectory);

        assertTrue(config.enabled());
        assertEquals(RuntimeConfig.ReleaseChannel.STABLE, config.channel());
        assertTrue(config.installOnStart());
        assertEquals("0.0.0.0", config.publicHost());
        assertEquals(8080, config.publicPort());
        assertTrue(config.publicUrl().isEmpty());
        assertEquals(30, config.readinessTimeout().toSeconds());
        assertEquals(RuntimeConfig.ResourceProfile.AUTO, config.resourceProfile());
        assertEquals(3, config.retainVersions());
        assertEquals(RuntimeConfig.TlsMode.GENERATED, config.tls().mode());
        assertTrue(config.tls().certificate().isEmpty());
        assertTrue(config.tls().privateKey().isEmpty());
        assertEquals(RuntimeConfig.PerformanceProfile.BALANCED, config.performance().profile());
        assertEquals(160, config.performance().entityBudget());
        assertEquals(20, config.performance().targetRenderFps());
        assertTrue(config.performance().tpsLoadShedding());
        assertEquals("127.0.0.1", config.rendererAddress());
    }

    @Test
    void rejectsInvalidExternalBoundaryValues() throws Exception {
        assertInvalid("runtime.channel", "nightly");
        assertInvalid("runtime.public-port", 0);
        assertInvalid("runtime.public-port", 65_536);
        assertInvalid("runtime.public-host", "203.0.113.10");
        assertInvalid("runtime.public-url", "http://example.test:8080");
        assertInvalid("runtime.public-url", "https://user:pass@example.test");
        assertInvalid("runtime.readiness-timeout-seconds", 4);
        assertInvalid("runtime.readiness-timeout-seconds", 301);
        assertInvalid("runtime.resource-profile", "unbounded");
        assertInvalid("runtime.retain-versions", 2);
        assertInvalid("runtime.retain-versions", 11);
        assertInvalid("runtime.tls.mode", "trust-me");
        assertInvalid("runtime.tls.certificate", "../outside.pem");
        assertInvalid("performance.profile", "turbo");
        assertInvalid("performance.entity-budget", 15);
        assertInvalid("performance.entity-budget", 2_001);
        assertInvalid("performance.target-render-fps", 0);
        assertInvalid("performance.target-render-fps", 61);
        assertInvalid("websocket.address", "0.0.0.0");
    }

    @Test
    void providedTlsRequiresBothOwnedPaths() throws Exception {
        YamlConfiguration config = defaultConfiguration();
        config.set("runtime.tls.mode", "provided");
        config.set("runtime.tls.certificate", "tls/server.crt");
        config.set("runtime.tls.private-key", "tls/server.key");

        RuntimeConfig loaded = loader.load(config, temporaryDirectory);

        assertEquals(
            temporaryDirectory.resolve("tls/server.crt").toAbsolutePath().normalize(),
            loaded.tls().certificate().orElseThrow()
        );
        assertEquals(
            temporaryDirectory.resolve("tls/server.key").toAbsolutePath().normalize(),
            loaded.tls().privateKey().orElseThrow()
        );

        config.set("runtime.tls.private-key", "");
        assertThrows(
            RuntimeConfigLoader.ConfigException.class,
            () -> loader.load(config, temporaryDirectory)
        );
    }

    @Test
    void migrationAddsOnlyOwnedBlocksAndPreservesUnownedBytes() throws Exception {
        Path configFile = temporaryDirectory.resolve("config.yml");
        byte[] before = (
            "# legacy administrator comment\r\n" +
            "websocket:\r\n" +
            "  address: \"127.0.0.1\" # keep inline\r\n" +
            "  port: 8765\r\n" +
            "custom-extension:\r\n" +
            "  value: \"do not rewrite\"\r\n" +
            "performance:\r\n" +
            "  # existing performance comment\r\n" +
            "  max_entities_per_zone: 256\r\n" +
            "bedrock:\r\n" +
            "  particle_quality: 3\r\n"
        ).getBytes(StandardCharsets.UTF_8);
        Files.write(configFile, before);

        RuntimeConfigLoader.MigrationResult result = loader.migrate(configFile);

        assertTrue(result.changed());
        Path backup = result.backup().orElseThrow();
        assertArrayEquals(before, Files.readAllBytes(backup));
        String after = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(after.contains("# legacy administrator comment\r\n"));
        assertTrue(after.contains("  address: \"127.0.0.1\" # keep inline\r\n"));
        assertTrue(after.contains("  value: \"do not rewrite\"\r\n"));
        assertTrue(after.contains("  max_entities_per_zone: 256\r\n"));
        assertTrue(after.contains("runtime:\r\n"));
        assertTrue(after.contains("  profile: balanced\r\n"));
        assertTrue(after.contains("  entity-budget: 160\r\n"));

        RuntimeConfigLoader.MigrationResult repeated = loader.migrate(configFile);
        assertFalse(repeated.changed());
        assertTrue(repeated.backup().isEmpty());
    }

    @Test
    void ambiguousYamlFailsWithoutChangingOrBackingUpFile() throws Exception {
        for (String invalid : List.of(
            "performance:\n  profile: balanced\nperformance:\n  entity-budget: 160\n",
            "performance:\n\tprofile: balanced\n",
            "performance:\n  \tprofile: balanced\n"
        )) {
            Path configFile = temporaryDirectory.resolve(
                "ambiguous-" + Integer.toUnsignedString(invalid.hashCode()) + ".yml"
            );
            byte[] before = invalid.getBytes(StandardCharsets.UTF_8);
            Files.write(configFile, before);

            assertThrows(RuntimeConfigLoader.ConfigException.class, () -> loader.migrate(configFile));
            assertArrayEquals(before, Files.readAllBytes(configFile));
        }
        try (var paths = Files.list(temporaryDirectory)) {
            assertEquals(
                0,
                paths.filter(path -> path.getFileName().toString().contains("backup"))
                    .count()
            );
        }
    }

    private void assertInvalid(String path, Object value) throws Exception {
        YamlConfiguration config = defaultConfiguration();
        config.set(path, value);
        assertThrows(
            RuntimeConfigLoader.ConfigException.class,
            () -> loader.load(config, temporaryDirectory),
            path
        );
    }

    private static YamlConfiguration defaultConfiguration() throws Exception {
        Path source = Path.of("src/main/resources/config.yml");
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(Files.readString(source, StandardCharsets.UTF_8));
        return configuration;
    }
}
