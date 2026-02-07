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
