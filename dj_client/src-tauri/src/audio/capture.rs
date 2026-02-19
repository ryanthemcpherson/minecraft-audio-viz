//! Audio capture implementation using a dedicated thread

use super::{AudioConfig, FftAnalyzer};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Device, SampleFormat, StreamConfig};
use parking_lot::Mutex;
use std::sync::mpsc;
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use thiserror::Error;

/// Audio capture errors
#[derive(Error, Debug)]
pub enum CaptureError {
    #[error("No audio host available")]
    NoHost,

    #[error("No output device found")]
    NoOutputDevice,

    #[error("Failed to get device config: {0}")]
    ConfigError(String),

    #[error("Failed to build audio stream: {0}")]
    StreamError(String),

    #[error("Failed to start stream: {0}")]
    PlayError(String),

    #[error("Source not found: {0}")]
    SourceNotFound(String),

    #[error("Thread error: {0}")]
    ThreadError(String),
}

/// FFT analysis result (Send-safe)
#[derive(Debug, Clone, Default)]
pub struct AnalysisResult {
    /// Frequency bands (bass, low, mid, high, air)
    pub bands: [f32; 5],

    /// Peak amplitude
    pub peak: f32,

    /// Beat detected
    pub is_beat: bool,

    /// Beat intensity
    pub beat_intensity: f32,

    /// Estimated BPM
    pub bpm: f32,
}

/// Commands sent to the audio thread
enum AudioCommand {
    Stop,
}

/// Audio capture handle (Send + Sync safe)
///
/// This struct doesn't contain the cpal::Stream directly.
/// Instead, it manages a dedicated thread that owns the stream.
pub struct AudioCaptureHandle {
    /// Command sender to control the audio thread
    command_tx: mpsc::Sender<AudioCommand>,

    /// Handle to the audio thread
    thread_handle: Option<JoinHandle<()>>,

    /// Latest analysis result (shared between threads)
    latest_result: Arc<Mutex<AnalysisResult>>,
}

// AudioCaptureHandle is now Send + Sync because it only contains:
// - mpsc::Sender (Send + Sync)
// - Option<JoinHandle> (Send)
// - Arc<Mutex<AnalysisResult>> (Send + Sync)
unsafe impl Send for AudioCaptureHandle {}
unsafe impl Sync for AudioCaptureHandle {}

impl AudioCaptureHandle {
    /// Create new audio capture from source ID
    pub fn new(source_id: Option<String>) -> Result<Self, CaptureError> {
        let (command_tx, command_rx) = mpsc::channel();
        let latest_result = Arc::new(Mutex::new(AnalysisResult::default()));
        let result_clone = latest_result.clone();

        // Spawn audio thread
        let thread_handle = thread::Builder::new()
            .name("audio-capture".to_string())
            .spawn(move || {
                if let Err(e) = run_audio_thread(source_id, command_rx, result_clone) {
                    log::error!("Audio thread error: {}", e);
                }
            })
            .map_err(|e| CaptureError::ThreadError(e.to_string()))?;

        Ok(Self {
            command_tx,
            thread_handle: Some(thread_handle),
            latest_result,
        })
    }

    /// Get the latest analysis result
    pub fn get_analysis(&self) -> AnalysisResult {
        self.latest_result.lock().clone()
    }

    /// Stop the audio capture
    pub fn stop(&mut self) {
        let _ = self.command_tx.send(AudioCommand::Stop);
        if let Some(handle) = self.thread_handle.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for AudioCaptureHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Circular audio buffer
struct AudioBuffer {
    samples: Vec<f32>,
    write_pos: usize,
    capacity: usize,
}

impl AudioBuffer {
    fn new(capacity: usize) -> Self {
        Self {
            samples: vec![0.0; capacity],
            write_pos: 0,
            capacity,
        }
    }

    fn push_samples(&mut self, data: &[f32]) {
        for &sample in data {
            self.samples[self.write_pos] = sample;
            self.write_pos = (self.write_pos + 1) % self.capacity;
        }
    }

    fn get_latest(&self, count: usize) -> Vec<f32> {
        let count = count.min(self.capacity);
        let mut result = Vec::with_capacity(count);

        let start = if self.write_pos >= count {
            self.write_pos - count
        } else {
            self.capacity - (count - self.write_pos)
        };

        for i in 0..count {
            let idx = (start + i) % self.capacity;
            result.push(self.samples[idx]);
        }

        result
    }
}

/// Run the audio capture in a dedicated thread
fn run_audio_thread(
    source_id: Option<String>,
    command_rx: mpsc::Receiver<AudioCommand>,
    result_out: Arc<Mutex<AnalysisResult>>,
) -> Result<(), CaptureError> {
    let host = cpal::default_host();

    // Track whether we're doing loopback capture (output device used as input)
    let mut is_loopback = false;

    // Get device based on source
    let device = match &source_id {
        Some(id) if id == "system_audio" => {
            // WASAPI loopback: capture from the default OUTPUT device
            // On Windows, cpal/WASAPI allows building an input stream on an output device
            // to capture system audio (loopback capture)
            log::info!("Using default output device for system audio loopback");
            is_loopback = true;
            host.default_output_device()
                .ok_or(CaptureError::NoOutputDevice)?
        }
        Some(id) if id.starts_with("output:") => {
            // Named output device for loopback
            let device_name = id.trim_start_matches("output:");
            log::info!("Using output device for loopback: {}", device_name);
            is_loopback = true;
            host.output_devices()
                .map_err(|e| CaptureError::ConfigError(e.to_string()))?
                .find(|d| d.name().map(|n| n == device_name).unwrap_or(false))
                .ok_or_else(|| CaptureError::SourceNotFound(device_name.to_string()))?
        }
        Some(id) if id.starts_with("input:") => {
            let device_name = id.trim_start_matches("input:");
            host.input_devices()
                .map_err(|e| CaptureError::ConfigError(e.to_string()))?
                .find(|d| d.name().map(|n| n == device_name).unwrap_or(false))
                .ok_or_else(|| CaptureError::SourceNotFound(device_name.to_string()))?
        }
        Some(id) if id.starts_with("app:") => {
            // Per-app capture not yet implemented - fall back to loopback
            log::info!("Per-app capture not yet implemented, using system audio loopback");
            is_loopback = true;
            host.default_output_device()
                .ok_or(CaptureError::NoOutputDevice)?
        }
        _ => {
            // Default: use loopback capture for system audio
            log::info!("Using default output device for system audio loopback");
            is_loopback = true;
            host.default_output_device()
                .ok_or(CaptureError::NoOutputDevice)?
        }
    };

    // Get supported config
    // For loopback, we query the output config (which is what the device is producing)
    let config = if is_loopback {
        device
            .default_output_config()
            .map_err(|e| CaptureError::ConfigError(format!("Loopback config: {}", e)))?
    } else {
        device
            .default_input_config()
            .map_err(|e| CaptureError::ConfigError(e.to_string()))?
    };

    let sample_rate = config.sample_rate().0;
    let channels = config.channels() as usize;

    log::info!("Audio capture: {} Hz, {} channels", sample_rate, channels);

    // Create buffer and analyzer
    let buffer = Arc::new(Mutex::new(AudioBuffer::new(sample_rate as usize * 2)));
    let analyzer = Arc::new(Mutex::new(FftAnalyzer::new(AudioConfig {
        sample_rate,
        ..Default::default()
    })));

    // Clone for stream callback
    let buffer_clone = buffer.clone();

    // Build stream based on sample format
    let stream = match config.sample_format() {
        SampleFormat::F32 => build_stream::<f32>(&device, &config.into(), buffer_clone, channels),
        SampleFormat::I16 => build_stream::<i16>(&device, &config.into(), buffer_clone, channels),
        SampleFormat::U16 => build_stream::<u16>(&device, &config.into(), buffer_clone, channels),
        _ => {
            return Err(CaptureError::ConfigError(
                "Unsupported sample format".to_string(),
            ))
        }
    }
    .map_err(|e| CaptureError::StreamError(e.to_string()))?;

    stream
        .play()
        .map_err(|e| CaptureError::PlayError(e.to_string()))?;

    log::info!("Audio capture started");

    // Main loop - analyze audio and check for stop command
    loop {
        // Check for stop command (non-blocking)
        match command_rx.try_recv() {
            Ok(AudioCommand::Stop) => {
                log::info!("Audio capture stopping");
                break;
            }
            Err(mpsc::TryRecvError::Disconnected) => {
                log::info!("Audio capture channel disconnected");
                break;
            }
            Err(mpsc::TryRecvError::Empty) => {
                // No command, continue processing
            }
        }

        // Analyze audio
        {
            let buf = buffer.lock();
            let mut ana = analyzer.lock();
            let samples = buf.get_latest(ana.fft_size());
            let result = ana.analyze(&samples);

            // Update shared result
            *result_out.lock() = result;
        }

        // Sleep briefly to avoid spinning
        std::thread::sleep(std::time::Duration::from_millis(10));
    }

    Ok(())
}

/// Build audio stream for given sample type
fn build_stream<T: cpal::Sample + cpal::SizedSample>(
    device: &Device,
    config: &StreamConfig,
    buffer: Arc<Mutex<AudioBuffer>>,
    channels: usize,
) -> Result<cpal::Stream, cpal::BuildStreamError>
where
    f32: cpal::FromSample<T>,
{
    device.build_input_stream(
        config,
        move |data: &[T], _: &cpal::InputCallbackInfo| {
            // Convert to mono f32 using cpal's Sample trait
            let mono: Vec<f32> = data
                .chunks(channels)
                .map(|frame| {
                    let sum: f32 = frame
                        .iter()
                        .map(|s| {
                            let sample: f32 = cpal::Sample::from_sample(*s);
                            sample
                        })
                        .sum();
                    sum / channels as f32
                })
                .collect();

            buffer.lock().push_samples(&mono);
        },
        |err| {
            log::error!("Audio stream error: {}", err);
        },
        None,
    )
}

#[cfg(test)]
mod tests {
    use super::AudioBuffer;

    #[test]
    fn get_latest_returns_recent_samples_in_order() {
        let mut buffer = AudioBuffer::new(8);
        buffer.push_samples(&[1.0, 2.0, 3.0, 4.0]);

        let latest = buffer.get_latest(3);
        assert_eq!(latest, vec![2.0, 3.0, 4.0]);
    }

    #[test]
    fn circular_buffer_wraps_and_preserves_time_order() {
        let mut buffer = AudioBuffer::new(5);
        buffer.push_samples(&[1.0, 2.0, 3.0]);
        buffer.push_samples(&[4.0, 5.0, 6.0]);

        let latest = buffer.get_latest(5);
        assert_eq!(latest, vec![2.0, 3.0, 4.0, 5.0, 6.0]);
    }

    #[test]
    fn get_latest_caps_count_to_capacity() {
        let mut buffer = AudioBuffer::new(4);
        buffer.push_samples(&[1.0, 2.0, 3.0, 4.0]);

        let latest = buffer.get_latest(100);
        assert_eq!(latest, vec![1.0, 2.0, 3.0, 4.0]);
    }
}
