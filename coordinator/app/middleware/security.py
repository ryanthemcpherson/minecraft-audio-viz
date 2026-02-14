"""ASGI middleware for security headers."""

from __future__ import annotations

import os

from starlette.datastructures import MutableHeaders
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Add security headers to all HTTP responses.

    Applies common security headers to protect against various attacks.
    HSTS is only enabled in production to avoid issues in local development.
    """

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        response = await call_next(request)
        headers = MutableHeaders(response.headers)

        # Prevent MIME type sniffing
        headers["X-Content-Type-Options"] = "nosniff"

        # Prevent clickjacking
        headers["X-Frame-Options"] = "DENY"

        # Disable legacy XSS filter (deprecated but some scanners check for it)
        headers["X-XSS-Protection"] = "0"

        # Control referrer information
        headers["Referrer-Policy"] = "strict-origin-when-cross-origin"

        # Disable potentially dangerous browser features
        headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"

        # Add HSTS only in production
        env = os.environ.get("MCAV_ENV", "development").lower()
        if env in ("production", "prod", "staging"):
            headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"

        return response
