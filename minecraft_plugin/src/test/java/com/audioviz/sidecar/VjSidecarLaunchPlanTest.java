package com.audioviz.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VjSidecarLaunchPlanTest {

    private static final String SECRET = "plugin-managed-secret-0123456789-abcdefgh";

    @TempDir
    Path temporaryDirectory;

    @Test
    void selectsRuntimeAndKeepsSecretOutOfArguments() throws Exception {
        Path pluginData = prepareBundle("linux-amd64");

        VjSidecarLaunchPlan plan = VjSidecarLaunchPlan.create(
            pluginData,
            "x86_64",
            Map.of("MCAV_PUBLIC_HOST", "8.8.8.8"),
            SECRET
        );

        assertTrue(plan.runtime().endsWith("bin/linux-amd64/audioviz-vj"));
        assertTrue(plan.bootstrapCommand().contains("--plugin-managed"));
        assertTrue(plan.serviceCommand().contains("https://8.8.8.8:8080"));
        assertTrue(plan.serviceCommand().contains("127.0.0.1"));
        assertEquals(SECRET, plan.childEnvironment().get("MINECRAFT_WS_SECRET"));
        assertFalse(String.join(" ", plan.bootstrapCommand()).contains(SECRET));
        assertFalse(String.join(" ", plan.serviceCommand()).contains(SECRET));
        assertFalse(plan.toString().contains(SECRET));
    }

    @Test
    void selectsArmRuntimeAndIpv6Bind() throws Exception {
        Path pluginData = prepareBundle("linux-arm64");

        VjSidecarLaunchPlan plan = VjSidecarLaunchPlan.create(
            pluginData,
            "aarch64",
            Map.of("MCAV_PUBLIC_HOST", "2606:4700:4700::1111"),
            SECRET
        );

        assertTrue(plan.runtime().endsWith("bin/linux-arm64/audioviz-vj"));
        assertTrue(plan.serviceCommand().contains("::"));
        assertTrue(plan.serviceCommand().contains("https://[2606:4700:4700::1111]:8080"));
    }

    @Test
    void rejectsMissingPublicHostAndUnsupportedArchitecture() throws Exception {
        Path pluginData = prepareBundle("linux-amd64");

        assertThrows(
            IllegalArgumentException.class,
            () -> VjSidecarLaunchPlan.create(pluginData, "x86_64", Map.of(), SECRET)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> VjSidecarLaunchPlan.create(
                pluginData,
                "riscv64",
                Map.of("MCAV_PUBLIC_HOST", "8.8.8.8"),
                SECRET
            )
        );
    }

    @Test
    void validatesCommittedIdentityFiles() throws Exception {
        Path pluginData = prepareBundle("linux-amd64");
        VjSidecarLaunchPlan plan = VjSidecarLaunchPlan.create(
            pluginData,
            "amd64",
            Map.of("MCAV_PUBLIC_HOST", "8.8.8.8"),
            SECRET
        );

        assertThrows(IllegalStateException.class, plan::validateIdentity);
        Path identity = plan.projectRoot().resolve("state/current-identity");
        Files.createDirectories(identity);
        for (String name : new String[] {"runtime.env", "dj_auth.json", "tls.crt", "tls.key"}) {
            Files.writeString(identity.resolve(name), "fixture");
        }
        assertTrue(plan.validateIdentity());
    }

    private Path prepareBundle(String architecture) throws Exception {
        Path plugins = temporaryDirectory.resolve("plugins");
        Path pluginData = plugins.resolve("AudioViz");
        Path projectRoot = temporaryDirectory.resolve("mcav-vj");
        Path runtime = projectRoot.resolve("bin").resolve(architecture).resolve("audioviz-vj");
        Files.createDirectories(pluginData);
        Files.createDirectories(runtime.getParent());
        Files.writeString(runtime, "runtime");
        Files.writeString(projectRoot.resolve("VERSION"), "26.2-event-rc2");
        return pluginData;
    }
}
