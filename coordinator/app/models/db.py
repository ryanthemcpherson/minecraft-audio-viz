"""SQLAlchemy 2.0 ORM models for the coordinator database.

Uses ``sqlalchemy.Uuid`` (SQLAlchemy 2.0+) for cross-database UUID support
so the same models work against PostgreSQL in production and SQLite in tests.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    Uuid,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    """Shared declarative base for all ORM models."""


# ---------------------------------------------------------------------------
# User & auth models
# ---------------------------------------------------------------------------


class User(Base):
    __tablename__ = "users"
    __table_args__ = (
        CheckConstraint(
            "email IS NOT NULL OR discord_id IS NOT NULL",
            name="ck_users_has_identity",
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    email: Mapped[str | None] = mapped_column(String(255), unique=True, nullable=True)
    password_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)
    display_name: Mapped[str] = mapped_column(String(100), nullable=False)
    avatar_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    discord_id: Mapped[str | None] = mapped_column(String(50), unique=True, nullable=True)
    discord_username: Mapped[str | None] = mapped_column(String(100), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    onboarding_completed_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    user_type: Mapped[str | None] = mapped_column(String(20), nullable=True)

    # Relationships
    org_memberships: Mapped[list[OrgMember]] = relationship(
        "OrgMember", back_populates="user", lazy="selectin"
    )
    refresh_tokens: Mapped[list[RefreshToken]] = relationship(
        "RefreshToken", back_populates="user", lazy="selectin"
    )
    dj_profile: Mapped[DJProfile | None] = relationship(
        "DJProfile", back_populates="user", uselist=False, lazy="selectin"
    )


class Organization(Base):
    __tablename__ = "organizations"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    slug: Mapped[str] = mapped_column(String(63), unique=True, nullable=False)
    owner_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id"), nullable=False)
    description: Mapped[str | None] = mapped_column(String(500), nullable=True)
    avatar_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)

    # Relationships
    owner: Mapped[User] = relationship("User", foreign_keys=[owner_id])
    members: Mapped[list[OrgMember]] = relationship(
        "OrgMember", back_populates="organization", lazy="selectin"
    )
    servers: Mapped[list[VJServer]] = relationship(
        "VJServer", back_populates="organization", lazy="selectin"
    )


class OrgMember(Base):
    __tablename__ = "org_members"
    __table_args__ = (UniqueConstraint("user_id", "org_id", name="uq_org_members_user_org"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id"), nullable=False)
    org_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("organizations.id"), nullable=False)
    role: Mapped[str] = mapped_column(String(20), default="owner")
    joined_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)

    # Relationships
    user: Mapped[User] = relationship("User", back_populates="org_memberships")
    organization: Mapped[Organization] = relationship("Organization", back_populates="members")


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id"), nullable=False)
    token_hash: Mapped[str] = mapped_column(String(128), nullable=False, unique=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)

    # Relationships
    user: Mapped[User] = relationship("User", back_populates="refresh_tokens")


# ---------------------------------------------------------------------------
# VJ server, show & DJ session models
# ---------------------------------------------------------------------------


class VJServer(Base):
    __tablename__ = "vj_servers"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    websocket_url: Mapped[str] = mapped_column(String(500), nullable=False)
    api_key_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    jwt_secret: Mapped[str] = mapped_column(String(128), nullable=False)
    org_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("organizations.id"), nullable=True
    )
    last_heartbeat: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)

    # Relationships
    organization: Mapped[Organization | None] = relationship(
        "Organization", back_populates="servers"
    )
    shows: Mapped[list[Show]] = relationship("Show", back_populates="server", lazy="selectin")


class Show(Base):
    __tablename__ = "shows"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    server_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("vj_servers.id"), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    connect_code: Mapped[str | None] = mapped_column(String(10), unique=True, nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="active")
    max_djs: Mapped[int] = mapped_column(Integer, default=8)
    current_djs: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # Relationships
    server: Mapped[VJServer] = relationship("VJServer", back_populates="shows")
    dj_sessions: Mapped[list[DJSession]] = relationship(
        "DJSession", back_populates="show", lazy="selectin"
    )


class DJSession(Base):
    __tablename__ = "dj_sessions"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    show_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("shows.id"), nullable=False)
    dj_name: Mapped[str] = mapped_column(String(100), nullable=False)
    connected_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    disconnected_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    ip_address: Mapped[str] = mapped_column(String(45), nullable=False, default="")

    # Relationships
    show: Mapped[Show] = relationship("Show", back_populates="dj_sessions")


# ---------------------------------------------------------------------------
# Org invites & DJ profiles
# ---------------------------------------------------------------------------


class OrgInvite(Base):
    __tablename__ = "org_invites"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    org_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("organizations.id"), nullable=False)
    code: Mapped[str] = mapped_column(String(8), unique=True, nullable=False)
    created_by: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id"), nullable=False)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    max_uses: Mapped[int] = mapped_column(Integer, default=0)
    use_count: Mapped[int] = mapped_column(Integer, default=0)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)

    # Relationships
    organization: Mapped[Organization] = relationship("Organization")
    creator: Mapped[User] = relationship("User")


class DJProfile(Base):
    __tablename__ = "dj_profiles"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("users.id"), unique=True, nullable=False
    )
    dj_name: Mapped[str] = mapped_column(String(100), nullable=False)
    bio: Mapped[str | None] = mapped_column(String(500), nullable=True)
    genres: Mapped[str | None] = mapped_column(String(500), nullable=True)
    avatar_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    is_public: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)

    # Relationships
    user: Mapped[User] = relationship("User", back_populates="dj_profile")
