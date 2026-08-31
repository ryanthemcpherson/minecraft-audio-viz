package com.audioviz.runtime.supervisor;

import java.util.Objects;
import java.util.Set;

public record RuntimeReady(
    String releaseVersion,
    int runtimeApi,
    long generation,
    String launchNonce,
    long pid,
    Set<String> capabilities
) {
    public RuntimeReady {
        if (releaseVersion == null || releaseVersion.isBlank() || releaseVersion.length() > 64) {
            throw new IllegalArgumentException("invalid release version");
        }
        if (runtimeApi <= 0 || generation <= 0 || pid <= 0) {
            throw new IllegalArgumentException("invalid runtime readiness identity");
        }
        if (launchNonce == null || launchNonce.length() < 16 || launchNonce.length() > 128) {
            throw new IllegalArgumentException("invalid launch nonce");
        }
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (capabilities.size() > 32 || capabilities.stream().anyMatch(RuntimeReady::invalidCapability)) {
            throw new IllegalArgumentException("invalid runtime capabilities");
        }
    }

    @Override
    public String toString() {
        return "RuntimeReady[releaseVersion=" + releaseVersion +
            ", runtimeApi=" + runtimeApi +
            ", generation=" + generation +
            ", pid=" + pid +
            ", capabilityCount=" + capabilities.size() + "]";
    }

    private static boolean invalidCapability(String capability) {
        if (capability == null || capability.isBlank() || capability.length() > 64) {
            return true;
        }
        for (int index = 0; index < capability.length(); index++) {
            char character = capability.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z') ||
                (character >= 'A' && character <= 'Z') ||
                (character >= '0' && character <= '9') ||
                character == '.' || character == '_' || character == '-';
            if (!allowed) {
                return true;
            }
        }
        return false;
    }
}
