package com.audioviz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseMetadataTest {
    @Test
    void filteredPluginMetadataTargetsPaper262() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(stream);
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("version: 1.1.0"));
            assertTrue(metadata.contains("api-version: '26.2'"));
            assertTrue(metadata.contains("Paper 26.2"));
        }
    }
}
