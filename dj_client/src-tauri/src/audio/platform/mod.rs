//! Platform-specific audio capture implementations
//!
//! Each platform module provides:
//! - `list_audio_applications()` - enumerate apps currently producing audio
//!
//! Audio capture itself is handled cross-platform by cpal in capture.rs.
//! These modules provide platform-specific enumeration features.

#[cfg(target_os = "windows")]
pub mod windows;

#[cfg(target_os = "macos")]
pub mod macos;

#[cfg(target_os = "linux")]
pub mod linux;
