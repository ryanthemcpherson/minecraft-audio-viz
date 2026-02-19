//! macOS audio application enumeration
//!
//! Uses CoreAudio to enumerate audio sessions on macOS.
//! System audio capture is handled by cpal (uses CoreAudio backend).
//!
//! Note: macOS does not natively support per-application audio capture
//! without a virtual audio driver (e.g., BlackHole, Loopback by Rogue Amoeba).
//! This module enumerates applications that are currently producing audio
//! so users can see what's playing, but actual capture uses system loopback.
//!
//! For true per-app capture on macOS, users need to install a virtual audio
//! device and route the target app's audio through it.

use crate::audio::sources::{AudioSource, SourceType};
use std::process::Command;

/// List audio applications currently producing audio on macOS.
///
/// Uses `coreaudiod` process inspection and the `lsof` command to find
/// processes that have audio devices open. This is a best-effort approach
/// since macOS doesn't expose per-session audio enumeration like WASAPI.
pub fn list_audio_applications() -> Result<Vec<AudioSource>, String> {
    let mut sources = Vec::new();

    // Use `lsof` to find processes with audio device file descriptors
    // CoreAudio processes open /dev/audio* or use IOAudioFamily
    let output = Command::new("sh")
        .arg("-c")
        .arg("lsof -c '' 2>/dev/null | grep -i 'coreaudio\\|audioqueue\\|auhal' | awk '{print $1, $2}' | sort -u")
        .output();

    match output {
        Ok(result) => {
            let stdout = String::from_utf8_lossy(&result.stdout);
            let mut seen_names = std::collections::HashSet::new();

            for line in stdout.lines() {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 {
                    let process_name = parts[0].to_string();
                    let pid = parts[1];

                    // Skip system processes and duplicates
                    if process_name == "coreaudiod"
                        || process_name == "kernel_task"
                        || !seen_names.insert(process_name.clone())
                    {
                        continue;
                    }

                    // Clean up display name
                    let display_name = {
                        let name = process_name.trim_end_matches(".app");
                        let mut chars = name.chars();
                        match chars.next() {
                            None => name.to_string(),
                            Some(c) => c.to_uppercase().to_string() + chars.as_str(),
                        }
                    };

                    sources.push(AudioSource {
                        id: format!("app:{}:{}", pid, process_name),
                        name: display_name,
                        source_type: SourceType::Application,
                    });
                }
            }
        }
        Err(e) => {
            log::warn!("Failed to enumerate macOS audio apps: {}", e);
        }
    }

    // Sort by name
    sources.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));

    Ok(sources)
}
