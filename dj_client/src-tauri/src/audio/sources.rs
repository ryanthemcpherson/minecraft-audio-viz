//! Audio source enumeration

use cpal::traits::{DeviceTrait, HostTrait};
use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Audio source information
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioSource {
    /// Unique identifier for the source
    pub id: String,

    /// Display name
    pub name: String,

    /// Source type
    pub source_type: SourceType,
}

/// Type of audio source
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum SourceType {
    /// System-wide audio (loopback)
    SystemAudio,

    /// Per-application capture
    Application,

    /// Input device (microphone)
    InputDevice,
}

/// Audio source errors
#[derive(Error, Debug)]
pub enum SourceError {
    #[error("No audio host available")]
    NoHost,

    #[error("Failed to enumerate devices: {0}")]
    EnumerationError(String),

    #[error("Device not found: {0}")]
    DeviceNotFound(String),
}

/// List available audio sources
pub fn list_sources() -> Result<Vec<AudioSource>, SourceError> {
    let mut sources = Vec::new();

    let host = cpal::default_host();

    // Add system audio loopback as first option (uses default output device)
    if let Some(device) = host.default_output_device() {
        let device_name = device.name().unwrap_or_else(|_| "Unknown".to_string());
        sources.push(AudioSource {
            id: "system_audio".to_string(),
            name: format!("System Audio ({})", device_name),
            source_type: SourceType::SystemAudio,
        });
    }

    // List per-application audio sources (platform-specific)
    #[cfg(target_os = "windows")]
    {
        match super::platform::windows::list_audio_applications() {
            Ok(app_sources) => {
                for source in app_sources {
                    sources.push(source);
                }
            }
            Err(e) => {
                log::warn!("Failed to enumerate audio applications: {}", e);
            }
        }
    }

    #[cfg(target_os = "macos")]
    {
        match super::platform::macos::list_audio_applications() {
            Ok(app_sources) => {
                for source in app_sources {
                    sources.push(source);
                }
            }
            Err(e) => {
                log::warn!("Failed to enumerate audio applications: {}", e);
            }
        }
    }

    #[cfg(target_os = "linux")]
    {
        match super::platform::linux::list_audio_applications() {
            Ok(app_sources) => {
                for source in app_sources {
                    sources.push(source);
                }
            }
            Err(e) => {
                log::warn!("Failed to enumerate audio applications: {}", e);
            }
        }
    }

    // List output devices as loopback sources
    if let Ok(devices) = host.output_devices() {
        for device in devices {
            if let Ok(name) = device.name() {
                // Skip the default output device (already added as "System Audio")
                if host
                    .default_output_device()
                    .and_then(|d| d.name().ok())
                    .map(|n| n == name)
                    .unwrap_or(false)
                {
                    continue;
                }
                sources.push(AudioSource {
                    id: format!("output:{}", name),
                    name: format!("Loopback: {}", name),
                    source_type: SourceType::SystemAudio,
                });
            }
        }
    }

    // List input devices (microphones)
    if let Ok(devices) = host.input_devices() {
        for device in devices {
            if let Ok(name) = device.name() {
                sources.push(AudioSource {
                    id: format!("input:{}", name),
                    name: format!("Input: {}", name),
                    source_type: SourceType::InputDevice,
                });
            }
        }
    }

    Ok(sources)
}
