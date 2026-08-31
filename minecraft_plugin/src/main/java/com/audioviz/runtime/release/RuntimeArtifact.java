package com.audioviz.runtime.release;

import java.net.URI;
import java.util.Objects;

public record RuntimeArtifact(
    RuntimePlatform platform,
    URI url,
    long archiveSize,
    long uncompressedSize,
    String sha256,
    String entrypoint,
    String filesManifestSha256
) {
    public RuntimeArtifact {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(filesManifestSha256, "filesManifestSha256");
    }
}
