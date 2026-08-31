package com.audioviz.runtime.release;

public final class ManifestVerificationException extends Exception {
    public enum FailureReason {
        MANIFEST_TOO_LARGE,
        ENVELOPE_TOO_LARGE,
        INVALID_UTF8,
        MALFORMED_JSON,
        DUPLICATE_FIELD,
        UNKNOWN_FIELD,
        MISSING_FIELD,
        INVALID_TYPE,
        OUT_OF_RANGE,
        MISSING_PLATFORM,
        INVALID_URL,
        UNKNOWN_KEY,
        UNSUPPORTED_ALGORITHM,
        MALFORMED_SIGNATURE,
        INVALID_SIGNATURE,
        KEY_ID_MISMATCH,
        EXPIRED,
        NOT_YET_VALID,
        GENERATION_ROLLBACK,
        API_INCOMPATIBLE,
        INTERNAL_CRYPTO_ERROR
    }

    private final FailureReason reason;

    public ManifestVerificationException(FailureReason reason) {
        super("MCAV_RUNTIME_MANIFEST_" + reason.name());
        this.reason = reason;
    }

    public ManifestVerificationException(FailureReason reason, Throwable cause) {
        super("MCAV_RUNTIME_MANIFEST_" + reason.name(), cause);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
