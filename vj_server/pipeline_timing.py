"""Bounded pipeline observations; DJ and server clocks are never subtracted."""

import math
from collections import deque
from typing import Any

MAX_DURATION_MS = 60_000.0
WINDOW_SIZE = 512
DJ_FIELDS = (
    "buffer_to_analysis_ms",
    "analysis_ms",
    "analysis_to_enqueue_ms",
    "window_ms",
)
STAGES = tuple(f"dj_{field}" for field in DJ_FIELDS) + (
    "server_handler_ms",
    "server_receipt_to_selection_ms",
)


def valid_duration(value: Any) -> bool:
    """Validate telemetry at the wire boundary without coercion or clamping."""
    return type(value) in (int, float) and 0 <= value <= MAX_DURATION_MS and math.isfinite(value)


def sanitize_timing(value: Any) -> dict[str, float] | None:
    """Ignore malformed optional telemetry without rejecting its audio frame."""
    if not isinstance(value, dict) or value.keys() != set(DJ_FIELDS):
        return None
    if not all(valid_duration(value[field]) for field in DJ_FIELDS):
        return None
    if value["window_ms"] <= 0:
        return None
    return {field: float(value[field]) for field in DJ_FIELDS}


class PipelineTimingMetrics:
    """Fixed-cardinality rolling samples, shared across DJs; event-loop owned.

    Selection age is observed per render selection, including repeated frames.
    Quantiles describe the most recent 512 observations per stage, not all time.
    """

    def __init__(self) -> None:
        self.samples: dict[str, deque[float]] = {
            stage: deque(maxlen=WINDOW_SIZE) for stage in STAGES
        }

    def observe(self, stage: str, duration_ms: float) -> None:
        if valid_duration(duration_ms):
            self.samples[stage].append(float(duration_ms))

    def observe_dj(self, timing: dict[str, float] | None) -> None:
        if timing is not None:
            for field in DJ_FIELDS:
                self.observe(f"dj_{field}", timing[field])

    def prometheus_lines(self) -> list[str]:
        """Omit unobserved series: missing evidence is not a zero measurement."""
        lines = [
            "# HELP mcav_pipeline_window_ms Local interval over the last 512 observations; not end-to-end latency",
            "# TYPE mcav_pipeline_window_ms gauge",
            "# HELP mcav_pipeline_window_samples Number of observations in the rolling window",
            "# TYPE mcav_pipeline_window_samples gauge",
        ]
        for stage, samples in self.samples.items():
            if not samples:
                continue
            ordered = sorted(samples)
            for statistic, value in (
                ("p50", ordered[math.ceil(len(ordered) * 0.50) - 1]),
                ("p95", ordered[math.ceil(len(ordered) * 0.95) - 1]),
                ("max", ordered[-1]),
            ):
                lines.append(
                    f'mcav_pipeline_window_ms{{stage="{stage}",statistic="{statistic}"}} {value:.6f}'
                )
            lines.append(f'mcav_pipeline_window_samples{{stage="{stage}"}} {len(samples)}')
        return lines
