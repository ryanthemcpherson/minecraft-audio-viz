//! Voice audio streaming for Simple Voice Chat integration
//!
//! Captures raw PCM audio, resamples to 48kHz mono i16, chunks into
//! 960-sample (20ms) frames, Opus-encodes (with PCM fallback), and
//! base64-encodes them for WebSocket transport.

use base64::Engine;
#[cfg(feature = "voice-opus")]
use opus::{Application, Channels, Encoder as OpusEncoder};
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

/// Number of samples per voice frame (20ms at 48kHz)
const VOICE_FRAME_SAMPLES: usize = 960;

/// Maximum number of queued frames before dropping oldest
const MAX_QUEUED_FRAMES: usize = 50;

/// Target sample rate for voice output
const VOICE_SAMPLE_RATE: u32 = 48_000;

/// Voice streaming configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VoiceConfig {
    pub enabled: bool,
    pub channel_type: String,
    pub distance: f64,
    pub zone: String,
}

impl Default for VoiceConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            channel_type: "static".to_string(),
            distance: 100.0,
            zone: "main".to_string(),
        }
    }
}

/// Voice status information
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VoiceStatus {
    pub available: bool,
    pub streaming: bool,
    pub channel_type: String,
    pub connected_players: u32,
}

impl Default for VoiceStatus {
    fn default() -> Self {
        Self {
            available: false,
            streaming: false,
            channel_type: "static".to_string(),
            connected_players: 0,
        }
    }
}

/// Thread-safe voice audio streamer.
///
/// Takes raw f32 audio samples from the capture callback, resamples to 48kHz
/// mono i16, and chunks into 960-sample frames for voice chat transmission.
pub struct VoiceStreamer {
    /// Whether voice streaming is enabled
    enabled: AtomicBool,

    /// Sequence counter for voice frames
    seq: AtomicU64,

    /// Inner state protected by mutex
    inner: Mutex<VoiceStreamerInner>,

    /// Source sample rate for resampling
    source_sample_rate: u32,

    /// Source channel count for downmixing
    #[allow(dead_code)]
    source_channels: u16,
}

struct VoiceStreamerInner {
    /// Residual f32 samples awaiting resampling (mono, source rate)
    residual: Vec<f32>,

    /// Resampled i16 samples awaiting framing (48kHz mono)
    frame_buffer: Vec<i16>,

    /// Completed base64-encoded frames ready for sending
    frames: VecDeque<String>,

    /// Opus encoder for compressing voice frames (None = PCM fallback)
    #[cfg(feature = "voice-opus")]
    opus_encoder: Option<OpusEncoder>,

    /// Codec identifier: "opus" or "pcm"
    codec: String,
}

impl VoiceStreamer {
    /// Create a new voice streamer for the given source format.
    pub fn new(source_sample_rate: u32, source_channels: u16) -> Self {
        #[cfg(feature = "voice-opus")]
        let (opus_encoder, codec) = {
            match OpusEncoder::new(48_000, Channels::Mono, Application::Audio) {
                Ok(enc) => {
                    log::info!("Opus encoder initialized for voice streaming");
                    (Some(enc), "opus".to_string())
                }
                Err(e) => {
                    log::warn!("Failed to create Opus encoder, falling back to PCM: {}", e);
                    (None, "pcm".to_string())
                }
            }
        };
        #[cfg(not(feature = "voice-opus"))]
        let codec = {
            log::info!("Voice streaming using PCM (build with voice-opus feature for Opus encoding)");
            "pcm".to_string()
        };

        Self {
            enabled: AtomicBool::new(false),
            seq: AtomicU64::new(0),
            inner: Mutex::new(VoiceStreamerInner {
                residual: Vec::with_capacity(4096),
                frame_buffer: Vec::with_capacity(VOICE_FRAME_SAMPLES * 2),
                frames: VecDeque::with_capacity(MAX_QUEUED_FRAMES),
                #[cfg(feature = "voice-opus")]
                opus_encoder,
                codec,
            }),
            source_sample_rate,
            source_channels: source_channels.max(1),
        }
    }

    /// Enable or disable voice streaming.
    pub fn set_enabled(&self, enabled: bool) {
        self.enabled.store(enabled, Ordering::Relaxed);
        if !enabled {
            // Clear buffered data when disabled
            let mut inner = self.inner.lock();
            inner.residual.clear();
            inner.frame_buffer.clear();
            inner.frames.clear();
        }
    }

    /// Check if voice streaming is enabled.
    pub fn is_enabled(&self) -> bool {
        self.enabled.load(Ordering::Relaxed)
    }

    /// Feed raw interleaved f32 samples from the audio capture callback.
    ///
    /// This method is designed to be called from the audio callback thread.
    /// It downmixes to mono, resamples to 48kHz, converts to i16, and
    /// chunks into 960-sample frames.
    pub fn push_samples(&self, data: &[f32], channels: usize) {
        if !self.is_enabled() || data.is_empty() {
            return;
        }

        let channels = channels.max(1);

        // Downmix to mono f32
        let mono: Vec<f32> = data
            .chunks(channels)
            .map(|frame| {
                let sum: f32 = frame.iter().sum();
                sum / channels as f32
            })
            .collect();

        let mut inner = self.inner.lock();

        // Append to residual buffer
        inner.residual.extend_from_slice(&mono);

        // Resample from source rate to 48kHz
        let resampled = resample(&inner.residual, self.source_sample_rate, VOICE_SAMPLE_RATE);

        // Calculate how many source samples were consumed
        // consumed = resampled.len() * source_rate / target_rate (approximately)
        let consumed = if self.source_sample_rate == VOICE_SAMPLE_RATE {
            resampled.len()
        } else {
            // For each output sample, we consumed source_rate/target_rate source samples
            // More precisely: output_len = floor(input_len * target / source)
            // so input_consumed = ceil(output_len * source / target)
            let ratio = self.source_sample_rate as f64 / VOICE_SAMPLE_RATE as f64;
            (resampled.len() as f64 * ratio).ceil() as usize
        };
        let consumed = consumed.min(inner.residual.len());

        // Convert f32 [-1,1] to i16 and append to frame buffer
        for &sample in &resampled {
            let clamped = sample.clamp(-1.0, 1.0);
            let i16_val = (clamped * 32767.0) as i16;
            inner.frame_buffer.push(i16_val);
        }

        // Drain consumed source samples
        inner.residual.drain(..consumed);

        // Extract complete 960-sample frames
        while inner.frame_buffer.len() >= VOICE_FRAME_SAMPLES {
            let frame_samples: Vec<i16> =
                inner.frame_buffer.drain(..VOICE_FRAME_SAMPLES).collect();

            // Encode frame: Opus if available, otherwise raw PCM bytes
            #[cfg(feature = "voice-opus")]
            let encoded = if let Some(ref mut encoder) = inner.opus_encoder {
                match encoder.encode_vec(&frame_samples, 4000) {
                    Ok(opus_bytes) => {
                        base64::engine::general_purpose::STANDARD.encode(&opus_bytes)
                    }
                    Err(e) => {
                        log::warn!("Opus encode failed, sending PCM fallback: {}", e);
                        encode_pcm_frame(&frame_samples)
                    }
                }
            } else {
                encode_pcm_frame(&frame_samples)
            };
            #[cfg(not(feature = "voice-opus"))]
            let encoded = encode_pcm_frame(&frame_samples);

            // Push to frame queue, dropping oldest if full
            if inner.frames.len() >= MAX_QUEUED_FRAMES {
                inner.frames.pop_front();
            }
            inner.frames.push_back(encoded);
        }
    }

    /// Drain all ready frames as `(base64_data, sequence_number, codec)` tuples.
    ///
    /// When the Opus encoder is active, frames contain compressed Opus packets.
    /// Otherwise they contain raw PCM (960 i16 samples = 1920 bytes LE).
    pub fn drain_frames(&self) -> Vec<(String, u64, String)> {
        let mut inner = self.inner.lock();
        let codec = inner.codec.clone();
        let mut result = Vec::with_capacity(inner.frames.len());
        while let Some(frame) = inner.frames.pop_front() {
            let seq = self.seq.fetch_add(1, Ordering::Relaxed);
            result.push((frame, seq, codec.clone()));
        }
        result
    }

    /// Get the current sequence number.
    pub fn current_seq(&self) -> u64 {
        self.seq.load(Ordering::Relaxed)
    }
}

/// Encode a PCM frame (960 i16 samples) to base64 little-endian bytes.
fn encode_pcm_frame(samples: &[i16]) -> String {
    let mut bytes = Vec::with_capacity(samples.len() * 2);
    for sample in samples {
        bytes.extend_from_slice(&sample.to_le_bytes());
    }
    base64::engine::general_purpose::STANDARD.encode(&bytes)
}

/// Simple linear interpolation resampler.
///
/// Resamples mono f32 samples from `from_rate` to `to_rate`.
fn resample(input: &[f32], from_rate: u32, to_rate: u32) -> Vec<f32> {
    if input.is_empty() {
        return Vec::new();
    }

    if from_rate == to_rate {
        return input.to_vec();
    }

    let ratio = from_rate as f64 / to_rate as f64;
    let output_len = ((input.len() as f64) / ratio).floor() as usize;

    if output_len == 0 {
        return Vec::new();
    }

    let mut output = Vec::with_capacity(output_len);
    for i in 0..output_len {
        let src_pos = i as f64 * ratio;
        let src_idx = src_pos.floor() as usize;
        let frac = (src_pos - src_idx as f64) as f32;

        let sample = if src_idx + 1 < input.len() {
            input[src_idx] * (1.0 - frac) + input[src_idx + 1] * frac
        } else if src_idx < input.len() {
            input[src_idx]
        } else {
            0.0
        };

        output.push(sample);
    }

    output
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn voice_streamer_disabled_by_default() {
        let streamer = VoiceStreamer::new(48000, 2);
        assert!(!streamer.is_enabled());
        assert!(streamer.drain_frames().is_empty());
    }

    #[test]
    fn voice_streamer_ignores_samples_when_disabled() {
        let streamer = VoiceStreamer::new(48000, 2);
        let samples = vec![0.5f32; 4800]; // 2400 stereo frames
        streamer.push_samples(&samples, 2);
        assert!(streamer.drain_frames().is_empty());
    }

    #[test]
    fn voice_streamer_produces_frames_when_enabled() {
        let streamer = VoiceStreamer::new(48000, 1);
        streamer.set_enabled(true);

        // Push enough mono samples for at least one frame (960 samples)
        let samples = vec![0.1f32; 960];
        streamer.push_samples(&samples, 1);

        let frames = streamer.drain_frames();
        assert_eq!(frames.len(), 1);

        // Verify codec field is set
        let codec = &frames[0].2;
        assert!(codec == "opus" || codec == "pcm");

        // Verify base64 decodes successfully
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(&frames[0].0)
            .unwrap();
        if codec == "pcm" {
            // PCM: 960 i16 samples = 1920 bytes
            assert_eq!(decoded.len(), 1920);
        } else {
            // Opus: compressed, should be smaller than raw PCM
            assert!(decoded.len() < 1920);
        }
    }

    #[test]
    fn voice_streamer_sequence_increments() {
        let streamer = VoiceStreamer::new(48000, 1);
        streamer.set_enabled(true);

        let samples = vec![0.1f32; 1920]; // 2 frames
        streamer.push_samples(&samples, 1);

        let frames = streamer.drain_frames();
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].1, 0);
        assert_eq!(frames[1].1, 1);
        // Both frames should have the same codec
        assert_eq!(frames[0].2, frames[1].2);
    }

    #[test]
    fn voice_streamer_drops_oldest_when_full() {
        let streamer = VoiceStreamer::new(48000, 1);
        streamer.set_enabled(true);

        // Push way more than MAX_QUEUED_FRAMES worth of samples
        for _ in 0..(MAX_QUEUED_FRAMES + 10) {
            let samples = vec![0.1f32; 960];
            streamer.push_samples(&samples, 1);
        }

        let frames = streamer.drain_frames();
        assert!(frames.len() <= MAX_QUEUED_FRAMES);
    }

    #[test]
    fn resample_passthrough_at_same_rate() {
        let input = vec![0.1, 0.2, 0.3, 0.4, 0.5];
        let output = resample(&input, 48000, 48000);
        assert_eq!(output, input);
    }

    #[test]
    fn resample_downsamples_correctly() {
        // 96kHz -> 48kHz should roughly halve the samples
        let input: Vec<f32> = (0..100).map(|i| i as f32 / 100.0).collect();
        let output = resample(&input, 96000, 48000);
        assert_eq!(output.len(), 50);
    }

    #[test]
    fn resample_upsamples_correctly() {
        // 24kHz -> 48kHz should roughly double the samples
        let input: Vec<f32> = (0..100).map(|i| i as f32 / 100.0).collect();
        let output = resample(&input, 24000, 48000);
        assert_eq!(output.len(), 200);
    }

    #[test]
    fn voice_streamer_stereo_downmix() {
        let streamer = VoiceStreamer::new(48000, 2);
        streamer.set_enabled(true);

        // Stereo samples: L=0.5, R=0.5 -> mono 0.5
        let mut samples = Vec::with_capacity(960 * 2);
        for _ in 0..960 {
            samples.push(0.5f32);
            samples.push(0.5f32);
        }
        streamer.push_samples(&samples, 2);

        let frames = streamer.drain_frames();
        assert_eq!(frames.len(), 1);
    }

    #[test]
    fn voice_config_default_values() {
        let config = VoiceConfig::default();
        assert!(!config.enabled);
        assert_eq!(config.channel_type, "static");
        assert_eq!(config.distance, 100.0);
        assert_eq!(config.zone, "main");
    }

    #[test]
    fn set_enabled_false_clears_buffers() {
        let streamer = VoiceStreamer::new(48000, 1);
        streamer.set_enabled(true);

        let samples = vec![0.1f32; 960];
        streamer.push_samples(&samples, 1);

        streamer.set_enabled(false);
        assert!(streamer.drain_frames().is_empty());
    }
}
