//! Linux audio application enumeration
//!
//! Uses PulseAudio/PipeWire to enumerate audio streams on Linux.
//! System audio capture is handled by cpal (uses ALSA or PulseAudio backend).
//!
//! Linux audio landscape:
//! - PulseAudio: Most common on desktop Linux, supports per-app stream enumeration
//! - PipeWire: Modern replacement, backward-compatible with PulseAudio CLI tools
//! - ALSA: Low-level, no per-app concept
//!
//! This module uses `pactl` (PulseAudio CLI) which works with both PulseAudio
//! and PipeWire (via pipewire-pulse compatibility layer). For systems using
//! only ALSA, per-app enumeration is not available.

use crate::audio::sources::{AudioSource, SourceType};
use std::process::Command;

/// List audio applications currently producing audio on Linux.
///
/// Uses `pactl list sink-inputs` to enumerate PulseAudio/PipeWire streams.
/// Each sink-input represents an application's audio stream to a particular
/// output device (sink).
pub fn list_audio_applications() -> Result<Vec<AudioSource>, String> {
    let mut sources = Vec::new();

    // Try pactl first (works with both PulseAudio and PipeWire)
    let output = Command::new("pactl").args(["list", "sink-inputs"]).output();

    match output {
        Ok(result) => {
            if !result.status.success() {
                // pactl failed - PulseAudio/PipeWire may not be running
                log::info!("pactl not available, per-app audio enumeration disabled");
                return Ok(sources);
            }

            let stdout = String::from_utf8_lossy(&result.stdout);
            let mut current_name: Option<String> = None;
            let mut current_pid: Option<String> = None;
            let mut current_binary: Option<String> = None;
            let mut seen_pids = std::collections::HashSet::new();

            for line in stdout.lines() {
                let line = line.trim();

                if line.starts_with("Sink Input #") {
                    // Save previous entry if valid
                    if let (Some(name), Some(pid)) = (&current_name, &current_pid) {
                        if !seen_pids.contains(pid) {
                            seen_pids.insert(pid.clone());
                            let binary = current_binary.as_deref().unwrap_or("unknown");
                            sources.push(AudioSource {
                                id: format!("app:{}:{}", pid, binary),
                                name: name.clone(),
                                source_type: SourceType::Application,
                            });
                        }
                    }
                    // Reset for next entry
                    current_name = None;
                    current_pid = None;
                    current_binary = None;
                }

                // Parse properties
                if line.starts_with("application.name = ") {
                    current_name = Some(
                        line.trim_start_matches("application.name = ")
                            .trim_matches('"')
                            .to_string(),
                    );
                } else if line.starts_with("application.process.id = ") {
                    current_pid = Some(
                        line.trim_start_matches("application.process.id = ")
                            .trim_matches('"')
                            .to_string(),
                    );
                } else if line.starts_with("application.process.binary = ") {
                    current_binary = Some(
                        line.trim_start_matches("application.process.binary = ")
                            .trim_matches('"')
                            .to_string(),
                    );
                }
            }

            // Don't forget the last entry
            if let (Some(name), Some(pid)) = (&current_name, &current_pid) {
                if !seen_pids.contains(pid) {
                    let binary = current_binary.as_deref().unwrap_or("unknown");
                    sources.push(AudioSource {
                        id: format!("app:{}:{}", pid, binary),
                        name: name.clone(),
                        source_type: SourceType::Application,
                    });
                }
            }
        }
        Err(_) => {
            // pactl not installed - try pw-cli for PipeWire-only systems
            let pw_output = Command::new("pw-cli").args(["list-objects"]).output();

            match pw_output {
                Ok(result) if result.status.success() => {
                    // PipeWire is available but pactl isn't
                    // Basic enumeration from pw-cli
                    let stdout = String::from_utf8_lossy(&result.stdout);
                    for line in stdout.lines() {
                        if line.contains("PipeWire:Interface:Node")
                            && line.contains("Stream/Output/Audio")
                        {
                            // Extract application name from the line if possible
                            if let Some(name_start) = line.find("node.name = ") {
                                let name = line[name_start + 13..]
                                    .split('"')
                                    .next()
                                    .unwrap_or("Unknown")
                                    .to_string();
                                sources.push(AudioSource {
                                    id: format!("app:0:{}", name),
                                    name,
                                    source_type: SourceType::Application,
                                });
                            }
                        }
                    }
                }
                _ => {
                    log::info!(
                        "No PulseAudio or PipeWire detected, per-app audio enumeration disabled"
                    );
                }
            }
        }
    }

    // Sort by name
    sources.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));

    Ok(sources)
}
