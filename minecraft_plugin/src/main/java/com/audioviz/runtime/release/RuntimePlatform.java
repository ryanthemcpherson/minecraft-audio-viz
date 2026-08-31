package com.audioviz.runtime.release;

import java.util.Locale;
import java.util.Set;

public enum RuntimePlatform {
    LINUX_X86_64("linux-x86_64"),
    LINUX_AARCH64("linux-aarch64"),
    WINDOWS_X86_64("windows-x86_64");

    private static final Set<String> X86_64_ALIASES = Set.of("amd64", "x86_64");
    private static final Set<String> AARCH64_ALIASES = Set.of("aarch64", "arm64");

    private final String wireName;

    RuntimePlatform(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static RuntimePlatform detect(String osName, String osArch) {
        String os = normalize(osName);
        String arch = normalize(osArch);
        if (os.contains("linux") && X86_64_ALIASES.contains(arch)) {
            return LINUX_X86_64;
        }
        if (os.contains("linux") && AARCH64_ALIASES.contains(arch)) {
            return LINUX_AARCH64;
        }
        if (os.contains("windows") && X86_64_ALIASES.contains(arch)) {
            return WINDOWS_X86_64;
        }
        throw new UnsupportedOperationException("MCAV_RUNTIME_UNSUPPORTED_PLATFORM");
    }

    static RuntimePlatform fromWireName(String wireName) {
        for (RuntimePlatform platform : values()) {
            if (platform.wireName.equals(wireName)) {
                return platform;
            }
        }
        throw new IllegalArgumentException("unsupported runtime platform");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
