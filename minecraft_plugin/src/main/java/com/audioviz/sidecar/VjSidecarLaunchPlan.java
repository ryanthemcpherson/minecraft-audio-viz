package com.audioviz.sidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable discovery and command model for the bundled VJ sidecar. */
public final class VjSidecarLaunchPlan {

    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final int DEFAULT_DJ_PORT = 25808;
    private static final int MINECRAFT_PORT = 8765;
    private static final int DEFAULT_METRICS_PORT = 9001;
    private static final int DEFAULT_ENTITY_COUNT = 160;

    private final Path projectRoot;
    private final Path pluginsDirectory;
    private final Path runtime;
    private final String publicHost;
    private final String releaseVersion;
    private final int httpPort;
    private final int djPort;
    private final int metricsPort;
    private final int entityCount;
    private final Map<String, String> childEnvironment;

    private VjSidecarLaunchPlan(
        Path projectRoot,
        Path pluginsDirectory,
        Path runtime,
        String publicHost,
        String releaseVersion,
        int httpPort,
        int djPort,
        int metricsPort,
        int entityCount,
        String sharedSecret
    ) {
        this.projectRoot = projectRoot;
        this.pluginsDirectory = pluginsDirectory;
        this.runtime = runtime;
        this.publicHost = publicHost;
        this.releaseVersion = releaseVersion;
        this.httpPort = httpPort;
        this.djPort = djPort;
        this.metricsPort = metricsPort;
        this.entityCount = entityCount;
        this.childEnvironment = Map.of("MINECRAFT_WS_SECRET", sharedSecret);
    }

    public static VjSidecarLaunchPlan create(
        Path pluginDataDirectory,
        String architecture,
        Map<String, String> environment,
        String sharedSecret
    ) throws IOException {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        Objects.requireNonNull(environment, "environment");
        if (sharedSecret == null || sharedSecret.length() < 32 || sharedSecret.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("The plugin-managed WebSocket secret is invalid");
        }

        Path pluginsDirectory = pluginDataDirectory.toAbsolutePath().normalize().getParent();
        if (pluginsDirectory == null || pluginsDirectory.getParent() == null) {
            throw new IllegalArgumentException("AudioViz plugin directory has no server root");
        }
        Path projectRoot = pluginsDirectory.getParent().resolve("mcav-vj").normalize();
        String runtimeArchitecture = switch (architecture.toLowerCase()) {
            case "amd64", "x86_64" -> "linux-amd64";
            case "arm64", "aarch64" -> "linux-arm64";
            default -> throw new IllegalArgumentException("Unsupported server architecture: " + architecture);
        };
        Path runtime = projectRoot.resolve("bin").resolve(runtimeArchitecture).resolve("audioviz-vj");
        if (!Files.isRegularFile(runtime)) {
            throw new IllegalArgumentException("Bundled VJ runtime is missing: " + runtime);
        }

        Path environmentFile = projectRoot.resolve("mcav.env");
        String publicHost = configuredValue(environment, environmentFile, "MCAV_PUBLIC_HOST", "");
        if (publicHost.isEmpty()) {
            throw new IllegalArgumentException("MCAV_PUBLIC_HOST is not configured");
        }
        int httpPort = configuredInteger(
            environment,
            environmentFile,
            "HTTP_PORT",
            DEFAULT_HTTP_PORT,
            1,
            65_535
        );
        int djPort = configuredInteger(
            environment,
            environmentFile,
            "VJ_SERVER_PORT",
            DEFAULT_DJ_PORT,
            1,
            65_535
        );
        int metricsPort = configuredInteger(
            environment,
            environmentFile,
            "METRICS_PORT",
            DEFAULT_METRICS_PORT,
            1,
            65_535
        );
        int entityCount = configuredInteger(
            environment,
            environmentFile,
            "ENTITY_COUNT",
            DEFAULT_ENTITY_COUNT,
            1,
            10_000
        );
        Path versionFile = projectRoot.resolve("VERSION");
        if (!Files.isRegularFile(versionFile)) {
            throw new IllegalArgumentException("Bundled VJ version file is missing: " + versionFile);
        }
        String releaseVersion = Files.readString(versionFile).strip();
        if (releaseVersion.isEmpty()) {
            throw new IllegalArgumentException("Bundled VJ version is empty");
        }

        return new VjSidecarLaunchPlan(
            projectRoot,
            pluginsDirectory,
            runtime,
            publicHost,
            releaseVersion,
            httpPort,
            djPort,
            metricsPort,
            entityCount,
            sharedSecret
        );
    }

    private static String configuredValue(
        Map<String, String> environment,
        Path environmentFile,
        String key,
        String defaultValue
    ) throws IOException {
        String value = environment.getOrDefault(key, "").strip();
        if (value.isEmpty()) {
            value = readEnvironmentFileValue(environmentFile, key);
        }
        return value.isEmpty() ? defaultValue : value;
    }

    private static int configuredInteger(
        Map<String, String> environment,
        Path environmentFile,
        String key,
        int defaultValue,
        int minimum,
        int maximum
    ) throws IOException {
        String rawValue = configuredValue(
            environment,
            environmentFile,
            key,
            Integer.toString(defaultValue)
        );
        try {
            int value = Integer.parseInt(rawValue);
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                    key + " must be between " + minimum + " and " + maximum
                );
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
    }

    private static String readEnvironmentFileValue(Path path, String key) throws IOException {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        for (String line : Files.readAllLines(path)) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int separator = stripped.indexOf('=');
            if (separator > 0 && stripped.substring(0, separator).strip().equals(key)) {
                return stripped.substring(separator + 1).strip();
            }
        }
        return "";
    }

    public List<String> bootstrapCommand() {
        return List.of(
            runtime.toString(),
            "--bootstrap-pterodactyl",
            "--plugin-managed",
            "--project-root", projectRoot.toString(),
            "--plugins-dir", pluginsDirectory.toString(),
            "--release-version", releaseVersion,
            "--public-host", publicHost,
            "--http-port", Integer.toString(httpPort),
            "--port", Integer.toString(djPort),
            "--unified-web"
        );
    }

    public List<String> serviceCommand() {
        Path identity = projectRoot.resolve("state/current-identity");
        String publicBindHost = publicHost.contains(":") ? "::" : "0.0.0.0";
        String publicAuthority = publicHost.contains(":") ? "[" + publicHost + "]" : publicHost;
        List<String> command = new ArrayList<>();
        command.addAll(List.of(
            runtime.toString(),
            "--project-root", projectRoot.toString(),
            "--minecraft-host", "127.0.0.1",
            "--minecraft-port", Integer.toString(MINECRAFT_PORT),
            "--auth-file", identity.resolve("dj_auth.json").toString(),
            "--http-host", publicBindHost,
            "--http-port", Integer.toString(httpPort),
            "--dj-host", publicBindHost,
            "--port", Integer.toString(djPort),
            "--unified-web",
            "--public-origin", "https://" + publicAuthority + ":" + httpPort,
            "--metrics-port", Integer.toString(metricsPort),
            "--tls-cert", identity.resolve("tls.crt").toString(),
            "--tls-key", identity.resolve("tls.key").toString(),
            "--entities", Integer.toString(entityCount),
            "--no-spectrograph"
        ));
        return List.copyOf(command);
    }

    public boolean validateIdentity() {
        Path identity = projectRoot.resolve("state/current-identity");
        for (String name : List.of("runtime.env", "dj_auth.json", "tls.crt", "tls.key")) {
            if (!Files.isRegularFile(identity.resolve(name))) {
                throw new IllegalStateException("Committed VJ identity is missing: " + identity.resolve(name));
            }
        }
        return true;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path runtime() {
        return runtime;
    }

    public Map<String, String> childEnvironment() {
        return childEnvironment;
    }

    @Override
    public String toString() {
        return "VjSidecarLaunchPlan[projectRoot=" + projectRoot + ", runtime=" + runtime + "]";
    }
}
