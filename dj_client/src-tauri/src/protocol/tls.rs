use super::client::ClientError;
use native_tls::TlsConnector;
use rustls_pki_types::{CertificateDer as PkiCertificateDer, ServerName};
use sha2::{Digest, Sha256};
use std::net::Ipv6Addr;
use subtle::ConstantTimeEq;
use tokio::net::TcpStream;
use tokio_native_tls::TlsConnector as TokioTlsConnector;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::{
    MaybeTlsStream, WebSocketStream, client_async_with_config, connect_async_tls_with_config,
};
use webpki::EndEntityCert;

/// Parse a SHA-256 certificate fingerprint into its 32-byte representation.
///
/// ASCII whitespace and colon separators are accepted. Every other character
/// must be ASCII hexadecimal, and exactly 64 hexadecimal characters are
/// required.
pub fn normalize_sha256_fingerprint(value: &str) -> Result<[u8; 32], ClientError> {
    let normalized: String = value
        .chars()
        .filter(|character| !character.is_ascii_whitespace() && *character != ':')
        .collect();
    if normalized.len() != 64 || !normalized.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(ClientError::InvalidTlsFingerprint);
    }

    let mut output = [0_u8; 32];
    for (index, slot) in output.iter_mut().enumerate() {
        *slot = u8::from_str_radix(&normalized[index * 2..index * 2 + 2], 16)
            .map_err(|_| ClientError::InvalidTlsFingerprint)?;
    }
    Ok(output)
}

/// Establish a WebSocket connection with optional explicit certificate pinning.
///
/// Pinned connections complete the TLS handshake and validate the peer leaf
/// certificate before sending the HTTP WebSocket upgrade. Unpinned connections
/// retain the platform trust-store behavior provided by tokio-tungstenite.
pub async fn connect_verified(
    url: &str,
    websocket_config: WebSocketConfig,
    expected_fingerprint: Option<&str>,
) -> Result<WebSocketStream<MaybeTlsStream<TcpStream>>, ClientError> {
    let Some(expected_fingerprint) = expected_fingerprint else {
        let (websocket, _) =
            connect_async_tls_with_config(url, Some(websocket_config), false, None)
                .await
                .map_err(map_websocket_connection_error)?;
        return Ok(websocket);
    };

    let expected_bytes = normalize_sha256_fingerprint(expected_fingerprint)?;
    let request = url
        .into_client_request()
        .map_err(|_| ClientError::ConnectionFailed("Invalid WebSocket server URL".to_string()))?;
    if request.uri().scheme_str() != Some("wss") {
        return Err(ClientError::MissingPeerCertificate);
    }

    let uri_host = request.uri().host().ok_or_else(|| {
        ClientError::ConnectionFailed("WebSocket server URL has no hostname".to_string())
    })?;
    let tls_host = uri_host
        .strip_prefix('[')
        .and_then(|host| host.strip_suffix(']'))
        .unwrap_or(uri_host);
    let port = request.uri().port_u16().unwrap_or(443);
    let socket_address = if tls_host.parse::<Ipv6Addr>().is_ok() {
        format!("[{tls_host}]:{port}")
    } else {
        format!("{tls_host}:{port}")
    };

    let mut connector_builder = TlsConnector::builder();
    connector_builder
        .danger_accept_invalid_certs(true)
        .danger_accept_invalid_hostnames(false);
    let connector = connector_builder
        .build()
        .map_err(|error| ClientError::TlsHandshake(error.to_string()))?;

    let tcp_stream = TcpStream::connect(socket_address)
        .await
        .map_err(|error| ClientError::ConnectionFailed(error.to_string()))?;
    let tls_stream = TokioTlsConnector::from(connector)
        .connect(tls_host, tcp_stream)
        .await
        .map_err(|error| ClientError::TlsHandshake(error.to_string()))?;

    let certificate = tls_stream
        .get_ref()
        .peer_certificate()
        .map_err(|error| ClientError::TlsHandshake(error.to_string()))?
        .ok_or(ClientError::MissingPeerCertificate)?;
    let certificate_der = certificate
        .to_der()
        .map_err(|error| ClientError::TlsHandshake(error.to_string()))?;
    verify_certificate_host(&certificate_der, tls_host)?;
    let observed_bytes: [u8; 32] = Sha256::digest(&certificate_der).into();
    if !fingerprints_match(&expected_bytes, &observed_bytes) {
        return Err(ClientError::TlsFingerprintMismatch {
            expected: format_fingerprint(&expected_bytes),
            observed: format_fingerprint(&observed_bytes),
        });
    }

    let tls_stream = MaybeTlsStream::NativeTls(tls_stream);
    let (websocket, _) = client_async_with_config(request, tls_stream, Some(websocket_config))
        .await
        .map_err(map_websocket_connection_error)?;
    Ok(websocket)
}

fn fingerprints_match(expected: &[u8; 32], observed: &[u8; 32]) -> bool {
    bool::from(expected.ct_eq(observed))
}

fn map_websocket_connection_error(error: tokio_tungstenite::tungstenite::Error) -> ClientError {
    if matches!(&error, tokio_tungstenite::tungstenite::Error::Tls(_)) {
        ClientError::TlsHandshake(error.to_string())
    } else {
        ClientError::ConnectionFailed(error.to_string())
    }
}

fn verify_certificate_host(certificate_der: &[u8], host: &str) -> Result<(), ClientError> {
    let certificate_der = PkiCertificateDer::from(certificate_der);
    let certificate = EndEntityCert::try_from(&certificate_der).map_err(|_| {
        ClientError::TlsHandshake("TLS peer certificate is not valid X.509 DER".to_string())
    })?;
    let server_name = ServerName::try_from(host).map_err(|_| {
        ClientError::ConnectionFailed("WebSocket server hostname is invalid".to_string())
    })?;
    certificate
        .verify_is_valid_for_subject_name(&server_name)
        .map_err(|_| {
            ClientError::TlsHandshake(
                "TLS certificate is not valid for the requested server host".to_string(),
            )
        })
}

fn format_fingerprint(bytes: &[u8; 32]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::super::client::{DjClient, DjClientConfig};
    use super::*;
    use futures_util::StreamExt;
    use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair};
    use sha2::{Digest, Sha256};
    use std::pin::Pin;
    use std::sync::Arc;
    use std::sync::Mutex as StdMutex;
    use std::task::{Context, Poll};
    use std::time::Duration;
    use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
    use tokio::net::TcpListener;
    use tokio::sync::{Mutex, mpsc, oneshot, watch};
    use tokio::task::JoinHandle;
    use tokio_rustls::TlsAcceptor;
    use tokio_rustls::rustls::ServerConfig;
    use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
    use tokio_tungstenite::{accept_async, tungstenite::Message};

    const LOWERCASE_FINGERPRINT: &str =
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    struct ApplicationFrameRecorder {
        received: Arc<Mutex<Vec<Message>>>,
        received_application_bytes: Arc<StdMutex<Vec<u8>>>,
        shutdown_tx: Mutex<Option<oneshot::Sender<()>>>,
        server_task: Mutex<Option<JoinHandle<()>>>,
        read_ready_rx: watch::Receiver<bool>,
        read_complete_rx: watch::Receiver<bool>,
    }

    impl ApplicationFrameRecorder {
        fn new(
            received: Arc<Mutex<Vec<Message>>>,
            received_application_bytes: Arc<StdMutex<Vec<u8>>>,
            shutdown_tx: oneshot::Sender<()>,
            server_task: JoinHandle<()>,
            read_ready_rx: watch::Receiver<bool>,
            read_complete_rx: watch::Receiver<bool>,
        ) -> Self {
            Self {
                received,
                received_application_bytes,
                shutdown_tx: Mutex::new(Some(shutdown_tx)),
                server_task: Mutex::new(Some(server_task)),
                read_ready_rx,
                read_complete_rx,
            }
        }

        async fn stop_and_read(&self) -> Vec<Message> {
            self.stop().await;
            self.received.lock().await.clone()
        }

        async fn stop_and_read_application_bytes(&self) -> Vec<u8> {
            self.stop().await;
            self.received_application_bytes
                .lock()
                .expect("test byte recorder mutex should not be poisoned")
                .clone()
        }

        async fn stop(&self) {
            let read_ready =
                Self::wait_for_signal(self.read_ready_rx.clone(), Duration::from_secs(1)).await;
            if read_ready {
                assert!(
                    Self::wait_for_signal(self.read_complete_rx.clone(), Duration::from_secs(1),)
                        .await,
                    "test server must finish reading the HTTP upgrade or post-TLS EOF"
                );
            }

            if let Some(shutdown_tx) = self.shutdown_tx.lock().await.take() {
                let _ = shutdown_tx.send(());
            }
            if let Some(mut server_task) = self.server_task.lock().await.take() {
                match tokio::time::timeout(Duration::from_secs(1), &mut server_task).await {
                    Ok(join_result) => {
                        join_result.expect("test WebSocket server task should not panic")
                    }
                    Err(_) => {
                        server_task.abort();
                        let join_error = server_task
                            .await
                            .expect_err("aborted test WebSocket server task should be cancelled");
                        assert!(join_error.is_cancelled());
                    }
                }
            }
        }

        async fn wait_for_read_ready(&self) -> bool {
            Self::wait_for_signal(self.read_ready_rx.clone(), Duration::from_secs(1)).await
        }

        async fn wait_for_signal(
            mut signal_rx: watch::Receiver<bool>,
            timeout_duration: Duration,
        ) -> bool {
            tokio::time::timeout(timeout_duration, async move {
                loop {
                    if *signal_rx.borrow() {
                        return true;
                    }
                    if signal_rx.changed().await.is_err() {
                        return false;
                    }
                }
            })
            .await
            .unwrap_or(false)
        }
    }

    struct RecordingStream<Stream> {
        inner: Stream,
        received_application_bytes: Arc<StdMutex<Vec<u8>>>,
    }

    impl<Stream> RecordingStream<Stream> {
        fn new(inner: Stream, received_application_bytes: Arc<StdMutex<Vec<u8>>>) -> Self {
            Self {
                inner,
                received_application_bytes,
            }
        }
    }

    impl<Stream> AsyncRead for RecordingStream<Stream>
    where
        Stream: AsyncRead + Unpin,
    {
        fn poll_read(
            self: Pin<&mut Self>,
            context: &mut Context<'_>,
            buffer: &mut ReadBuf<'_>,
        ) -> Poll<std::io::Result<()>> {
            let this = self.get_mut();
            let previous_length = buffer.filled().len();
            let result = Pin::new(&mut this.inner).poll_read(context, buffer);
            if matches!(result, Poll::Ready(Ok(()))) {
                this.received_application_bytes
                    .lock()
                    .expect("test byte recorder mutex should not be poisoned")
                    .extend_from_slice(&buffer.filled()[previous_length..]);
            }
            result
        }
    }

    impl<Stream> AsyncWrite for RecordingStream<Stream>
    where
        Stream: AsyncWrite + Unpin,
    {
        fn poll_write(
            self: Pin<&mut Self>,
            context: &mut Context<'_>,
            buffer: &[u8],
        ) -> Poll<std::io::Result<usize>> {
            Pin::new(&mut self.get_mut().inner).poll_write(context, buffer)
        }

        fn poll_flush(
            self: Pin<&mut Self>,
            context: &mut Context<'_>,
        ) -> Poll<std::io::Result<()>> {
            Pin::new(&mut self.get_mut().inner).poll_flush(context)
        }

        fn poll_shutdown(
            self: Pin<&mut Self>,
            context: &mut Context<'_>,
        ) -> Poll<std::io::Result<()>> {
            Pin::new(&mut self.get_mut().inner).poll_shutdown(context)
        }
    }

    struct TlsWebSocketFixture {
        url: String,
        port: u16,
        fingerprint: String,
        recorder: ApplicationFrameRecorder,
        tcp_accepted_rx: Mutex<Option<oneshot::Receiver<()>>>,
        release_tls_tx: Mutex<Option<oneshot::Sender<()>>>,
    }

    impl TlsWebSocketFixture {
        async fn start() -> Self {
            Self::start_with_subject_alt_name("127.0.0.1").await
        }

        async fn start_with_subject_alt_name(subject_alt_name: &str) -> Self {
            Self::start_with_identity("127.0.0.1", subject_alt_name, None).await
        }

        async fn start_with_identity(
            url_host: &str,
            subject_alt_name: &str,
            common_name: Option<&str>,
        ) -> Self {
            Self::start_configured(url_host, subject_alt_name, common_name, false).await
        }

        async fn start_with_delayed_tls() -> Self {
            Self::start_configured("127.0.0.1", "127.0.0.1", None, true).await
        }

        async fn start_configured(
            url_host: &str,
            subject_alt_name: &str,
            common_name: Option<&str>,
            delay_tls_handshake: bool,
        ) -> Self {
            let key_pair = KeyPair::generate().expect("test key should generate");
            let mut certificate_params = CertificateParams::new(vec![subject_alt_name.to_string()])
                .expect("test certificate parameters should parse");
            if let Some(common_name) = common_name {
                let mut distinguished_name = DistinguishedName::new();
                distinguished_name.push(DnType::CommonName, common_name);
                certificate_params.distinguished_name = distinguished_name;
            }
            let cert = certificate_params
                .self_signed(&key_pair)
                .expect("test certificate should generate");
            let certificate_der = cert.der().to_vec();
            let private_key_der =
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_pair.serialize_der()));
            let tls_config = ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(
                    vec![CertificateDer::from(certificate_der.clone())],
                    private_key_der,
                )
                .expect("test TLS server config should build");
            let tls_acceptor = TlsAcceptor::from(Arc::new(tls_config));
            let fingerprint = format_fingerprint(&Sha256::digest(&certificate_der));

            let listener = TcpListener::bind(("127.0.0.1", 0))
                .await
                .expect("test listener should bind");
            let port = listener
                .local_addr()
                .expect("test listener should have an address")
                .port();
            let received = Arc::new(Mutex::new(Vec::new()));
            let received_by_server = Arc::clone(&received);
            let received_application_bytes = Arc::new(StdMutex::new(Vec::new()));
            let received_application_bytes_by_server = Arc::clone(&received_application_bytes);
            let (shutdown_tx, mut shutdown_rx) = oneshot::channel();
            let (tcp_accepted_tx, tcp_accepted_rx) = oneshot::channel();
            let (release_tls_tx, mut release_tls_rx) = oneshot::channel();
            let release_tls_tx = if delay_tls_handshake {
                Some(release_tls_tx)
            } else {
                None
            };
            let (read_ready_tx, read_ready_rx) = watch::channel(false);
            let (read_complete_tx, read_complete_rx) = watch::channel(false);

            let server_task = tokio::spawn(async move {
                let accepted = tokio::select! {
                    accepted = listener.accept() => accepted,
                    _ = &mut shutdown_rx => return,
                };
                let Ok((tcp_stream, _)) = accepted else {
                    return;
                };
                let _ = tcp_accepted_tx.send(());
                if delay_tls_handshake {
                    tokio::select! {
                        _ = &mut release_tls_rx => {}
                        _ = &mut shutdown_rx => return,
                    }
                }
                let tls_stream = tokio::select! {
                    tls_stream = tls_acceptor.accept(tcp_stream) => tls_stream,
                    _ = &mut shutdown_rx => return,
                };
                let Ok(tls_stream) = tls_stream else {
                    return;
                };
                let _ = read_ready_tx.send(true);
                let recording_stream =
                    RecordingStream::new(tls_stream, received_application_bytes_by_server);
                let websocket = accept_async(recording_stream).await;
                let _ = read_complete_tx.send(true);
                let Ok(mut websocket) = websocket else {
                    return;
                };

                loop {
                    tokio::select! {
                        _ = &mut shutdown_rx => {
                            let _ = websocket.close(None).await;
                            break;
                        }
                        message = websocket.next() => {
                            match message {
                                Some(Ok(message @ (Message::Text(_) | Message::Binary(_)))) => {
                                    received_by_server.lock().await.push(message);
                                }
                                Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                                Some(Ok(_)) => {}
                            }
                        }
                    }
                }
            });

            Self {
                url: format!("wss://{url_host}:{port}"),
                port,
                fingerprint,
                recorder: ApplicationFrameRecorder::new(
                    received,
                    received_application_bytes,
                    shutdown_tx,
                    server_task,
                    read_ready_rx,
                    read_complete_rx,
                ),
                tcp_accepted_rx: Mutex::new(Some(tcp_accepted_rx)),
                release_tls_tx: Mutex::new(release_tls_tx),
            }
        }

        fn different_fingerprint(&self) -> String {
            let replacement = if self.fingerprint.starts_with('0') {
                '1'
            } else {
                '0'
            };
            format!("{replacement}{}", &self.fingerprint[1..])
        }

        async fn received_messages(&self) -> Vec<Message> {
            self.recorder.stop_and_read().await
        }

        async fn received_application_bytes(&self) -> Vec<u8> {
            self.recorder.stop_and_read_application_bytes().await
        }

        async fn wait_for_post_tls_ready(&self) {
            assert!(
                self.recorder.wait_for_read_ready().await,
                "test server should complete TLS before the client rejects the certificate"
            );
        }

        async fn wait_for_tcp_accept(&self) {
            let tcp_accepted_rx = self
                .tcp_accepted_rx
                .lock()
                .await
                .take()
                .expect("TCP accept barrier should only be awaited once");
            tokio::time::timeout(Duration::from_secs(1), tcp_accepted_rx)
                .await
                .expect("test server should accept the delayed connection")
                .expect("test server should signal the delayed connection");
        }

        async fn release_tls_handshake(&self) {
            if let Some(release_tls_tx) = self.release_tls_tx.lock().await.take() {
                let _ = release_tls_tx.send(());
            }
        }
    }

    struct PlainWebSocketFixture {
        url: String,
        recorder: ApplicationFrameRecorder,
    }

    impl PlainWebSocketFixture {
        async fn start() -> Self {
            let listener = TcpListener::bind(("127.0.0.1", 0))
                .await
                .expect("test listener should bind");
            let port = listener
                .local_addr()
                .expect("test listener should have an address")
                .port();
            let received = Arc::new(Mutex::new(Vec::new()));
            let received_by_server = Arc::clone(&received);
            let received_application_bytes = Arc::new(StdMutex::new(Vec::new()));
            let received_application_bytes_by_server = Arc::clone(&received_application_bytes);
            let (shutdown_tx, mut shutdown_rx) = oneshot::channel();
            let (read_ready_tx, read_ready_rx) = watch::channel(false);
            let (read_complete_tx, read_complete_rx) = watch::channel(false);

            let server_task = tokio::spawn(async move {
                let accepted = tokio::select! {
                    accepted = listener.accept() => accepted,
                    _ = &mut shutdown_rx => return,
                };
                let Ok((tcp_stream, _)) = accepted else {
                    return;
                };
                let _ = read_ready_tx.send(true);
                let recording_stream =
                    RecordingStream::new(tcp_stream, received_application_bytes_by_server);
                let websocket = accept_async(recording_stream).await;
                let _ = read_complete_tx.send(true);
                let Ok(mut websocket) = websocket else {
                    return;
                };

                loop {
                    tokio::select! {
                        _ = &mut shutdown_rx => {
                            let _ = websocket.close(None).await;
                            break;
                        }
                        message = websocket.next() => {
                            match message {
                                Some(Ok(message @ (Message::Text(_) | Message::Binary(_)))) => {
                                    received_by_server.lock().await.push(message);
                                }
                                Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                                Some(Ok(_)) => {}
                            }
                        }
                    }
                }
            });

            Self {
                url: format!("ws://127.0.0.1:{port}"),
                recorder: ApplicationFrameRecorder::new(
                    received,
                    received_application_bytes,
                    shutdown_tx,
                    server_task,
                    read_ready_rx,
                    read_complete_rx,
                ),
            }
        }

        async fn received_messages(&self) -> Vec<Message> {
            self.recorder.stop_and_read().await
        }

        async fn received_application_bytes(&self) -> Vec<u8> {
            self.recorder.stop_and_read_application_bytes().await
        }
    }

    fn format_fingerprint(bytes: &[u8]) -> String {
        bytes.iter().map(|byte| format!("{byte:02x}")).collect()
    }

    fn assert_websocket_upgrade_observed(application_bytes: &[u8]) {
        let request = String::from_utf8_lossy(application_bytes).to_ascii_lowercase();
        assert!(
            request.starts_with("get "),
            "HTTP Upgrade request was not read"
        );
        assert!(
            request.contains("\r\nupgrade: websocket\r\n"),
            "WebSocket Upgrade header was not read"
        );
    }

    #[test]
    fn lowercase_fingerprint_parses_to_sha256_bytes() {
        let parsed = normalize_sha256_fingerprint(LOWERCASE_FINGERPRINT)
            .expect("lowercase fingerprint should parse");

        assert_eq!(
            parsed,
            [
                0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d,
                0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b,
                0x1c, 0x1d, 0x1e, 0x1f,
            ]
        );
    }

    #[test]
    fn uppercase_fingerprint_parses_to_sha256_bytes() {
        let parsed = normalize_sha256_fingerprint(
            "ABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABAB",
        )
        .expect("uppercase fingerprint should parse");

        assert_eq!(parsed, [0xab; 32]);
    }

    #[test]
    fn colon_separated_fingerprint_parses_to_sha256_bytes() {
        let parsed = normalize_sha256_fingerprint(
            "00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:\n\
             10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F",
        )
        .expect("colon-separated fingerprint should parse");

        assert_eq!(
            parsed,
            [
                0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d,
                0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b,
                0x1c, 0x1d, 0x1e, 0x1f,
            ]
        );
    }

    #[test]
    fn fingerprint_with_wrong_length_is_rejected() {
        let result = normalize_sha256_fingerprint(&LOWERCASE_FINGERPRINT[..62]);

        assert!(matches!(result, Err(ClientError::InvalidTlsFingerprint)));
    }

    #[test]
    fn fingerprint_with_non_hex_data_is_rejected() {
        let result = normalize_sha256_fingerprint(
            "gg0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
        );

        assert!(matches!(result, Err(ClientError::InvalidTlsFingerprint)));
    }

    #[test]
    fn fingerprint_comparison_accepts_equal_digests_and_rejects_any_difference() {
        let expected = [0x5a; 32];
        assert!(fingerprints_match(&expected, &expected));

        for index in [0, 15, 31] {
            let mut observed = expected;
            observed[index] ^= 0xff;
            assert!(
                !fingerprints_match(&expected, &observed),
                "digest mismatch at byte {index} must be rejected"
            );
        }
    }

    #[tokio::test]
    async fn matching_fingerprint_connects_to_self_signed_ip_certificate() {
        let fixture = TlsWebSocketFixture::start().await;

        let result = connect_verified(
            &fixture.url,
            WebSocketConfig::default(),
            Some(&fixture.fingerprint),
        )
        .await;

        let mut websocket = result.expect("matching pin and IP SAN should connect");
        websocket
            .close(None)
            .await
            .expect("test WebSocket should close");
        let application_bytes = fixture.received_application_bytes().await;
        assert_websocket_upgrade_observed(&application_bytes);
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn matching_fingerprint_connects_to_self_signed_dns_certificate() {
        let fixture =
            TlsWebSocketFixture::start_with_identity("localhost", "localhost", None).await;

        let result = connect_verified(
            &fixture.url,
            WebSocketConfig::default(),
            Some(&fixture.fingerprint),
        )
        .await;

        let mut websocket = result.expect("matching pin and DNS SAN should connect");
        websocket
            .close(None)
            .await
            .expect("test WebSocket should close");
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn fingerprint_mismatch_sends_no_application_data() {
        let fixture = TlsWebSocketFixture::start().await;

        let result = connect_verified(
            &fixture.url,
            WebSocketConfig::default(),
            Some(&fixture.different_fingerprint()),
        )
        .await;

        assert!(matches!(
            result,
            Err(ClientError::TlsFingerprintMismatch { .. })
        ));
        fixture.wait_for_post_tls_ready().await;
        assert!(fixture.received_application_bytes().await.is_empty());
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn replacement_cancels_delayed_pinned_attempt_before_upgrade_or_authentication() {
        let fixture = TlsWebSocketFixture::start_with_delayed_tls().await;
        let state = Arc::new(parking_lot::Mutex::new(crate::state::AppState::default()));
        let old_config = DjClientConfig {
            server_host: "127.0.0.1".to_string(),
            server_port: fixture.port,
            dj_name: "Old DJ".to_string(),
            connect_code: Some("old-secret-code".to_string()),
            tls_fingerprint: Some(fixture.fingerprint.clone()),
            ..Default::default()
        };
        let old_attempt =
            crate::prepare_connection_replacement(&state, &old_config, Some("old-code".into()));
        let old_generation = old_attempt.generation;
        let old_task = tokio::spawn(crate::connect_client_until_cancelled(
            DjClient::new(old_config.clone()),
            None,
            old_attempt.cancellation_rx,
        ));

        fixture.wait_for_tcp_accept().await;

        let replacement_config = DjClientConfig {
            server_host: "new.example".to_string(),
            server_port: 9443,
            dj_name: "Replacement DJ".to_string(),
            connect_code: Some("replacement-code".to_string()),
            tls_fingerprint: Some("ab".repeat(32)),
            ..Default::default()
        };
        let replacement_attempt = crate::prepare_connection_replacement(
            &state,
            &replacement_config,
            Some("replacement-code".into()),
        );
        replacement_attempt
            .previous_cancellation_tx
            .expect("replacement should cancel the old connection attempt")
            .send(())
            .expect("old attempt should still be awaiting TLS");

        let replacement_generation = replacement_attempt.generation;
        let (replacement_shutdown_tx, _replacement_shutdown_rx) = mpsc::channel(1);
        let replacement_publish = crate::publish_connected_client_if_current(
            &state,
            replacement_generation,
            DjClient::new(replacement_config.clone()),
            replacement_shutdown_tx,
        );
        assert!(
            replacement_publish.is_none(),
            "current replacement should publish"
        );

        fixture.release_tls_handshake().await;
        let old_result = tokio::time::timeout(Duration::from_secs(1), old_task)
            .await
            .expect("cancelled old attempt must terminate")
            .expect("old attempt task must be awaited");
        let old_error = match old_result {
            Err(error) => error,
            Ok(_) => panic!("old attempt must be superseded"),
        };
        assert!(old_error.contains("superseded"));

        let (stale_shutdown_tx, _stale_shutdown_rx) = mpsc::channel(1);
        assert!(
            crate::publish_connected_client_if_current(
                &state,
                old_generation,
                DjClient::new(old_config),
                stale_shutdown_tx,
            )
            .is_some(),
            "stale generation must not overwrite the replacement client"
        );
        {
            let app_state = state.lock();
            assert_eq!(app_state.server_host, "new.example");
            assert_eq!(app_state.server_port, 9443);
            assert_eq!(
                app_state
                    .client
                    .as_ref()
                    .expect("replacement client should remain installed")
                    .configured_server_host(),
                "new.example"
            );
        }
        assert!(fixture.received_application_bytes().await.is_empty());
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn dj_client_verifies_pin_before_sending_authentication() {
        let fixture = TlsWebSocketFixture::start().await;
        let mut client = DjClient::new(DjClientConfig {
            server_host: "127.0.0.1".to_string(),
            server_port: fixture.port,
            dj_name: "Fixture DJ".to_string(),
            connect_code: Some("fixture-secret-code".to_string()),
            tls_fingerprint: Some(fixture.different_fingerprint()),
            ..Default::default()
        });

        let result = client.connect().await;

        assert!(matches!(
            result,
            Err(ClientError::TlsFingerprintMismatch { .. })
        ));
        assert!(fixture.received_application_bytes().await.is_empty());
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn rotated_certificate_rejects_previous_fingerprint_before_application_data() {
        let previous_fixture = TlsWebSocketFixture::start().await;
        let previous_fingerprint = previous_fixture.fingerprint.clone();
        assert!(previous_fixture.received_messages().await.is_empty());
        let rotated_fixture = TlsWebSocketFixture::start().await;
        assert_ne!(rotated_fixture.fingerprint, previous_fingerprint);

        let result = connect_verified(
            &rotated_fixture.url,
            WebSocketConfig::default(),
            Some(&previous_fingerprint),
        )
        .await;

        assert!(matches!(
            result,
            Err(ClientError::TlsFingerprintMismatch { .. })
        ));
        assert!(
            rotated_fixture
                .received_application_bytes()
                .await
                .is_empty()
        );
        assert!(rotated_fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn malformed_fingerprint_is_rejected_before_network_or_application_data() {
        let fixture = TlsWebSocketFixture::start().await;

        let result = connect_verified(
            &fixture.url,
            WebSocketConfig::default(),
            Some("not-a-sha256-fingerprint"),
        )
        .await;

        assert!(matches!(result, Err(ClientError::InvalidTlsFingerprint)));
        assert!(fixture.received_application_bytes().await.is_empty());
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn configured_pin_requires_a_tls_peer_certificate() {
        let fixture = PlainWebSocketFixture::start().await;

        let result = connect_verified(
            &fixture.url,
            WebSocketConfig::default(),
            Some(LOWERCASE_FINGERPRINT),
        )
        .await;

        assert!(matches!(result, Err(ClientError::MissingPeerCertificate)));
        assert!(fixture.received_application_bytes().await.is_empty());
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn pinned_certificate_with_wrong_ip_san_is_rejected_before_application_data() {
        let fixture = TlsWebSocketFixture::start_with_subject_alt_name("127.0.0.2").await;

        let result = connect_verified(
            &fixture.url,
            WebSocketConfig::default(),
            Some(&fixture.fingerprint),
        )
        .await;

        let outcome = match &result {
            Ok(_) => "connection unexpectedly succeeded".to_string(),
            Err(error) => format!("connection returned {error:?}"),
        };
        assert!(
            matches!(result, Err(ClientError::TlsHandshake(_))),
            "{outcome}"
        );
        assert!(fixture.received_application_bytes().await.is_empty());
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn pinned_certificate_does_not_fall_back_to_matching_common_name() {
        let fixture = TlsWebSocketFixture::start_with_identity(
            "localhost",
            "wrong.example",
            Some("localhost"),
        )
        .await;

        let result = connect_verified(
            &fixture.url,
            WebSocketConfig::default(),
            Some(&fixture.fingerprint),
        )
        .await;

        assert!(matches!(result, Err(ClientError::TlsHandshake(_))));
        assert!(fixture.received_application_bytes().await.is_empty());
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn self_signed_certificate_without_pin_uses_platform_validation() {
        let fixture = TlsWebSocketFixture::start().await;

        let result = connect_verified(&fixture.url, WebSocketConfig::default(), None).await;

        assert!(matches!(result, Err(ClientError::TlsHandshake(_))));
        assert!(fixture.received_application_bytes().await.is_empty());
        assert!(fixture.received_messages().await.is_empty());
    }

    #[tokio::test]
    async fn ordinary_plain_websocket_without_pin_remains_supported() {
        let fixture = PlainWebSocketFixture::start().await;

        let result = connect_verified(&fixture.url, WebSocketConfig::default(), None).await;

        let mut websocket = result.expect("un-pinned local WebSocket should connect");
        websocket
            .close(None)
            .await
            .expect("test WebSocket should close");
        assert!(fixture.received_messages().await.is_empty());
    }
}
