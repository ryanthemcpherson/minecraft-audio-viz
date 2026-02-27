"""Request context middleware: request IDs and access logs."""

from __future__ import annotations

import logging
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from app.services.metrics import observe_http_request_duration

logger = logging.getLogger("request")


class RequestContextMiddleware(BaseHTTPMiddleware):
    """Attach a request ID and emit structured access logs."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        request_id = request.headers.get("X-Request-ID", "").strip() or str(uuid.uuid4())
        request.state.request_id = request_id

        start = time.perf_counter()
        response = await call_next(request)
        duration_ms = round((time.perf_counter() - start) * 1000, 2)
        route = request.scope.get("route")
        path_template = getattr(route, "path", request.url.path)
        observe_http_request_duration(request.method, path_template, duration_ms)

        response.headers["X-Request-ID"] = request_id

        logger.info(
            "request_complete",
            extra={
                "request_id": request_id,
                "path": request.url.path,
                "method": request.method,
                "status_code": response.status_code,
                "duration_ms": duration_ms,
                "event": "http_request",
            },
        )
        return response
