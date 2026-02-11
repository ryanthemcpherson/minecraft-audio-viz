//! WebSocket client for VJ server communication

use super::messages::*;
use futures_util::{SinkExt, StreamExt};
use parking_lot::Mutex;
use std::sync::Arc;
use std::time::Duration;
use thiserror::Error;
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message};

/// Client errors
#[derive(Error, Debug)]
pub enum ClientError {
    #[error("Connection failed: {0}")]
    ConnectionFailed(String),

    #[error("Authentication failed: {0}")]
    AuthenticationFailed(String),

    #[error("WebSocket error: {0}")]
    WebSocketError(String),

    #[error("Send error: {0}")]
    SendError(String),

    #[error("Already connected")]
    AlreadyConnected,

    #[error("Not connected")]
    NotConnected,
}

/// Client configuration
#[derive(Debug, Clone)]
pub struct DjClientConfig {
    /// VJ server hostname
    pub server_host: String,

    /// VJ server port
    pub server_port: u16,

    /// DJ display name
    pub dj_name: String,

    /// Connect code (for code-based auth)
    pub connect_code: Option<String>,

    /// DJ ID (for credential auth)
    pub dj_id: Option<String>,

    /// DJ key (for credential auth)
    pub dj_key: Option<String>,

    /// Reconnection attempts
    pub max_reconnect_attempts: u32,

    /// Initial reconnection delay (seconds)
    pub reconnect_delay: f64,

    /// Heartbeat interval (seconds)
    pub heartbeat_interval: f64,
}

impl Default for DjClientConfig {
    fn default() -> Self {
        Self {
            server_host: "localhost".to_string(),
            server_port: 9000,
            dj_name: "DJ".to_string(),
            connect_code: None,
            dj_id: None,
            dj_key: None,
            max_reconnect_attempts: 10,
            reconnect_delay: 2.0,
            heartbeat_interval: 2.0,
        }
    }
}

/// Connection state
#[derive(Debug, Clone, Default)]
pub struct ConnectionState {
    pub connected: bool,
    pub authenticated: bool,
    pub is_active: bool,
    pub dj_id: Option<String>,
    pub latency_ms: f32,
    pub reconnect_attempts: u32,
    pub route_mode: String, // relay | dual
    pub mc_host: Option<String>,
    pub mc_port: Option<u16>,
    pub mc_zone: Option<String>,
    pub mc_entity_count: Option<u32>,
    // Voice status fields (populated by server voice_status messages)
    pub voice_available: bool,
    pub voice_streaming: bool,
    pub voice_channel_type: Option<String>,
    pub voice_connected_players: Option<u32>,
}

/// DJ Client for VJ server communication
pub struct DjClient {
    config: DjClientConfig,
    state: Arc<Mutex<ConnectionState>>,
    tx: Option<mpsc::Sender<Message>>,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

impl DjClient {
    /// Create a new DJ client
    pub fn new(config: DjClientConfig) -> Self {
        Self {
            config,
            state: Arc::new(Mutex::new(ConnectionState::default())),
            tx: None,
            shutdown_tx: None,
        }
    }

    /// Connect to the VJ server
    pub async fn connect(&mut self) -> Result<(), ClientError> {
        if self.state.lock().connected {
            return Err(ClientError::AlreadyConnected);
        }

        let scheme = if crate::is_local_host(&self.config.server_host) {
            "ws"
        } else {
            "wss"
        };
        let url = format!(
            "{}://{}:{}",
            scheme, self.config.server_host, self.config.server_port
        );

        log::info!("Connecting to VJ server at {}", url);

        // Connect with timeout
        let ws_stream = tokio::time::timeout(Duration::from_secs(10), connect_async(&url))
            .await
            .map_err(|_| ClientError::ConnectionFailed("Connection timeout".to_string()))?
            .map_err(|e| ClientError::ConnectionFailed(e.to_string()))?;

        let (ws_stream, _) = ws_stream;
        let (mut write, mut read) = ws_stream.split();

        // Create message channel
        let (tx, mut rx) = mpsc::channel::<Message>(100);
        let (shutdown_tx, mut shutdown_rx) = mpsc::channel::<()>(1);

        self.tx = Some(tx.clone());
        self.shutdown_tx = Some(shutdown_tx);

        // Send authentication
        let auth_msg = if let Some(ref code) = self.config.connect_code {
            // Code-based authentication
            serde_json::to_string(&CodeAuthMessage::new(
                code.clone(),
                self.config.dj_name.clone(),
            ))
            .unwrap()
        } else if let (Some(ref id), Some(ref key)) = (&self.config.dj_id, &self.config.dj_key) {
            // Credential-based authentication
            serde_json::to_string(&DjAuthMessage::new(
                id.clone(),
                key.clone(),
                self.config.dj_name.clone(),
            ))
            .unwrap()
        } else {
            return Err(ClientError::AuthenticationFailed(
                "No credentials provided. Set a connect code or DJ ID/key in settings.".to_string(),
            ));
        };

        write
            .send(Message::Text(auth_msg.into()))
            .await
            .map_err(|e| ClientError::SendError(e.to_string()))?;

        // Handle auth handshake + clock sync inline before spawning background tasks.
        // The server sends: auth_success, then clock_sync_request immediately after.
        // We must respond to both before the heartbeat task starts, otherwise the
        // heartbeat fires first and the server sees a wrong message type for clock sync.
        let handshake_timeout = Duration::from_secs(5);
        let deadline = tokio::time::Instant::now() + handshake_timeout;

        loop {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                log::warn!("Handshake timeout - proceeding without full sync");
                break;
            }

            match tokio::time::timeout(remaining, read.next()).await {
                Ok(Some(Ok(Message::Text(ref text)))) => {
                    if let Ok(msg) = serde_json::from_str::<serde_json::Value>(text) {
                        let msg_type = msg.get("type").and_then(|t| t.as_str()).unwrap_or("");

                        match msg_type {
                            "auth_success" => {
                                if let Ok(auth) = serde_json::from_value::<AuthSuccessMessage>(msg)
                                {
                                    let mut s = self.state.lock();
                                    s.authenticated = true;
                                    s.is_active = auth.is_active;
                                    s.dj_id = Some(auth.dj_id.clone());
                                    if let Some(route_mode) = auth.route_mode {
                                        s.route_mode = route_mode;
                                    }
                                    if let Some(pattern_cfg) = auth.pattern_config.as_ref() {
                                        s.mc_entity_count = pattern_cfg.entity_count;
                                    }
                                    log::info!(
                                        "Authenticated as {} (active: {})",
                                        auth.dj_name,
                                        auth.is_active
                                    );
                                }
                            }
                            "auth_error" => {
                                if let Ok(err) = serde_json::from_value::<AuthErrorMessage>(msg) {
                                    return Err(ClientError::AuthenticationFailed(err.error));
                                }
                            }
                            "clock_sync_request" => {
                                let now = std::time::SystemTime::now()
                                    .duration_since(std::time::UNIX_EPOCH)
                                    .unwrap()
                                    .as_secs_f64();
                                let response = ClockSyncResponse::new(now);
                                let json = serde_json::to_string(&response).unwrap();
                                write
                                    .send(Message::Text(json.into()))
                                    .await
                                    .map_err(|e| ClientError::SendError(e.to_string()))?;
                                log::info!("Clock sync completed");
                                // Clock sync is the last handshake message, we're done
                                break;
                            }
                            "status_update" => {
                                // May arrive during handshake, handle it
                                if let Some(is_active) =
                                    msg.get("is_active").and_then(|v| v.as_bool())
                                {
                                    self.state.lock().is_active = is_active;
                                }
                            }
                            _ => {
                                log::debug!("Unexpected message during handshake: {}", msg_type);
                            }
                        }
                    }
                }
                Ok(Some(Ok(Message::Close(_)))) | Ok(Some(Err(_))) | Ok(None) => {
                    return Err(ClientError::ConnectionFailed(
                        "Connection closed during handshake".to_string(),
                    ));
                }
                Ok(Some(Ok(_))) => {
                    // Binary/ping/pong - ignore
                }
                Err(_) => {
                    log::warn!("Handshake timeout - proceeding without clock sync");
                    break;
                }
            }
        }

        // Mark as connected
        self.state.lock().connected = true;

        // Spawn message handling tasks
        let state = self.state.clone();
        let heartbeat_interval = self.config.heartbeat_interval;

        // Writer task
        let _tx_clone = tx.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    Some(msg) = rx.recv() => {
                        if write.send(msg).await.is_err() {
                            break;
                        }
                    }
                    _ = shutdown_rx.recv() => {
                        // Send going offline message
                        let _ = write.send(Message::Text(
                            serde_json::to_string(&GoingOfflineMessage::new()).unwrap().into()
                        )).await;
                        let _ = write.close().await;
                        break;
                    }
                }
            }
        });

        // Reader task
        let state_reader = state.clone();
        let tx_reader = tx.clone();
        tokio::spawn(async move {
            while let Some(msg) = read.next().await {
                match msg {
                    Ok(Message::Text(ref text)) => {
                        if let Ok(server_msg) = serde_json::from_str::<ServerMessage>(text) {
                            handle_server_message(&state_reader, &tx_reader, server_msg).await;
                        }
                    }
                    Ok(Message::Close(_)) => {
                        let mut s = state_reader.lock();
                        s.connected = false;
                        s.authenticated = false;
                        break;
                    }
                    Err(_) => {
                        let mut s = state_reader.lock();
                        s.connected = false;
                        s.authenticated = false;
                        break;
                    }
                    _ => {}
                }
            }
        });

        // Heartbeat task - delay first tick to avoid racing with handshake
        let tx_heartbeat = tx;
        tokio::spawn(async move {
            // Wait one full interval before sending first heartbeat
            tokio::time::sleep(Duration::from_secs_f64(heartbeat_interval)).await;
            let mut interval = tokio::time::interval(Duration::from_secs_f64(heartbeat_interval));
            loop {
                interval.tick().await;
                let msg = serde_json::to_string(&HeartbeatMessage::new()).unwrap();
                if tx_heartbeat.send(Message::Text(msg.into())).await.is_err() {
                    break;
                }
            }
        });

        Ok(())
    }

    /// Send audio frame
    #[allow(clippy::too_many_arguments)]
    pub async fn send_audio_frame(
        &self,
        seq: u64,
        bands: [f32; 5],
        peak: f32,
        beat: bool,
        beat_intensity: f32,
        bpm: f32,
        tempo_confidence: f32,
        beat_phase: f32,
    ) -> Result<(), ClientError> {
        let tx = self.tx.as_ref().ok_or(ClientError::NotConnected)?;

        let msg = AudioFrameMessage::new(
            seq,
            bands,
            peak,
            beat,
            beat_intensity,
            bpm,
            tempo_confidence,
            beat_phase,
        );
        let json =
            serde_json::to_string(&msg).map_err(|e| ClientError::SendError(e.to_string()))?;

        tx.send(Message::Text(json.into()))
            .await
            .map_err(|e| ClientError::SendError(e.to_string()))?;

        Ok(())
    }

    /// Disconnect from server
    pub async fn disconnect(&self) -> Result<(), ClientError> {
        if let Some(ref shutdown) = self.shutdown_tx {
            let _ = shutdown.send(()).await;
        }

        let mut state = self.state.lock();
        state.connected = false;
        state.authenticated = false;

        Ok(())
    }

    /// Get current connection state
    pub fn get_state(&self) -> ConnectionState {
        self.state.lock().clone()
    }

    /// Check if connected
    pub fn is_connected(&self) -> bool {
        self.state.lock().connected
    }

    /// Check if active DJ
    pub fn is_active(&self) -> bool {
        self.state.lock().is_active
    }

    /// Get a clone of the message sender for external use (e.g., bridge task).
    /// Returns None if not connected.
    pub fn get_tx_clone(&self) -> Option<mpsc::Sender<Message>> {
        self.tx.clone()
    }
}

/// Handle incoming server messages
async fn handle_server_message(
    state: &Arc<Mutex<ConnectionState>>,
    tx: &mpsc::Sender<Message>,
    msg: ServerMessage,
) {
    match msg {
        ServerMessage::AuthSuccess(auth) => {
            let mut s = state.lock();
            s.authenticated = true;
            s.is_active = auth.is_active;
            s.dj_id = Some(auth.dj_id);
            if let Some(route_mode) = auth.route_mode {
                s.route_mode = route_mode;
            }
            if let Some(pattern_cfg) = auth.pattern_config.as_ref() {
                s.mc_entity_count = pattern_cfg.entity_count;
            }
            log::info!(
                "Authenticated as {} (active: {})",
                auth.dj_name,
                auth.is_active
            );
        }
        ServerMessage::AuthError(err) => {
            let mut s = state.lock();
            s.authenticated = false;
            log::error!("Authentication failed: {}", err.error);
        }
        ServerMessage::StatusUpdate(update) => {
            let mut s = state.lock();
            s.is_active = update.is_active;
            log::info!("Status update: active={}", update.is_active);
        }
        ServerMessage::ClockSyncRequest(_req) => {
            // Respond to clock sync
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs_f64();

            let response = ClockSyncResponse::new(now);
            let json = serde_json::to_string(&response).unwrap();
            let _ = tx.send(Message::Text(json.into())).await;
        }
        ServerMessage::HeartbeatAck(ack) => {
            // Calculate latency: prefer RTT from echoed heartbeat timestamp.
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs_f64();

            let latency = if let Some(echo_ts) = ack.echo_ts {
                ((now - echo_ts) * 1000.0) as f32
            } else {
                ((now - ack.server_time) * 1000.0) as f32
            };
            state.lock().latency_ms = latency.max(0.0);
        }
        ServerMessage::PatternSync(_)
        | ServerMessage::ConfigSync(_)
        | ServerMessage::PresetSync(_)
        | ServerMessage::EffectTriggered(_) => {
            // These are informational, no action needed for basic client
        }
        ServerMessage::StreamRoute(route) => {
            let mut s = state.lock();
            s.route_mode = route.route_mode;
            if let Some(active) = route.is_active {
                s.is_active = active;
            }
            s.mc_host = route.minecraft_host;
            s.mc_port = route.minecraft_port;
            s.mc_zone = route.zone;
            s.mc_entity_count = route
                .entity_count
                .or_else(|| route.pattern_config.and_then(|cfg| cfg.entity_count));
        }
        ServerMessage::VoiceStatus(vs) => {
            log::info!(
                "Voice status: available={}, streaming={}, players={}",
                vs.available,
                vs.streaming,
                vs.connected_players.unwrap_or(0)
            );
            // Voice status is stored in AppState, not ConnectionState.
            // The bridge task reads this from the ServerMessage via the reader task.
            // We store in ConnectionState temporarily for the bridge to pick up.
            let mut s = state.lock();
            s.voice_available = vs.available;
            s.voice_streaming = vs.streaming;
            s.voice_channel_type = vs.channel_type;
            s.voice_connected_players = vs.connected_players;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_uses_expected_connection_settings() {
        let config = DjClientConfig::default();

        assert_eq!(config.server_host, "localhost");
        assert_eq!(config.server_port, 9000);
        assert_eq!(config.dj_name, "DJ");
        assert_eq!(config.max_reconnect_attempts, 10);
        assert_eq!(config.reconnect_delay, 2.0);
        assert_eq!(config.heartbeat_interval, 2.0);
        assert!(config.connect_code.is_none());
        assert!(config.dj_id.is_none());
        assert!(config.dj_key.is_none());
    }

    #[test]
    fn new_client_starts_disconnected() {
        let client = DjClient::new(DjClientConfig::default());

        let state = client.get_state();
        assert!(!state.connected);
        assert!(!state.authenticated);
        assert!(!state.is_active);
        assert!(state.dj_id.is_none());
        assert_eq!(state.route_mode, "");
        assert!(state.mc_host.is_none());
        assert!(state.mc_port.is_none());
        assert!(state.mc_zone.is_none());
        assert!(state.mc_entity_count.is_none());
        assert!(!state.voice_available);
        assert!(!state.voice_streaming);
        assert!(client.get_tx_clone().is_none());
        assert!(!client.is_connected());
        assert!(!client.is_active());
    }

    #[tokio::test]
    async fn disconnect_without_connection_succeeds() {
        let client = DjClient::new(DjClientConfig::default());

        client
            .disconnect()
            .await
            .expect("disconnect should be safe when not connected");
        client
            .disconnect()
            .await
            .expect("disconnect should be idempotent");
    }
}
