use super::client::{ClientError, map_websocket_connection_error};
use super::tls::{
    ProfileError, ServerProfile, TlsConfigError, build_tls_config_with_roots, load_tls_config_with,
    normalize_certificate_sha256,
};
use rustls::RootCertStore;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::{Connector, accept_async, connect_async_tls_with_config};

const PIN: &str = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

#[test]
fn server_profile_requires_exact_websocket_endpoint() {
    let profile = ServerProfile::new("wss://mc.example:8443/ws/dj", Some(PIN)).unwrap();

    assert_eq!(profile.server_url(), "wss://mc.example:8443/ws/dj");
    assert_eq!(profile.certificate_sha256(), Some(PIN));

    for invalid in [
        "https://mc.example:8443/ws/dj",
        "wss://admin:secret@mc.example/ws/dj",
        "wss://mc.example/",
        "wss://mc.example/ws/admin",
        "wss://mc.example/ws/dj?token=secret",
        "wss://mc.example/ws/dj#fragment",
    ] {
        assert!(
            ServerProfile::new(invalid, None).is_err(),
            "unexpectedly allowed {invalid}"
        );
    }
}

#[test]
fn server_profile_rejects_oversized_urls_and_malformed_pins() {
    let oversized = format!("wss://{}.example/ws/dj", "a".repeat(2048));
    assert!(matches!(
        ServerProfile::new(&oversized, None),
        Err(ProfileError::UrlTooLong)
    ));

    for invalid in ["AA", &"a".repeat(64), &"GG".repeat(32)] {
        assert!(normalize_certificate_sha256(invalid).is_err());
    }
}

#[test]
fn pin_is_scoped_to_the_exact_server_profile() {
    let first = ServerProfile::new("wss://one.example/ws/dj", Some(PIN)).unwrap();
    let second = ServerProfile::new("wss://two.example/ws/dj", None).unwrap();

    assert_ne!(first.profile_key(), second.profile_key());
    assert_eq!(first.certificate_sha256(), Some(PIN));
    assert_eq!(second.certificate_sha256(), None);
}

#[test]
fn server_profile_serializes_for_the_tauri_frontend_without_secrets() {
    let profile = ServerProfile::new("wss://mc.example/ws/dj", Some(PIN)).unwrap();

    assert_eq!(
        serde_json::to_value(profile).unwrap(),
        serde_json::json!({
            "serverUrl": "wss://mc.example/ws/dj",
            "certificateSha256": PIN,
        })
    );
}

#[test]
fn no_pin_uses_only_supplied_trust_roots() {
    let profile = ServerProfile::new("wss://mc.example/ws/dj", None).unwrap();
    let roots = RootCertStore::empty();

    let config = build_tls_config_with_roots(&profile, roots).unwrap();

    assert!(config.alpn_protocols.is_empty());
}

#[test]
fn pinned_profile_does_not_require_native_roots() {
    let profile = ServerProfile::new("wss://mc.example/ws/dj", Some(PIN)).unwrap();
    let config = load_tls_config_with(&profile, || Err(TlsConfigError::NativeRootsUnavailable));

    assert!(config.is_ok());
}

#[test]
fn unpinned_profile_reports_unavailable_native_roots() {
    let profile = ServerProfile::new("wss://mc.example/ws/dj", None).unwrap();
    let config = load_tls_config_with(&profile, || Err(TlsConfigError::NativeRootsUnavailable));

    assert!(matches!(
        config,
        Err(TlsConfigError::NativeRootsUnavailable)
    ));
}

#[test]
fn plain_websocket_is_debug_only() {
    let result = ServerProfile::new("ws://127.0.0.1:9000/ws/dj", None);
    if cfg!(debug_assertions) {
        assert!(result.is_ok());
    } else {
        assert!(matches!(result, Err(ProfileError::SecureTransportRequired)));
    }
}

struct TestCertificate {
    certificate: CertificateDer<'static>,
    private_key: Vec<u8>,
    fingerprint: String,
}

fn test_certificate(subject_name: &str, expired: bool) -> TestCertificate {
    let mut parameters = rcgen::CertificateParams::new(vec![subject_name.to_string()]).unwrap();
    if expired {
        parameters.not_before = rcgen::date_time_ymd(2019, 1, 1);
        parameters.not_after = rcgen::date_time_ymd(2020, 1, 1);
    }
    let signing_key = rcgen::KeyPair::generate().unwrap();
    let certificate = parameters.self_signed(&signing_key).unwrap();
    let der = CertificateDer::from(certificate.der().to_vec());
    let fingerprint = Sha256::digest(der.as_ref())
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect();
    TestCertificate {
        certificate: der,
        private_key: signing_key.serialize_der(),
        fingerprint,
    }
}

struct TestTlsServer {
    url: String,
    websocket_handshakes: Arc<AtomicUsize>,
    task: tokio::task::JoinHandle<()>,
}

impl TestTlsServer {
    async fn start(certificate: &TestCertificate) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
        let config = rustls::ServerConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(
                vec![certificate.certificate.clone()],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.private_key.clone())),
            )
            .unwrap();
        let websocket_handshakes = Arc::new(AtomicUsize::new(0));
        let counter = websocket_handshakes.clone();
        let task = tokio::spawn(async move {
            let Ok((socket, _)) = listener.accept().await else {
                return;
            };
            let Ok(tls_stream) = TlsAcceptor::from(Arc::new(config)).accept(socket).await else {
                return;
            };
            if accept_async(tls_stream).await.is_ok() {
                counter.fetch_add(1, Ordering::SeqCst);
            }
        });
        Self {
            url: format!("wss://127.0.0.1:{port}/ws/dj"),
            websocket_handshakes,
            task,
        }
    }

    async fn finish(self) -> usize {
        let _ = tokio::time::timeout(Duration::from_secs(2), self.task).await;
        self.websocket_handshakes.load(Ordering::SeqCst)
    }
}

async fn connect_test_profile(
    profile: &ServerProfile,
    roots: RootCertStore,
) -> Result<(), ClientError> {
    let config = build_tls_config_with_roots(profile, roots)
        .map_err(|error| ClientError::InvalidServerProfile(error.to_string()))?;
    let result = tokio::time::timeout(
        Duration::from_secs(2),
        connect_async_tls_with_config(
            profile.server_url(),
            None,
            false,
            Some(Connector::Rustls(Arc::new(config))),
        ),
    )
    .await
    .map_err(|_| ClientError::ConnectionTimeout)?
    .map_err(map_websocket_connection_error)?;
    drop(result);
    Ok(())
}

#[tokio::test]
async fn trusted_root_connects_without_a_pin() {
    let certificate = test_certificate("127.0.0.1", false);
    let server = TestTlsServer::start(&certificate).await;
    let mut roots = RootCertStore::empty();
    roots.add(certificate.certificate.clone()).unwrap();
    let profile = ServerProfile::new(&server.url, None).unwrap();

    connect_test_profile(&profile, roots).await.unwrap();

    assert_eq!(server.finish().await, 1);
}

#[tokio::test]
async fn correct_pin_connects_to_a_self_signed_server() {
    let certificate = test_certificate("127.0.0.1", false);
    let server = TestTlsServer::start(&certificate).await;
    let profile = ServerProfile::new(&server.url, Some(&certificate.fingerprint)).unwrap();

    connect_test_profile(&profile, RootCertStore::empty())
        .await
        .unwrap();

    assert_eq!(server.finish().await, 1);
}

#[tokio::test]
async fn wrong_pin_sends_no_websocket_handshake() {
    let certificate = test_certificate("127.0.0.1", false);
    let server = TestTlsServer::start(&certificate).await;
    let profile = ServerProfile::new(&server.url, Some(PIN)).unwrap();

    let error = connect_test_profile(&profile, RootCertStore::empty()).await;

    assert!(matches!(error, Err(ClientError::CertificatePinMismatch)));
    assert_eq!(server.finish().await, 0);
}

#[tokio::test]
async fn no_pin_rejects_a_self_signed_server() {
    let certificate = test_certificate("127.0.0.1", false);
    let server = TestTlsServer::start(&certificate).await;
    let profile = ServerProfile::new(&server.url, None).unwrap();

    let error = connect_test_profile(&profile, RootCertStore::empty()).await;

    assert!(matches!(error, Err(ClientError::CertificateUntrusted)));
    assert_eq!(server.finish().await, 0);
}

#[tokio::test]
async fn correct_pin_does_not_bypass_hostname_validation() {
    let certificate = test_certificate("other.example", false);
    let server = TestTlsServer::start(&certificate).await;
    let profile = ServerProfile::new(&server.url, Some(&certificate.fingerprint)).unwrap();

    let error = connect_test_profile(&profile, RootCertStore::empty()).await;

    assert!(matches!(
        error,
        Err(ClientError::CertificateHostnameMismatch)
    ));
    assert_eq!(server.finish().await, 0);
}

#[tokio::test]
async fn correct_pin_does_not_bypass_expiration_validation() {
    let certificate = test_certificate("127.0.0.1", true);
    let server = TestTlsServer::start(&certificate).await;
    let profile = ServerProfile::new(&server.url, Some(&certificate.fingerprint)).unwrap();

    let error = connect_test_profile(&profile, RootCertStore::empty()).await;

    assert!(matches!(error, Err(ClientError::CertificateExpired)));
    assert_eq!(server.finish().await, 0);
}

#[tokio::test]
async fn changed_certificate_invalidates_the_saved_pin() {
    let original = test_certificate("127.0.0.1", false);
    let replacement = test_certificate("127.0.0.1", false);
    let server = TestTlsServer::start(&replacement).await;
    let profile = ServerProfile::new(&server.url, Some(&original.fingerprint)).unwrap();

    let error = connect_test_profile(&profile, RootCertStore::empty()).await;

    assert!(matches!(error, Err(ClientError::CertificatePinMismatch)));
    assert_eq!(server.finish().await, 0);
}
