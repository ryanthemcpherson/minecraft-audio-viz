package com.audioviz.runtime.store;

import com.audioviz.runtime.RuntimeLimits;
import com.audioviz.runtime.release.RuntimeArtifact;
import com.audioviz.runtime.release.RuntimePlatform;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Test-support CLI used by the packaged-runtime integration harness. */
public final class RuntimeArchiveVerifierCli {
    private RuntimeArchiveVerifierCli() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 8) {
            throw new IllegalArgumentException(
                "archive root platform archive-size uncompressed-size archive-sha " +
                    "files-manifest-sha entrypoint"
            );
        }
        Path sourceArchive = Path.of(arguments[0]).toAbsolutePath().normalize();
        RuntimePaths paths = RuntimePaths.create(Path.of(arguments[1]));
        RuntimePlatform platform = platform(arguments[2]);
        Path verifiedArchive = paths.downloads().resolve("runtime.zip");
        Files.copy(sourceArchive, verifiedArchive, StandardCopyOption.REPLACE_EXISTING);
        RuntimeArtifact artifact = new RuntimeArtifact(
            platform,
            URI.create("https://runtime.invalid/runtime.zip"),
            Long.parseLong(arguments[3]),
            Long.parseLong(arguments[4]),
            arguments[5],
            arguments[7],
            arguments[6]
        );
        RuntimeArchiveVerifier.VerifiedRuntimeLayout layout = new RuntimeArchiveVerifier(
            RuntimeLimits.releaseDefaults(),
            paths
        ).extract(verifiedArchive, paths.newStaging(), artifact, platform);
        System.out.println(layout.root());
        System.out.println(layout.entrypoint());
    }

    private static RuntimePlatform platform(String wireName) {
        return switch (wireName) {
            case "linux-x86_64" -> RuntimePlatform.LINUX_X86_64;
            case "linux-aarch64" -> RuntimePlatform.LINUX_AARCH64;
            case "windows-x86_64" -> RuntimePlatform.WINDOWS_X86_64;
            default -> throw new IllegalArgumentException("unsupported runtime platform");
        };
    }
}
