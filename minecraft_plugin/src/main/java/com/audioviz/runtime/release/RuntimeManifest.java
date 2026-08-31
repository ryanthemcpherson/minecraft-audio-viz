package com.audioviz.runtime.release;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record RuntimeManifest(
    int schemaVersion,
    long generation,
    String releaseVersion,
    int runtimeApiMin,
    int runtimeApiMax,
    Instant publishedAt,
    Instant expiresAt,
    String signingKeyId,
    Map<RuntimePlatform, RuntimeArtifact> artifacts
) {
    public RuntimeManifest {
        Objects.requireNonNull(releaseVersion, "releaseVersion");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(signingKeyId, "signingKeyId");
        artifacts = Map.copyOf(artifacts);
    }
}
