package com.audioviz.runtime.store;

import java.time.Instant;
import java.util.Objects;

public record RuntimeState(
    int schemaVersion,
    RuntimeVersionId id,
    long manifestGeneration,
    String archiveSha256,
    String filesManifestSha256,
    int runtimeApi,
    Instant activatedAt,
    HealthState health
) {
    public enum HealthState {
        CANDIDATE("candidate"),
        HEALTHY("healthy");

        private final String wireName;

        HealthState(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static HealthState fromWireName(String value) {
            for (HealthState state : values()) {
                if (state.wireName.equals(value)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("unsupported health state");
        }
    }

    public RuntimeState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(health, "health");
        if (
            schemaVersion != 1 ||
            manifestGeneration < 0 ||
            runtimeApi <= 0 ||
            !isDigest(archiveSha256) ||
            !isDigest(filesManifestSha256)
        ) {
            throw new IllegalArgumentException("invalid runtime state");
        }
    }

    public RuntimeState withHealth(HealthState nextHealth) {
        return new RuntimeState(
            schemaVersion,
            id,
            manifestGeneration,
            archiveSha256,
            filesManifestSha256,
            runtimeApi,
            activatedAt,
            nextHealth
        );
    }

    private static boolean isDigest(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (
                (character < '0' || character > '9') &&
                (character < 'a' || character > 'f')
            ) {
                return false;
            }
        }
        return true;
    }
}
