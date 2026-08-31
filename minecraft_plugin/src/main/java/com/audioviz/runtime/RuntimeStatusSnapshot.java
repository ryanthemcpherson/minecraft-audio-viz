package com.audioviz.runtime;

import com.audioviz.runtime.store.RuntimeVersionId;
import com.audioviz.runtime.supervisor.SupervisorState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RuntimeStatusSnapshot(
    SupervisorState state,
    Optional<RuntimeVersionId> activeVersion,
    Optional<RuntimeVersionId> lastKnownGoodVersion,
    int runtimeApi,
    Instant updatedAt,
    String publicHost,
    int publicPort,
    String reasonCode,
    Optional<Instant> retryAt,
    boolean rendererConnected,
    long lastHealthSequence,
    long lastRenderAgeMillis,
    int ingressQueueDepth,
    int renderQueueDepth,
    long rejectedSignals
) {
    public RuntimeStatusSnapshot {
        Objects.requireNonNull(state, "state");
        activeVersion = Objects.requireNonNull(activeVersion, "activeVersion");
        lastKnownGoodVersion = Objects.requireNonNull(
            lastKnownGoodVersion,
            "lastKnownGoodVersion"
        );
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(publicHost, "publicHost");
        reasonCode = safeReason(reasonCode);
        retryAt = Objects.requireNonNull(retryAt, "retryAt");
        if (
            runtimeApi <= 0 || publicHost.isBlank() || publicHost.length() > 253 ||
            publicPort <= 0 || publicPort > 65_535 || lastHealthSequence < -1 ||
            lastRenderAgeMillis < 0 || ingressQueueDepth < 0 || renderQueueDepth < 0 ||
            rejectedSignals < 0
        ) {
            throw new IllegalArgumentException("invalid runtime status snapshot");
        }
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("invalid runtime status reason");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < 'A' || character > 'Z') && character != '_') {
                throw new IllegalArgumentException("invalid runtime status reason");
            }
        }
        return value;
    }
}
