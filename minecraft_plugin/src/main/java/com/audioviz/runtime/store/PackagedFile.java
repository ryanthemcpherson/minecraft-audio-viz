package com.audioviz.runtime.store;

import java.util.Objects;

public record PackagedFile(String path, long size, String sha256, boolean executable) {
    public PackagedFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256, "sha256");
        if (size < 0) {
            throw new IllegalArgumentException("packaged file size must not be negative");
        }
    }
}
