package com.audioviz.websocket;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** Resolves the configured WebSocket pairing secret without exposing it in diagnostics. */
public final class WebSocketSecretManager {

    private static final int SECRET_BYTES = 32;

    private final SecureRandom secureRandom;

    public WebSocketSecretManager(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public SecretResolution resolve(String configuredSecret) {
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            return new SecretResolution(configuredSecret.strip(), false);
        }

        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return new SecretResolution(
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            true
        );
    }

    public record SecretResolution(String secret, boolean generated) {

        @Override
        public String toString() {
            return "SecretResolution[generated=" + generated + "]";
        }
    }
}
