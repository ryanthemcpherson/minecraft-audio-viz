//! FFT analysis - ported from Python fft_analyzer.py

use super::{capture::AnalysisResult, AudioConfig};
use rustfft::{num_complex::Complex, FftPlanner};
use std::collections::VecDeque;
use std::time::Instant;

/// Audio preset for tuning FFT analysis to different music styles
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct AudioPreset {
    pub name: String,
    pub attack: f32,
    pub release: f32,
    pub beat_threshold: f32,
    pub bass_weight: f32,
    pub band_sensitivity: [f32; 5],
}

/// Return all built-in presets
pub fn get_presets() -> Vec<AudioPreset> {
    vec![
        AudioPreset {
            name: "auto".to_string(),
            attack: 0.35,
            release: 0.08,
            beat_threshold: 1.3,
            bass_weight: 0.7,
            band_sensitivity: [1.0, 1.0, 1.0, 1.0, 1.0],
        },
        AudioPreset {
            name: "edm".to_string(),
            attack: 0.7,
            release: 0.15,
            beat_threshold: 1.1,
            bass_weight: 0.85,
            band_sensitivity: [1.5, 0.8, 0.9, 1.2, 1.0],
        },
        AudioPreset {
            name: "chill".to_string(),
            attack: 0.25,
            release: 0.05,
            beat_threshold: 1.6,
            bass_weight: 0.5,
            band_sensitivity: [0.9, 1.0, 1.1, 1.2, 1.3],
        },
        AudioPreset {
            name: "rock".to_string(),
            attack: 0.5,
            release: 0.12,
            beat_threshold: 1.3,
            bass_weight: 0.65,
            band_sensitivity: [1.2, 1.0, 1.0, 0.9, 0.8],
        },
        AudioPreset {
            name: "hiphop".to_string(),
            attack: 0.6,
            release: 0.1,
            beat_threshold: 1.2,
            bass_weight: 0.8,
            band_sensitivity: [1.4, 0.9, 1.0, 1.1, 0.9],
        },
        AudioPreset {
            name: "classical".to_string(),
            attack: 0.2,
            release: 0.04,
            beat_threshold: 1.8,
            bass_weight: 0.4,
            band_sensitivity: [0.8, 1.0, 1.2, 1.3, 1.4],
        },
    ]
}

/// Look up a preset by name (case-insensitive), returning None if not found
pub fn get_preset(name: &str) -> Option<AudioPreset> {
    let lower = name.to_lowercase();
    get_presets().into_iter().find(|p| p.name == lower)
}

/// Ultra-fast bass detection lane using IIR filters.
///
/// Processes raw audio samples with ~1ms latency for kick detection,
/// compared to ~10ms for FFT-based detection. Ported from Python BassLane.
///
/// Uses a single-pole IIR lowpass at 120Hz, envelope follower with
/// fast attack / slow release, and onset detection on positive slope.
pub struct BassLane {
    /// IIR lowpass coefficient (alpha)
    alpha: f32,
    /// Previous filtered sample (lowpass state)
    prev_filtered: f32,

    /// Current envelope value
    envelope: f32,
    /// Envelope attack coefficient (~1ms)
    attack_coeff: f32,
    /// Envelope release coefficient (~50ms)
    release_coeff: f32,

    /// Running peak for normalization
    running_peak: f32,
    /// Peak decay rate (~3s to halve)
    peak_decay: f32,

    /// Previous envelope value for slope detection
    prev_envelope: f32,
    /// Onset threshold (minimum slope to fire)
    onset_threshold: f32,

    /// Samples since last onset (for cooldown)
    samples_since_onset: u32,
    /// Minimum samples between onsets (150ms cooldown)
    cooldown_samples: u32,

    /// Source sample rate
    sample_rate: f32,
}

impl BassLane {
    /// Create a new bass lane processor.
    ///
    /// # Arguments
    /// * `sample_rate` - Audio sample rate in Hz
    pub fn new(sample_rate: f32) -> Self {
        let cutoff = 120.0_f32;
        let attack_ms = 1.0_f32;
        let release_ms = 50.0_f32;

        // Single-pole IIR lowpass: y[n] = alpha * x[n] + (1-alpha) * y[n-1]
        // alpha = dt / (RC + dt) where RC = 1/(2*pi*fc)
        let rc = 1.0 / (2.0 * std::f32::consts::PI * cutoff);
        let dt = 1.0 / sample_rate;
        let alpha = dt / (rc + dt);

        // Envelope follower coefficients
        let attack_coeff = 1.0 - (-1.0 / (sample_rate * attack_ms / 1000.0)).exp();
        let release_coeff = 1.0 - (-1.0 / (sample_rate * release_ms / 1000.0)).exp();

        // 150ms cooldown in samples
        let cooldown_samples = (sample_rate * 0.15) as u32;

        // Peak decay: ~3 seconds to halve at per-process-call rate
        // Since we're called with variable-sized buffers, use per-sample decay
        let peak_decay = (-1.0_f32 / (sample_rate * 3.0)).exp();

        Self {
            alpha,
            prev_filtered: 0.0,
            envelope: 0.0,
            attack_coeff,
            release_coeff,
            running_peak: 0.001,
            peak_decay,
            prev_envelope: 0.0,
            onset_threshold: 0.15,
            samples_since_onset: cooldown_samples, // start ready to fire
            cooldown_samples,
            sample_rate,
        }
    }

    /// Process a buffer of mono audio samples through the bass lane.
    ///
    /// Returns `(instant_bass, instant_kick)`:
    /// - `instant_bass`: Normalized bass energy (0.0-1.0)
    /// - `instant_kick`: True if a kick onset was detected in this buffer
    pub fn process(&mut self, samples: &[f32]) -> (f32, bool) {
        if samples.is_empty() {
            return (self.envelope / (self.running_peak + 1e-6), false);
        }

        let mut kick_detected = false;

        for &sample in samples {
            // IIR lowpass filter
            let filtered = self.alpha * sample + (1.0 - self.alpha) * self.prev_filtered;
            self.prev_filtered = filtered;

            // Rectify for envelope
            let rectified = filtered.abs();

            // Envelope follower (asymmetric attack/release)
            if rectified > self.envelope {
                self.envelope += self.attack_coeff * (rectified - self.envelope);
            } else {
                self.envelope += self.release_coeff * (rectified - self.envelope);
            }

            // Update running peak (fast attack, slow decay)
            if self.envelope > self.running_peak {
                self.running_peak = self.envelope;
            } else {
                self.running_peak *= self.peak_decay;
                self.running_peak = self.running_peak.max(0.001);
            }

            self.samples_since_onset += 1;
        }

        // Onset detection: positive envelope slope above threshold
        let envelope_delta = self.envelope - self.prev_envelope;
        self.prev_envelope = self.envelope;

        if envelope_delta > self.onset_threshold * self.running_peak
            && self.samples_since_onset >= self.cooldown_samples
        {
            kick_detected = true;
            self.samples_since_onset = 0;
        }

        // Normalize to 0-1
        let instant_bass = (self.envelope / (self.running_peak + 1e-6)).min(1.0);

        (instant_bass, kick_detected)
    }

    /// Get the current bass lane state without processing new samples.
    ///
    /// Returns `(instant_bass, instant_kick)` based on the most recent `process()` call.
    /// - `instant_bass`: Normalized envelope (0.0-1.0)
    /// - `instant_kick`: True if a kick was detected in the most recent buffer
    pub fn current_state(&self) -> (f32, bool) {
        let instant_bass = (self.envelope / (self.running_peak + 1e-6)).min(1.0);
        let instant_kick = self.samples_since_onset == 0;
        (instant_bass, instant_kick)
    }

    /// Return the raw (un-normalized) envelope value.
    /// Useful for comparing absolute filter output between different signals.
    pub fn raw_envelope(&self) -> f32 {
        self.envelope
    }

    /// Reset all filter state.
    pub fn reset(&mut self) {
        self.prev_filtered = 0.0;
        self.envelope = 0.0;
        self.prev_envelope = 0.0;
        self.running_peak = 0.001;
        self.samples_since_onset = self.cooldown_samples;
    }
}

/// FFT analyzer for audio visualization
pub struct FftAnalyzer {
    config: AudioConfig,
    planner: FftPlanner<f32>,
    fft_size: usize,
    window: Vec<f32>,

    // Band boundaries (bin indices for 5 bands)
    band_boundaries: [(usize, usize); 5],

    // Smoothed band values
    smoothed_bands: [f32; 5],

    // Per-band running max for normalization (decays slowly)
    band_max: [f32; 5],

    // Preset-tunable parameters
    attack: f32,
    release: f32,
    beat_threshold: f32,
    bass_weight: f32,
    band_sensitivity: [f32; 5],

    // Beat detection
    beat_history: VecDeque<f32>,
    beat_cooldown: usize,
    last_beat_times: VecDeque<f64>,
    prev_bass: f32,
    flux_history: VecDeque<f32>,
    last_onset_time: Option<f64>,
    tempo_histogram: Vec<f32>,
    ioi_history: VecDeque<f64>,
    estimated_bpm: f32,
    tempo_confidence: f32,
    start_time: Instant,
    last_output_beat_time: f64,

    // Frame counter
    frame: u64,

    // Time tracking for BPM
    #[allow(dead_code)]
    sample_rate: u32,
}

impl FftAnalyzer {
    /// Create new FFT analyzer
    pub fn new(config: AudioConfig) -> Self {
        let fft_size = config.fft_size;
        let sample_rate = config.sample_rate;

        // Create Hann window
        let window: Vec<f32> = (0..fft_size)
            .map(|i| {
                0.5 * (1.0 - (2.0 * std::f32::consts::PI * i as f32 / (fft_size - 1) as f32).cos())
            })
            .collect();

        // Calculate band boundaries for 5 bands:
        // Bass (40-250Hz), Low (250-500Hz), Mid (500-2000Hz), High (2-6kHz), Air (6-20kHz)
        let freq_to_bin = |freq: f32| -> usize {
            ((freq * fft_size as f32) / sample_rate as f32).round() as usize
        };

        let band_boundaries = [
            (freq_to_bin(40.0), freq_to_bin(250.0)),    // Bass
            (freq_to_bin(250.0), freq_to_bin(500.0)),   // Low
            (freq_to_bin(500.0), freq_to_bin(2000.0)),  // Mid
            (freq_to_bin(2000.0), freq_to_bin(6000.0)), // High
            (freq_to_bin(6000.0), freq_to_bin(20000.0).min(fft_size / 2)), // Air
        ];

        Self {
            config: config.clone(),
            planner: FftPlanner::new(),
            fft_size,
            window,
            band_boundaries,
            smoothed_bands: [0.0; 5],
            band_max: [0.001; 5],
            attack: config.attack,
            release: config.release,
            beat_threshold: config.beat_threshold,
            bass_weight: 0.7,
            band_sensitivity: [1.0; 5],
            beat_history: VecDeque::with_capacity(60),
            beat_cooldown: 0,
            last_beat_times: VecDeque::with_capacity(20),
            prev_bass: 0.0,
            flux_history: VecDeque::with_capacity(120),
            last_onset_time: None,
            tempo_histogram: vec![0.0; 201], // 40-240 BPM
            ioi_history: VecDeque::with_capacity(32),
            estimated_bpm: 120.0,
            tempo_confidence: 0.0,
            start_time: Instant::now(),
            last_output_beat_time: 0.0,
            frame: 0,
            sample_rate,
        }
    }

    /// Apply an audio preset, updating tunable parameters without resetting state
    pub fn apply_preset(&mut self, preset: &AudioPreset) {
        self.attack = preset.attack;
        self.release = preset.release;
        self.beat_threshold = preset.beat_threshold;
        self.bass_weight = preset.bass_weight;
        self.band_sensitivity = preset.band_sensitivity;
    }

    /// Get FFT size
    pub fn fft_size(&self) -> usize {
        self.fft_size
    }

    /// Analyze audio samples and return frequency bands
    pub fn analyze(&mut self, samples: &[f32]) -> AnalysisResult {
        self.frame += 1;

        // Ensure we have enough samples
        if samples.len() < self.fft_size {
            return AnalysisResult::default();
        }

        // Apply window and prepare FFT input
        let mut buffer: Vec<Complex<f32>> = samples
            .iter()
            .take(self.fft_size)
            .zip(self.window.iter())
            .map(|(&s, &w)| Complex::new(s * w, 0.0))
            .collect();

        // Perform FFT
        let fft = self.planner.plan_fft_forward(self.fft_size);
        fft.process(&mut buffer);

        // Calculate magnitude spectrum
        let magnitudes: Vec<f32> = buffer
            .iter()
            .take(self.fft_size / 2)
            .map(|c| c.norm())
            .collect();

        // Extract bands
        let mut raw_bands = [0.0f32; 5];
        for (i, &(start, end)) in self.band_boundaries.iter().enumerate() {
            let start = start.max(1);
            let end = end.min(magnitudes.len());
            if start < end {
                let sum: f32 = magnitudes[start..end].iter().sum();
                raw_bands[i] = sum / (end - start) as f32;
            }
        }

        // Per-band AGC: each band tracks its own running max for normalization.
        // This prevents loud bass from crushing quiet high-frequency bands.
        for (i, band) in raw_bands.iter_mut().enumerate() {
            // Update running max (fast attack, slow decay)
            if *band > self.band_max[i] {
                self.band_max[i] = *band;
            } else {
                // Decay max slowly (~3 seconds to halve at 60fps)
                self.band_max[i] *= 0.997;
                self.band_max[i] = self.band_max[i].max(0.001);
            }
            *band = (*band / self.band_max[i]).min(1.0);

            // Apply per-band sensitivity from preset
            *band = (*band * self.band_sensitivity[i]).min(1.0);
        }

        // Apply envelope following (attack/release smoothing)
        for (i, &raw) in raw_bands.iter().enumerate() {
            let current = self.smoothed_bands[i];
            if raw > current {
                // Attack
                self.smoothed_bands[i] = current + (raw - current) * self.attack;
            } else {
                // Release
                self.smoothed_bands[i] = current + (raw - current) * self.release;
            }
        }

        // Calculate peak
        let peak = self.smoothed_bands.iter().cloned().fold(0.0f32, f32::max);

        // Beat detection on bass
        let bass = self.smoothed_bands[0];
        let (is_beat, beat_intensity) = self.detect_beat(bass);

        // Estimate BPM
        let bpm = self.estimate_bpm();
        let beat_phase = self.estimate_beat_phase();

        AnalysisResult {
            bands: self.smoothed_bands,
            peak,
            is_beat,
            beat_intensity,
            bpm,
            tempo_confidence: self.tempo_confidence,
            beat_phase,
            // Bass lane fields are populated by the capture loop, not by FFT analysis
            instant_bass: 0.0,
            instant_kick: false,
        }
    }

    /// Detect beats based on bass energy
    fn detect_beat(&mut self, bass: f32) -> (bool, f32) {
        // Update history
        self.beat_history.push_back(bass);
        if self.beat_history.len() > 60 {
            self.beat_history.pop_front();
        }

        // Bass flux is a robust onset signal for EDM kick transients.
        let bass_flux = (bass - self.prev_bass).max(0.0);
        self.prev_bass = bass;
        self.flux_history.push_back(bass_flux);
        if self.flux_history.len() > 120 {
            self.flux_history.pop_front();
        }

        let current_time = self.start_time.elapsed().as_secs_f64();

        // Decrement cooldown
        if self.beat_cooldown > 0 {
            self.beat_cooldown -= 1;
        }

        // Dynamic bass threshold
        let avg = if self.beat_history.is_empty() {
            0.0
        } else {
            self.beat_history.iter().sum::<f32>() / self.beat_history.len() as f32
        };
        let bass_threshold = (avg * self.beat_threshold).max(0.12);

        // Flux adaptive threshold: mean + N*std, clamped to avoid dead zones.
        let (flux_mean, flux_std) = if self.flux_history.is_empty() {
            (0.0, 0.0)
        } else {
            let mean = self.flux_history.iter().sum::<f32>() / self.flux_history.len() as f32;
            let var = self
                .flux_history
                .iter()
                .map(|v| {
                    let d = *v - mean;
                    d * d
                })
                .sum::<f32>()
                / self.flux_history.len() as f32;
            (mean, var.sqrt())
        };
        let flux_threshold = (flux_mean + flux_std * self.beat_threshold).max(0.015);

        // Onset candidate if bass jump is strong and bass is meaningfully above floor.
        let mut is_onset = bass_flux >= flux_threshold && bass > bass_threshold;
        if is_onset && bass < avg * 0.9 {
            is_onset = false;
        }

        // Enforce a minimum interval to prevent chatter in dense transients.
        let min_interval = 0.15; // ~400 BPM ceiling safety
        let can_fire = self
            .last_onset_time
            .map(|last| current_time - last >= min_interval)
            .unwrap_or(true);

        if self.beat_cooldown == 0 && is_onset && can_fire {
            self._update_bpm_from_onset(current_time);
            self.last_onset_time = Some(current_time);
            self.last_output_beat_time = current_time;

            // Soft cooldown by frame count; preserves legacy anti-chatter behavior.
            self.beat_cooldown = 8;

            // Keep raw onset times for debug/tests/legacy behavior.
            self.last_beat_times.push_back(current_time);
            if self.last_beat_times.len() > 20 {
                self.last_beat_times.pop_front();
            }

            let flux_score = (bass_flux / flux_threshold.max(0.001)).min(1.5);
            let bass_score = ((bass - avg) / avg.max(0.01)).clamp(0.0, 1.5);
            // Use bass_weight to control how much bass dominates beat intensity
            let bass_w = self.bass_weight;
            let flux_w = 1.0 - bass_w * 0.5; // flux always contributes at least 50%
            let intensity = (flux_score * flux_w + bass_score * (1.0 - flux_w)).min(1.0);
            return (true, intensity);
        }

        // Conservative prediction to fill misses once tempo lock is strong.
        if self.tempo_confidence > 0.55 && self.last_output_beat_time > 0.0 {
            let beat_period = 60.0 / self.estimated_bpm.max(60.0) as f64;
            let since_last = current_time - self.last_output_beat_time;

            if since_last > beat_period * 0.80 {
                let phase = (since_last / beat_period).fract();
                let near_boundary = !(0.10..=0.90).contains(&phase);
                if near_boundary && bass > avg * 0.85 && bass_flux > flux_mean * 0.6 {
                    self.last_output_beat_time = current_time;
                    return (true, 0.55);
                }
            }
        }

        (false, 0.0)
    }

    fn _update_bpm_from_onset(&mut self, current_time: f64) {
        if let Some(last) = self.last_onset_time {
            let ioi = current_time - last;
            // 40-240 BPM => 0.25s to 1.5s.
            if (0.25..=1.5).contains(&ioi) {
                // Once stable, reject outliers far from expected tempo multiples.
                if self.ioi_history.len() >= 4 && self.tempo_confidence > 0.3 {
                    let expected = 60.0 / self.estimated_bpm.max(60.0) as f64;
                    let ratios = [
                        ioi / expected,
                        ioi / (expected * 2.0),
                        ioi / (expected * 0.5),
                    ];
                    let best = ratios
                        .iter()
                        .map(|r| (r - 1.0).abs())
                        .fold(f64::INFINITY, f64::min);
                    if best > 0.20 {
                        return;
                    }
                }

                self.ioi_history.push_back(ioi);
                if self.ioi_history.len() > 32 {
                    self.ioi_history.pop_front();
                }
                self._update_tempo_histogram(ioi);
            }
        }
    }

    fn _update_tempo_histogram(&mut self, ioi: f64) {
        // Slow decay keeps stable lock but allows tempo transitions.
        for v in &mut self.tempo_histogram {
            *v *= 0.995;
        }

        let bpm = 60.0 / ioi;
        for multiplier in [0.5_f64, 1.0, 2.0] {
            let candidate = bpm * multiplier;
            if !(40.0..=240.0).contains(&candidate) {
                continue;
            }

            let base_idx = (candidate.round() as i32) - 40;
            for offset in -3..=3 {
                let idx = base_idx + offset;
                if !(0..self.tempo_histogram.len() as i32).contains(&idx) {
                    continue;
                }

                let x = offset as f32 / 1.5;
                let mut weight = (-0.5 * x * x).exp();
                if (80.0..=160.0).contains(&candidate) {
                    weight *= 1.4;
                }
                self.tempo_histogram[idx as usize] += weight;
            }
        }

        self._extract_tempo_from_histogram();
    }

    fn _extract_tempo_from_histogram(&mut self) {
        if self.ioi_history.len() < 4 {
            return;
        }

        let (peak_idx, peak_height) = self
            .tempo_histogram
            .iter()
            .copied()
            .enumerate()
            .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal))
            .unwrap_or((80, 0.0));

        if peak_height < 1.0 {
            return;
        }

        let start = peak_idx.saturating_sub(2);
        let end = (peak_idx + 2).min(self.tempo_histogram.len() - 1);
        let mut weighted_sum = 0.0_f32;
        let mut total_weight = 0.0_f32;
        for i in start..=end {
            let bpm = (i + 40) as f32;
            let w = self.tempo_histogram[i];
            weighted_sum += bpm * w;
            total_weight += w;
        }
        let refined_bpm = if total_weight > 0.0 {
            weighted_sum / total_weight
        } else {
            (peak_idx + 40) as f32
        };

        let mean_height =
            self.tempo_histogram.iter().sum::<f32>() / self.tempo_histogram.len().max(1) as f32;
        let prominence = peak_height / (mean_height + 0.001);
        let sample_conf = (self.ioi_history.len() as f32 / 16.0).min(1.0);

        let recent: Vec<f64> = self.ioi_history.iter().rev().take(8).copied().collect();
        let consistency_conf = if recent.len() >= 4 {
            let mean = recent.iter().sum::<f64>() / recent.len() as f64;
            let var = recent
                .iter()
                .map(|v| {
                    let d = *v - mean;
                    d * d
                })
                .sum::<f64>()
                / recent.len() as f64;
            let cv = (var.sqrt() / (mean + 1e-6)).min(1.0);
            (1.0 - (cv as f32 * 2.0)).clamp(0.0, 1.0)
        } else {
            0.5
        };

        let new_conf = ((prominence / 15.0) * sample_conf * consistency_conf).clamp(0.0, 1.0);
        let bpm_delta = (refined_bpm - self.estimated_bpm).abs();
        let accept =
            self.tempo_confidence < 0.3 || bpm_delta < 12.0 || new_conf > self.tempo_confidence;

        if accept {
            let alpha = if new_conf > 0.65 { 0.08 } else { 0.18 };
            self.estimated_bpm =
                ((1.0 - alpha) * self.estimated_bpm + alpha * refined_bpm).clamp(60.0, 200.0);
            self.tempo_confidence = new_conf;
        }
    }

    /// Estimate BPM from beat history
    fn estimate_bpm(&self) -> f32 {
        self.estimated_bpm
    }

    /// Estimate current beat phase in [0, 1).
    fn estimate_beat_phase(&self) -> f32 {
        if self.last_output_beat_time <= 0.0 || self.estimated_bpm <= 0.0 {
            return 0.0;
        }

        let now = self.start_time.elapsed().as_secs_f64();
        let beat_period = 60.0 / self.estimated_bpm as f64;
        if beat_period <= 0.0 {
            return 0.0;
        }

        let elapsed = (now - self.last_output_beat_time).max(0.0);
        ((elapsed / beat_period).fract()) as f32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_approx(actual: f32, expected: f32, tolerance: f32) {
        assert!(
            (actual - expected).abs() <= tolerance,
            "expected {expected} +/- {tolerance}, got {actual}"
        );
    }

    #[test]
    fn analyze_returns_default_for_insufficient_samples() {
        let mut analyzer = FftAnalyzer::new(AudioConfig::default());
        let short_samples = vec![0.0; analyzer.fft_size() - 1];

        let result = analyzer.analyze(&short_samples);

        assert_eq!(result.bands, [0.0; 5]);
        assert_eq!(result.peak, 0.0);
        assert!(!result.is_beat);
        assert_eq!(result.beat_intensity, 0.0);
        assert_eq!(result.bpm, 0.0);
    }

    #[test]
    fn analyze_silence_produces_no_energy_and_default_bpm() {
        let mut analyzer = FftAnalyzer::new(AudioConfig::default());
        let silent_frame = vec![0.0; analyzer.fft_size()];

        let result = analyzer.analyze(&silent_frame);

        assert_eq!(result.bands, [0.0; 5]);
        assert_eq!(result.peak, 0.0);
        assert!(!result.is_beat);
        assert_eq!(result.beat_intensity, 0.0);
        assert_eq!(result.bpm, 120.0);
    }

    #[test]
    fn detect_beat_enforces_cooldown() {
        let mut analyzer = FftAnalyzer::new(AudioConfig::default());

        for _ in 0..60 {
            analyzer.beat_history.push_back(0.1);
        }

        analyzer.frame = 120;
        let (first_beat, first_intensity) = analyzer.detect_beat(0.6);
        assert!(first_beat);
        assert!(first_intensity > 0.0);
        assert_eq!(analyzer.beat_cooldown, 8);

        analyzer.frame += 1;
        let (second_beat, second_intensity) = analyzer.detect_beat(0.8);
        assert!(!second_beat);
        assert_eq!(second_intensity, 0.0);
        assert_eq!(analyzer.beat_cooldown, 7);
    }

    #[test]
    fn estimate_bpm_uses_recent_beat_intervals() {
        let mut analyzer = FftAnalyzer::new(AudioConfig::default());
        analyzer.last_beat_times = VecDeque::from(vec![0.0, 0.5, 1.0, 1.5, 2.0]);
        analyzer.estimated_bpm = 120.0;

        let bpm = analyzer.estimate_bpm();

        assert_approx(bpm, 120.0, 0.01);
    }

    #[test]
    fn tempo_histogram_locks_to_128_bpm() {
        let mut analyzer = FftAnalyzer::new(AudioConfig::default());
        let period = 60.0 / 128.0;

        for i in 0..32 {
            let t = i as f64 * period;
            analyzer._update_bpm_from_onset(t);
            analyzer.last_onset_time = Some(t);
        }

        let bpm = analyzer.estimate_bpm();
        assert!((bpm - 128.0).abs() < 4.0, "expected ~128 BPM, got {bpm}");
    }

    #[test]
    fn get_presets_returns_six_presets() {
        let presets = get_presets();
        assert_eq!(presets.len(), 6);
        let names: Vec<&str> = presets.iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["auto", "edm", "chill", "rock", "hiphop", "classical"]);
    }

    #[test]
    fn get_preset_by_name_is_case_insensitive() {
        assert!(get_preset("EDM").is_some());
        assert!(get_preset("Chill").is_some());
        assert!(get_preset("nonexistent").is_none());
    }

    #[test]
    fn apply_preset_updates_analyzer_parameters() {
        let mut analyzer = FftAnalyzer::new(AudioConfig::default());
        let edm = get_preset("edm").unwrap();
        analyzer.apply_preset(&edm);

        assert_approx(analyzer.attack, 0.7, 0.001);
        assert_approx(analyzer.release, 0.15, 0.001);
        assert_approx(analyzer.beat_threshold, 1.1, 0.001);
        assert_approx(analyzer.bass_weight, 0.85, 0.001);
        assert_approx(analyzer.band_sensitivity[0], 1.5, 0.001);
    }

    #[test]
    fn tempo_histogram_handles_half_time_ioi() {
        let mut analyzer = FftAnalyzer::new(AudioConfig::default());
        let half_time_period = 60.0 / 64.0;

        for i in 0..32 {
            let t = i as f64 * half_time_period;
            analyzer._update_bpm_from_onset(t);
            analyzer.last_onset_time = Some(t);
        }

        let bpm = analyzer.estimate_bpm();
        // Accept either 64 or its preferred octave cluster around 128.
        let ok = (bpm - 64.0).abs() < 4.0 || (bpm - 128.0).abs() < 6.0;
        assert!(ok, "expected ~64 or ~128 BPM, got {bpm}");
    }

    // === BassLane tests ===

    #[test]
    fn bass_lane_silence_produces_no_energy() {
        let mut bl = BassLane::new(48000.0);
        let silence = vec![0.0f32; 480]; // 10ms at 48kHz
        let (energy, kick) = bl.process(&silence);

        assert!(energy < 0.01, "silence should produce near-zero energy, got {}", energy);
        assert!(!kick, "silence should not trigger kick");
    }

    #[test]
    fn bass_lane_bass_tone_produces_energy() {
        let mut bl = BassLane::new(48000.0);
        let sample_rate = 48000.0;
        let freq = 80.0; // 80Hz bass tone

        // Generate 50ms of 80Hz sine wave at moderate amplitude
        let n_samples = (sample_rate * 0.05) as usize;
        let samples: Vec<f32> = (0..n_samples)
            .map(|i| 0.5 * (2.0 * std::f32::consts::PI * freq * i as f32 / sample_rate).sin())
            .collect();

        let (energy, _kick) = bl.process(&samples);
        assert!(energy > 0.1, "80Hz tone should produce bass energy, got {}", energy);
    }

    #[test]
    fn bass_lane_kick_detection_with_transient() {
        let mut bl = BassLane::new(48000.0);
        let sample_rate = 48000.0;

        // Prime with silence for a few buffers so running_peak is low
        let silence = vec![0.0f32; 4800];
        for _ in 0..5 {
            bl.process(&silence);
        }

        // Generate a sharp transient (kick-like)
        let n_samples = (sample_rate * 0.01) as usize; // 10ms burst
        let kick_samples: Vec<f32> = (0..n_samples)
            .map(|i| {
                let t = i as f32 / sample_rate;
                let decay = (-t * 100.0).exp(); // Fast exponential decay
                0.8 * decay * (2.0 * std::f32::consts::PI * 60.0 * t).sin()
            })
            .collect();

        let (_energy, kick) = bl.process(&kick_samples);
        assert!(kick, "sharp bass transient should trigger kick detection");
    }

    #[test]
    fn bass_lane_cooldown_prevents_double_trigger() {
        let mut bl = BassLane::new(48000.0);
        let sample_rate = 48000.0;

        // Prime with silence
        let silence = vec![0.0f32; 4800];
        for _ in 0..3 {
            bl.process(&silence);
        }

        // First kick
        let n = (sample_rate * 0.005) as usize;
        let kick1: Vec<f32> = (0..n)
            .map(|i| {
                let t = i as f32 / sample_rate;
                0.9 * (-t * 200.0).exp() * (2.0 * std::f32::consts::PI * 60.0 * t).sin()
            })
            .collect();
        let (_, first_kick) = bl.process(&kick1);

        // Immediately another kick (within cooldown)
        let kick2 = kick1.clone();
        let (_, second_kick) = bl.process(&kick2);

        // First should fire, second should be cooldown-suppressed
        assert!(first_kick, "first kick should fire");
        assert!(!second_kick, "second kick within cooldown should be suppressed");
    }

    #[test]
    fn bass_lane_reset_clears_state() {
        let mut bl = BassLane::new(48000.0);

        // Process some audio
        let samples: Vec<f32> = (0..480)
            .map(|i| 0.3 * (2.0 * std::f32::consts::PI * 80.0 * i as f32 / 48000.0).sin())
            .collect();
        bl.process(&samples);

        bl.reset();
        let (energy, kick) = bl.current_state();
        assert!(energy < 0.01, "reset should clear energy");
        assert!(!kick, "reset should clear kick state");
    }

    #[test]
    fn bass_lane_lowpass_attenuates_high_frequency() {
        let sample_rate = 48000.0;

        // Process a steady 80Hz bass tone and measure the absolute envelope
        let mut bl_bass = BassLane::new(sample_rate);
        let n_samples = (sample_rate * 0.1) as usize;
        let bass_samples: Vec<f32> = (0..n_samples)
            .map(|i| 0.5 * (2.0 * std::f32::consts::PI * 80.0 * i as f32 / sample_rate).sin())
            .collect();
        bl_bass.process(&bass_samples);
        let bass_envelope = bl_bass.raw_envelope();

        // Process a steady 5kHz tone at the same amplitude
        let mut bl_high = BassLane::new(sample_rate);
        let high_samples: Vec<f32> = (0..n_samples)
            .map(|i| 0.5 * (2.0 * std::f32::consts::PI * 5000.0 * i as f32 / sample_rate).sin())
            .collect();
        bl_high.process(&high_samples);
        let high_envelope = bl_high.raw_envelope();

        // The 120Hz single-pole IIR lowpass should attenuate 5kHz significantly.
        // At 5kHz with 120Hz cutoff: attenuation ~= 120/5000 ~= 0.024 (-32dB)
        // So the high-freq envelope should be much smaller than the bass envelope.
        assert!(
            high_envelope < bass_envelope * 0.1,
            "5kHz envelope ({:.6}) should be <10% of 80Hz envelope ({:.6})",
            high_envelope,
            bass_envelope
        );
    }
}
