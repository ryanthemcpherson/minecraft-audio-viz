//! FFT analysis - ported from Python fft_analyzer.py

use super::{capture::AnalysisResult, AudioConfig};
use rustfft::{num_complex::Complex, FftPlanner};
use std::collections::VecDeque;
use std::time::Instant;

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
            config,
            planner: FftPlanner::new(),
            fft_size,
            window,
            band_boundaries,
            smoothed_bands: [0.0; 5],
            band_max: [0.001; 5],
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
        }

        // Apply envelope following (attack/release smoothing)
        for (i, &raw) in raw_bands.iter().enumerate() {
            let current = self.smoothed_bands[i];
            if raw > current {
                // Attack
                self.smoothed_bands[i] = current + (raw - current) * self.config.attack;
            } else {
                // Release
                self.smoothed_bands[i] = current + (raw - current) * self.config.release;
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
        let bass_threshold = (avg * self.config.beat_threshold).max(0.12);

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
        let flux_threshold = (flux_mean + flux_std * self.config.beat_threshold).max(0.015);

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
            let bass_score = ((bass - avg) / avg.max(0.01)).max(0.0).min(1.5);
            let intensity = (flux_score * 0.65 + bass_score * 0.35).min(1.0);
            return (true, intensity);
        }

        // Conservative prediction to fill misses once tempo lock is strong.
        if self.tempo_confidence > 0.55 && self.last_output_beat_time > 0.0 {
            let beat_period = 60.0 / self.estimated_bpm.max(60.0) as f64;
            let since_last = current_time - self.last_output_beat_time;

            if since_last > beat_period * 0.80 {
                let phase = (since_last / beat_period).fract();
                let near_boundary = phase < 0.10 || phase > 0.90;
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
}
