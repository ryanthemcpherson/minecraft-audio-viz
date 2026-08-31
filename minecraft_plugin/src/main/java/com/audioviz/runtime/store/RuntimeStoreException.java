package com.audioviz.runtime.store;

public final class RuntimeStoreException extends Exception {
    public enum FailureReason {
        INVALID_ROOT,
        INVALID_VERSION,
        ARCHIVE_NOT_FOUND,
        ARCHIVE_SIZE_MISMATCH,
        ARCHIVE_DIGEST_MISMATCH,
        STAGING_NOT_UNIQUE,
        INVALID_ZIP,
        TOO_MANY_MEMBERS,
        INVALID_MEMBER_PATH,
        DUPLICATE_MEMBER,
        CASE_COLLISION,
        PATH_COLLISION,
        UNSUPPORTED_COMPRESSION,
        ENCRYPTED_MEMBER,
        NON_REGULAR_MEMBER,
        MEMBER_TOO_LARGE,
        TOTAL_TOO_LARGE,
        COMPRESSION_RATIO,
        FILES_MANIFEST_MISSING,
        FILES_MANIFEST_DIGEST,
        FILES_MANIFEST_INVALID,
        UNDECLARED_MEMBER,
        MISSING_MEMBER,
        MEMBER_SIZE_MISMATCH,
        MEMBER_DIGEST_MISMATCH,
        ENTRYPOINT_INVALID,
        PLATFORM_MISMATCH,
        PATH_ESCAPE,
        IO_FAILURE
    }

    private final FailureReason reason;

    public RuntimeStoreException(FailureReason reason) {
        super("MCAV_RUNTIME_STORE_" + reason.name());
        this.reason = reason;
    }

    public RuntimeStoreException(FailureReason reason, Throwable cause) {
        super("MCAV_RUNTIME_STORE_" + reason.name(), cause);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
