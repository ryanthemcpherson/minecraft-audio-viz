package com.audioviz.runtime.release;

import java.net.URI;
import java.security.PublicKey;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record ReleaseDescriptor(
    URI manifestUri,
    Map<String, PublicKey> trustedKeys,
    Set<String> allowedHosts,
    int runtimeApi,
    long minimumGeneration
) {
    public ReleaseDescriptor {
        Objects.requireNonNull(manifestUri, "manifestUri");
        trustedKeys = Map.copyOf(trustedKeys);
        allowedHosts = allowedHosts
            .stream()
            .map(host -> host.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
        if (runtimeApi < 1 || minimumGeneration < 1) {
            throw new IllegalArgumentException("release descriptor API and generation must be positive");
        }
    }

    @Override
    public Set<String> allowedHosts() {
        return Collections.unmodifiableSet(new HashSet<>(allowedHosts));
    }
}
