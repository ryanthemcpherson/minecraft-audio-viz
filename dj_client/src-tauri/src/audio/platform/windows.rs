//! Windows WASAPI per-application audio capture
//!
//! Note: Full per-app capture requires complex COM interactions with Windows Audio
//! Session API. For the MVP, we provide a stub that returns an empty list.
//! System audio capture via cpal works correctly.
//!
//! TODO: Implement full per-app audio capture using WASAPI AudioSessionManager

use crate::audio::sources::{AudioSource, SourceType};

/// List audio applications currently playing audio on Windows
///
/// Note: This is a stub for the MVP. Full implementation would use
/// IAudioSessionManager2 to enumerate active audio sessions.
#[cfg(target_os = "windows")]
pub fn list_audio_applications() -> Result<Vec<AudioSource>, String> {
    // For MVP, just return common audio applications as suggestions
    // The user can select "System Audio" which works correctly

    // TODO: Implement proper WASAPI session enumeration
    // This requires:
    // 1. CoCreateInstance(MMDeviceEnumerator)
    // 2. GetDefaultAudioEndpoint
    // 3. Activate<IAudioSessionManager2>
    // 4. GetSessionEnumerator
    // 5. Iterate sessions and get process IDs

    Ok(vec![
        // Return empty for now - system audio is the main capture method
    ])
}

/// Fallback for non-Windows platforms
#[cfg(not(target_os = "windows"))]
pub fn list_audio_applications() -> Result<Vec<AudioSource>, String> {
    Ok(Vec::new())
}
