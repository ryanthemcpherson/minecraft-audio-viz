package com.audioviz;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModConfigTest {

    @TempDir
    Path configDir;

    @Test
    void newConfigPersistsLoopbackAddressAndEmptySecret() throws Exception {
        ModConfig config = ModConfig.load(configDir);

        assertEquals("127.0.0.1", config.websocketAddress);
        assertEquals("", config.websocketSecret);

        JsonObject persisted = JsonParser.parseString(
            Files.readString(configDir.resolve("audioviz.json"))
        ).getAsJsonObject();
        assertEquals("127.0.0.1", persisted.get("websocketAddress").getAsString());
        assertEquals("", persisted.get("websocketSecret").getAsString());
    }

    @Test
    void loadedWebSocketValuesAreNormalizedBeforePersistence() throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(
            configDir.resolve("audioviz.json"),
            "{\"websocketAddress\":\" 0.0.0.0 \",\"websocketSecret\":\"  exact-token  \"}"
        );

        ModConfig config = ModConfig.load(configDir);

        assertEquals("0.0.0.0", config.websocketAddress);
        assertEquals("exact-token", config.websocketSecret);

        JsonObject persisted = JsonParser.parseString(
            Files.readString(configDir.resolve("audioviz.json"))
        ).getAsJsonObject();
        assertEquals("0.0.0.0", persisted.get("websocketAddress").getAsString());
        assertEquals("exact-token", persisted.get("websocketSecret").getAsString());
    }

    @Test
    void loadedWhitespaceSecretBecomesEmpty() throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(
            configDir.resolve("audioviz.json"),
            "{\"websocketAddress\":\"127.0.0.1\",\"websocketSecret\":\" \\t \\n \"}"
        );

        ModConfig config = ModConfig.load(configDir);

        assertEquals("", config.websocketSecret);
    }
}
