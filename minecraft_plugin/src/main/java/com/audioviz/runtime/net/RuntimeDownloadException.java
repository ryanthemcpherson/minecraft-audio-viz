package com.audioviz.runtime.net;

public final class RuntimeDownloadException extends Exception {
    public enum FailureReason {
        INVALID_EXPECTATION,
        INVALID_URI,
        INSECURE_URI,
        HOST_NOT_ALLOWED,
        HTTP_STATUS,
        REDIRECT_LOOP,
        REDIRECT_LIMIT,
        MISSING_REDIRECT_LOCATION,
        INVALID_CONTENT_LENGTH,
        TRUNCATED,
        OVERSIZED,
        HASH_MISMATCH,
        CANCELLED,
        TIMEOUT,
        INTERRUPTED,
        DESTINATION_EXISTS,
        ATOMIC_MOVE_UNSUPPORTED,
        IO_FAILURE
    }

    private final FailureReason reason;

    public RuntimeDownloadException(FailureReason reason) {
        super("MCAV_RUNTIME_DOWNLOAD_" + reason.name());
        this.reason = reason;
    }

    public RuntimeDownloadException(FailureReason reason, Throwable cause) {
        super("MCAV_RUNTIME_DOWNLOAD_" + reason.name(), cause);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
