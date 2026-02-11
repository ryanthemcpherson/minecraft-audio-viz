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
    pub tempo_conf: f32,
    pub beat_phase: f32,
    pub ts: f64,
}

impl AudioFrameMessage {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        seq: u64,
        bands: [f32; 5],
        peak: f32,
        beat: bool,
        beat_intensity: f32,
        bpm: f32,
        tempo_confidence: f32,
        beat_phase: f32,
    ) -> Self {
        Self {
            msg_type: "dj_audio_frame".to_string(),
            seq,
            bands,
            peak,
            beat,
            beat_i: beat_intensity,
            bpm,
            tempo_conf: tempo_confidence,
            beat_phase,
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

/// Voice audio frame message sent to VJ server
#[derive(Debug, Clone, Serialize)]
pub struct VoiceAudioMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub data: String,
    pub seq: u64,
    pub codec: String,
}

impl VoiceAudioMessage {
    pub fn new(data: String, seq: u64, codec: String) -> Self {
        Self {
            msg_type: "voice_audio".to_string(),
            data,
            seq,
            codec,
        }
    }
}

/// Voice config message sent to VJ server
#[derive(Debug, Clone, Serialize)]
pub struct VoiceConfigMessage {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub enabled: bool,
    pub channel_type: String,
    pub distance: f64,
    pub zone: String,
}

impl VoiceConfigMessage {
    pub fn new(enabled: bool, channel_type: String, distance: f64, zone: String) -> Self {
        Self {
            msg_type: "voice_config".to_string(),
            enabled,
            channel_type,
            distance,
            zone,
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

    #[serde(rename = "stream_route")]
    StreamRoute(StreamRouteMessage),

    #[serde(rename = "voice_status")]
    VoiceStatus(VoiceStatusMessage),
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
    #[serde(default)]
    pub route_mode: Option<String>,
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
    #[serde(default)]
    pub echo_ts: Option<f64>,
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

/// Stream routing policy from VJ server.
#[derive(Debug, Clone, Deserialize)]
pub struct StreamRouteMessage {
    pub route_mode: String, // relay | dual
    #[serde(default)]
    pub is_active: Option<bool>,
    #[serde(default)]
    pub minecraft_host: Option<String>,
    #[serde(default)]
    pub minecraft_port: Option<u16>,
    #[serde(default)]
    pub zone: Option<String>,
    #[serde(default)]
    pub entity_count: Option<u32>,
    #[serde(default)]
    pub pattern_config: Option<PatternConfigInfo>,
}

/// Voice status update from server
#[derive(Debug, Clone, Deserialize)]
pub struct VoiceStatusMessage {
    #[serde(default)]
    pub available: bool,
    #[serde(default)]
    pub streaming: bool,
    #[serde(default)]
    pub channel_type: Option<String>,
    #[serde(default)]
    pub connected_players: Option<u32>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn code_auth_message_serializes_expected_shape() {
        let msg = CodeAuthMessage::new("BEAT-7K3M".to_string(), "DJ Spark".to_string());
        let json = serde_json::to_value(&msg).expect("code auth should serialize");

        assert_eq!(json["type"], "code_auth");
        assert_eq!(json["code"], "BEAT-7K3M");
        assert_eq!(json["dj_name"], "DJ Spark");
    }

    #[test]
    fn dj_auth_message_defaults_direct_mode_to_none() {
        let msg = DjAuthMessage::new("dj_1".to_string(), "key_1".to_string(), "DJ".to_string());
        let json = serde_json::to_value(&msg).expect("dj auth should serialize");

        assert_eq!(json["type"], "dj_auth");
        assert_eq!(json["dj_id"], "dj_1");
        assert_eq!(json["dj_key"], "key_1");
        assert_eq!(json["dj_name"], "DJ");
        assert!(json.get("direct_mode").is_none());
    }

    #[test]
    fn audio_frame_message_uses_expected_type_and_payload() {
        let msg = AudioFrameMessage::new(
            42,
            [0.1, 0.2, 0.3, 0.4, 0.5],
            0.5,
            true,
            0.8,
            128.0,
            0.75,
            0.2,
        );
        let json = serde_json::to_value(&msg).expect("audio frame should serialize");

        assert_eq!(json["type"], "dj_audio_frame");
        assert_eq!(json["seq"], 42);
        assert_eq!(json["beat"], true);
        assert!((json["beat_i"].as_f64().unwrap_or_default() - 0.8).abs() < 1e-6);
        assert!((json["bpm"].as_f64().unwrap_or_default() - 128.0).abs() < 1e-6);
        assert!((json["tempo_conf"].as_f64().unwrap_or_default() - 0.75).abs() < 1e-6);
        assert!((json["beat_phase"].as_f64().unwrap_or_default() - 0.2).abs() < 1e-6);
        assert!(json["ts"].as_f64().unwrap_or(0.0) > 0.0);
    }

    #[test]
    fn clock_sync_response_preserves_received_time() {
        let recv_time = 1234.5;
        let msg = ClockSyncResponse::new(recv_time);

        assert_eq!(msg.msg_type, "clock_sync_response");
        assert_eq!(msg.dj_recv_time, recv_time);
        assert!(msg.dj_send_time >= recv_time);
    }

    #[test]
    fn server_message_deserializes_auth_success_variant() {
        let input = r#"{
          "type": "auth_success",
          "dj_id": "abc",
          "dj_name": "DJ Test",
          "is_active": true
        }"#;

        let parsed: ServerMessage =
            serde_json::from_str(input).expect("auth_success payload should deserialize");

        match parsed {
            ServerMessage::AuthSuccess(msg) => {
                assert_eq!(msg.dj_id, "abc");
                assert_eq!(msg.dj_name, "DJ Test");
                assert!(msg.is_active);
            }
            _ => panic!("expected auth_success variant"),
        }
    }

    #[test]
    fn server_message_deserializes_stream_route_variant() {
        let input = r#"{
          "type": "stream_route",
          "route_mode": "dual",
          "is_active": true,
          "minecraft_host": "127.0.0.1",
          "minecraft_port": 8765,
          "zone": "main",
          "entity_count": 24,
          "pattern_config": {
            "entity_count": 24
          }
        }"#;

        let parsed: ServerMessage =
            serde_json::from_str(input).expect("stream_route payload should deserialize");

        match parsed {
            ServerMessage::StreamRoute(msg) => {
                assert_eq!(msg.route_mode, "dual");
                assert_eq!(msg.is_active, Some(true));
                assert_eq!(msg.minecraft_host.as_deref(), Some("127.0.0.1"));
                assert_eq!(msg.minecraft_port, Some(8765));
                assert_eq!(msg.zone.as_deref(), Some("main"));
                assert_eq!(msg.entity_count, Some(24));
                assert_eq!(
                    msg.pattern_config.as_ref().and_then(|cfg| cfg.entity_count),
                    Some(24)
                );
            }
            _ => panic!("expected stream_route variant"),
        }
    }
}
