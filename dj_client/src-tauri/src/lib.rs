//! MCAV DJ Client Library
//!
//! Cross-platform DJ client for connecting to VJ servers and streaming
//! audio visualizations to Minecraft.

pub mod audio;
pub mod patterns;
pub mod protocol;
pub mod state;
pub mod voice;

use audio::{AudioCaptureHandle, AudioPreset, AudioSource};
use protocol::{AudioFrameMessage, DjClient, DjClientConfig};
use state::AppState;
use voice::{VoiceStatus, VoiceStreamer};

use futures_util::{SinkExt, StreamExt};
use parking_lot::Mutex;
use serde_json::json;
use std::f32::consts::TAU;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{TrayIconBuilder, TrayIconEvent};
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message};

/// Application state wrapper
pub struct AppStateWrapper(pub Arc<Mutex<AppState>>);

/// Sequence counter for audio frames (shared between bridge task and get_audio_levels)
static FRAME_SEQ: AtomicU64 = AtomicU64::new(0);

#[derive(Clone)]
struct DirectMcRoute {
    host: String,
    port: u16,
    zone: String,
    entity_count: u32,
}

/// Returns true if the host is a private/local IP address that should use ws:// instead of wss://.
pub(crate) fn is_local_host(host: &str) -> bool {
    if host == "localhost" {
        return true;
    }
    if let Ok(ip) = host.parse::<std::net::Ipv4Addr>() {
        return ip.is_loopback() // 127.*
            || ip.octets()[0] == 10 // 10.*
            || (ip.octets()[0] == 172 && (16..=31).contains(&ip.octets()[1])) // 172.16-31.*
            || (ip.octets()[0] == 192 && ip.octets()[1] == 168); // 192.168.*
    }
    false
}

fn resolve_direct_mc_route(conn_state: &protocol::ConnectionState) -> Option<DirectMcRoute> {
    if conn_state.route_mode != "dual" || !conn_state.is_active {
        return None;
    }
    let host = conn_state.mc_host.clone()?;
    let port = conn_state.mc_port?;
    let zone = conn_state
        .mc_zone
        .clone()
        .unwrap_or_else(|| "main".to_string());
    let entity_count = conn_state.mc_entity_count.unwrap_or(16).max(1);
    Some(DirectMcRoute {
        host,
        port,
        zone,
        entity_count,
    })
}

fn build_direct_entities(
    analysis: &audio::AnalysisResult,
    entity_count: usize,
    seq: u64,
) -> Vec<serde_json::Value> {
    let count = entity_count.clamp(1, 512);
    let peak_scale = analysis.peak.clamp(0.0, 1.0);
    let beat_boost = if analysis.is_beat {
        (analysis.beat_intensity * 0.25).clamp(0.0, 0.3)
    } else {
        0.0
    };

    (0..count)
        .map(|i| {
            let t = i as f32 / count as f32;
            let band_idx = ((i * 5) / count).min(4);
            let band = analysis.bands[band_idx].clamp(0.0, 1.0);
            let angle = t * TAU + (seq as f32 * 0.01);
            let radius = 0.2 + (band * 0.35) + (peak_scale * 0.15);
            let x = (0.5 + angle.cos() * radius).clamp(0.0, 1.0);
            let z = (0.5 + angle.sin() * radius).clamp(0.0, 1.0);
            let y = (0.08 + band * 0.82 + beat_boost).clamp(0.0, 1.0);
            let scale = (0.12 + band * 0.75 + beat_boost).clamp(0.05, 1.6);
            let brightness = (6.0 + peak_scale * 9.0).round() as i32;

            json!({
                "id": format!("block_{}", i),
                "x": x,
                "y": y,
                "z": z,
                "scale": scale,
                "rotation": ((seq as f32 * 2.0) + (i as f32 * 7.5)) % 360.0,
                "brightness": brightness.clamp(0, 15),
                "glow": analysis.is_beat,
                "visible": true,
                "interpolation": 2
            })
        })
        .collect()
}

async fn start_direct_mc_session(
    route: &DirectMcRoute,
) -> Result<(mpsc::Sender<Message>, mpsc::Sender<()>), String> {
    let scheme = if is_local_host(&route.host) {
        "ws"
    } else {
        "wss"
    };
    let uri = format!("{}://{}:{}", scheme, route.host, route.port);
    let ws_stream = tokio::time::timeout(Duration::from_secs(5), connect_async(&uri))
        .await
        .map_err(|_| format!("Direct MC connect timeout: {}", uri))?
        .map_err(|e| format!("Direct MC connect failed: {}", e))?;

    let (ws_stream, _) = ws_stream;
    let (mut write, mut read) = ws_stream.split();

    // Drain welcome packet if present; MC server sends a "connected" message on open.
    if let Ok(Some(Ok(Message::Text(_)))) =
        tokio::time::timeout(Duration::from_millis(500), read.next()).await
    {}

    let (tx, mut rx) = mpsc::channel::<Message>(200);
    let (shutdown_tx, mut shutdown_rx) = mpsc::channel::<()>(1);

    // Writer
    tokio::spawn(async move {
        loop {
            tokio::select! {
                Some(msg) = rx.recv() => {
                    if write.send(msg).await.is_err() {
                        break;
                    }
                }
                _ = shutdown_rx.recv() => {
                    let _ = write.close().await;
                    break;
                }
            }
        }
    });

    // Reader (respond to ping from Minecraft, measure RTT from pong)
    let tx_reader = tx.clone();
    tokio::spawn(async move {
        while let Some(msg) = read.next().await {
            match msg {
                Ok(Message::Text(text)) => {
                    if let Ok(data) = serde_json::from_str::<serde_json::Value>(&text) {
                        match data.get("type").and_then(|t| t.as_str()) {
                            Some("ping") => {
                                let _ = tx_reader
                                    .send(Message::Text(
                                        serde_json::json!({ "type": "pong" }).to_string().into(),
                                    ))
                                    .await;
                            }
                            Some("pong") => {
                                // RTT measurement: extract echoed timestamp
                                if let Some(ts) = data.get("ts").and_then(|v| v.as_f64()) {
                                    let now = std::time::SystemTime::now()
                                        .duration_since(std::time::UNIX_EPOCH)
                                        .unwrap()
                                        .as_secs_f64();
                                    let rtt_ms = ((now - ts) * 1000.0).max(0.0);
                                    log::debug!("Direct MC RTT: {:.1}ms", rtt_ms);
                                }
                            }
                            _ => {}
                        }
                    }
                }
                Ok(Message::Close(_)) | Err(_) => break,
                _ => {}
            }
        }
    });

    // Periodic ping for RTT measurement (every 5 seconds)
    let tx_ping = tx.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_secs(5));
        loop {
            interval.tick().await;
            let ts = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs_f64();
            let ping = serde_json::json!({ "type": "ping", "ts": ts }).to_string();
            if tx_ping.send(Message::Text(ping.into())).await.is_err() {
                break;
            }
        }
    });

    Ok((tx, shutdown_tx))
}

/// List available audio sources
#[tauri::command]
async fn list_audio_sources() -> Result<Vec<AudioSource>, String> {
    audio::list_sources().map_err(|e| e.to_string())
}

/// Connect to VJ server with connect code
#[tauri::command]
async fn connect_with_code(
    app_handle: AppHandle,
    state: State<'_, AppStateWrapper>,
    code: String,
    dj_name: String,
    server_host: String,
    server_port: u16,
) -> Result<(), String> {
    let config = DjClientConfig {
        server_host: server_host.clone(),
        server_port,
        dj_name: dj_name.clone(),
        connect_code: Some(code.clone()),
        ..Default::default()
    };

    // If a previous bridge task is still running (e.g. reconnecting after
    // server restart), shut it down before starting a new connection.
    {
        let old_tx = state.0.lock().bridge_shutdown_tx.take();
        if let Some(tx) = old_tx {
            log::info!("Shutting down existing bridge task before reconnecting");
            let _ = tx.send(()).await;
            tokio::time::sleep(Duration::from_millis(150)).await;
            // Clean up old client
            let old_client = state.0.lock().client.take();
            if let Some(c) = old_client {
                let _ = c.disconnect().await;
            }
        }
    }

    {
        let mut app_state = state.0.lock();
        app_state.connect_code = Some(code);
        app_state.dj_name = dj_name;
        app_state.server_host = server_host;
        app_state.server_port = server_port;
    }

    // Create and connect client (async, no mutex held)
    let mut client = DjClient::new(config);
    client.connect().await.map_err(|e| e.to_string())?;

    // Create shutdown channel for bridge task
    let (shutdown_tx, shutdown_rx) = mpsc::channel::<()>(1);

    // Store connected client and shutdown channel
    {
        let mut app_state = state.0.lock();
        app_state.client = Some(client);
        app_state.bridge_shutdown_tx = Some(shutdown_tx);
        app_state.status.connected = true;
        app_state.status.error = None;
    }

    // Spawn bridge task
    let state_arc = state.0.clone();
    tokio::spawn(async move {
        run_bridge(state_arc, shutdown_rx, app_handle).await;
    });

    Ok(())
}

/// Connect to VJ server directly (no connect code needed, for testing)
#[tauri::command]
async fn connect_direct(
    app_handle: AppHandle,
    state: State<'_, AppStateWrapper>,
    dj_name: String,
    server_host: String,
    server_port: u16,
) -> Result<(), String> {
    let config = DjClientConfig {
        server_host: server_host.clone(),
        server_port,
        dj_name: dj_name.clone(),
        dj_id: Some(format!("tauri_dj_{:08x}", rand::random::<u32>())),
        dj_key: Some(String::new()),
        ..Default::default()
    };

    // If a previous bridge task is still running, shut it down first.
    {
        let old_tx = state.0.lock().bridge_shutdown_tx.take();
        if let Some(tx) = old_tx {
            log::info!("Shutting down existing bridge task before reconnecting");
            let _ = tx.send(()).await;
            tokio::time::sleep(Duration::from_millis(150)).await;
            let old_client = state.0.lock().client.take();
            if let Some(c) = old_client {
                let _ = c.disconnect().await;
            }
        }
    }

    {
        let mut app_state = state.0.lock();
        app_state.dj_name = dj_name;
        app_state.server_host = server_host;
        app_state.server_port = server_port;
        app_state.connect_code = None;
    }

    // Create and connect client (async, no mutex held)
    let mut client = DjClient::new(config);
    client.connect().await.map_err(|e| e.to_string())?;

    // Create shutdown channel for bridge task
    let (shutdown_tx, shutdown_rx) = mpsc::channel::<()>(1);

    // Store connected client and shutdown channel
    {
        let mut app_state = state.0.lock();
        app_state.client = Some(client);
        app_state.bridge_shutdown_tx = Some(shutdown_tx);
        app_state.status.connected = true;
        app_state.status.error = None;
    }

    // Spawn bridge task
    let state_arc = state.0.clone();
    tokio::spawn(async move {
        run_bridge(state_arc, shutdown_rx, app_handle).await;
    });

    Ok(())
}

/// Maximum number of automatic reconnection attempts before giving up.
const MAX_RECONNECT_ATTEMPTS: u32 = 10;
/// Maximum backoff delay between reconnection attempts in seconds.
const MAX_RECONNECT_DELAY_SECS: u64 = 30;

/// Bridge task: reads audio analysis and sends frames to VJ server at ~60fps.
/// Automatically reconnects with exponential backoff when the connection drops.
async fn run_bridge(
    state_arc: Arc<Mutex<AppState>>,
    mut shutdown_rx: mpsc::Receiver<()>,
    app_handle: AppHandle,
) {
    let mut reconnect_count: u32 = 0;

    'reconnect: loop {
        let mut interval = tokio::time::interval(Duration::from_millis(16));
        FRAME_SEQ.store(0, Ordering::Relaxed);
        let mut mc_tx: Option<mpsc::Sender<Message>> = None;
        let mut mc_shutdown_tx: Option<mpsc::Sender<()>> = None;
        let mut mc_target_key: Option<String> = None;
        let mut mc_pool_key: Option<String> = None;
        let mut next_mc_connect_attempt = Instant::now();
        let mut last_phase_predicted_beat_at = 0.0_f64;
        let mut last_mc_send = Instant::now() - Duration::from_secs(1);
        let mut pattern_engine: Option<patterns::PatternEngine> = None;
        // Throttle UI events: audio-levels ~30fps, status/voice ~4fps
        let mut last_audio_emit = Instant::now() - Duration::from_secs(1);
        let mut last_status_emit = Instant::now() - Duration::from_secs(1);
        let mut prev_status_hash: u64 = 0;
        let mut prev_voice_hash: u64 = 0;
        // Track whether this iteration exited due to explicit shutdown
        let mut shutdown_requested = false;

        log::info!("Bridge task started (reconnect #{})", reconnect_count);

        loop {
            tokio::select! {
                _ = shutdown_rx.recv() => {
                    log::info!("Bridge task received shutdown signal");
                    shutdown_requested = true;
                    break;
                }
                _ = interval.tick() => {
                    // 1. Read audio analysis + VJ sender + connection state (brief lock)
                    let (analysis, tx, conn_state_opt) = {
                        let app_state = state_arc.lock();
                        let analysis = app_state.audio_capture.as_ref()
                            .map(|c| c.get_analysis());
                        let tx = app_state.client.as_ref()
                            .and_then(|c| c.get_tx_clone());
                        let conn_state = app_state.client.as_ref()
                            .map(|c| c.get_state());
                        (analysis, tx, conn_state)
                    };
                    // Lock dropped

                    // If no client tx, connection is lost
                    let tx = match tx {
                        Some(tx) => tx,
                        None => {
                            // Client disconnected externally
                            let mut app_state = state_arc.lock();
                            app_state.status.connected = false;
                            app_state.status.error = Some("Connection lost".to_string());
                            break;
                        }
                    };
                    let conn_state = match conn_state_opt {
                        Some(s) => s,
                        None => {
                            let mut app_state = state_arc.lock();
                            app_state.status.connected = false;
                            app_state.status.error = Some("Connection lost".to_string());
                            break;
                        }
                    };

                    // 1.5 Manage direct MC route (dual publish) based on stream_route policy.
                    let desired_route = resolve_direct_mc_route(&conn_state);
                    let desired_target_key = desired_route
                        .as_ref()
                        .map(|r| format!("{}:{}:{}", r.host, r.port, r.zone));

                    if desired_route.is_none() {
                        if let Some(shutdown) = mc_shutdown_tx.take() {
                            let _ = shutdown.send(()).await;
                        }
                        mc_tx = None;
                        mc_target_key = None;
                        mc_pool_key = None;
                    } else if (mc_target_key != desired_target_key || mc_tx.is_none())
                        && Instant::now() >= next_mc_connect_attempt
                    {
                        if let Some(route) = desired_route.as_ref() {
                            match start_direct_mc_session(route).await {
                                Ok((direct_tx, direct_shutdown)) => {
                                    mc_tx = Some(direct_tx);
                                    mc_shutdown_tx = Some(direct_shutdown);
                                    mc_target_key = desired_target_key;
                                    mc_pool_key = None;
                                    log::info!(
                                        "Direct MC dual-publish enabled -> {}:{} ({}, entities={})",
                                        route.host, route.port, route.zone, route.entity_count
                                    );
                                }
                                Err(e) => {
                                    log::warn!("{}", e);
                                    next_mc_connect_attempt =
                                        Instant::now() + Duration::from_secs(2);
                                }
                            }
                        }
                    }

                    // 2. Send audio frame if we have analysis data
                    // Hoist beat output vars for use in UI event emission (section 3)
                    let mut out_is_beat = analysis.as_ref().map_or(false, |a| a.is_beat);
                    let mut out_beat_intensity = analysis.as_ref().map_or(0.0, |a| a.beat_intensity);
                    if let Some(ref analysis) = analysis {
                        let seq = FRAME_SEQ.fetch_add(1, Ordering::Relaxed);
                        let now_secs = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap()
                            .as_secs_f64();

                        // Phase-aware beat assist: when tempo lock is strong and phase is near
                        // the beat boundary, emit a conservative predicted beat so both VJ and
                        // direct MC routes stay visually tight.
                        if !out_is_beat && analysis.tempo_confidence >= 0.60 && analysis.bpm >= 60.0 {
                            let beat_period = 60.0_f64 / analysis.bpm as f64;
                            let phase = analysis.beat_phase.clamp(0.0, 1.0);
                            let near_boundary = !(0.08..=0.92).contains(&phase);
                            let can_fire = last_phase_predicted_beat_at <= 0.0
                                || (now_secs - last_phase_predicted_beat_at) >= (beat_period * 0.60);
                            if near_boundary && can_fire {
                                out_is_beat = true;
                                out_beat_intensity =
                                    out_beat_intensity.max((0.50 + analysis.tempo_confidence * 0.25).clamp(0.0, 1.0));
                                last_phase_predicted_beat_at = now_secs;
                            }
                        }

                        // Use bass lane kick to supplement beat detection
                        if !out_is_beat && analysis.instant_kick {
                            out_is_beat = true;
                            out_beat_intensity = out_beat_intensity.max(0.5);
                        }

                        let msg = AudioFrameMessage::new(
                            seq,
                            analysis.bands,
                            analysis.peak,
                            out_is_beat,
                            out_beat_intensity,
                            analysis.bpm,
                            analysis.tempo_confidence,
                            analysis.beat_phase,
                            analysis.instant_bass,
                            analysis.instant_kick,
                        );

                        if let Ok(json) = serde_json::to_string(&msg) {
                            if tx.send(Message::Text(json.into())).await.is_err() {
                                log::error!("Failed to send audio frame - channel closed");
                                let mut app_state = state_arc.lock();
                                app_state.status.connected = false;
                                app_state.status.error = Some("Connection lost".to_string());
                                break;
                            }
                        }

                        // Dual publish: send full batch_update directly to MC,
                        // bypassing the VJ relay hop for ~10-20ms latency savings.
                        if let (Some(direct_tx), Some(route)) = (mc_tx.as_ref(), desired_route.as_ref()) {
                            // Pace direct MC sends to ~20fps (every 45ms) to match MC's 20 TPS.
                            // VJ server still gets 60fps for browser preview + admin panel.
                            if last_mc_send.elapsed() >= Duration::from_millis(45) {
                                let pool_key = format!(
                                    "{}:{}:{}:{}",
                                    route.host, route.port, route.zone, route.entity_count
                                );
                                if mc_pool_key.as_ref() != Some(&pool_key) {
                                    let init_msg = json!({
                                        "type": "init_pool",
                                        "zone": route.zone,
                                        "count": route.entity_count,
                                        "material": "SEA_LANTERN"
                                    })
                                    .to_string();
                                    if direct_tx.send(Message::Text(init_msg.into())).await.is_err() {
                                        mc_tx = None;
                                        if let Some(shutdown) = mc_shutdown_tx.take() {
                                            let _ = shutdown.send(()).await;
                                        }
                                        next_mc_connect_attempt = Instant::now() + Duration::from_secs(2);
                                        log::warn!("Direct MC init_pool failed; will retry");
                                        continue;
                                    }
                                    mc_pool_key = Some(pool_key);
                                }

                                let entities = if let Some(ref engine) = pattern_engine {
                                    let lua_entities = engine.calculate_entities(analysis, seq);
                                    if lua_entities.is_empty() {
                                        build_direct_entities(analysis, route.entity_count as usize, seq)
                                    } else {
                                        lua_entities
                                    }
                                } else {
                                    build_direct_entities(analysis, route.entity_count as usize, seq)
                                };
                                let particles = if out_is_beat && out_beat_intensity > 0.2 {
                                    vec![json!({
                                        "particle": "NOTE",
                                        "x": 0.5,
                                        "y": 0.5,
                                        "z": 0.5,
                                        "count": ((out_beat_intensity * 24.0).round() as i32).clamp(1, 100)
                                    })]
                                } else {
                                    Vec::new()
                                };
                                let mc_msg = json!({
                                    "type": "batch_update",
                                    "zone": route.zone,
                                    "entities": entities,
                                    "particles": particles,
                                    "bands": analysis.bands,
                                    "amplitude": analysis.peak,
                                    "is_beat": out_is_beat,
                                    "beat_intensity": out_beat_intensity,
                                    "bpm": analysis.bpm,
                                    "tempo_confidence": analysis.tempo_confidence,
                                    "beat_phase": analysis.beat_phase,
                                    "i_bass": analysis.instant_bass,
                                    "i_kick": analysis.instant_kick,
                                    "frame": seq,
                                    "source_id": "dj_tauri_client",
                                    "stream_seq": seq
                                })
                                .to_string();

                                if direct_tx.send(Message::Text(mc_msg.into())).await.is_err() {
                                    mc_tx = None;
                                    if let Some(shutdown) = mc_shutdown_tx.take() {
                                        let _ = shutdown.send(()).await;
                                    }
                                    mc_pool_key = None;
                                    next_mc_connect_attempt = Instant::now() + Duration::from_secs(2);
                                    log::warn!("Direct MC dual-publish channel closed; will retry");
                                } else {
                                    last_mc_send = Instant::now();
                                }
                            }
                        }
                    }

                    // 2.5 Send voice audio frames if streaming is enabled
                    {
                        let voice_streamer = {
                            let app_state = state_arc.lock();
                            app_state.voice_streamer.clone()
                        };

                        if let Some(ref streamer) = voice_streamer {
                            if streamer.is_enabled() {
                                let frames = streamer.drain_frames();
                                // Send at most 3 frames per tick (~48ms at 16ms ticks)
                                // to avoid flooding the WebSocket
                                for (data, seq, codec) in frames.into_iter().take(3) {
                                    let voice_msg = protocol::VoiceAudioMessage::new(data, seq, codec);
                                    if let Ok(json) = serde_json::to_string(&voice_msg) {
                                        if tx.send(Message::Text(json.into())).await.is_err() {
                                            log::error!("Failed to send voice frame - channel closed");
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Update connection state from DjClient (brief lock, no events)
                    let (status_snapshot, voice_snapshot, preset_changed) = {
                        let mc_is_connected = mc_tx.is_some();
                        let mut app_state = state_arc.lock();
                        // Update the atomic flag for heartbeats before borrowing mutably
                        if let Some(ref client) = app_state.client {
                            client.set_mc_connected(mc_is_connected);
                        }

                        // Check for preset_sync from server
                        let mut preset_event: Option<String> = None;
                        if let Some(ref client) = app_state.client {
                            if let Some(preset_name) = client.take_pending_preset() {
                                if let Some(preset) = audio::get_preset(&preset_name) {
                                    if let Some(ref capture) = app_state.audio_capture {
                                        capture.analyzer().lock().apply_preset(&preset);
                                    }
                                    app_state.active_preset = preset.name.clone();
                                    preset_event = Some(preset.name.clone());
                                }
                            }
                        }

                        // Consume pending pattern data from server
                        if let Some(ref client) = app_state.client {
                            // Load pattern scripts
                            if let Some(scripts) = client.take_pending_pattern_scripts() {
                                let engine = pattern_engine.get_or_insert_with(patterns::PatternEngine::new);
                                // Look for lib script first
                                if let Some(lib_src) = scripts.get("lib") {
                                    if let Err(e) = engine.load_lib(lib_src) {
                                        log::warn!("Failed to load lib.lua: {}", e);
                                    }
                                }
                                for (name, src) in &scripts {
                                    if name != "lib" {
                                        engine.load_pattern(name, src);
                                    }
                                }
                                log::info!("Loaded {} pattern scripts from server", scripts.len());
                            }

                            // Switch pattern
                            if let Some(pattern_name) = client.take_pending_pattern_change() {
                                if let Some(ref mut engine) = pattern_engine {
                                    if let Err(e) = engine.set_pattern(&pattern_name) {
                                        log::warn!("Failed to switch pattern: {}", e);
                                    }
                                }
                            }

                            // Update band sensitivity
                            if let Some(sensitivity) = client.take_pending_band_sensitivity() {
                                if let Some(ref mut engine) = pattern_engine {
                                    engine.set_band_sensitivity(sensitivity);
                                }
                            }

                            // Update config (entity_count, zone)
                            if let Some((entity_count, _zone)) = client.take_pending_config_change() {
                                if let Some(ref mut engine) = pattern_engine {
                                    let mut cfg = patterns::PatternConfig::default();
                                    cfg.entity_count = entity_count;
                                    engine.set_config(cfg);
                                }
                            }
                        }

                        if let Some(ref client) = app_state.client {
                            let latest = client.get_state();
                            app_state.status.is_active = latest.is_active;
                            app_state.status.latency_ms = latest.latency_ms;
                            app_state.status.route_mode = latest.route_mode;
                            app_state.status.mc_connected = mc_is_connected;
                            if !latest.connected {
                                app_state.status.connected = false;
                                app_state.status.error = Some("Server disconnected".to_string());
                                app_state.status.mc_connected = false;
                            }

                            // Sync voice status from server messages
                            app_state.voice_status.available = latest.voice_available;
                            app_state.voice_status.streaming = latest.voice_streaming;
                            if let Some(ref ct) = latest.voice_channel_type {
                                app_state.voice_status.channel_type = ct.clone();
                            }
                            if let Some(players) = latest.voice_connected_players {
                                app_state.voice_status.connected_players = players;
                            }
                        }

                        // Clone data for events — lock is released after this block
                        (app_state.status.clone(), app_state.voice_status.clone(), preset_event)
                    };
                    // state_arc lock dropped — emit events without holding any lock

                    if let Some(ref preset_name) = preset_changed {
                        let _ = app_handle.emit("preset-changed", preset_name);
                    }

                    // Audio levels: emit at ~30fps, but always emit immediately on beat
                    if let Some(ref analysis) = analysis {
                        let is_beat_frame = analysis.is_beat || out_is_beat;
                        if is_beat_frame || last_audio_emit.elapsed() >= Duration::from_millis(33) {
                            let _ = app_handle.emit("audio-levels", AudioLevels {
                                bands: analysis.bands,
                                peak: analysis.peak,
                                is_beat: out_is_beat,
                                beat_intensity: out_beat_intensity,
                                bpm: analysis.bpm,
                            });
                            last_audio_emit = Instant::now();
                        }
                    }

                    // Status + voice: emit at ~4fps OR immediately on change
                    {
                        use std::hash::{Hash, Hasher};
                        let mut h = std::collections::hash_map::DefaultHasher::new();
                        format!("{:?}", status_snapshot).hash(&mut h);
                        let s_hash = h.finish();

                        let mut h2 = std::collections::hash_map::DefaultHasher::new();
                        format!("{:?}", voice_snapshot).hash(&mut h2);
                        let v_hash = h2.finish();

                        let status_changed = s_hash != prev_status_hash;
                        let voice_changed = v_hash != prev_voice_hash;
                        let throttle_elapsed = last_status_emit.elapsed() >= Duration::from_millis(250);

                        if status_changed || throttle_elapsed {
                            let _ = app_handle.emit("dj-status", &status_snapshot);
                            // Update tray tooltip when connection status changes
                            if status_changed {
                                update_tray_tooltip(&app_handle, status_snapshot.connected);
                            }
                            prev_status_hash = s_hash;
                        }
                        if voice_changed || throttle_elapsed {
                            let _ = app_handle.emit("voice-status", &voice_snapshot);
                            prev_voice_hash = v_hash;
                        }
                        if status_changed || voice_changed || throttle_elapsed {
                            last_status_emit = Instant::now();
                        }
                    }
                }
            }
        }

        // Cleanup current connection
        log::info!("Bridge task cleaning up");
        let client = {
            let mut app_state = state_arc.lock();
            app_state.client.take()
        };
        if let Some(client) = client {
            let _ = client.disconnect().await;
        }
        if let Some(shutdown) = mc_shutdown_tx {
            let _ = shutdown.send(()).await;
        }
        {
            let mut app_state = state_arc.lock();
            app_state.status.connected = false;
            app_state.status.mc_connected = false;
        }

        // If shutdown was explicitly requested, do not reconnect
        if shutdown_requested {
            let mut app_state = state_arc.lock();
            app_state.bridge_shutdown_tx = None;
            log::info!("Bridge task stopped (user disconnect)");
            break 'reconnect;
        }

        // Auto-reconnect with exponential backoff
        reconnect_count += 1;
        if reconnect_count > MAX_RECONNECT_ATTEMPTS {
            let mut app_state = state_arc.lock();
            app_state.bridge_shutdown_tx = None;
            app_state.status.error = Some("Connection lost (max retries reached)".to_string());
            let _ = app_handle.emit("dj-status", &app_state.status);
            log::error!(
                "Bridge task gave up after {} reconnect attempts",
                MAX_RECONNECT_ATTEMPTS
            );
            break 'reconnect;
        }

        let delay_secs = std::cmp::min(1u64 << (reconnect_count - 1), MAX_RECONNECT_DELAY_SECS);
        log::info!(
            "Reconnecting in {}s (attempt {}/{})",
            delay_secs,
            reconnect_count,
            MAX_RECONNECT_ATTEMPTS
        );
        {
            let mut app_state = state_arc.lock();
            app_state.status.error = Some(format!(
                "Reconnecting in {}s ({}/{})",
                delay_secs, reconnect_count, MAX_RECONNECT_ATTEMPTS
            ));
            let _ = app_handle.emit("dj-status", &app_state.status);
        }

        // Wait for backoff delay or shutdown signal
        tokio::select! {
            _ = tokio::time::sleep(Duration::from_secs(delay_secs)) => {}
            _ = shutdown_rx.recv() => {
                let mut app_state = state_arc.lock();
                app_state.bridge_shutdown_tx = None;
                log::info!("Bridge task stopped during reconnect backoff (user disconnect)");
                break 'reconnect;
            }
        }

        // Attempt to reconnect using stored config
        let reconnect_result = {
            let app_state = state_arc.lock();
            let config = DjClientConfig {
                server_host: app_state.server_host.clone(),
                server_port: app_state.server_port,
                dj_name: app_state.dj_name.clone(),
                connect_code: app_state.connect_code.clone(),
                dj_id: Some(format!("tauri_dj_{:08x}", rand::random::<u32>())),
                dj_key: if app_state.connect_code.is_none() {
                    Some(String::new())
                } else {
                    None
                },
                ..Default::default()
            };
            config
        };

        let mut client = DjClient::new(reconnect_result);
        match client.connect().await {
            Ok(()) => {
                let mut app_state = state_arc.lock();
                app_state.client = Some(client);
                app_state.status.connected = true;
                app_state.status.error = None;
                let _ = app_handle.emit("dj-status", &app_state.status);
                log::info!("Reconnected successfully");
                reconnect_count = 0;
                continue 'reconnect;
            }
            Err(e) => {
                log::warn!("Reconnect failed: {}", e);
                continue 'reconnect;
            }
        }
    } // end 'reconnect loop
}

/// Start audio capture from selected source
#[tauri::command]
async fn start_capture(
    state: State<'_, AppStateWrapper>,
    source_id: Option<String>,
) -> Result<(), String> {
    // Create voice streamer (48kHz, stereo assumed; resampling handles mismatches)
    let voice_streamer = Arc::new(VoiceStreamer::new(48000, 2));

    // Propagate current voice config
    {
        let app_state = state.0.lock();
        voice_streamer.set_enabled(app_state.voice_config.enabled);
    }

    let capture = AudioCaptureHandle::new_with_voice(source_id, Some(voice_streamer.clone()))
        .map_err(|e| e.to_string())?;

    let mut app_state = state.0.lock();

    // Apply the active preset to the new analyzer
    if let Some(preset) = audio::get_preset(&app_state.active_preset) {
        capture.analyzer().lock().apply_preset(&preset);
    }

    app_state.audio_capture = Some(capture);
    app_state.voice_streamer = Some(voice_streamer);

    Ok(())
}

/// Stop audio capture
#[tauri::command]
async fn stop_capture(state: State<'_, AppStateWrapper>) -> Result<(), String> {
    let mut app_state = state.0.lock();
    if let Some(mut capture) = app_state.audio_capture.take() {
        capture.stop();
    }
    Ok(())
}

/// Disconnect from VJ server
#[tauri::command]
async fn disconnect(state: State<'_, AppStateWrapper>) -> Result<(), String> {
    // Signal bridge task to stop (it handles client disconnect)
    let (shutdown_tx, capture, voice_streamer) = {
        let mut app_state = state.0.lock();
        (
            app_state.bridge_shutdown_tx.take(),
            app_state.audio_capture.take(),
            app_state.voice_streamer.take(),
        )
    };

    // Disable voice streaming
    if let Some(ref streamer) = voice_streamer {
        streamer.set_enabled(false);
    }

    if let Some(tx) = shutdown_tx {
        let _ = tx.send(()).await;
        // Give bridge task a moment to clean up
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    } else {
        // No bridge task running, disconnect client directly
        let client = {
            let mut app_state = state.0.lock();
            app_state.client.take()
        };
        if let Some(client) = client {
            let _ = client.disconnect().await;
        }
    }

    // Stop audio capture
    if let Some(mut capture) = capture {
        capture.stop();
    }

    // Reset status
    {
        let mut app_state = state.0.lock();
        app_state.status.connected = false;
        app_state.status.is_active = false;
        app_state.status.latency_ms = 0.0;
        app_state.status.route_mode = String::new();
        app_state.status.mc_connected = false;
        app_state.status.error = None;
        app_state.voice_config.enabled = false;
        app_state.voice_status = VoiceStatus::default();
    }

    Ok(())
}

/// Enable or disable voice audio streaming
#[tauri::command]
async fn set_voice_streaming(
    app_handle: AppHandle,
    state: State<'_, AppStateWrapper>,
    enabled: bool,
) -> Result<(), String> {
    let (voice_streamer, tx, voice_config) = {
        let mut app_state = state.0.lock();
        app_state.voice_config.enabled = enabled;

        let streamer = app_state.voice_streamer.clone();
        let tx = app_state
            .client
            .as_ref()
            .and_then(|c| c.get_tx_clone());
        let config = app_state.voice_config.clone();
        (streamer, tx, config)
    };

    // Update streamer state
    if let Some(ref streamer) = voice_streamer {
        streamer.set_enabled(enabled);
    }

    // Send voice_config message to VJ server
    if let Some(tx) = tx {
        let msg = protocol::VoiceConfigMessage::new(
            enabled,
            voice_config.channel_type.clone(),
            voice_config.distance,
            voice_config.zone.clone(),
        );
        if let Ok(json) = serde_json::to_string(&msg) {
            let _ = tx.send(Message::Text(json.into())).await;
        }
    }

    // Emit updated voice status
    {
        let app_state = state.0.lock();
        let _ = app_handle.emit("voice-status", &app_state.voice_status);
    }

    Ok(())
}

/// Get current voice streaming status
#[tauri::command]
fn get_voice_status(state: State<'_, AppStateWrapper>) -> VoiceStatus {
    let app_state = state.0.lock();
    app_state.voice_status.clone()
}

/// Update voice streaming configuration
#[tauri::command]
async fn set_voice_config(
    state: State<'_, AppStateWrapper>,
    channel_type: String,
    distance: f64,
) -> Result<(), String> {
    let tx = {
        let mut app_state = state.0.lock();
        app_state.voice_config.channel_type = channel_type.clone();
        app_state.voice_config.distance = distance;

        app_state
            .client
            .as_ref()
            .and_then(|c| c.get_tx_clone())
    };

    // Send updated voice_config to server if connected
    if let Some(tx) = tx {
        let config = {
            let app_state = state.0.lock();
            app_state.voice_config.clone()
        };
        let msg = protocol::VoiceConfigMessage::new(
            config.enabled,
            config.channel_type,
            config.distance,
            config.zone,
        );
        if let Ok(json) = serde_json::to_string(&msg) {
            let _ = tx.send(Message::Text(json.into())).await;
        }
    }

    Ok(())
}

/// List available audio presets
#[tauri::command]
fn list_presets() -> Vec<AudioPreset> {
    audio::get_presets()
}

/// Get the currently active preset name
#[tauri::command]
fn get_current_preset(state: State<'_, AppStateWrapper>) -> String {
    state.0.lock().active_preset.clone()
}

/// Apply an audio preset by name
#[tauri::command]
fn set_preset(state: State<'_, AppStateWrapper>, name: String) -> Result<String, String> {
    let preset = audio::get_preset(&name).ok_or_else(|| format!("Unknown preset: {}", name))?;
    let mut app_state = state.0.lock();
    if let Some(ref capture) = app_state.audio_capture {
        capture.analyzer().lock().apply_preset(&preset);
    }
    app_state.active_preset = preset.name.clone();
    Ok(preset.name)
}

/// Get current connection status
#[tauri::command]
fn get_status(state: State<'_, AppStateWrapper>) -> state::ConnectionStatus {
    let app_state = state.0.lock();
    app_state.status.clone()
}

/// Get current audio levels (5 bands + peak + beat info)
#[tauri::command]
fn get_audio_levels(state: State<'_, AppStateWrapper>) -> AudioLevels {
    let mut app_state = state.0.lock();

    // Get latest analysis from capture if running
    if let Some(ref capture) = app_state.audio_capture {
        let result = capture.get_analysis();
        app_state.bands = result.bands;
        app_state.peak = result.peak;
        app_state.is_beat = result.is_beat;
        app_state.beat_intensity = result.beat_intensity;
        app_state.bpm = result.bpm;
    }

    AudioLevels {
        bands: app_state.bands,
        peak: app_state.peak,
        is_beat: app_state.is_beat,
        beat_intensity: app_state.beat_intensity,
        bpm: app_state.bpm,
    }
}

/// Audio levels response
#[derive(Clone, serde::Serialize)]
pub struct AudioLevels {
    pub bands: [f32; 5],
    pub peak: f32,
    pub is_beat: bool,
    pub beat_intensity: f32,
    pub bpm: f32,
}

/// Update the system tray tooltip based on connection status
fn update_tray_tooltip(app: &AppHandle, connected: bool) {
    if let Some(tray) = app.tray_by_id("main-tray") {
        let tooltip = if connected {
            "MCAV DJ - Connected"
        } else {
            "MCAV DJ - Disconnected"
        };
        let _ = tray.set_tooltip(Some(tooltip));
    }
}

/// Show the main window
#[tauri::command]
fn show_window(app: AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
    }
    Ok(())
}

/// Initialize the Tauri application
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    env_logger::init();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(AppStateWrapper(Arc::new(Mutex::new(AppState::default()))))
        .invoke_handler(tauri::generate_handler![
            list_audio_sources,
            connect_with_code,
            connect_direct,
            start_capture,
            stop_capture,
            disconnect,
            get_status,
            get_audio_levels,
            set_voice_streaming,
            get_voice_status,
            set_voice_config,
            list_presets,
            get_current_preset,
            set_preset,
            show_window,
        ])
        .setup(|app| {
            // Create system tray menu
            let show_item = MenuItem::with_id(app, "show", "Show Window", true, None::<&str>)?;
            let disconnect_item = MenuItem::with_id(app, "disconnect", "Disconnect", true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;

            let menu = Menu::with_items(
                app,
                &[
                    &show_item,
                    &disconnect_item,
                    &quit_item,
                ],
            )?;

            // Create system tray icon
            let _tray = TrayIconBuilder::with_id("main-tray")
                .tooltip("MCAV DJ - Disconnected")
                .icon(app.default_window_icon().unwrap().clone())
                .menu(&menu)
                .on_menu_event(|app, event| {
                    match event.id.as_ref() {
                        "show" => {
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                        "disconnect" => {
                            let state = app.state::<AppStateWrapper>();
                            let is_connected = state.0.lock().status.connected;
                            if is_connected {
                                let app_clone = app.clone();
                                tauri::async_runtime::spawn(async move {
                                    let _ = disconnect(app_clone.state::<AppStateWrapper>()).await;
                                });
                            }
                        }
                        "quit" => {
                            app.exit(0);
                        }
                        _ => {}
                    }
                })
                .on_tray_icon_event(|tray, event| {
                    // Double-click to show window
                    if let TrayIconEvent::DoubleClick { .. } = event {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                })
                .build(app)?;

            // Handle window close event - minimize to tray instead of quitting
            if let Some(window) = app.get_webview_window("main") {
                let window_clone = window.clone();
                window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        // Prevent default close behavior
                        api.prevent_close();
                        // Hide the window instead
                        let _ = window_clone.hide();
                    }
                });
            }

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
