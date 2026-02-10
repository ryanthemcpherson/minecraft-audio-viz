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
    api_key: str = Field(
        ...,
        min_length=1,
        max_length=200,
        description="Plaintext API key chosen by the server operator",
    )


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
    onboarding_completed: bool


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


class DJProfileResponse(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    dj_name: str
    bio: str | None
    genres: str | None
    avatar_url: str | None
    is_public: bool
    created_at: datetime


class UserProfileResponse(BaseModel):
    id: uuid.UUID
    display_name: str
    email: str | None
    discord_username: str | None
    avatar_url: str | None
    onboarding_completed: bool
    user_type: str | None
    dj_profile: DJProfileResponse | None
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


# ---------------------------------------------------------------------------
# Onboarding schemas
# ---------------------------------------------------------------------------


VALID_USER_TYPES = {"server_owner", "team_member", "dj"}


class CompleteOnboardingRequest(BaseModel):
    user_type: str = Field(..., min_length=1, max_length=20)

    @field_validator("user_type")
    @classmethod
    def validate_user_type(cls, v: str) -> str:
        if v not in VALID_USER_TYPES:
            raise ValueError(f"user_type must be one of: {', '.join(sorted(VALID_USER_TYPES))}")
        return v


# ---------------------------------------------------------------------------
# Invite schemas
# ---------------------------------------------------------------------------


class CreateInviteRequest(BaseModel):
    max_uses: int = Field(default=0, ge=0, description="0 = unlimited")
    expires_in_hours: int | None = Field(None, ge=1, le=720)


class InviteResponse(BaseModel):
    id: uuid.UUID
    org_id: uuid.UUID
    code: str
    max_uses: int
    use_count: int
    is_active: bool
    expires_at: datetime | None
    created_at: datetime


class JoinOrgRequest(BaseModel):
    invite_code: str = Field(..., min_length=1, max_length=8)


class JoinOrgResponse(BaseModel):
    org_id: uuid.UUID
    org_name: str
    org_slug: str
    role: str


# ---------------------------------------------------------------------------
# DJ profile schemas
# ---------------------------------------------------------------------------


class CreateDJProfileRequest(BaseModel):
    dj_name: str = Field(..., min_length=1, max_length=100)
    bio: str | None = Field(None, max_length=500)
    genres: str | None = Field(None, max_length=500)


class UpdateDJProfileRequest(BaseModel):
    dj_name: str | None = Field(None, min_length=1, max_length=100)
    bio: str | None = Field(None, max_length=500)
    genres: str | None = Field(None, max_length=500)


# ---------------------------------------------------------------------------
# Dashboard schemas
# ---------------------------------------------------------------------------


class ServerOwnerChecklist(BaseModel):
    org_created: bool
    server_registered: bool
    invite_created: bool
    show_started: bool


class OrgDashboardSummary(BaseModel):
    id: uuid.UUID
    name: str
    slug: str
    role: str
    server_count: int
    member_count: int
    active_show_count: int


class RecentShowSummary(BaseModel):
    id: uuid.UUID
    name: str
    server_name: str
    connect_code: str | None
    status: str
    current_djs: int
    created_at: datetime


class ServerOwnerDashboard(BaseModel):
    user_type: str = "server_owner"
    checklist: ServerOwnerChecklist
    organizations: list[OrgDashboardSummary]
    recent_shows: list[RecentShowSummary]


class TeamMemberDashboard(BaseModel):
    user_type: str = "team_member"
    organizations: list[OrgDashboardSummary]
    active_shows: list[RecentShowSummary]


class DJDashboardData(BaseModel):
    user_type: str = "dj"
    dj_name: str
    bio: str | None
    genres: str | None
    session_count: int
    recent_sessions: list[RecentShowSummary]


class GenericDashboard(BaseModel):
    user_type: str = "generic"
    organizations: list[OrgDashboardSummary]
