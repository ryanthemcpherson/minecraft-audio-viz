"""Shared pytest fixtures for the coordinator test suite.

Uses an in-memory SQLite database (via aiosqlite) so tests are fast and
require no external infrastructure.
"""

from __future__ import annotations

import asyncio
from typing import AsyncIterator

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.config import Settings
from app.database import get_session
from app.main import create_app
from app.middleware.rate_limit import get_auth_limiter, get_connect_limiter
from app.models.db import Base


# ---------------------------------------------------------------------------
# Event-loop fixture (session-scoped)
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def event_loop():
    """Override the default event-loop fixture to be session-scoped."""
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


# ---------------------------------------------------------------------------
# Settings override
# ---------------------------------------------------------------------------

@pytest.fixture()
def settings() -> Settings:
    return Settings(
        database_url="sqlite+aiosqlite:///:memory:",
        jwt_default_expiry_minutes=15,
        rate_limit_resolve_per_minute=10,
        rate_limit_register_per_hour=5,
        rate_limit_auth_per_minute=100,  # relaxed for tests
        cors_origins=["http://localhost:3000"],
        user_jwt_secret="test-user-jwt-secret",
        user_jwt_expiry_minutes=60,
        refresh_token_expiry_days=30,
    )


# ---------------------------------------------------------------------------
# Async engine + session wired to in-memory SQLite
# ---------------------------------------------------------------------------

@pytest_asyncio.fixture()
async def db_session(settings: Settings) -> AsyncIterator[AsyncSession]:
    engine = create_async_engine(settings.database_url, echo=False)

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(bind=engine, class_=AsyncSession, expire_on_commit=False)

    async with session_factory() as session:
        yield session

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)

    await engine.dispose()


# ---------------------------------------------------------------------------
# httpx AsyncClient wired to the FastAPI app
# ---------------------------------------------------------------------------

@pytest_asyncio.fixture()
async def client(settings: Settings, db_session: AsyncSession) -> AsyncIterator[AsyncClient]:
    app = create_app(settings=settings)

    # Override the get_session dependency to use our test session
    async def _override_get_session() -> AsyncIterator[AsyncSession]:
        yield db_session

    app.dependency_overrides[get_session] = _override_get_session

    # Reset rate limiters between tests so they don't bleed across test cases
    get_connect_limiter().reset()
    get_auth_limiter().reset()

    transport = ASGITransport(app=app)  # type: ignore[arg-type]
    async with AsyncClient(transport=transport, base_url="http://testserver") as ac:
        yield ac
