package com.audioviz.runtime.store;

import com.audioviz.runtime.release.RuntimePlatform;
import java.util.Objects;

public record RuntimeVersionId(String releaseVersion, RuntimePlatform platform) {
    public RuntimeVersionId {
        Objects.requireNonNull(platform, "platform");
        if (!isSafeVersion(releaseVersion)) {
            throw new IllegalArgumentException("MCAV_RUNTIME_STORE_INVALID_VERSION");
        }
    }

    public static RuntimeVersionId of(String releaseVersion, RuntimePlatform platform) {
        return new RuntimeVersionId(releaseVersion, platform);
    }

    public String directoryName() {
        return releaseVersion + "--" + platform.wireName();
    }

    private static boolean isSafeVersion(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean alphaNumeric = (character >= '0' && character <= '9') ||
                (character >= 'A' && character <= 'Z') ||
                (character >= 'a' && character <= 'z');
            if (!alphaNumeric && (index == 0 || (character != '.' && character != '_' && character != '-'))) {
                return false;
            }
        }
        return true;
    }
}
