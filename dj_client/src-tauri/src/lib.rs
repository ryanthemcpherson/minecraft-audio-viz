//! MCAV DJ Client Library
//!
//! Cross-platform DJ client for connecting to VJ servers and streaming
//! audio visualizations to Minecraft.

pub mod audio;
pub mod protocol;
pub mod state;

use audio::{AudioCaptureHandle, AudioSource};
use protocol::{AudioFrameMessage, DjClient, DjClientConfig};
use state::AppState;

use futures_util::{SinkExt, StreamExt};
use parking_lot::Mutex;
use serde_json::json;
use std::f32::consts::TAU;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, State};
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
        return ip.is_loopback()             // 127.*
            || ip.octets()[0] == 10         // 10.*
            || (ip.octets()[0] == 172 && (16..=31).contains(&ip.octets()[1]))  // 172.16-31.*
            || (ip.octets()[0] == 192 && ip.octets()[1] == 168);               // 192.168.*
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

fn env_flag_enabled(name: &str) -> bool {
    std::env::var(name)
        .map(|v| {
            matches!(
                v.trim().to_ascii_lowercase().as_str(),
                "1" | "true" | "yes" | "on"
            )
        })
        .unwrap_or(false)
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
    let scheme = if is_local_host(&route.host) { "ws" } else { "wss" };
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

    // Reader (respond to ping from Minecraft heartbeat loop)
    let tx_reader = tx.clone();
    tokio::spawn(async move {
        while let Some(msg) = read.next().await {
            match msg {
                Ok(Message::Text(text)) => {
                    if let Ok(data) = serde_json::from_str::<serde_json::Value>(&text) {
                        if data.get("type").and_then(|t| t.as_str()) == Some("ping") {
                            let _ = tx_reader
                                .send(Message::Text(
                                    serde_json::json!({ "type": "pong" }).to_string().into(),
                                ))
                                .await;
                        }
                    }
                }
                Ok(Message::Close(_)) | Err(_) => break,
                _ => {}
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

    // Brief lock: store metadata, check not already connected
    {
        let app_state = state.0.lock();
        if app_state.bridge_shutdown_tx.is_some() {
            return Err("Already connected".to_string());
        }
    }

    {
        let mut app_state = state.0.lock();
        app_state.connect_code = Some(code);
        app_state.dj_name = dj_name;
        app_state.server_host = server_host;
        app_state.server_port = server_port;
    }
    // Lock dropped

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

    // Brief lock: check not already connected
    {
        let app_state = state.0.lock();
        if app_state.bridge_shutdown_tx.is_some() {
            return Err("Already connected".to_string());
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
    let direct_batch_enabled = env_flag_enabled("MCAV_DIRECT_BATCH_UPDATE");
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
        // Track whether this iteration exited due to explicit shutdown
        let mut shutdown_requested = false;

        log::info!(
            "Bridge task started (direct batch mode: {}, reconnect #{})",
            if direct_batch_enabled {
                "enabled"
            } else {
                "disabled"
            },
            reconnect_count
        );

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
                    if let Some(ref analysis) = analysis {
                        let seq = FRAME_SEQ.fetch_add(1, Ordering::Relaxed);
                        let now_secs = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap()
                            .as_secs_f64();

                        // Phase-aware beat assist: when tempo lock is strong and phase is near
                        // the beat boundary, emit a conservative predicted beat so both VJ and
                        // direct MC routes stay visually tight.
                        let mut out_is_beat = analysis.is_beat;
                        let mut out_beat_intensity = analysis.beat_intensity;
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

                        let msg = AudioFrameMessage::new(
                            seq,
                            analysis.bands,
                            analysis.peak,
                            out_is_beat,
                            out_beat_intensity,
                            analysis.bpm,
                            analysis.tempo_confidence,
                            analysis.beat_phase,
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

                        // Dual publish: route policy controls whether MC receives audio_state
                        // (legacy) or full batch_update (feature flagged).
                        if let (Some(direct_tx), Some(route)) = (mc_tx.as_ref(), desired_route.as_ref()) {
                            let mc_msg = if direct_batch_enabled {
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

                                let entities =
                                    build_direct_entities(analysis, route.entity_count as usize, seq);
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
                                json!({
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
                                    "frame": seq,
                                    "source_id": "dj_tauri_client",
                                    "stream_seq": seq
                                })
                                .to_string()
                            } else {
                                json!({
                                    "type": "audio_state",
                                    "zone": route.zone,
                                    "bands": analysis.bands,
                                    "amplitude": analysis.peak,
                                    "is_beat": out_is_beat,
                                    "beat_intensity": out_beat_intensity,
                                    "bpm": analysis.bpm,
                                    "tempo_confidence": analysis.tempo_confidence,
                                    "beat_phase": analysis.beat_phase,
                                    "frame": seq
                                })
                                .to_string()
                            };

                            if direct_tx.send(Message::Text(mc_msg.into())).await.is_err() {
                                mc_tx = None;
                                if let Some(shutdown) = mc_shutdown_tx.take() {
                                    let _ = shutdown.send(()).await;
                                }
                                mc_pool_key = None;
                                next_mc_connect_attempt = Instant::now() + Duration::from_secs(2);
                                log::warn!("Direct MC dual-publish channel closed; will retry");
                            }
                        }
                    }

                    // 3. Update connection state from DjClient and emit events
                    {
                        let mut app_state = state_arc.lock();
                        if let Some(ref client) = app_state.client {
                            let latest = client.get_state();
                            app_state.status.is_active = latest.is_active;
                            app_state.status.latency_ms = latest.latency_ms;
                            app_state.status.route_mode = latest.route_mode;
                            app_state.status.mc_connected = mc_tx.is_some();
                            if !latest.connected {
                                app_state.status.connected = false;
                                app_state.status.error = Some("Server disconnected".to_string());
                                app_state.status.mc_connected = false;
                            }
                        }

                        // Push audio levels and status to frontend via events
                        if let Some(ref analysis) = analysis {
                            let _ = app_handle.emit("audio-levels", AudioLevels {
                                bands: analysis.bands,
                                peak: analysis.peak,
                                is_beat: analysis.is_beat,
                                beat_intensity: analysis.beat_intensity,
                                bpm: analysis.bpm,
                            });
                        }
                        let _ = app_handle.emit("dj-status", &app_state.status);
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
    let capture = AudioCaptureHandle::new(source_id).map_err(|e| e.to_string())?;

    let mut app_state = state.0.lock();
    app_state.audio_capture = Some(capture);

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
    let (shutdown_tx, capture) = {
        let mut app_state = state.0.lock();
        (
            app_state.bridge_shutdown_tx.take(),
            app_state.audio_capture.take(),
        )
    };

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
    }

    Ok(())
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
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
