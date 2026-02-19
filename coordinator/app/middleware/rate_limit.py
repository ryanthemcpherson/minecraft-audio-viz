"""Starlette middleware that applies rate-limiting to selected routes."""

from __future__ import annotations

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from app.services.rate_limiter import InMemoryRateLimiter

# A single shared rate-limiter instance for the /api/v1/connect endpoint.
# 10 requests per IP per 60-second window (matches spec).
_connect_limiter = InMemoryRateLimiter(max_requests=10, window_seconds=60)

# Rate limiter for auth endpoints (20 requests per IP per 60-second window).
_auth_limiter = InMemoryRateLimiter(max_requests=20, window_seconds=60)

# Rate limiter for write operations on servers and orgs endpoints.
_write_limiter = InMemoryRateLimiter(max_requests=20, window_seconds=60)

_WRITE_METHODS = {"POST", "PUT", "DELETE"}


def get_connect_limiter() -> InMemoryRateLimiter:
    """Return the module-level connect rate limiter (useful for testing)."""
    return _connect_limiter


def get_auth_limiter() -> InMemoryRateLimiter:
    """Return the module-level auth rate limiter (useful for testing)."""
    return _auth_limiter


def get_write_limiter() -> InMemoryRateLimiter:
    """Return the module-level write rate limiter (useful for testing)."""
    return _write_limiter


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Apply per-IP rate limiting to public endpoints."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        client_ip = request.client.host if request.client else "unknown"

        # Rate-limit the public code-resolution endpoint
        if request.url.path.startswith("/api/v1/connect/"):
            if not _connect_limiter.check(client_ip):
                remaining = _connect_limiter.remaining(client_ip)
                return JSONResponse(
                    status_code=429,
                    content={"detail": "Rate limit exceeded. Try again in 60 seconds."},
                    headers={
                        "Retry-After": "60",
                        "X-RateLimit-Limit": str(_connect_limiter.max_requests),
                        "X-RateLimit-Remaining": str(remaining),
                    },
                )

        # Rate-limit auth endpoints
        if request.url.path.startswith("/api/v1/auth/"):
            if not _auth_limiter.check(client_ip):
                remaining = _auth_limiter.remaining(client_ip)
                return JSONResponse(
                    status_code=429,
                    content={"detail": "Rate limit exceeded. Try again in 60 seconds."},
                    headers={
                        "Retry-After": "60",
                        "X-RateLimit-Limit": str(_auth_limiter.max_requests),
                        "X-RateLimit-Remaining": str(remaining),
                    },
                )

        # Rate-limit write operations on servers and orgs endpoints
        if request.method in _WRITE_METHODS and (
            request.url.path.startswith("/api/v1/servers/")
            or request.url.path.startswith("/api/v1/orgs/")
        ):
            if not _write_limiter.check(client_ip):
                remaining = _write_limiter.remaining(client_ip)
                return JSONResponse(
                    status_code=429,
                    content={"detail": "Rate limit exceeded. Try again in 60 seconds."},
                    headers={
                        "Retry-After": "60",
                        "X-RateLimit-Limit": str(_write_limiter.max_requests),
                        "X-RateLimit-Remaining": str(remaining),
                    },
                )

        response = await call_next(request)
        return response
