//! Deterministic synthetic benchmark for the production DSP path
//! (`FftAnalyzer` + `BassLane`), processed exactly like the capture loop:
//! the latest `FFT_SIZE` samples are re-analyzed every `HOP` samples.
//!
//! What this measures: local per-block processing cost on this host's
//! monotonic clock (bass lane, FFT, combined). It is a cost benchmark, not a
//! latency benchmark — it says nothing about device acquisition time, network,
//! or end-to-end latency.
//!
//! Documented limitations (properties of the existing DSP, kept unchanged):
//! - `FftAnalyzer` beat detection gates onsets with wall-clock `Instant` time
//!   (150ms minimum interval; BPM histogram built from inter-onset wall time).
//!   Offline PCM is processed far faster than real time, so tempo lock, BPM
//!   accuracy and FFT onset timing observed here are NOT representative of
//!   live behavior. This harness therefore never reports them as timing
//!   metrics; beat flags are only checked for finite, bounded values.
//! - `BassLane` is purely sample-domain (IIR state + sample-count cooldown),
//!   so its kick decisions are deterministic offline and are regression-tested.
//!   Note `instant_bass` is normalized against the lane's own running peak, so
//!   any constant-amplitude signal self-normalizes toward 1.0; it is not a
//!   frequency-selectivity measure.
//! - This harness does not observe hardware acquisition timestamps, so
//!   device-to-analysis latency cannot be measured with synthetic PCM.
//! - Overlapping windows replay samples into BassLane, just as production
//!   currently does. Its sample counter is not elapsed stream time here.
//!
//! This module is compiled only under `cfg(test)` (see `audio/mod.rs`).

use std::hint::black_box;
use std::time::Instant;

use super::{AudioConfig, BassLane, FftAnalyzer};

const SAMPLE_RATE: u32 = 48_000;
const FFT_SIZE: usize = 1024;
/// Analysis hop matching the production ~10ms capture tick at 48kHz.
const HOP: usize = 480;

/// Per-block processing cost percentiles in microseconds.
#[derive(Debug, Clone, Copy)]
struct BlockStats {
    p50_us: f64,
    p95_us: f64,
    max_us: f64,
}

impl BlockStats {
    fn from_samples(mut samples: Vec<f64>) -> Self {
        samples.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let n = samples.len();
        if n == 0 {
            return Self {
                p50_us: 0.0,
                p95_us: 0.0,
                max_us: 0.0,
            };
        }
        let pick = |p: f64| -> f64 {
            let idx = ((p / 100.0) * (n - 1) as f64).round() as usize;
            samples[idx.min(n - 1)]
        };
        Self {
            p50_us: pick(50.0),
            p95_us: pick(95.0),
            max_us: samples[n - 1],
        }
    }
}

/// Aggregated result of running the production analyzers over one PCM track.
struct ScenarioReport {
    name: &'static str,
    blocks: usize,
    /// BassLane kick decisions (deterministic: sample-domain state only).
    kicks: u32,
    /// Start sample of each analysis window that reported a BassLane onset.
    kick_windows: Vec<usize>,
    /// FFT beat flags (wall-clock dependent; informational only).
    beats: u32,
    bass: BlockStats,
    fft: BlockStats,
    total: BlockStats,
    peak_max: f32,
    instant_bass_max: f32,
}

/// Fixed digital silence.
fn silence_pcm(seconds: f32) -> Vec<f32> {
    vec![0.0; (seconds * SAMPLE_RATE as f32) as usize]
}

/// Kick-like bursts: 10ms of 60Hz sine with exponential decay, one burst
/// per `period_secs` after `lead_in_secs` of silence. Spacing only
/// distributes transients; no BPM claim is made (see module limitations).
fn kick_bursts_pcm(seconds: f32, lead_in_secs: f32, period_secs: f32) -> Vec<f32> {
    let n = (seconds * SAMPLE_RATE as f32) as usize;
    let mut pcm = vec![0.0f32; n];
    let burst_len = (0.010 * SAMPLE_RATE as f32) as usize;
    let period = (period_secs * SAMPLE_RATE as f32) as usize;
    let mut start = (lead_in_secs * SAMPLE_RATE as f32) as usize;
    while start + burst_len < n {
        for i in 0..burst_len {
            let t = i as f32 / SAMPLE_RATE as f32;
            let decay = (-t * 60.0).exp();
            pcm[start + i] = 0.9 * decay * (2.0 * std::f32::consts::PI * 60.0 * t).sin();
        }
        start += period;
    }
    pcm
}

/// Number of bursts `kick_bursts_pcm` will emit (must stay in sync with it).
fn expected_bursts(seconds: f32, lead_in_secs: f32, period_secs: f32) -> usize {
    let n = (seconds * SAMPLE_RATE as f32) as usize;
    let burst_len = (0.010 * SAMPLE_RATE as f32) as usize;
    let period = (period_secs * SAMPLE_RATE as f32) as usize;
    let mut start = (lead_in_secs * SAMPLE_RATE as f32) as usize;
    let mut count = 0;
    while start + burst_len < n {
        count += 1;
        start += period;
    }
    count
}

/// Linear chirp from 40Hz to 20kHz across the full duration, fixed amplitude.
fn sweep_pcm(seconds: f32) -> Vec<f32> {
    let n = (seconds * SAMPLE_RATE as f32) as usize;
    let f0 = 40.0_f32;
    let f1 = 20_000.0_f32;
    let mut phase = 0.0_f32;
    let mut pcm = Vec::with_capacity(n);
    for i in 0..n {
        let t = i as f32 / SAMPLE_RATE as f32;
        let freq = f0 + (f1 - f0) * (t / seconds);
        phase += 2.0 * std::f32::consts::PI * freq / SAMPLE_RATE as f32;
        pcm.push(0.5 * phase.sin());
    }
    pcm
}

/// Run the production analyzers over `pcm` in overlapping windows
/// (`pcm[k*HOP .. k*HOP + FFT_SIZE]`), timing each block. Reuses one
/// window buffer, matching the capture loop's no-allocation behavior.
fn run_scenario(name: &'static str, pcm: &[f32]) -> ScenarioReport {
    let mut analyzer = FftAnalyzer::new(AudioConfig {
        sample_rate: SAMPLE_RATE,
        fft_size: FFT_SIZE,
        ..AudioConfig::default()
    });
    let mut bass_lane = BassLane::new(SAMPLE_RATE as f32);

    assert!(
        pcm.len() >= FFT_SIZE,
        "track must cover at least one window"
    );
    let blocks = (pcm.len() - FFT_SIZE) / HOP + 1;
    let mut window = vec![0.0f32; FFT_SIZE];

    let mut bass_us = Vec::with_capacity(blocks);
    let mut fft_us = Vec::with_capacity(blocks);
    let mut kicks = 0u32;
    let mut kick_windows = Vec::with_capacity(blocks);
    let mut beats = 0u32;
    let mut peak_max = 0.0_f32;
    let mut instant_bass_max = 0.0_f32;
    // Keeps analyzer results observationally used inside the timing loop.
    let mut sink: f64 = 0.0;

    for k in 0..blocks {
        let base = k * HOP;
        window.copy_from_slice(&pcm[base..base + FFT_SIZE]);

        let t0 = Instant::now();
        let (i_bass, i_kick) = bass_lane.process(black_box(window.as_slice()));
        let bass_elapsed = t0.elapsed().as_secs_f64() * 1e6;

        let t1 = Instant::now();
        let result = analyzer.analyze(black_box(window.as_slice()));
        let fft_elapsed = t1.elapsed().as_secs_f64() * 1e6;

        // Check every frame: an aggregate maximum alone could hide NaNs.
        for value in result.bands.iter().copied().chain([
            result.peak,
            result.beat_intensity,
            result.tempo_confidence,
            result.beat_phase,
            i_bass,
        ]) {
            assert!(value.is_finite() && (0.0..=1.0).contains(&value));
        }
        assert!(result.bpm.is_finite() && result.bpm >= 0.0);

        bass_us.push(bass_elapsed);
        fft_us.push(fft_elapsed);
        kicks += u32::from(i_kick);
        if i_kick {
            kick_windows.push(base);
        }
        beats += u32::from(result.is_beat);
        peak_max = peak_max.max(result.peak);
        instant_bass_max = instant_bass_max.max(i_bass);
        sink += result.peak as f64
            + result.bands.iter().map(|&b| b as f64).sum::<f64>()
            + i_bass as f64
            + result.beat_intensity as f64;
    }
    black_box(sink);

    let total_us: Vec<f64> = bass_us
        .iter()
        .zip(fft_us.iter())
        .map(|(b, f)| b + f)
        .collect();

    ScenarioReport {
        name,
        blocks,
        kicks,
        kick_windows,
        beats,
        bass: BlockStats::from_samples(bass_us),
        fft: BlockStats::from_samples(fft_us),
        total: BlockStats::from_samples(total_us),
        peak_max,
        instant_bass_max,
    }
}

fn print_report(r: &ScenarioReport) {
    println!(
        "[DSP-BENCH] scenario={} rate={}Hz window={} hop={} blocks={}",
        r.name, SAMPLE_RATE, FFT_SIZE, HOP, r.blocks
    );
    println!(
        "[DSP-BENCH]   bass_us  p50={:.1} p95={:.1} max={:.1}",
        r.bass.p50_us, r.bass.p95_us, r.bass.max_us
    );
    println!(
        "[DSP-BENCH]   fft_us   p50={:.1} p95={:.1} max={:.1}",
        r.fft.p50_us, r.fft.p95_us, r.fft.max_us
    );
    println!(
        "[DSP-BENCH]   sum_us   p50={:.1} p95={:.1} max={:.1}",
        r.total.p50_us, r.total.p95_us, r.total.max_us
    );
    println!(
        "[DSP-BENCH]   basslane_kicks={} fft_beats={} (wall-clock dependent, not a timing metric) peak_max={:.3} instant_bass_max={:.3}",
        r.kicks, r.beats, r.peak_max, r.instant_bass_max
    );
}

fn assert_finite_bounded(r: &ScenarioReport) {
    assert!(r.blocks > 0);
    assert!(r.peak_max.is_finite() && (0.0..=1.0).contains(&r.peak_max));
    assert!(r.instant_bass_max.is_finite() && (0.0..=1.0).contains(&r.instant_bass_max));
    assert!(r.beats <= r.blocks as u32);
    for s in [&r.bass, &r.fft, &r.total] {
        assert!(s.p50_us.is_finite() && s.p50_us >= 0.0);
        assert!(s.p95_us >= s.p50_us);
        assert!(s.max_us >= s.p95_us);
    }
}

#[test]
fn silence_is_stable_and_finite() {
    let report = run_scenario("silence", &silence_pcm(2.0));

    // Digital silence: no energy, no beat, no kick. Fully deterministic.
    assert_eq!(report.kicks, 0);
    assert_eq!(report.beats, 0);
    assert_eq!(report.peak_max, 0.0);
    assert_eq!(report.instant_bass_max, 0.0);
    assert_finite_bounded(&report);
}

#[test]
fn kick_bursts_fire_bass_lane_deterministically() {
    let (seconds, lead_in, period) = (10.0_f32, 0.5_f32, 0.5_f32);
    let pcm = kick_bursts_pcm(seconds, lead_in, period);
    let expected = expected_bursts(seconds, lead_in, period) as u32;
    assert!(expected >= 10);
    let report = run_scenario("kick_bursts", &pcm);

    // BassLane state is sample-domain only, so every burst's rising edge
    // must produce a kick flag: one or more firings per burst, and never
    // more than one per analysis block.
    assert!(
        report.kicks >= expected,
        "expected >= {expected} kicks, got {}",
        report.kicks
    );
    assert!(report.kicks <= report.blocks as u32);
    // Counting alone could hide one missed burst plus one false positive.
    // Match detections to known PCM bursts, without treating window positions
    // as measured live latency (the production windows overlap).
    let lead_samples = (lead_in * SAMPLE_RATE as f32) as usize;
    let period_samples = (period * SAMPLE_RATE as f32) as usize;
    let burst_samples = (0.010 * SAMPLE_RATE as f32) as usize;
    assert_eq!(report.kick_windows.len(), expected as usize);
    // Both streams are chronological, so a linear pairing also rules out
    // extra detections, missed bursts, and events in the silent gaps.
    for (index, &window_start) in report.kick_windows.iter().enumerate() {
        let burst_start = lead_samples + index * period_samples;
        assert!(
            window_start < burst_start + burst_samples && window_start + FFT_SIZE > burst_start,
            "burst at sample {burst_start} mismatched detection window {window_start}"
        );
    }
    assert!(report.instant_bass_max > 0.1);
    assert!(report.peak_max > 0.1);
    assert_finite_bounded(&report);
}

#[test]
fn kick_burst_results_are_repeatable() {
    // Same PCM, fresh analyzers: identical signal-derived outputs.
    let pcm = kick_bursts_pcm(4.0, 0.25, 0.5);
    let a = run_scenario("kick_bursts_a", &pcm);
    let b = run_scenario("kick_bursts_b", &pcm);

    assert_eq!(a.blocks, b.blocks);
    assert_eq!(a.kicks, b.kicks);
    assert_eq!(a.kick_windows, b.kick_windows);
    assert_eq!(a.peak_max, b.peak_max);
    assert_eq!(a.instant_bass_max, b.instant_bass_max);
}

#[test]
fn sweep_output_is_finite_and_bounded() {
    let report = run_scenario("sweep", &sweep_pcm(4.0));

    assert!(report.peak_max > 0.1);
    // instant_bass is normalized against the lane's own running peak, so a
    // constant-amplitude chirp legitimately self-normalizes high; only its
    // finite/bounded range is asserted here (see module limitations).
    assert_finite_bounded(&report);
}

#[test]
fn sweep_is_repeatable() {
    let pcm = sweep_pcm(4.0);
    let a = run_scenario("sweep_a", &pcm);
    let b = run_scenario("sweep_b", &pcm);

    assert_eq!(a.blocks, b.blocks);
    assert_eq!(a.peak_max, b.peak_max);
    assert_eq!(a.instant_bass_max, b.instant_bass_max);
}

/// Machine-dependent cost report. Run explicitly with:
/// `cargo test --lib dsp_benchmark -- --ignored --nocapture`
/// Not a CI threshold: absolute numbers vary per host and load.
#[test]
#[ignore = "manual benchmark; prints host-specific timings"]
fn report_baseline() {
    let scenarios: Vec<(&'static str, Vec<f32>)> = vec![
        ("silence", silence_pcm(2.0)),
        ("kick_bursts", kick_bursts_pcm(10.0, 0.5, 0.5)),
        ("sweep", sweep_pcm(10.0)),
    ];
    for (name, pcm) in &scenarios {
        // Warm code/data caches. The reported run still starts with fresh DSP state.
        black_box(run_scenario(name, pcm));
        let report = run_scenario(name, pcm);
        assert_finite_bounded(&report);
        print_report(&report);
    }
}
