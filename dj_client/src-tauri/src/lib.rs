//! AudioViz DJ Client Library
//!
//! Cross-platform DJ client for connecting to VJ servers and streaming
//! audio visualizations to Minecraft.

pub mod audio;
pub mod protocol;
pub mod state;

use audio::{AudioCaptureHandle, AudioSource};
use protocol::{AudioFrameMessage, DjClient, DjClientConfig};
use state::AppState;

use parking_lot::Mutex;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use tauri::State;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;

/// Application state wrapper
pub struct AppStateWrapper(pub Arc<Mutex<AppState>>);

/// Sequence counter for audio frames (shared between bridge task and get_audio_levels)
static FRAME_SEQ: AtomicU64 = AtomicU64::new(0);

/// List available audio sources
#[tauri::command]
async fn list_audio_sources() -> Result<Vec<AudioSource>, String> {
    audio::list_sources().map_err(|e| e.to_string())
}

/// Connect to VJ server with connect code
#[tauri::command]
async fn connect_with_code(
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
        run_bridge(state_arc, shutdown_rx).await;
    });

    Ok(())
}

/// Connect to VJ server directly (no connect code needed, for testing)
#[tauri::command]
async fn connect_direct(
    state: State<'_, AppStateWrapper>,
    dj_name: String,
    server_host: String,
    server_port: u16,
) -> Result<(), String> {
    let config = DjClientConfig {
        server_host: server_host.clone(),
        server_port,
        dj_name: dj_name.clone(),
        dj_id: Some("tauri_dj".to_string()),
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
        run_bridge(state_arc, shutdown_rx).await;
    });

    Ok(())
}

/// Bridge task: reads audio analysis and sends frames to VJ server at ~60fps
async fn run_bridge(
    state_arc: Arc<Mutex<AppState>>,
    mut shutdown_rx: mpsc::Receiver<()>,
) {
    let mut interval = tokio::time::interval(std::time::Duration::from_millis(16));
    FRAME_SEQ.store(0, Ordering::Relaxed);

    log::info!("Bridge task started");

    loop {
        tokio::select! {
            _ = shutdown_rx.recv() => {
                log::info!("Bridge task received shutdown signal");
                break;
            }
            _ = interval.tick() => {
                // 1. Read audio analysis and get tx clone (brief lock)
                let (analysis, tx) = {
                    let app_state = state_arc.lock();
                    let analysis = app_state.audio_capture.as_ref()
                        .map(|c| c.get_analysis());
                    let tx = app_state.client.as_ref()
                        .and_then(|c| c.get_tx_clone());
                    (analysis, tx)
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

                // 2. Send audio frame if we have analysis data
                if let Some(analysis) = analysis {
                    let seq = FRAME_SEQ.fetch_add(1, Ordering::Relaxed);

                    let msg = AudioFrameMessage::new(
                        seq,
                        analysis.bands,
                        analysis.peak,
                        analysis.is_beat,
                        analysis.beat_intensity,
                        analysis.bpm,
                    );

                    if let Ok(json) = serde_json::to_string(&msg) {
                        if tx.send(Message::Text(json)).await.is_err() {
                            log::error!("Failed to send audio frame - channel closed");
                            let mut app_state = state_arc.lock();
                            app_state.status.connected = false;
                            app_state.status.error = Some("Connection lost".to_string());
                            break;
                        }
                    }
                }

                // 3. Update connection state from DjClient
                {
                    let mut app_state = state_arc.lock();
                    if let Some(ref client) = app_state.client {
                        let conn_state = client.get_state();
                        app_state.status.is_active = conn_state.is_active;
                        app_state.status.latency_ms = conn_state.latency_ms;
                        if !conn_state.connected {
                            app_state.status.connected = false;
                            app_state.status.error = Some("Server disconnected".to_string());
                        }
                    }
                }
            }
        }
    }

    // Cleanup: disconnect client
    log::info!("Bridge task cleaning up");
    let client = {
        let mut app_state = state_arc.lock();
        app_state.bridge_shutdown_tx = None;
        app_state.client.take()
    };
    if let Some(client) = client {
        let _ = client.disconnect().await;
    }
    {
        let mut app_state = state_arc.lock();
        app_state.status.connected = false;
    }
    log::info!("Bridge task stopped");
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
        (app_state.bridge_shutdown_tx.take(), app_state.audio_capture.take())
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
#[derive(serde::Serialize)]
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
