package com.audioviz.websocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketSecretManagerTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", " \r\n "})
    void blankSecretGeneratesUrlSafeThirtyTwoByteValue(String configuredSecret) {
        WebSocketSecretManager manager = new WebSocketSecretManager(new SecureRandom());

        WebSocketSecretManager.SecretResolution result = manager.resolve(configuredSecret);

        assertTrue(result.generated());
        assertEquals(32, Base64.getUrlDecoder().decode(result.secret()).length);
        assertFalse(result.secret().contains("="));
        assertTrue(result.secret().matches("[A-Za-z0-9_-]{43}"));
        assertFalse(result.toString().contains(result.secret()));
    }

    @Test
    void generatedSecretsAreUnique() {
        WebSocketSecretManager manager = new WebSocketSecretManager(new SecureRandom());

        String first = manager.resolve("").secret();
        String second = manager.resolve("").secret();

        assertNotEquals(first, second);
    }

    @Test
    void configuredSecretIsNormalizedWithoutReplacement() {
        WebSocketSecretManager.SecretResolution result =
            new WebSocketSecretManager(new SecureRandom()).resolve("  stable-secret  ");

        assertFalse(result.generated());
        assertEquals("stable-secret", result.secret());
        assertFalse(result.toString().contains(result.secret()));
    }

    @Test
    void secureRandomIsRequired() {
        assertThrows(NullPointerException.class, () -> new WebSocketSecretManager(null));
    }
}
