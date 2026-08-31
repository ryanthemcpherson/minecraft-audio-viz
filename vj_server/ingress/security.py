"""Security policy helpers for the unified public ingress."""

from __future__ import annotations

import asyncio
import ipaddress
import ssl
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from aiohttp import web
from yarl import URL

CONTENT_SECURITY_POLICY = (
    "default-src 'self'; "
    "script-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com; "
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
    "font-src 'self' https://fonts.gstatic.com; "
    "img-src 'self' data:; "
    "connect-src 'self' ws: wss:; "
    "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
)


class ConnectionLimiter:
    def __init__(self, capacity: int) -> None:
        if capacity <= 0:
            raise ValueError("connection capacity must be positive")
        self._capacity = capacity
        self._active = 0
        self._lock = asyncio.Lock()

    @property
    def active(self) -> int:
        return self._active

    @asynccontextmanager
    async def reserve(self) -> AsyncIterator[bool]:
        accepted = False
        async with self._lock:
            if self._active < self._capacity:
                self._active += 1
                accepted = True
        try:
            yield accepted
        finally:
            if accepted:
                async with self._lock:
                    self._active -= 1


def require_tls_policy(
    host: str,
    ssl_context: ssl.SSLContext | None,
    *,
    allow_insecure_loopback: bool,
) -> None:
    if ssl_context is not None:
        return
    if allow_insecure_loopback and is_loopback_host(host):
        return
    raise ValueError("TLS is required for public ingress")


def is_loopback_host(host: str) -> bool:
    normalized = host.strip().strip("[]").lower()
    if normalized == "localhost":
        return True
    try:
        return ipaddress.ip_address(normalized).is_loopback
    except ValueError:
        return False


def browser_origin_allowed(
    origin: str | None,
    request_host: str,
    *,
    secure_transport: bool,
    allowed_origins: tuple[str, ...] = (),
) -> bool:
    candidate_origin = _normalized_http_origin(origin)
    expected_origin = _normalized_http_origin(
        f"{'https' if secure_transport else 'http'}://{request_host}"
    )
    if candidate_origin is None or expected_origin is None:
        return False
    if candidate_origin == expected_origin:
        return True
    for allowed in allowed_origins:
        if candidate_origin == _normalized_http_origin(allowed):
            return True
    return False


def _normalized_http_origin(value: str | None) -> str | None:
    if not value:
        return None
    try:
        candidate = URL(value)
        if (
            candidate.scheme not in {"http", "https"}
            or not candidate.is_absolute()
            or candidate.host is None
            or candidate.user is not None
            or candidate.password is not None
            or candidate.query_string
            or candidate.fragment
            or candidate.path not in {"", "/"}
        ):
            return None
        return str(candidate.origin())
    except ValueError:
        return None


def security_headers(*, secure_transport: bool) -> dict[str, str]:
    headers = {
        "Content-Security-Policy": CONTENT_SECURITY_POLICY,
        "Cross-Origin-Opener-Policy": "same-origin",
        "Cross-Origin-Resource-Policy": "same-origin",
        "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
        "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
    }
    if secure_transport:
        headers["Strict-Transport-Security"] = "max-age=31536000"
    return headers


def security_headers_middleware(*, secure_transport: bool) -> web.middleware:
    headers = security_headers(secure_transport=secure_transport)

    @web.middleware
    async def middleware(request: web.Request, handler: web.RequestHandler) -> web.StreamResponse:
        try:
            response = await handler(request)
        except web.HTTPException as response:
            for name, value in headers.items():
                response.headers.setdefault(name, value)
            raise
        for name, value in headers.items():
            response.headers.setdefault(name, value)
        return response

    return middleware
