//! FFT analysis - ported from Python fft_analyzer.py

use super::{capture::AnalysisResult, AudioConfig};
use rustfft::{num_complex::Complex, FftPlanner};
use std::collections::VecDeque;

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

        AnalysisResult {
            bands: self.smoothed_bands,
            peak,
            is_beat,
            beat_intensity,
            bpm,
        }
    }

    /// Detect beats based on bass energy
    fn detect_beat(&mut self, bass: f32) -> (bool, f32) {
        // Update history
        self.beat_history.push_back(bass);
        if self.beat_history.len() > 60 {
            self.beat_history.pop_front();
        }

        // Decrement cooldown
        if self.beat_cooldown > 0 {
            self.beat_cooldown -= 1;
            return (false, 0.0);
        }

        // Calculate average and threshold
        let avg = if self.beat_history.is_empty() {
            0.0
        } else {
            self.beat_history.iter().sum::<f32>() / self.beat_history.len() as f32
        };

        let threshold = avg * self.config.beat_threshold;

        // Detect beat
        if bass > threshold && bass > 0.2 {
            self.beat_cooldown = 8; // ~133ms cooldown at 60fps

            // Record beat time for BPM calculation
            let current_time = self.frame as f64 / 60.0; // Approximate time in seconds
            self.last_beat_times.push_back(current_time);
            if self.last_beat_times.len() > 20 {
                self.last_beat_times.pop_front();
            }

            let intensity = ((bass - threshold) / avg.max(0.01)).min(1.0);
            return (true, intensity);
        }

        (false, 0.0)
    }

    /// Estimate BPM from beat history
    fn estimate_bpm(&self) -> f32 {
        if self.last_beat_times.len() < 4 {
            return 120.0; // Default BPM
        }

        // Calculate intervals between beats
        let mut intervals: Vec<f64> = Vec::new();
        for i in 1..self.last_beat_times.len() {
            let interval = self.last_beat_times[i] - self.last_beat_times[i - 1];
            if interval > 0.2 && interval < 2.0 {
                // Filter outliers (30-300 BPM range)
                intervals.push(interval);
            }
        }

        if intervals.is_empty() {
            return 120.0;
        }

        // Calculate average interval
        let avg_interval = intervals.iter().sum::<f64>() / intervals.len() as f64;

        // Convert to BPM
        let bpm = 60.0 / avg_interval;

        // Clamp to reasonable range
        bpm.clamp(60.0, 200.0) as f32
    }
}
