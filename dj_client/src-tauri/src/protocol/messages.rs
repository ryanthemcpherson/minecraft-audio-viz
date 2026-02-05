//! Message types for VJ server protocol

use serde::{Deserialize, Serialize};

/// DJ authentication message (traditional credentials)
#[derive(Debug, Clone, Serialize)]
pub struct DjAuthMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub dj_id: String,
    pub dj_key: String,
    pub dj_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub direct_mode: Option<bool>,
}

impl DjAuthMessage {
    pub fn new(dj_id: String, dj_key: String, dj_name: String) -> Self {
        Self {
            msg_type: "dj_auth".to_string(),
            dj_id,
            dj_key,
            dj_name,
            direct_mode: None,
        }
    }
}

/// Connect code authentication message
#[derive(Debug, Clone, Serialize)]
pub struct CodeAuthMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub code: String,
    pub dj_name: String,
}

impl CodeAuthMessage {
    pub fn new(code: String, dj_name: String) -> Self {
        Self {
            msg_type: "code_auth".to_string(),
            code,
            dj_name,
        }
    }
}

/// Audio frame message sent to VJ server
#[derive(Debug, Clone, Serialize)]
pub struct AudioFrameMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub seq: u64,
    pub bands: [f32; 5],
    pub peak: f32,
    pub beat: bool,
    pub beat_i: f32,
    pub bpm: f32,
    pub ts: f64,
}

impl AudioFrameMessage {
    pub fn new(
        seq: u64,
        bands: [f32; 5],
        peak: f32,
        beat: bool,
        beat_intensity: f32,
        bpm: f32,
    ) -> Self {
        Self {
            msg_type: "dj_audio_frame".to_string(),
            seq,
            bands,
            peak,
            beat,
            beat_i: beat_intensity,
            bpm,
            ts: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs_f64(),
        }
    }
}

/// Heartbeat message
#[derive(Debug, Clone, Serialize)]
pub struct HeartbeatMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
    /// Client timestamp when heartbeat was sent (for RTT calculation)
    pub ts: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mc_connected: Option<bool>,
}

impl HeartbeatMessage {
    pub fn new() -> Self {
        Self {
            msg_type: "dj_heartbeat".to_string(),
            ts: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs_f64(),
            mc_connected: None,
        }
    }
}

impl Default for HeartbeatMessage {
    fn default() -> Self {
        Self::new()
    }
}

/// Going offline message (graceful disconnect)
#[derive(Debug, Clone, Serialize)]
pub struct GoingOfflineMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
}

impl GoingOfflineMessage {
    pub fn new() -> Self {
        Self {
            msg_type: "going_offline".to_string(),
        }
    }
}

impl Default for GoingOfflineMessage {
    fn default() -> Self {
        Self::new()
    }
}

/// Clock sync response message
#[derive(Debug, Clone, Serialize)]
pub struct ClockSyncResponse {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub dj_recv_time: f64,
    pub dj_send_time: f64,
}

impl ClockSyncResponse {
    pub fn new(recv_time: f64) -> Self {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs_f64();

        Self {
            msg_type: "clock_sync_response".to_string(),
            dj_recv_time: recv_time,
            dj_send_time: now,
        }
    }
}

// === Incoming Messages ===

/// Server message types (incoming)
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "type")]
pub enum ServerMessage {
    #[serde(rename = "auth_success")]
    AuthSuccess(AuthSuccessMessage),

    #[serde(rename = "auth_error")]
    AuthError(AuthErrorMessage),

    #[serde(rename = "status_update")]
    StatusUpdate(StatusUpdateMessage),

    #[serde(rename = "clock_sync_request")]
    ClockSyncRequest(ClockSyncRequest),

    #[serde(rename = "heartbeat_ack")]
    HeartbeatAck(HeartbeatAckMessage),

    #[serde(rename = "pattern_sync")]
    PatternSync(PatternSyncMessage),

    #[serde(rename = "config_sync")]
    ConfigSync(ConfigSyncMessage),

    #[serde(rename = "preset_sync")]
    PresetSync(PresetSyncMessage),

    #[serde(rename = "effect_triggered")]
    EffectTriggered(EffectTriggeredMessage),
}

/// Auth success response
#[derive(Debug, Clone, Deserialize)]
pub struct AuthSuccessMessage {
    pub dj_id: String,
    pub dj_name: String,
    pub is_active: bool,
    #[serde(default)]
    pub current_pattern: Option<String>,
    #[serde(default)]
    pub pattern_config: Option<PatternConfigInfo>,
}

/// Auth error response
#[derive(Debug, Clone, Deserialize)]
pub struct AuthErrorMessage {
    pub error: String,
}

/// Status update from server
#[derive(Debug, Clone, Deserialize)]
pub struct StatusUpdateMessage {
    pub is_active: bool,
}

/// Clock sync request from server
#[derive(Debug, Clone, Deserialize)]
pub struct ClockSyncRequest {
    pub server_time: f64,
}

/// Heartbeat acknowledgement
#[derive(Debug, Clone, Deserialize)]
pub struct HeartbeatAckMessage {
    pub server_time: f64,
}

/// Pattern sync from server
#[derive(Debug, Clone, Deserialize)]
pub struct PatternSyncMessage {
    pub pattern: String,
    #[serde(default)]
    pub config: Option<PatternConfigInfo>,
}

/// Pattern config info
#[derive(Debug, Clone, Deserialize)]
pub struct PatternConfigInfo {
    pub entity_count: Option<u32>,
    pub zone_size: Option<f32>,
    pub beat_boost: Option<f32>,
    pub base_scale: Option<f32>,
    pub max_scale: Option<f32>,
}

/// Config sync from server
#[derive(Debug, Clone, Deserialize)]
pub struct ConfigSyncMessage {
    pub entity_count: u32,
    pub zone: String,
}

/// Preset sync from server
#[derive(Debug, Clone, Deserialize)]
pub struct PresetSyncMessage {
    pub preset: serde_json::Value,
}

/// Effect triggered notification
#[derive(Debug, Clone, Deserialize)]
pub struct EffectTriggeredMessage {
    pub effect: String,
}
