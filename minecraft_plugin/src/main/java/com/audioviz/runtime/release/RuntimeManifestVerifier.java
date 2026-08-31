package com.audioviz.runtime.release;

import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.API_INCOMPATIBLE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.EXPIRED;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.GENERATION_ROLLBACK;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.INTERNAL_CRYPTO_ERROR;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.INVALID_SIGNATURE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.INVALID_URL;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.KEY_ID_MISMATCH;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.MANIFEST_TOO_LARGE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.NOT_YET_VALID;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.OUT_OF_RANGE;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.UNKNOWN_KEY;
import static com.audioviz.runtime.release.ManifestVerificationException.FailureReason.UNSUPPORTED_ALGORITHM;

import com.audioviz.runtime.RuntimeLimits;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public final class RuntimeManifestVerifier {
    private final RuntimeLimits limits;

    public RuntimeManifestVerifier(RuntimeLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public RuntimeManifest verify(
        byte[] manifestBytes,
        byte[] envelopeBytes,
        ReleaseDescriptor descriptor,
        Instant now
    ) throws ManifestVerificationException {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(now, "now");
        if (descriptor.trustedKeys().size() > limits.maximumTrustedKeys()) {
            throw failure(OUT_OF_RANGE);
        }
        if (
            manifestBytes == null
                || manifestBytes.length == 0
                || manifestBytes.length > limits.maximumManifestBytes()
        ) {
            throw failure(MANIFEST_TOO_LARGE);
        }

        SignatureEnvelope envelope = StrictJson.readSignatureEnvelope(envelopeBytes, limits);
        if (!"Ed25519".equals(envelope.algorithm())) {
            throw failure(UNSUPPORTED_ALGORITHM);
        }
        PublicKey key = descriptor.trustedKeys().get(envelope.keyId());
        if (key == null) {
            throw failure(UNKNOWN_KEY);
        }
        verifySignature(key, manifestBytes, envelope.signature());

        RuntimeManifest manifest = StrictJson.readManifest(manifestBytes, limits);
        validateTrustedFields(manifest, envelope, descriptor, now);
        return manifest;
    }

    private static void verifySignature(PublicKey key, byte[] payload, byte[] signatureBytes)
        throws ManifestVerificationException {
        if (payload == null) {
            throw failure(INVALID_SIGNATURE);
        }
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update(payload);
            if (!signature.verify(signatureBytes)) {
                throw failure(INVALID_SIGNATURE);
            }
        } catch (ManifestVerificationException error) {
            throw error;
        } catch (GeneralSecurityException error) {
            throw failure(INTERNAL_CRYPTO_ERROR, error);
        }
    }

    private static void validateTrustedFields(
        RuntimeManifest manifest,
        SignatureEnvelope envelope,
        ReleaseDescriptor descriptor,
        Instant now
    ) throws ManifestVerificationException {
        if (!manifest.signingKeyId().equals(envelope.keyId())) {
            throw failure(KEY_ID_MISMATCH);
        }
        if (manifest.generation() < descriptor.minimumGeneration()) {
            throw failure(GENERATION_ROLLBACK);
        }
        if (
            descriptor.runtimeApi() < manifest.runtimeApiMin()
                || descriptor.runtimeApi() > manifest.runtimeApiMax()
        ) {
            throw failure(API_INCOMPATIBLE);
        }
        if (now.isBefore(manifest.publishedAt())) {
            throw failure(NOT_YET_VALID);
        }
        if (!now.isBefore(manifest.expiresAt())) {
            throw failure(EXPIRED);
        }
        for (RuntimePlatform platform : RuntimePlatform.values()) {
            RuntimeArtifact artifact = manifest.artifacts().get(platform);
            if (artifact == null) {
                throw failure(ManifestVerificationException.FailureReason.MISSING_PLATFORM);
            }
            validateArtifactUri(artifact.url(), descriptor);
        }
    }

    private static void validateArtifactUri(URI uri, ReleaseDescriptor descriptor)
        throws ManifestVerificationException {
        String host = uri.getHost();
        if (
            !"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !descriptor.allowedHosts().contains(host.toLowerCase(Locale.ROOT))
        ) {
            throw failure(INVALID_URL);
        }
    }

    private static ManifestVerificationException failure(
        ManifestVerificationException.FailureReason reason
    ) {
        return new ManifestVerificationException(reason);
    }

    private static ManifestVerificationException failure(
        ManifestVerificationException.FailureReason reason,
        Throwable cause
    ) {
        return new ManifestVerificationException(reason, cause);
    }
}
