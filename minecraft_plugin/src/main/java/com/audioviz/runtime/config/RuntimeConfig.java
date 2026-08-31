package com.audioviz.runtime.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record RuntimeConfig(
    boolean enabled,
    ReleaseChannel channel,
    boolean installOnStart,
    String publicHost,
    int publicPort,
    Optional<URI> publicUrl,
    Duration readinessTimeout,
    ResourceProfile resourceProfile,
    int retainVersions,
    TlsConfig tls,
    PerformanceConfig performance,
    String rendererAddress
) {
    public RuntimeConfig {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(publicHost, "publicHost");
        publicUrl = Objects.requireNonNull(publicUrl, "publicUrl");
        Objects.requireNonNull(readinessTimeout, "readinessTimeout");
        Objects.requireNonNull(resourceProfile, "resourceProfile");
        Objects.requireNonNull(tls, "tls");
        Objects.requireNonNull(performance, "performance");
        Objects.requireNonNull(rendererAddress, "rendererAddress");
    }

    public enum ReleaseChannel {
        STABLE,
        BETA
    }

    public enum ResourceProfile {
        AUTO,
        SAFE,
        BALANCED,
        PERFORMANCE
    }

    public enum TlsMode {
        GENERATED,
        PROVIDED
    }

    public enum PerformanceProfile {
        SAFE,
        BALANCED,
        PERFORMANCE
    }

    public record TlsConfig(
        TlsMode mode,
        Optional<Path> certificate,
        Optional<Path> privateKey
    ) {
        public TlsConfig {
            Objects.requireNonNull(mode, "mode");
            certificate = Objects.requireNonNull(certificate, "certificate");
            privateKey = Objects.requireNonNull(privateKey, "privateKey");
        }

        @Override
        public String toString() {
            return "TlsConfig[mode=" + mode +
                ", certificateConfigured=" + certificate.isPresent() +
                ", privateKeyConfigured=" + privateKey.isPresent() + "]";
        }
    }

    public record PerformanceConfig(
        PerformanceProfile profile,
        int entityBudget,
        int targetRenderFps,
        boolean tpsLoadShedding
    ) {
        public PerformanceConfig {
            Objects.requireNonNull(profile, "profile");
        }
    }
}
