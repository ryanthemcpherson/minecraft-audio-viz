//! Audio capture implementation using a dedicated thread

use super::{AudioConfig, BassLane, FftAnalyzer};
use crate::voice::VoiceStreamer;
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

    /// Confidence in the BPM estimate (0-1)
    pub tempo_confidence: f32,

    /// Position within the beat cycle [0, 1)
    pub beat_phase: f32,

    /// Instant bass energy from bass lane IIR filter (0-1), ~1ms latency
    pub instant_bass: f32,

    /// Instant kick detected by bass lane onset detector
    pub instant_kick: bool,
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

    /// Shared FFT analyzer (for applying presets at runtime)
    analyzer: Arc<Mutex<FftAnalyzer>>,
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
        Self::new_with_voice(source_id, None)
    }

    /// Create new audio capture from source ID with optional voice streamer.
    pub fn new_with_voice(
        source_id: Option<String>,
        voice_streamer: Option<Arc<VoiceStreamer>>,
    ) -> Result<Self, CaptureError> {
        let (command_tx, command_rx) = mpsc::channel();
        let latest_result = Arc::new(Mutex::new(AnalysisResult::default()));
        let result_clone = latest_result.clone();

        // Create a shared analyzer so presets can be applied at runtime.
        // The audio thread will replace this with a properly configured one
        // once the device sample rate is known.
        let analyzer = Arc::new(Mutex::new(FftAnalyzer::new(AudioConfig::default())));
        let analyzer_clone = analyzer.clone();

        // Spawn audio thread
        let thread_handle = thread::Builder::new()
            .name("audio-capture".to_string())
            .spawn(move || {
                if let Err(e) = run_audio_thread(
                    source_id,
                    command_rx,
                    result_clone,
                    voice_streamer,
                    analyzer_clone,
                ) {
                    log::error!("Audio thread error: {}", e);
                }
            })
            .map_err(|e| CaptureError::ThreadError(e.to_string()))?;

        Ok(Self {
            command_tx,
            thread_handle: Some(thread_handle),
            latest_result,
            analyzer,
        })
    }

    /// Get a reference to the shared analyzer for applying presets
    pub fn analyzer(&self) -> &Arc<Mutex<FftAnalyzer>> {
        &self.analyzer
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
pub struct AudioBuffer {
    samples: Vec<f32>,
    write_pos: usize,
    capacity: usize,
}

impl AudioBuffer {
    pub fn new(capacity: usize) -> Self {
        Self {
            samples: vec![0.0; capacity],
            write_pos: 0,
            capacity,
        }
    }

    pub fn push_samples(&mut self, data: &[f32]) {
        for &sample in data {
            self.samples[self.write_pos] = sample;
            self.write_pos = (self.write_pos + 1) % self.capacity;
        }
    }

    pub fn get_latest(&self, count: usize) -> Vec<f32> {
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
    voice_streamer: Option<Arc<VoiceStreamer>>,
    shared_analyzer: Arc<Mutex<FftAnalyzer>>,
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
        #[cfg(target_os = "windows")]
        Some(id) if id.starts_with("app:") => {
            // Per-app capture via Process Loopback API (Windows 10 Build 20348+)
            // Source ID format: "app:<pid>:<name>"
            let parts: Vec<&str> = id.splitn(3, ':').collect();
            let pid = parts
                .get(1)
                .and_then(|p| p.parse::<u32>().ok())
                .ok_or_else(|| CaptureError::SourceNotFound(format!("Invalid app source: {}", id)))?;

            if super::platform::windows::supports_process_loopback() {
                log::info!("Using Process Loopback API for PID {}", pid);

                // Create buffer; reinitialize the shared analyzer once we know sample rate
                let buffer = Arc::new(Mutex::new(AudioBuffer::new(48000 * 2)));

                match super::platform::windows::start_process_loopback(
                    pid,
                    buffer.clone(),
                    voice_streamer.clone(),
                ) {
                    Ok((mut loopback_handle, sample_rate, _channels)) => {
                        log::info!(
                            "Process loopback active: PID {} ({}Hz)",
                            pid,
                            sample_rate,
                        );

                        // Resize buffer for actual sample rate
                        {
                            let mut buf = buffer.lock();
                            *buf = AudioBuffer::new(sample_rate as usize * 2);
                        }

                        // Reinitialize analyzer with actual sample rate
                        {
                            let mut ana = shared_analyzer.lock();
                            *ana = FftAnalyzer::new(AudioConfig {
                                sample_rate,
                                ..Default::default()
                            });
                        }
                        let analyzer = shared_analyzer;
                        let bass_lane = Arc::new(Mutex::new(BassLane::new(sample_rate as f32)));

                        // Run analysis loop - copy samples under lock, release, then process
                        loop {
                            match command_rx.try_recv() {
                                Ok(AudioCommand::Stop) => {
                                    log::info!("Audio capture stopping (process loopback)");
                                    break;
                                }
                                Err(mpsc::TryRecvError::Disconnected) => {
                                    log::info!("Audio capture channel disconnected");
                                    break;
                                }
                                Err(mpsc::TryRecvError::Empty) => {}
                            }

                            // Copy samples under lock, then release before expensive processing
                            let (samples, fft_size) = {
                                let buf = buffer.lock();
                                let ana = analyzer.lock();
                                let sz = ana.fft_size();
                                (buf.get_latest(sz), sz)
                            };

                            if samples.len() >= fft_size {
                                let (i_bass, i_kick) = {
                                    let mut bl = bass_lane.lock();
                                    bl.process(&samples)
                                };

                                let mut result = {
                                    let mut ana = analyzer.lock();
                                    ana.analyze(&samples)
                                };

                                result.instant_bass = i_bass;
                                result.instant_kick = i_kick;

                                if i_kick && !result.is_beat {
                                    result.is_beat = true;
                                    result.beat_intensity = result.beat_intensity.max(0.5);
                                }

                                *result_out.lock() = result;
                            }

                            std::thread::sleep(std::time::Duration::from_millis(10));
                        }

                        loopback_handle.stop();
                        return Ok(());
                    }
                    Err(e) => {
                        log::warn!(
                            "Process loopback failed for PID {}: {}. Falling back to system loopback.",
                            pid,
                            e
                        );
                        is_loopback = true;
                        host.default_output_device()
                            .ok_or(CaptureError::NoOutputDevice)?
                    }
                }
            } else {
                log::warn!(
                    "Process Loopback API not supported (Windows build < {}). Falling back to system loopback.",
                    20348
                );
                is_loopback = true;
                host.default_output_device()
                    .ok_or(CaptureError::NoOutputDevice)?
            }
        }
        #[cfg(not(target_os = "windows"))]
        Some(id) if id.starts_with("app:") => {
            log::info!("Per-app capture not available on this platform, using system audio loopback");
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

    // Create buffer; reinitialize the shared analyzer with the actual sample rate
    let buffer = Arc::new(Mutex::new(AudioBuffer::new(sample_rate as usize * 2)));
    {
        let mut ana = shared_analyzer.lock();
        *ana = FftAnalyzer::new(AudioConfig {
            sample_rate,
            ..Default::default()
        });
    }
    let analyzer = shared_analyzer;

    // Create bass lane for ultra-fast kick detection (~1ms latency)
    let bass_lane = Arc::new(Mutex::new(BassLane::new(sample_rate as f32)));

    // Clone for stream callback
    let buffer_clone = buffer.clone();

    // Build stream based on sample format
    let stream = match config.sample_format() {
        SampleFormat::F32 => build_stream::<f32>(
            &device,
            &config.into(),
            buffer_clone,
            channels,
            voice_streamer,
        ),
        SampleFormat::I16 => build_stream::<i16>(
            &device,
            &config.into(),
            buffer_clone,
            channels,
            voice_streamer,
        ),
        SampleFormat::U16 => build_stream::<u16>(
            &device,
            &config.into(),
            buffer_clone,
            channels,
            voice_streamer,
        ),
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

        // Analyze audio (FFT + merge bass lane results)
        // IMPORTANT: Copy samples under lock, then release lock before expensive FFT.
        // Holding the buffer lock during analyze() blocks the audio callback.
        let (samples, fft_size) = {
            let buf = buffer.lock();
            let ana = analyzer.lock();
            let sz = ana.fft_size();
            (buf.get_latest(sz), sz)
        };
        // All locks dropped - audio callback can push freely

        if samples.len() >= fft_size {
            // Run bass lane on the same samples (moved out of audio callback to avoid contention)
            let (i_bass, i_kick) = {
                let mut bl = bass_lane.lock();
                bl.process(&samples)
            };
            // bass_lane lock dropped

            let mut result = {
                let mut ana = analyzer.lock();
                ana.analyze(&samples)
            };
            // analyzer lock dropped

            result.instant_bass = i_bass;
            result.instant_kick = i_kick;

            // If bass lane detects kick but FFT didn't, supplement beat detection
            if i_kick && !result.is_beat {
                result.is_beat = true;
                result.beat_intensity = result.beat_intensity.max(0.5);
            }

            // Update shared result (brief lock)
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
    voice_streamer: Option<Arc<VoiceStreamer>>,
) -> Result<cpal::Stream, cpal::BuildStreamError>
where
    f32: cpal::FromSample<T>,
{
    device.build_input_stream(
        config,
        move |data: &[T], _: &cpal::InputCallbackInfo| {
            // Convert all samples to f32 (interleaved)
            let f32_data: Vec<f32> = data
                .iter()
                .map(|s| cpal::Sample::from_sample(*s))
                .collect();

            // Feed raw interleaved f32 samples to voice streamer (before downmix)
            if let Some(ref streamer) = voice_streamer {
                streamer.push_samples(&f32_data, channels);
            }

            // Convert to mono f32 for FFT analysis
            let mono: Vec<f32> = f32_data
                .chunks(channels)
                .map(|frame| {
                    let sum: f32 = frame.iter().sum();
                    sum / channels as f32
                })
                .collect();

            // Push mono samples to buffer for FFT + bass lane analysis
            // Bass lane runs in the analysis thread (not callback) to avoid lock contention
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
