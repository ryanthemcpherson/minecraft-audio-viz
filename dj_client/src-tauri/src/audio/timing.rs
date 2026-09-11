//! Clock-local pipeline observations. Instants never cross the wire.

use std::time::Instant;

const MAX_DURATION_MS: f64 = 60_000.0;

/// Receipt means entry into the mono sample buffer, not hardware acquisition.
#[derive(Debug, Clone, Copy)]
pub struct AnalysisTiming {
    pub buffer_received_at: Instant,
    pub analysis_started_at: Instant,
    pub analysis_finished_at: Instant,
    pub window_ms: f64,
}

/// All intervals are measured on the DJ's monotonic clock, in milliseconds.
#[derive(Debug, Clone, Copy, serde::Serialize)]
pub struct FrameTiming {
    pub buffer_to_analysis_ms: f64,
    pub analysis_ms: f64,
    pub analysis_to_enqueue_ms: f64,
    pub window_ms: f64,
}

impl AnalysisTiming {
    /// Observe immediately before bridge serialization/enqueue, not socket delivery.
    /// Omit stale or incoherent observations rather than turn them into zero latency.
    pub fn at_enqueue(self, now: Instant) -> Option<FrameTiming> {
        let timing = FrameTiming {
            buffer_to_analysis_ms: self
                .analysis_started_at
                .checked_duration_since(self.buffer_received_at)?
                .as_secs_f64()
                * 1000.0,
            analysis_ms: self
                .analysis_finished_at
                .checked_duration_since(self.analysis_started_at)?
                .as_secs_f64()
                * 1000.0,
            analysis_to_enqueue_ms: now
                .checked_duration_since(self.analysis_finished_at)?
                .as_secs_f64()
                * 1000.0,
            window_ms: self.window_ms,
        };
        if [
            timing.buffer_to_analysis_ms,
            timing.analysis_ms,
            timing.analysis_to_enqueue_ms,
            timing.window_ms,
        ]
        .iter()
        .all(|value| value.is_finite() && (0.0..=MAX_DURATION_MS).contains(value))
            && timing.window_ms > 0.0
        {
            Some(timing)
        } else {
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn invalid_window_and_reversed_analysis_are_omitted() {
        let now = Instant::now();
        let observation = AnalysisTiming {
            buffer_received_at: now,
            analysis_started_at: now,
            analysis_finished_at: now,
            window_ms: 1.0,
        };
        assert!(observation.at_enqueue(now).is_some());
        for window_ms in [0.0, -1.0, 60_001.0, f64::NAN, f64::INFINITY] {
            assert!(
                AnalysisTiming {
                    window_ms,
                    ..observation
                }
                .at_enqueue(now)
                .is_none()
            );
        }
        assert!(
            AnalysisTiming {
                buffer_received_at: now + Duration::from_millis(1),
                ..observation
            }
            .at_enqueue(now)
            .is_none()
        );
        assert!(
            AnalysisTiming {
                analysis_started_at: now + Duration::from_millis(1),
                ..observation
            }
            .at_enqueue(now + Duration::from_millis(2))
            .is_none()
        );
    }

    #[test]
    fn intervals_share_one_clock_and_stale_analysis_ages_between_enqueues() {
        let start = Instant::now();
        let observation = AnalysisTiming {
            buffer_received_at: start,
            analysis_started_at: start + Duration::from_millis(7),
            analysis_finished_at: start + Duration::from_millis(9),
            window_ms: 1024.0 / 48.0,
        };
        let frame = observation
            .at_enqueue(start + Duration::from_millis(16))
            .unwrap();
        assert_eq!(frame.buffer_to_analysis_ms, 7.0);
        assert_eq!(frame.analysis_ms, 2.0);
        assert_eq!(frame.analysis_to_enqueue_ms, 7.0);
        assert_eq!(
            observation
                .at_enqueue(start + Duration::from_millis(32))
                .unwrap()
                .analysis_to_enqueue_ms,
            23.0
        );
        assert!(observation.at_enqueue(start).is_none());
        assert!(
            observation
                .at_enqueue(start + Duration::from_secs(61))
                .is_none()
        );
    }
}
