"""FastAPI application entry-point for the MCAV DJ Coordinator."""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import Settings, get_settings
from app.database import init_engine, shutdown_engine
from app.middleware.rate_limit import RateLimitMiddleware
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
)
from app.services.rate_limiter import RateLimitExceeded


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """Initialise the database on startup, tear down on shutdown."""
    settings = get_settings()
    init_engine(settings)
    yield
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
    application.include_router(dashboard.router, prefix="/api/v1")

    # -- Exception handlers ----------------------------------------------------
    @application.exception_handler(RateLimitExceeded)
    async def _rate_limit_handler(_request: Request, exc: RateLimitExceeded) -> JSONResponse:
        return JSONResponse(
            status_code=429,
            content={"detail": str(exc)},
            headers={"Retry-After": str(exc.retry_after)},
        )

    return application


app = create_app()
