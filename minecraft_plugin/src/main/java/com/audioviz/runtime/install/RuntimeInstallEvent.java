package com.audioviz.runtime.install;

import java.util.Objects;

public record RuntimeInstallEvent(Stage stage, String code, long generation) {
    public enum Stage {
        CHECKING,
        DOWNLOADING,
        VERIFYING,
        STAGING,
        INSTALLED
    }

    public RuntimeInstallEvent {
        Objects.requireNonNull(stage, "stage");
        if (code == null || code.isEmpty() || code.length() > 64) {
            throw new IllegalArgumentException("invalid install event code");
        }
        for (int index = 0; index < code.length(); index++) {
            char character = code.charAt(index);
            if (
                (character < 'a' || character > 'z') &&
                (character < '0' || character > '9') &&
                character != '_'
            ) {
                throw new IllegalArgumentException("invalid install event code");
            }
        }
        if (generation < 0) {
            throw new IllegalArgumentException("invalid install event generation");
        }
    }
}
