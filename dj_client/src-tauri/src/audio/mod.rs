//! Audio capture and analysis module

mod capture;
mod fft;
mod sources;

// Platform-specific audio code (disabled for MVP - using cpal for all platforms)
// #[cfg(target_os = "windows")]
// mod platform;

pub use capture::{AnalysisResult, AudioCaptureHandle};
pub use fft::FftAnalyzer;
pub use sources::{list_sources, AudioSource};

/// Audio processing configuration
#[derive(Debug, Clone)]
pub struct AudioConfig {
    /// Sample rate in Hz
    pub sample_rate: u32,

    /// FFT window size
    pub fft_size: usize,

    /// Attack rate for envelope following (0-1)
    pub attack: f32,

    /// Release rate for envelope following (0-1)
    pub release: f32,

    /// Beat detection threshold
    pub beat_threshold: f32,
}

impl Default for AudioConfig {
    fn default() -> Self {
        Self {
            sample_rate: 48000,
            fft_size: 1024,
            attack: 0.35,
            release: 0.08,
            beat_threshold: 1.3,
        }
    }
}
