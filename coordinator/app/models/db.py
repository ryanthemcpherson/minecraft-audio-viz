"""SQLAlchemy 2.0 ORM models for the coordinator database.

Uses ``sqlalchemy.Uuid`` (SQLAlchemy 2.0+) for cross-database UUID support
so the same models work against PostgreSQL in production and SQLite in tests.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Uuid,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    """Shared declarative base for all ORM models."""


class VJServer(Base):
    __tablename__ = "vj_servers"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    websocket_url: Mapped[str] = mapped_column(String(500), nullable=False)
    api_key_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    jwt_secret: Mapped[str] = mapped_column(String(128), nullable=False)
    last_heartbeat: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow
    )

    # Relationships
    shows: Mapped[list[Show]] = relationship("Show", back_populates="server", lazy="selectin")


class Show(Base):
    __tablename__ = "shows"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    server_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("vj_servers.id"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    connect_code: Mapped[str | None] = mapped_column(
        String(10), unique=True, nullable=True
    )
    status: Mapped[str] = mapped_column(
        String(20), default="active"
    )
    max_djs: Mapped[int] = mapped_column(Integer, default=8)
    current_djs: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow
    )
    ended_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    # Relationships
    server: Mapped[VJServer] = relationship("VJServer", back_populates="shows")
    dj_sessions: Mapped[list[DJSession]] = relationship(
        "DJSession", back_populates="show", lazy="selectin"
    )


class DJSession(Base):
    __tablename__ = "dj_sessions"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    show_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("shows.id"), nullable=False
    )
    dj_name: Mapped[str] = mapped_column(String(100), nullable=False)
    connected_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow
    )
    disconnected_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    ip_address: Mapped[str] = mapped_column(String(45), nullable=False, default="")

    # Relationships
    show: Mapped[Show] = relationship("Show", back_populates="dj_sessions")
