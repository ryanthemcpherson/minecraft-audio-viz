"""SQLAlchemy async engine and session factory."""

from __future__ import annotations

from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.config import Settings

_engine = None
_session_factory: async_sessionmaker[AsyncSession] | None = None


def init_engine(settings: Settings) -> None:
    """Create the global async engine and session factory."""
    global _engine, _session_factory

    _engine = create_async_engine(
        settings.database_url,
        echo=False,
        pool_pre_ping=True,
    )
    _session_factory = async_sessionmaker(
        bind=_engine,
        class_=AsyncSession,
        expire_on_commit=False,
    )


async def shutdown_engine() -> None:
    """Dispose of the engine connection pool."""
    global _engine
    if _engine is not None:
        await _engine.dispose()
        _engine = None


async def get_session() -> AsyncIterator[AsyncSession]:
    """FastAPI dependency that yields a database session."""
    if _session_factory is None:
        raise RuntimeError("Database engine not initialised. Call init_engine() first.")
    async with _session_factory() as session:
        yield session
