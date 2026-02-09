"""In-memory sliding-window rate limiter.

Tracks request counts per IP address within a configurable time window.
"""

from __future__ import annotations

import time
from collections import defaultdict
from dataclasses import dataclass, field


class RateLimitExceeded(Exception):
    """Raised when a client exceeds the rate limit."""

    def __init__(self, retry_after: int = 60) -> None:
        self.retry_after = retry_after
        super().__init__(f"Rate limit exceeded. Try again in {retry_after} seconds.")


@dataclass
class _BucketEntry:
    timestamps: list[float] = field(default_factory=list)


class InMemoryRateLimiter:
    """Simple sliding-window counter keyed by IP address.

    Parameters
    ----------
    max_requests:
        Maximum number of requests allowed within *window_seconds*.
    window_seconds:
        Length of the sliding window in seconds.
    """

    def __init__(self, max_requests: int = 10, window_seconds: int = 60) -> None:
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._buckets: dict[str, _BucketEntry] = defaultdict(_BucketEntry)

    def check(self, key: str) -> bool:
        """Return ``True`` if the request is allowed, ``False`` otherwise."""
        entry = self._buckets[key]
        now = time.monotonic()

        # Prune timestamps outside the window
        cutoff = now - self.window_seconds
        entry.timestamps = [t for t in entry.timestamps if t > cutoff]

        if len(entry.timestamps) >= self.max_requests:
            return False

        entry.timestamps.append(now)
        return True

    def remaining(self, key: str) -> int:
        """Return how many requests remain for *key* in the current window."""
        entry = self._buckets.get(key)
        if entry is None:
            return self.max_requests
        now = time.monotonic()
        cutoff = now - self.window_seconds
        active = [t for t in entry.timestamps if t > cutoff]
        return max(0, self.max_requests - len(active))

    def reset(self) -> None:
        """Clear all tracked state (useful for tests)."""
        self._buckets.clear()
