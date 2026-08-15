"""Unit tests for app.services.rate_limiter.

Tests the in-memory sliding-window rate limiter: basic allow/deny,
bucket expiry, remaining count, and reset behavior.
"""

from __future__ import annotations

import time
from unittest.mock import patch

from app.services.rate_limiter import InMemoryRateLimiter, RateLimitExceeded


# ---------------------------------------------------------------------------
# RateLimitExceeded exception
# ---------------------------------------------------------------------------


class TestRateLimitExceeded:
    def test_default_retry_after(self) -> None:
        exc = RateLimitExceeded()
        assert exc.retry_after == 60
        assert "60" in str(exc)

    def test_custom_retry_after(self) -> None:
        exc = RateLimitExceeded(retry_after=120)
        assert exc.retry_after == 120
        assert "120" in str(exc)


# ---------------------------------------------------------------------------
# InMemoryRateLimiter.check
# ---------------------------------------------------------------------------


class TestRateLimiterCheck:
    def test_allows_requests_within_limit(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=5, window_seconds=60)
        for _ in range(5):
            assert limiter.check("192.168.1.1") is True

    def test_blocks_requests_over_limit(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=3, window_seconds=60)
        for _ in range(3):
            assert limiter.check("10.0.0.1") is True
        # 4th request should be blocked
        assert limiter.check("10.0.0.1") is False

    def test_different_keys_are_independent(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=2, window_seconds=60)
        assert limiter.check("ip-a") is True
        assert limiter.check("ip-a") is True
        assert limiter.check("ip-a") is False  # ip-a exhausted
        assert limiter.check("ip-b") is True  # ip-b is independent

    def test_window_expiry_allows_new_requests(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=1, window_seconds=1)
        assert limiter.check("key") is True
        assert limiter.check("key") is False

        # Simulate time advancing past the window
        with patch("time.monotonic", return_value=time.monotonic() + 2):
            assert limiter.check("key") is True

    def test_single_request_limit(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=1, window_seconds=60)
        assert limiter.check("single") is True
        assert limiter.check("single") is False


# ---------------------------------------------------------------------------
# InMemoryRateLimiter.remaining
# ---------------------------------------------------------------------------


class TestRateLimiterRemaining:
    def test_full_remaining_for_new_key(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=10, window_seconds=60)
        assert limiter.remaining("unknown") == 10

    def test_remaining_decreases_with_usage(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=5, window_seconds=60)
        limiter.check("key")
        limiter.check("key")
        assert limiter.remaining("key") == 3

    def test_remaining_zero_when_exhausted(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=2, window_seconds=60)
        limiter.check("key")
        limiter.check("key")
        assert limiter.remaining("key") == 0

    def test_remaining_never_negative(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=1, window_seconds=60)
        limiter.check("key")
        limiter.check("key")  # returns False but doesn't add timestamp
        assert limiter.remaining("key") == 0

    def test_remaining_recovers_after_window(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=3, window_seconds=1)
        limiter.check("key")
        limiter.check("key")
        assert limiter.remaining("key") == 1

        with patch("time.monotonic", return_value=time.monotonic() + 2):
            assert limiter.remaining("key") == 3


# ---------------------------------------------------------------------------
# InMemoryRateLimiter.reset
# ---------------------------------------------------------------------------


class TestRateLimiterReset:
    def test_reset_clears_all_state(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=2, window_seconds=60)
        limiter.check("key-a")
        limiter.check("key-b")
        limiter.reset()
        assert limiter.remaining("key-a") == 2
        assert limiter.remaining("key-b") == 2

    def test_reset_allows_previously_blocked_requests(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=1, window_seconds=60)
        assert limiter.check("key") is True
        assert limiter.check("key") is False
        limiter.reset()
        assert limiter.check("key") is True


# ---------------------------------------------------------------------------
# Stale bucket cleanup
# ---------------------------------------------------------------------------


class TestStaleCleanup:
    def test_stale_buckets_cleaned_periodically(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=10, window_seconds=1)
        # Add a request for a key
        limiter.check("stale-key")
        assert "stale-key" in limiter._buckets

        # Advance time past window so the bucket is stale
        future = time.monotonic() + 2
        with patch("time.monotonic", return_value=future):
            # Trigger enough calls to hit the cleanup threshold (every 100 calls)
            # Use the limiter's internal counter to fast-forward
            limiter._call_count = 99
            limiter.check("trigger-cleanup")
            # After cleanup, stale bucket should be gone
            assert "stale-key" not in limiter._buckets


# ---------------------------------------------------------------------------
# Configuration edge cases
# ---------------------------------------------------------------------------


class TestRateLimiterConfig:
    def test_zero_max_requests_blocks_all(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=0, window_seconds=60)
        assert limiter.check("any") is False

    def test_large_window(self) -> None:
        limiter = InMemoryRateLimiter(max_requests=100, window_seconds=3600)
        for _ in range(100):
            assert limiter.check("key") is True
        assert limiter.check("key") is False
