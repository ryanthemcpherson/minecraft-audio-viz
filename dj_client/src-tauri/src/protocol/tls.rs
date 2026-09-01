//! Strict DJ-server profile parsing and certificate-bound TLS configuration.

use rustls::client::WebPkiServerVerifier;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, WebPkiSupportedAlgorithms};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{
    CertificateError, ClientConfig, DigitallySignedStruct, Error as RustlsError, OtherError,
    RootCertStore, SignatureScheme,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::error::Error as StdError;
use std::fmt;
use std::sync::Arc;
use subtle::ConstantTimeEq;
use thiserror::Error;
use url::Url;
use x509_parser::prelude::FromDer;

const MAX_SERVER_URL_BYTES: usize = 2048;

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum ProfileError {
    #[error("server URL exceeds 2048 bytes")]
    UrlTooLong,
    #[error("server URL is invalid")]
    InvalidUrl,
    #[error("release connections require wss://")]
    SecureTransportRequired,
    #[error("server URL must be credential-free and end exactly in /ws/dj")]
    InvalidEndpoint,
    #[error("certificate fingerprint must be 64 uppercase hexadecimal characters")]
    InvalidFingerprint,
}

#[derive(Debug, Error)]
pub enum TlsConfigError {
    #[error("INVALID_SERVER_PROFILE: {0}")]
    InvalidProfile(#[from] ProfileError),
    #[error("CERTIFICATE_UNTRUSTED: platform trust store is unavailable")]
    NativeRootsUnavailable,
    #[error("CERTIFICATE_UNTRUSTED: no platform trust roots were loaded")]
    NoNativeRoots,
    #[error("CERTIFICATE_UNTRUSTED: TLS verifier configuration failed")]
    VerifierConfiguration,
}

/// A normalized endpoint and optional exact SHA-256 leaf-certificate pin.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ServerProfile {
    server_url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    certificate_sha256: Option<String>,
}

impl ServerProfile {
    pub fn new(server_url: &str, certificate_sha256: Option<&str>) -> Result<Self, ProfileError> {
        if server_url.len() > MAX_SERVER_URL_BYTES {
            return Err(ProfileError::UrlTooLong);
        }
        let parsed = Url::parse(server_url).map_err(|_| ProfileError::InvalidUrl)?;
        let scheme_allowed =
            parsed.scheme() == "wss" || (cfg!(debug_assertions) && parsed.scheme() == "ws");
        if !scheme_allowed {
            return Err(ProfileError::SecureTransportRequired);
        }
        if parsed.host().is_none()
            || !parsed.username().is_empty()
            || parsed.password().is_some()
            || parsed.path() != "/ws/dj"
            || parsed.query().is_some()
            || parsed.fragment().is_some()
        {
            return Err(ProfileError::InvalidEndpoint);
        }

        let normalized_pin = certificate_sha256
            .map(normalize_certificate_sha256)
            .transpose()?;
        Ok(Self {
            server_url: parsed.to_string(),
            certificate_sha256: normalized_pin,
        })
    }

    pub fn server_url(&self) -> &str {
        &self.server_url
    }

    pub fn certificate_sha256(&self) -> Option<&str> {
        self.certificate_sha256.as_deref()
    }

    pub fn profile_key(&self) -> &str {
        &self.server_url
    }

    pub fn is_secure(&self) -> bool {
        self.server_url.starts_with("wss://")
    }
}

impl Default for ServerProfile {
    fn default() -> Self {
        let server_url = if cfg!(debug_assertions) {
            "ws://localhost:9000/ws/dj"
        } else {
            "wss://localhost:9000/ws/dj"
        };
        Self::new(server_url, None).expect("built-in server profile must be valid")
    }
}

pub fn normalize_certificate_sha256(value: &str) -> Result<String, ProfileError> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'A'..=b'F').contains(&byte))
    {
        return Err(ProfileError::InvalidFingerprint);
    }
    Ok(value.to_owned())
}

fn decode_fingerprint(value: &str) -> Result<[u8; 32], ProfileError> {
    let normalized = normalize_certificate_sha256(value)?;
    let mut bytes = [0_u8; 32];
    for (index, output) in bytes.iter_mut().enumerate() {
        let offset = index * 2;
        *output = u8::from_str_radix(&normalized[offset..offset + 2], 16)
            .map_err(|_| ProfileError::InvalidFingerprint)?;
    }
    Ok(bytes)
}

fn crypto_provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::aws_lc_rs::default_provider())
}

#[derive(Debug)]
struct CertificatePinMismatch;

impl fmt::Display for CertificatePinMismatch {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("CERTIFICATE_PIN_MISMATCH")
    }
}

impl StdError for CertificatePinMismatch {}

#[derive(Debug)]
struct PinnedServerVerifier {
    expected_sha256: [u8; 32],
    provider: Arc<CryptoProvider>,
    supported_algorithms: WebPkiSupportedAlgorithms,
}

impl PinnedServerVerifier {
    fn new(expected_sha256: [u8; 32], provider: Arc<CryptoProvider>) -> Self {
        Self {
            expected_sha256,
            supported_algorithms: provider.signature_verification_algorithms,
            provider,
        }
    }
}

impl ServerCertVerifier for PinnedServerVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        server_name: &ServerName<'_>,
        ocsp_response: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, RustlsError> {
        let (_, parsed) = x509_parser::certificate::X509Certificate::from_der(end_entity.as_ref())
            .map_err(|_| RustlsError::InvalidCertificate(CertificateError::BadEncoding))?;
        if parsed.as_ref() != end_entity.as_ref() {
            return Err(RustlsError::InvalidCertificate(
                CertificateError::BadEncoding,
            ));
        }

        let actual_sha256 = Sha256::digest(end_entity.as_ref());
        if actual_sha256
            .as_slice()
            .ct_eq(&self.expected_sha256)
            .unwrap_u8()
            != 1
        {
            return Err(RustlsError::InvalidCertificate(CertificateError::Other(
                OtherError(Arc::new(CertificatePinMismatch)),
            )));
        }

        // A matching pin establishes only the trust anchor. WebPKI still verifies
        // time validity, hostname, key purpose, chain, and handshake signatures.
        let mut roots = RootCertStore::empty();
        roots
            .add(end_entity.clone().into_owned())
            .map_err(|_| RustlsError::InvalidCertificate(CertificateError::BadEncoding))?;
        let verifier =
            WebPkiServerVerifier::builder_with_provider(Arc::new(roots), self.provider.clone())
                .build()
                .map_err(|error| RustlsError::General(error.to_string()))?;
        verifier.verify_server_cert(end_entity, intermediates, server_name, ocsp_response, now)
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        rustls::crypto::verify_tls12_signature(
            message,
            certificate,
            signature,
            &self.supported_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        rustls::crypto::verify_tls13_signature(
            message,
            certificate,
            signature,
            &self.supported_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.supported_algorithms.supported_schemes()
    }
}

pub fn load_native_tls_config(profile: &ServerProfile) -> Result<ClientConfig, TlsConfigError> {
    load_tls_config_with(profile, || {
        let native = rustls_native_certs::load_native_certs();
        if native.certs.is_empty() && !native.errors.is_empty() {
            return Err(TlsConfigError::NativeRootsUnavailable);
        }
        let mut roots = RootCertStore::empty();
        roots.add_parsable_certificates(native.certs);
        if roots.is_empty() {
            return Err(TlsConfigError::NoNativeRoots);
        }
        Ok(roots)
    })
}

pub(crate) fn load_tls_config_with(
    profile: &ServerProfile,
    load_roots: impl FnOnce() -> Result<RootCertStore, TlsConfigError>,
) -> Result<ClientConfig, TlsConfigError> {
    // An administrator-provided pin is a complete trust anchor for this exact
    // server profile. It must remain usable on minimal hosts whose native root
    // store is absent or inaccessible; hostname and validity checks still run
    // in PinnedServerVerifier.
    let roots = if profile.certificate_sha256().is_some() {
        RootCertStore::empty()
    } else {
        load_roots()?
    };
    build_tls_config_with_roots(profile, roots)
}

pub(crate) fn build_tls_config_with_roots(
    profile: &ServerProfile,
    roots: RootCertStore,
) -> Result<ClientConfig, TlsConfigError> {
    let provider = crypto_provider();
    let builder = ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .map_err(|_| TlsConfigError::VerifierConfiguration)?;
    let config = if let Some(pin) = profile.certificate_sha256() {
        let verifier = Arc::new(PinnedServerVerifier::new(
            decode_fingerprint(pin)?,
            provider,
        ));
        builder
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth()
    } else {
        builder.with_root_certificates(roots).with_no_client_auth()
    };
    Ok(config)
}
