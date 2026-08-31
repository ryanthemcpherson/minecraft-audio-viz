package com.audioviz.runtime;

public record RuntimeLimits(
    int maximumManifestBytes,
    int maximumSignatureEnvelopeBytes,
    int maximumTrustedKeys,
    int maximumArtifacts,
    int maximumIdentifierCharacters,
    int maximumVersionCharacters,
    int maximumEntrypointCharacters,
    long maximumArchiveBytes,
    long maximumExtractedBytes,
    int maximumArchiveMembers,
    int maximumMemberPathBytes,
    long maximumMemberBytes,
    int maximumCompressionRatio,
    int maximumJsonDepth
) {
    public RuntimeLimits {
        if (
            maximumManifestBytes <= 0
                || maximumSignatureEnvelopeBytes <= 0
                || maximumTrustedKeys <= 0
                || maximumArtifacts <= 0
                || maximumIdentifierCharacters <= 0
                || maximumVersionCharacters <= 0
                || maximumEntrypointCharacters <= 0
                || maximumArchiveBytes <= 0
                || maximumExtractedBytes <= 0
                || maximumArchiveMembers <= 0
                || maximumMemberPathBytes <= 0
                || maximumMemberBytes <= 0
                || maximumCompressionRatio <= 0
                || maximumJsonDepth <= 0
        ) {
            throw new IllegalArgumentException("runtime limits must be positive");
        }
    }

    public static RuntimeLimits releaseDefaults() {
        return new RuntimeLimits(
            1024 * 1024,
            4 * 1024,
            8,
            3,
            128,
            128,
            128,
            2L * 1024 * 1024 * 1024,
            4L * 1024 * 1024 * 1024,
            16_384,
            512,
            512L * 1024 * 1024,
            100,
            8
        );
    }
}
