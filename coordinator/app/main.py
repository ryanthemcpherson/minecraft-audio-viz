"""FastAPI application entry-point for the MCAV DJ Coordinator.

Serves the REST API for DJ authentication, show management,
organization management, and the unified dashboard.
"""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import Settings, get_settings
from app.database import init_engine, shutdown_engine
from app.logging_config import configure_logging
from app.middleware.rate_limit import RateLimitMiddleware
from app.middleware.security import SecurityHeadersMiddleware
from app.routers import (
    auth,
    connect,
    dashboard,
    dj_profiles,
    health,
    onboarding,
    orgs,
    servers,
    shows,
    tenants,
    uploads,
)
from app.services.rate_limiter import RateLimitExceeded

_logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """Initialise the database on startup, tear down on shutdown."""
    # Configure logging before anything else
    configure_logging()
    _logger.info("Starting MCAV DJ Coordinator")

    settings = get_settings()
    init_engine(settings)
    yield
    _logger.info("Shutting down MCAV DJ Coordinator")
    await shutdown_engine()


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build and return the FastAPI application."""
    if settings is None:
        settings = get_settings()

    application = FastAPI(
        title="MCAV DJ Coordinator",
        description="Central coordination service for MCAV connect-code resolution and show management.",
        version="0.1.0",
        lifespan=lifespan,
    )

    # -- Security headers middleware -------------------------------------------
    application.add_middleware(SecurityHeadersMiddleware)

    # -- Rate-limit middleware --------------------------------------------------
    application.add_middleware(RateLimitMiddleware)

    # -- CORS ------------------------------------------------------------------
    # Keep CORS as the outermost middleware so headers are present even when
    # downstream handlers raise 5xx errors.
    application.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_methods=["*"],
        allow_headers=["*"],
        max_age=3600,
    )

    # -- Routers ---------------------------------------------------------------
    application.include_router(health.router)
    application.include_router(servers.router, prefix="/api/v1")
    application.include_router(shows.router, prefix="/api/v1")
    application.include_router(connect.router, prefix="/api/v1")
    application.include_router(auth.router, prefix="/api/v1")
    application.include_router(orgs.router, prefix="/api/v1")
    application.include_router(onboarding.router, prefix="/api/v1")
    application.include_router(dj_profiles.router, prefix="/api/v1")
    application.include_router(tenants.router, prefix="/api/v1")
    application.include_router(uploads.router, prefix="/api/v1")
    application.include_router(dashboard.router, prefix="/api/v1")

    # -- Exception handlers ----------------------------------------------------
    @application.exception_handler(RateLimitExceeded)
    async def _rate_limit_handler(_request: Request, exc: RateLimitExceeded) -> JSONResponse:
        return JSONResponse(
            status_code=429,
            content={"detail": str(exc)},
            headers={"Retry-After": str(exc.retry_after)},
        )

    @application.exception_handler(Exception)
    async def _global_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        """Catch unhandled exceptions and return a clean JSON error response."""
        env = os.environ.get("MCAV_ENV", "development").lower()
        is_production = env in ("production", "prod", "staging")

        # Log the full traceback
        _logger.exception(
            "Unhandled exception",
            extra={
                "path": request.url.path,
                "method": request.method,
            },
        )

        # Don't expose internal details in production
        if is_production:
            return JSONResponse(
                status_code=500,
                content={"detail": "Internal server error"},
            )
        else:
            # In development, include exception details for debugging
            return JSONResponse(
                status_code=500,
                content={
                    "detail": "Internal server error",
                    "error": str(exc),
                    "type": type(exc).__name__,
                },
            )

    return application


app = create_app()
