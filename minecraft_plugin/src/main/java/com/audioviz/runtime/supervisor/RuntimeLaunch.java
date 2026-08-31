package com.audioviz.runtime.supervisor;

import com.audioviz.runtime.store.RuntimeVersionId;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record RuntimeLaunch(
    RuntimeVersionId version,
    Path entrypoint,
    Path workingDirectory,
    Path stateDirectory,
    String publicHost,
    int publicPort,
    Map<String, String> environment,
    String launchNonce,
    long generation,
    int runtimeApi
) {
    private static final int MAX_HOST_LENGTH = 253;

    public RuntimeLaunch {
        Objects.requireNonNull(version, "version");
        workingDirectory = normalize(workingDirectory, "workingDirectory");
        entrypoint = normalize(entrypoint, "entrypoint");
        stateDirectory = normalize(stateDirectory, "stateDirectory");
        if (!entrypoint.startsWith(workingDirectory)) {
            throw new IllegalArgumentException("entrypoint must belong to the runtime directory");
        }
        if (publicHost == null || publicHost.isBlank() || publicHost.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("invalid public host");
        }
        if (publicPort <= 0 || publicPort > 65_535) {
            throw new IllegalArgumentException("invalid public port");
        }
        environment = copyEnvironment(environment);
        if (!RuntimeReady.isValidLaunchNonce(launchNonce)) {
            throw new IllegalArgumentException("invalid launch nonce");
        }
        if (generation <= 0 || runtimeApi <= 0) {
            throw new IllegalArgumentException("invalid launch identity");
        }
    }

    @Override
    public Map<String, String> environment() {
        return Map.copyOf(new LinkedHashMap<>(environment));
    }

    @Override
    public String toString() {
        return "RuntimeLaunch[version=" + version +
            ", generation=" + generation +
            ", runtimeApi=" + runtimeApi +
            ", publicHost=" + publicHost +
            ", publicPort=" + publicPort + "]";
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static Map<String, String> copyEnvironment(Map<String, String> source) {
        Objects.requireNonNull(source, "environment");
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (
                !isMcavEnvironmentKey(key) ||
                value == null || value.length() > 8_192
            ) {
                throw new IllegalArgumentException("invalid runtime environment");
            }
            result.put(key, value);
        });
        return Map.copyOf(result);
    }

    private static boolean isMcavEnvironmentKey(String key) {
        if (key == null || !key.startsWith("MCAV_") || key.length() > 128) {
            return false;
        }
        for (int index = 5; index < key.length(); index++) {
            char character = key.charAt(index);
            if (
                (character < 'A' || character > 'Z') &&
                (character < '0' || character > '9') &&
                character != '_'
            ) {
                return false;
            }
        }
        return key.length() > 5;
    }
}
