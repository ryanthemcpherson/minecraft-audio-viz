"""In-process counters for lightweight operational observability."""

from __future__ import annotations

from collections import Counter
from threading import Lock

_lock = Lock()
_counters: Counter[str] = Counter()
_HTTP_LATENCY_BUCKETS_MS: tuple[float, ...] = (
    5.0,
    10.0,
    25.0,
    50.0,
    100.0,
    250.0,
    500.0,
    1000.0,
    2500.0,
    5000.0,
)
_http_latency_buckets: dict[tuple[str, str], list[int]] = {}
_http_latency_count: Counter[tuple[str, str]] = Counter()
_http_latency_sum_ms: dict[tuple[str, str], float] = {}


def incr(name: str, value: int = 1) -> None:
    """Increment a named counter."""
    if value <= 0:
        return
    with _lock:
        _counters[name] += value


def snapshot() -> dict[str, int]:
    """Return a copy of current counter values."""
    with _lock:
        return dict(_counters)


def observe_http_request_duration(method: str, path: str, duration_ms: float) -> None:
    """Record HTTP request latency in a histogram keyed by (method, path)."""
    key = (method.upper(), path)
    value = max(0.0, float(duration_ms))
    with _lock:
        counts = _http_latency_buckets.get(key)
        if counts is None:
            counts = [0] * len(_HTTP_LATENCY_BUCKETS_MS)
            _http_latency_buckets[key] = counts
        for idx, upper in enumerate(_HTTP_LATENCY_BUCKETS_MS):
            if value <= upper:
                counts[idx] += 1
        _http_latency_count[key] += 1
        _http_latency_sum_ms[key] = _http_latency_sum_ms.get(key, 0.0) + value


def snapshot_http_latency() -> dict[tuple[str, str], dict[str, object]]:
    """Return a copy of HTTP request latency histogram data."""
    with _lock:
        return {
            key: {
                "buckets_ms": _HTTP_LATENCY_BUCKETS_MS,
                "counts": list(_http_latency_buckets.get(key, [0] * len(_HTTP_LATENCY_BUCKETS_MS))),
                "count": int(_http_latency_count.get(key, 0)),
                "sum_ms": float(_http_latency_sum_ms.get(key, 0.0)),
            }
            for key in set(_http_latency_buckets.keys()) | set(_http_latency_count.keys())
        }


def reset() -> None:
    """Reset counters (used by tests)."""
    with _lock:
        _counters.clear()
        _http_latency_buckets.clear()
        _http_latency_count.clear()
        _http_latency_sum_ms.clear()
