package com.audioviz.runtime.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuntimePlatformTest {

    @ParameterizedTest
    @MethodSource("supportedPlatforms")
    void detectsSupportedPlatformAliases(String osName, String osArch, RuntimePlatform expected) {
        assertEquals(expected, RuntimePlatform.detect(osName, osArch));
    }

    @ParameterizedTest
    @MethodSource("unsupportedPlatforms")
    void rejectsUnsupportedPlatforms(String osName, String osArch) {
        UnsupportedOperationException failure = assertThrows(
            UnsupportedOperationException.class,
            () -> RuntimePlatform.detect(osName, osArch)
        );

        assertEquals("MCAV_RUNTIME_UNSUPPORTED_PLATFORM", failure.getMessage());
    }

    private static Stream<Arguments> supportedPlatforms() {
        return Stream.of(
            Arguments.of("Linux", "amd64", RuntimePlatform.LINUX_X86_64),
            Arguments.of("GNU/Linux", "x86_64", RuntimePlatform.LINUX_X86_64),
            Arguments.of("linux", "aarch64", RuntimePlatform.LINUX_AARCH64),
            Arguments.of("LINUX", "arm64", RuntimePlatform.LINUX_AARCH64),
            Arguments.of("Windows 11", "AMD64", RuntimePlatform.WINDOWS_X86_64),
            Arguments.of("Windows Server 2025", "x86_64", RuntimePlatform.WINDOWS_X86_64)
        );
    }

    private static Stream<Arguments> unsupportedPlatforms() {
        return Stream.of(
            Arguments.of("Mac OS X", "aarch64"),
            Arguments.of("Linux", "x86"),
            Arguments.of("Windows 11", "arm64"),
            Arguments.of("FreeBSD", "amd64"),
            Arguments.of("", "amd64")
        );
    }
}
