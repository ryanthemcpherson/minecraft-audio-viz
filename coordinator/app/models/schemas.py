"""Pydantic request / response schemas for the coordinator API."""

from __future__ import annotations

import re
import uuid
from datetime import datetime

from pydantic import BaseModel, Field, field_validator

# ---------------------------------------------------------------------------
# Shared
# ---------------------------------------------------------------------------

CODE_PATTERN = re.compile(r"^[A-Z]{4}-[A-Z2-9]{4}$")


# ---------------------------------------------------------------------------
# Server schemas
# ---------------------------------------------------------------------------

class RegisterServerRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=100)
    websocket_url: str = Field(..., min_length=1, max_length=500, pattern=r"^wss?://.+")
    api_key: str = Field(..., min_length=1, max_length=200, description="Plaintext API key chosen by the server operator")


class RegisterServerResponse(BaseModel):
    server_id: uuid.UUID
    jwt_secret: str
    name: str


class HeartbeatResponse(BaseModel):
    server_id: uuid.UUID
    last_heartbeat: datetime


# ---------------------------------------------------------------------------
# Show schemas
# ---------------------------------------------------------------------------

class CreateShowRequest(BaseModel):
    server_id: uuid.UUID
    name: str = Field(..., min_length=1, max_length=200)
    max_djs: int = Field(default=8, ge=1, le=64)


class CreateShowResponse(BaseModel):
    show_id: uuid.UUID
    connect_code: str
    name: str
    server_id: uuid.UUID
    created_at: datetime


class ShowDetailResponse(BaseModel):
    show_id: uuid.UUID
    name: str
    server_id: uuid.UUID
    status: str
    connect_code: str | None
    max_djs: int
    current_djs: int
    created_at: datetime
    ended_at: datetime | None


class EndShowResponse(BaseModel):
    show_id: uuid.UUID
    status: str
    ended_at: datetime


# ---------------------------------------------------------------------------
# Connect (public) schemas
# ---------------------------------------------------------------------------

class ConnectCodeResponse(BaseModel):
    websocket_url: str
    token: str
    show_name: str
    dj_count: int


# ---------------------------------------------------------------------------
# Health schema
# ---------------------------------------------------------------------------

class HealthResponse(BaseModel):
    status: str
    version: str
    active_shows: int
    active_servers: int


# ---------------------------------------------------------------------------
# Auth schemas
# ---------------------------------------------------------------------------


class RegisterRequest(BaseModel):
    email: str = Field(..., min_length=5, max_length=255)
    password: str = Field(..., min_length=8, max_length=200)
    display_name: str = Field(..., min_length=1, max_length=100)

    @field_validator("email")
    @classmethod
    def validate_email(cls, v: str) -> str:
        if "@" not in v or "." not in v.split("@")[-1]:
            raise ValueError("Invalid email format")
        return v.lower().strip()


class LoginRequest(BaseModel):
    email: str = Field(..., min_length=1, max_length=255)
    password: str = Field(..., min_length=1, max_length=200)


class UserResponse(BaseModel):
    id: uuid.UUID
    display_name: str
    email: str | None
    discord_username: str | None
    avatar_url: str | None


class AuthResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    user: UserResponse


class RefreshRequest(BaseModel):
    refresh_token: str


class LogoutRequest(BaseModel):
    refresh_token: str


class DiscordAuthorizeResponse(BaseModel):
    authorize_url: str


class OrgSummary(BaseModel):
    id: uuid.UUID
    name: str
    slug: str
    role: str


class UserProfileResponse(BaseModel):
    id: uuid.UUID
    display_name: str
    email: str | None
    discord_username: str | None
    avatar_url: str | None
    organizations: list[OrgSummary]


# ---------------------------------------------------------------------------
# Organization schemas
# ---------------------------------------------------------------------------


class CreateOrgRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=100)
    slug: str = Field(..., min_length=3, max_length=63)
    description: str | None = Field(None, max_length=500)


class CreateOrgResponse(BaseModel):
    id: uuid.UUID
    name: str
    slug: str
    owner_id: uuid.UUID
    created_at: datetime


class OrgDetailResponse(BaseModel):
    id: uuid.UUID
    name: str
    slug: str
    description: str | None
    avatar_url: str | None
    owner_id: uuid.UUID
    created_at: datetime


class UpdateOrgRequest(BaseModel):
    name: str | None = Field(None, min_length=1, max_length=100)
    description: str | None = Field(None, max_length=500)
    avatar_url: str | None = Field(None, max_length=500)


class AssignServerRequest(BaseModel):
    server_id: uuid.UUID


class AssignServerResponse(BaseModel):
    server_id: uuid.UUID
    org_id: uuid.UUID


class OrgServerResponse(BaseModel):
    id: uuid.UUID
    name: str
    websocket_url: str
    is_active: bool


# ---------------------------------------------------------------------------
# Tenant resolution schemas
# ---------------------------------------------------------------------------


class TenantOrgInfo(BaseModel):
    id: uuid.UUID
    name: str
    slug: str
    description: str | None
    avatar_url: str | None


class TenantServerInfo(BaseModel):
    id: uuid.UUID
    name: str
    is_active: bool
    show_count: int


class TenantShowInfo(BaseModel):
    id: uuid.UUID
    name: str
    connect_code: str | None
    current_djs: int
    max_djs: int
    server_name: str


class TenantResolveResponse(BaseModel):
    org: TenantOrgInfo
    servers: list[TenantServerInfo]
    active_shows: list[TenantShowInfo]
