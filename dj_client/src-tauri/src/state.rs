//! Application state management

use crate::audio::AudioCaptureHandle;
use crate::protocol::DjClient;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;

/// Connection status
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ConnectionStatus {
    pub connected: bool,
    pub is_active: bool,
    pub latency_ms: f32,
    pub queue_position: usize,
    pub total_djs: usize,
    pub active_dj_name: Option<String>,
    pub error: Option<String>,
}

/// Application state
pub struct AppState {
    /// WebSocket client
    pub client: Option<DjClient>,

    /// Audio capture handle (Send + Sync safe)
    pub audio_capture: Option<AudioCaptureHandle>,

    /// Current audio bands (5 bands: bass, low, mid, high, air)
    pub bands: [f32; 5],

    /// Current peak amplitude
    pub peak: f32,

    /// Beat detection state
    pub is_beat: bool,

    /// Beat intensity
    pub beat_intensity: f32,

    /// Estimated BPM
    pub bpm: f32,

    /// Connection status
    pub status: ConnectionStatus,

    /// DJ name
    pub dj_name: String,

    /// Connect code
    pub connect_code: Option<String>,

    /// Server host
    pub server_host: String,

    /// Server port
    pub server_port: u16,

    /// Selected audio source ID
    pub audio_source_id: Option<String>,

    /// Shutdown signal sender for the bridge task
    pub bridge_shutdown_tx: Option<mpsc::Sender<()>>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            client: None,
            audio_capture: None,
            bands: [0.0; 5],
            peak: 0.0,
            is_beat: false,
            beat_intensity: 0.0,
            bpm: 120.0,
            status: ConnectionStatus::default(),
            dj_name: String::new(),
            connect_code: None,
            server_host: "192.168.1.204".to_string(),
            server_port: 9000,
            audio_source_id: None,
            bridge_shutdown_tx: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_connection_status_is_disconnected() {
        let status = ConnectionStatus::default();

        assert!(!status.connected);
        assert!(!status.is_active);
        assert_eq!(status.latency_ms, 0.0);
        assert_eq!(status.queue_position, 0);
        assert_eq!(status.total_djs, 0);
        assert!(status.active_dj_name.is_none());
        assert!(status.error.is_none());
    }

    #[test]
    fn default_app_state_has_expected_server_defaults() {
        let state = AppState::default();

        assert!(state.client.is_none());
        assert!(state.audio_capture.is_none());
        assert_eq!(state.bands, [0.0; 5]);
        assert_eq!(state.peak, 0.0);
        assert!(!state.is_beat);
        assert_eq!(state.beat_intensity, 0.0);
        assert_eq!(state.bpm, 120.0);
        assert_eq!(state.server_host, "192.168.1.204");
        assert_eq!(state.server_port, 9000);
        assert!(state.bridge_shutdown_tx.is_none());
    }
}
