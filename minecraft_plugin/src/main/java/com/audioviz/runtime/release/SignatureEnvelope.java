package com.audioviz.runtime.release;

import java.util.Objects;

public record SignatureEnvelope(int schemaVersion, String algorithm, String keyId, byte[] signature) {
    public SignatureEnvelope {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(keyId, "keyId");
        signature = signature.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }
}
