from __future__ import annotations

import pytest

from scripts.release.paper_performance import (
    APPLIED_FRAME_TIMEOUT_SECONDS,
    MAX_APPLIED_P95_MS,
    MAX_MAIN_THREAD_P95_MS,
    MIN_TPS,
    REQUIRED_ENTITY_COUNT,
    REQUIRED_SOAK_SECONDS,
    _parse_heap_info,
    assert_minimum_samples,
    assert_queue_caps,
    is_exact_release_soak,
    minimum_one_minute_tps,
    parse_tps_line,
    percentile,
    resource_delta,
)


def test_release_acceptance_constants_are_fixed() -> None:
    assert MIN_TPS == 19.8
    assert MAX_APPLIED_P95_MS == 100.0
    assert MAX_MAIN_THREAD_P95_MS == 10.0
    assert REQUIRED_ENTITY_COUNT == 256
    assert REQUIRED_SOAK_SECONDS == 8 * 60 * 60
    assert APPLIED_FRAME_TIMEOUT_SECONDS == 10.0


def test_percentile_interpolates_sorted_samples() -> None:
    assert percentile([1.0, 2.0, 3.0, 4.0], 0) == 1.0
    assert percentile([4.0, 1.0, 3.0, 2.0], 50) == 2.5
    assert percentile([float(value) for value in range(1, 21)], 95) == pytest.approx(19.05)
    assert percentile([1.0], 100) == 1.0


@pytest.mark.parametrize("samples,quantile", [([], 95), ([1.0], -1), ([1.0], 101)])
def test_percentile_rejects_invalid_input(samples: list[float], quantile: float) -> None:
    with pytest.raises(ValueError):
        percentile(samples, quantile)


def test_tps_parser_handles_paper_output_and_ansi_codes() -> None:
    line = "\x1b[38;5;3mTPS from last 1m, 5m, 15m: \x1b[38;5;10m*20.0, 19.95, 19.81\x1b[0m"

    assert parse_tps_line(line) == (20.0, 19.95, 19.81)


def test_tps_parser_rejects_unrelated_output() -> None:
    with pytest.raises(ValueError, match="TPS values"):
        parse_tps_line("There are 0 players online")


def test_minimum_tps_uses_one_minute_samples_not_startup_skewed_long_windows() -> None:
    samples = [(20.0, 17.0, 14.0), (19.9, 18.5, 16.0)]

    assert minimum_one_minute_tps(samples) == 19.9


def test_queue_cap_assertion_accepts_caps_and_rejects_overflow() -> None:
    assert_queue_caps({"parsedQueueDepth": 1000, "rawQueueDepth": 64})

    with pytest.raises(AssertionError, match="parsedQueueDepth"):
        assert_queue_caps({"parsedQueueDepth": 1001, "rawQueueDepth": 0})
    with pytest.raises(AssertionError, match="rawQueueDepth"):
        assert_queue_caps({"parsedQueueDepth": 0, "rawQueueDepth": 65})


def test_resource_delta_uses_only_shared_numeric_keys() -> None:
    baseline = {"heap_used_bytes": 100, "thread_count": 10, "label": "before"}
    current = {"heap_used_bytes": 145, "thread_count": 8, "extra": 99}

    assert resource_delta(baseline, current) == {
        "heap_used_bytes": 45,
        "thread_count": -2,
    }


def test_heap_parser_accepts_java_25_g1_output() -> None:
    output = "garbage-first heap   total reserved 2097152K, committed 1048576K, used 777180K\n"

    assert _parse_heap_info(output) == (1_073_741_824, 795_832_320)


def test_minimum_sample_count_is_enforced() -> None:
    assert_minimum_samples([1.0] * 1000, 1000)

    with pytest.raises(AssertionError, match="999 latency samples"):
        assert_minimum_samples([1.0] * 999, 1000)


def test_release_soak_duration_must_be_exactly_eight_hours() -> None:
    assert is_exact_release_soak(28_800)
    assert not is_exact_release_soak(28_799)
    assert not is_exact_release_soak(28_801)
