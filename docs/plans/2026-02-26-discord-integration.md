# Discord Integration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a community Discord bot with role management and two-way role sync between mcav.live coordinator and Discord.

**Architecture:** New `community_bot/` Python package (discord.py + aiohttp) communicates with the coordinator via REST webhooks. Coordinator gets a new `user_roles` table replacing the single `user_type` field, plus webhook endpoints for bidirectional sync.

**Tech Stack:** Python 3.12+, discord.py 2.3+, aiohttp (webhook server), FastAPI (coordinator), SQLAlchemy 2.0, Alembic, pytest + pytest-asyncio

---

### Task 1: Coordinator — Add `user_roles` DB Table + Migration

**Files:**
- Modify: `coordinator/app/models/db.py`
- Create: `coordinator/alembic/versions/017_add_user_roles_table.py`
- Test: `coordinator/tests/test_user_roles.py`

**Step 1: Write the failing test**

```python
# coordinator/tests/test_user_roles.py
import uuid
import pytest
from sqlalchemy import select
from app.models.db import User, UserRole, RoleType

pytestmark = pytest.mark.asyncio(loop_scope="session")


async def test_user_can_have_multiple_roles(session):
    """A user can have DJ and Server Owner roles simultaneously."""
    user = User(id=uuid.uuid4(), display_name="tester")
    session.add(user)
    await session.flush()

    role_dj = UserRole(user_id=user.id, role=RoleType.DJ, source="discord")
    role_owner = UserRole(
        user_id=user.id, role=RoleType.SERVER_OWNER, source="coordinator"
    )
    session.add_all([role_dj, role_owner])
    await session.flush()

    result = await session.execute(
        select(UserRole).where(UserRole.user_id == user.id)
    )
    roles = result.scalars().all()
    assert len(roles) == 2
    assert {r.role for r in roles} == {RoleType.DJ, RoleType.SERVER_OWNER}


async def test_duplicate_role_rejected(session):
    """Same user + same role should raise IntegrityError."""
    user = User(id=uuid.uuid4(), display_name="tester2")
    session.add(user)
    await session.flush()

    session.add(UserRole(user_id=user.id, role=RoleType.VJ, source="discord"))
    await session.flush()

    session.add(UserRole(user_id=user.id, role=RoleType.VJ, source="coordinator"))
    with pytest.raises(Exception):  # IntegrityError
        await session.flush()
```

**Step 2: Run test to verify it fails**

Run: `cd coordinator && python -m pytest tests/test_user_roles.py -v`
Expected: FAIL — `UserRole` and `RoleType` don't exist yet

**Step 3: Add the model and enum to `db.py`**

Add to `coordinator/app/models/db.py`:

```python
import enum

class RoleType(str, enum.Enum):
    DJ = "dj"
    SERVER_OWNER = "server_owner"
    VJ = "vj"
    DEVELOPER = "developer"
    BETA_TESTER = "beta_tester"


class RoleSource(str, enum.Enum):
    DISCORD = "discord"
    COORDINATOR = "coordinator"
    BOTH = "both"


class UserRole(Base):
    __tablename__ = "user_roles"
    __table_args__ = (
        UniqueConstraint("user_id", "role", name="uq_user_role"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    role: Mapped[RoleType] = mapped_column(
        SAEnum(RoleType, name="roletype", create_constraint=False),
        nullable=False,
    )
    source: Mapped[RoleSource] = mapped_column(
        SAEnum(RoleSource, name="rolesource", create_constraint=False),
        nullable=False,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow
    )

    user: Mapped["User"] = relationship("User", back_populates="roles", lazy="raise")
```

Also add to the `User` model:

```python
    roles: Mapped[list["UserRole"]] = relationship(
        "UserRole", back_populates="user", lazy="raise", cascade="all, delete-orphan"
    )
```

Note: Import `Enum as SAEnum` from sqlalchemy to avoid name collision with Python's enum. Check existing imports in db.py — the file already imports from sqlalchemy, so add `SAEnum` alias there. Also add `UniqueConstraint` import if not present.

**Step 4: Run test to verify it passes**

Run: `cd coordinator && python -m pytest tests/test_user_roles.py -v`
Expected: PASS (tests use in-memory SQLite which auto-creates tables from metadata)

**Step 5: Create Alembic migration**

Run: `cd coordinator && alembic revision --autogenerate -m "add user_roles table"`

Verify the generated migration creates the `user_roles` table with the correct columns and unique constraint. The migration number should be 017.

**Step 6: Commit**

```bash
git add coordinator/app/models/db.py coordinator/alembic/versions/017_* coordinator/tests/test_user_roles.py
git commit -m "feat(coordinator): add user_roles table with multi-role support"
```

---

### Task 2: Coordinator — Role Pydantic Schemas

**Files:**
- Modify: `coordinator/app/models/schemas.py`

**Step 1: Add role schemas**

Add to `coordinator/app/models/schemas.py`:

```python
from app.models.db import RoleType, RoleSource


class UserRoleResponse(BaseModel):
    role: RoleType
    source: RoleSource
    created_at: datetime


class UserRolesResponse(BaseModel):
    user_id: uuid.UUID
    roles: list[UserRoleResponse]


class UpdateRolesRequest(BaseModel):
    roles: list[RoleType] = Field(..., max_length=5)


class DiscordRoleSyncRequest(BaseModel):
    discord_id: str = Field(..., min_length=1, max_length=30)
    roles: list[RoleType]


class DiscordRoleChangeNotification(BaseModel):
    discord_id: str
    roles: list[RoleType]
    user_id: uuid.UUID
```

**Step 2: Commit**

```bash
git add coordinator/app/models/schemas.py
git commit -m "feat(coordinator): add role sync Pydantic schemas"
```

---

### Task 3: Coordinator — Role CRUD Endpoints

**Files:**
- Create: `coordinator/app/routers/roles.py`
- Modify: `coordinator/app/main.py` (register router)
- Test: `coordinator/tests/test_roles_api.py`

**Step 1: Write failing tests**

```python
# coordinator/tests/test_roles_api.py
import pytest

pytestmark = pytest.mark.asyncio(loop_scope="session")


async def test_get_roles_empty(authed_client):
    """New user has no roles."""
    resp = await authed_client.get("/api/v1/users/me/roles")
    assert resp.status_code == 200
    data = resp.json()
    assert data["roles"] == []


async def test_update_roles(authed_client):
    """User can set multiple roles."""
    resp = await authed_client.put(
        "/api/v1/users/me/roles",
        json={"roles": ["dj", "server_owner"]},
    )
    assert resp.status_code == 200
    roles = {r["role"] for r in resp.json()["roles"]}
    assert roles == {"dj", "server_owner"}


async def test_update_roles_union(authed_client):
    """Updating roles adds new ones without removing existing."""
    await authed_client.put(
        "/api/v1/users/me/roles", json={"roles": ["dj"]}
    )
    resp = await authed_client.put(
        "/api/v1/users/me/roles", json={"roles": ["vj"]}
    )
    roles = {r["role"] for r in resp.json()["roles"]}
    assert roles == {"dj", "vj"}


async def test_remove_role(authed_client):
    """User can explicitly remove a role."""
    await authed_client.put(
        "/api/v1/users/me/roles", json={"roles": ["dj", "vj"]}
    )
    resp = await authed_client.delete("/api/v1/users/me/roles/dj")
    assert resp.status_code == 200
    roles = {r["role"] for r in resp.json()["roles"]}
    assert roles == {"vj"}
```

Note: Check existing test fixtures (conftest.py) for how `authed_client` is set up. You may need to adapt these tests to match the existing test patterns — look at `coordinator/tests/conftest.py` for the exact fixture names and how authenticated clients are created.

**Step 2: Run tests to verify they fail**

Run: `cd coordinator && python -m pytest tests/test_roles_api.py -v`
Expected: FAIL — router doesn't exist

**Step 3: Implement the router**

```python
# coordinator/app/routers/roles.py
"""User role management endpoints."""
from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import User, UserRole, RoleType, RoleSource
from app.models.schemas import (
    UserRolesResponse,
    UserRoleResponse,
    UpdateRolesRequest,
)
from app.services.auth_service import get_current_user
from app.dependencies import get_session

router = APIRouter(prefix="/users", tags=["roles"])


async def _get_user_roles(
    session: AsyncSession, user_id: uuid.UUID
) -> UserRolesResponse:
    result = await session.execute(
        select(UserRole).where(UserRole.user_id == user_id)
    )
    roles = result.scalars().all()
    return UserRolesResponse(
        user_id=user_id,
        roles=[
            UserRoleResponse(
                role=r.role, source=r.source, created_at=r.created_at
            )
            for r in roles
        ],
    )


@router.get("/me/roles", response_model=UserRolesResponse)
async def get_my_roles(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserRolesResponse:
    return await _get_user_roles(session, user.id)


@router.put("/me/roles", response_model=UserRolesResponse)
async def update_my_roles(
    body: UpdateRolesRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserRolesResponse:
    # Get existing roles
    result = await session.execute(
        select(UserRole).where(UserRole.user_id == user.id)
    )
    existing = {r.role: r for r in result.scalars().all()}

    # Add new roles (union — never remove)
    for role in body.roles:
        if role not in existing:
            session.add(
                UserRole(
                    user_id=user.id,
                    role=role,
                    source=RoleSource.COORDINATOR,
                )
            )
    await session.commit()
    return await _get_user_roles(session, user.id)


@router.delete("/me/roles/{role}", response_model=UserRolesResponse)
async def remove_my_role(
    role: RoleType,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserRolesResponse:
    result = await session.execute(
        delete(UserRole).where(
            UserRole.user_id == user.id, UserRole.role == role
        )
    )
    if result.rowcount == 0:
        raise HTTPException(404, f"Role {role.value} not found")
    await session.commit()
    return await _get_user_roles(session, user.id)
```

Note: Check how `get_current_user` and `get_session` are imported in existing routers — the exact import paths may differ. Look at `coordinator/app/routers/auth.py` or `coordinator/app/routers/dj_profiles.py` for reference.

**Step 4: Register router in `main.py`**

Add to `coordinator/app/main.py` alongside other router includes:

```python
from app.routers import roles
# ...
application.include_router(roles.router, prefix="/api/v1")
```

**Step 5: Run tests to verify they pass**

Run: `cd coordinator && python -m pytest tests/test_roles_api.py -v`
Expected: PASS

**Step 6: Commit**

```bash
git add coordinator/app/routers/roles.py coordinator/app/main.py coordinator/tests/test_roles_api.py
git commit -m "feat(coordinator): add role CRUD endpoints (GET/PUT/DELETE)"
```

---

### Task 4: Coordinator — Discord Webhook Endpoints

**Files:**
- Create: `coordinator/app/routers/discord_webhooks.py`
- Modify: `coordinator/app/config.py` (add webhook secret setting)
- Modify: `coordinator/app/main.py` (register router)
- Test: `coordinator/tests/test_discord_webhooks.py`

**Step 1: Add webhook secret to config**

Add to `coordinator/app/config.py` Settings class:

```python
    # Community bot webhook
    discord_webhook_secret: str = ""
    discord_guild_id: str = ""
    community_bot_url: str = ""  # e.g. http://localhost:8100
```

**Step 2: Write failing tests**

```python
# coordinator/tests/test_discord_webhooks.py
import uuid
import pytest
from unittest.mock import AsyncMock, patch

pytestmark = pytest.mark.asyncio(loop_scope="session")

WEBHOOK_SECRET = "test-webhook-secret-123"


async def test_discord_role_sync_creates_roles(client, session):
    """Bot pushes Discord roles → coordinator creates them."""
    # Create a user with a discord_id first
    from app.models.db import User
    user = User(
        id=uuid.uuid4(),
        discord_id="123456789",
        discord_username="testuser",
        display_name="Test",
    )
    session.add(user)
    await session.commit()

    resp = await client.post(
        "/api/v1/webhooks/discord/role-sync",
        json={"discord_id": "123456789", "roles": ["dj", "vj"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    assert len(resp.json()["roles"]) == 2


async def test_discord_role_sync_unauthorized(client):
    """Missing or wrong secret returns 401."""
    resp = await client.post(
        "/api/v1/webhooks/discord/role-sync",
        json={"discord_id": "123456789", "roles": ["dj"]},
        headers={"X-Webhook-Secret": "wrong"},
    )
    assert resp.status_code == 401


async def test_discord_role_sync_unknown_user(client):
    """Unknown discord_id returns 404."""
    resp = await client.post(
        "/api/v1/webhooks/discord/role-sync",
        json={"discord_id": "999999999", "roles": ["dj"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 404


async def test_get_user_by_discord_id(client, session):
    """Lookup user by Discord ID."""
    from app.models.db import User
    user = User(
        id=uuid.uuid4(),
        discord_id="111222333",
        discord_username="lookuptest",
        display_name="Lookup",
    )
    session.add(user)
    await session.commit()

    resp = await client.get(
        "/api/v1/webhooks/discord/users/111222333",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    assert resp.json()["discord_id"] == "111222333"
```

Note: The test needs the webhook secret to match. Check how the test app overrides settings — likely via a fixture or env var override in conftest.py. You may need to set `MCAV_DISCORD_WEBHOOK_SECRET=test-webhook-secret-123` in the test environment.

**Step 3: Implement webhook router**

```python
# coordinator/app/routers/discord_webhooks.py
"""Endpoints for community bot ↔ coordinator role sync."""
from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.dependencies import get_session
from app.models.db import User, UserRole, RoleType, RoleSource
from app.models.schemas import (
    DiscordRoleSyncRequest,
    UserRolesResponse,
    UserRoleResponse,
)

router = APIRouter(prefix="/webhooks/discord", tags=["discord-webhooks"])


def _verify_secret(
    x_webhook_secret: str = Header(...),
    settings: Settings = Depends(get_settings),
) -> None:
    if not settings.discord_webhook_secret:
        raise HTTPException(503, "Webhook secret not configured")
    if x_webhook_secret != settings.discord_webhook_secret:
        raise HTTPException(401, "Invalid webhook secret")


@router.post("/role-sync", response_model=UserRolesResponse)
async def discord_role_sync(
    body: DiscordRoleSyncRequest,
    _auth: None = Depends(_verify_secret),
    session: AsyncSession = Depends(get_session),
) -> UserRolesResponse:
    """Bot pushes role changes from Discord → coordinator (union merge)."""
    user = (
        await session.execute(
            select(User).where(User.discord_id == body.discord_id)
        )
    ).scalar_one_or_none()
    if not user:
        raise HTTPException(404, "User not linked to Discord")

    # Get existing roles
    result = await session.execute(
        select(UserRole).where(UserRole.user_id == user.id)
    )
    existing = {r.role: r for r in result.scalars().all()}

    # Union merge — add new Discord roles
    for role in body.roles:
        if role not in existing:
            session.add(
                UserRole(
                    user_id=user.id,
                    role=role,
                    source=RoleSource.DISCORD,
                )
            )
        elif existing[role].source == RoleSource.COORDINATOR:
            existing[role].source = RoleSource.BOTH
    await session.commit()

    return await _get_user_roles(session, user.id)


@router.delete("/role-sync/{discord_id}/{role}", response_model=UserRolesResponse)
async def discord_role_remove(
    discord_id: str,
    role: RoleType,
    _auth: None = Depends(_verify_secret),
    session: AsyncSession = Depends(get_session),
) -> UserRolesResponse:
    """Bot notifies coordinator of explicit role removal in Discord."""
    user = (
        await session.execute(
            select(User).where(User.discord_id == discord_id)
        )
    ).scalar_one_or_none()
    if not user:
        raise HTTPException(404, "User not linked to Discord")

    await session.execute(
        delete(UserRole).where(
            UserRole.user_id == user.id, UserRole.role == role
        )
    )
    await session.commit()
    return await _get_user_roles(session, user.id)


@router.get("/users/{discord_id}")
async def get_user_by_discord_id(
    discord_id: str,
    _auth: None = Depends(_verify_secret),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Lookup user by Discord ID. Used by bot to check if user is linked."""
    user = (
        await session.execute(
            select(User).where(User.discord_id == discord_id)
        )
    ).scalar_one_or_none()
    if not user:
        raise HTTPException(404, "User not found")

    result = await session.execute(
        select(UserRole).where(UserRole.user_id == user.id)
    )
    roles = result.scalars().all()

    return {
        "user_id": str(user.id),
        "discord_id": user.discord_id,
        "discord_username": user.discord_username,
        "roles": [r.role.value for r in roles],
    }


async def _get_user_roles(
    session: AsyncSession, user_id: uuid.UUID
) -> UserRolesResponse:
    result = await session.execute(
        select(UserRole).where(UserRole.user_id == user_id)
    )
    roles = result.scalars().all()
    return UserRolesResponse(
        user_id=user_id,
        roles=[
            UserRoleResponse(
                role=r.role, source=r.source, created_at=r.created_at
            )
            for r in roles
        ],
    )
```

**Step 4: Register router in `main.py`**

```python
from app.routers import discord_webhooks
# ...
application.include_router(discord_webhooks.router, prefix="/api/v1")
```

**Step 5: Run tests**

Run: `cd coordinator && python -m pytest tests/test_discord_webhooks.py -v`
Expected: PASS

**Step 6: Commit**

```bash
git add coordinator/app/routers/discord_webhooks.py coordinator/app/config.py coordinator/app/main.py coordinator/tests/test_discord_webhooks.py
git commit -m "feat(coordinator): add Discord webhook endpoints for role sync"
```

---

### Task 5: Community Bot — Project Scaffold

**Files:**
- Create: `community_bot/__init__.py`
- Create: `community_bot/__main__.py`
- Create: `community_bot/config.py`
- Create: `community_bot/pyproject.toml`

**Step 1: Create pyproject.toml**

```toml
# community_bot/pyproject.toml
[project]
name = "mcav-community-bot"
version = "0.1.0"
description = "MCAV Discord community bot — role management and server setup"
requires-python = ">=3.12"
dependencies = [
    "discord.py>=2.3.0",
    "aiohttp>=3.9.0",
    "python-dotenv>=1.0.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.0",
    "pytest-asyncio>=0.23",
    "ruff>=0.4.0",
]

[project.scripts]
mcav-community-bot = "community_bot.__main__:main_sync"

[tool.ruff]
target-version = "py312"
line-length = 99
```

**Step 2: Create config**

```python
# community_bot/config.py
"""Configuration loaded from environment variables."""
from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Config:
    bot_token: str
    guild_id: int
    coordinator_url: str  # e.g. http://localhost:8090
    webhook_secret: str
    webhook_port: int = 8100

    @classmethod
    def from_env(cls) -> Config:
        token = os.environ.get("MCAV_COMMUNITY_BOT_TOKEN", "")
        if not token:
            raise RuntimeError("MCAV_COMMUNITY_BOT_TOKEN is required")
        guild_id = os.environ.get("MCAV_DISCORD_GUILD_ID", "")
        if not guild_id:
            raise RuntimeError("MCAV_DISCORD_GUILD_ID is required")
        return cls(
            bot_token=token,
            guild_id=int(guild_id),
            coordinator_url=os.environ.get(
                "MCAV_COORDINATOR_URL", "http://localhost:8090"
            ),
            webhook_secret=os.environ.get("MCAV_WEBHOOK_SECRET", ""),
            webhook_port=int(os.environ.get("MCAV_WEBHOOK_PORT", "8100")),
        )
```

**Step 3: Create entry point**

```python
# community_bot/__init__.py
"""MCAV Community Bot — role management and Discord server setup."""

# community_bot/__main__.py
"""Entry point: python -m community_bot"""
from __future__ import annotations

import asyncio
import logging

from community_bot.config import Config

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
log = logging.getLogger("community_bot")


async def main() -> None:
    config = Config.from_env()
    log.info("Starting MCAV Community Bot (guild=%s)", config.guild_id)

    # Import here to avoid import-time side effects
    from community_bot.bot import CommunityBot

    bot = CommunityBot(config)
    await bot.start(config.bot_token)


def main_sync() -> None:
    asyncio.run(main())


if __name__ == "__main__":
    main_sync()
```

**Step 4: Commit**

```bash
git add community_bot/
git commit -m "feat(community-bot): scaffold project with config and entry point"
```

---

### Task 6: Community Bot — Coordinator API Client

**Files:**
- Create: `community_bot/coordinator_client.py`
- Test: `community_bot/tests/test_coordinator_client.py`

**Step 1: Write the failing test**

```python
# community_bot/tests/__init__.py
# (empty)

# community_bot/tests/test_coordinator_client.py
from __future__ import annotations

import pytest
from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase, unittest_run_loop
from community_bot.coordinator_client import CoordinatorClient

pytestmark = pytest.mark.asyncio


async def test_get_user_by_discord_id(aiohttp_server):
    """Client fetches user by Discord ID."""

    async def handler(request):
        assert request.headers["X-Webhook-Secret"] == "secret123"
        return web.json_response({
            "user_id": "abc-123",
            "discord_id": "111",
            "discord_username": "tester",
            "roles": ["dj"],
        })

    app = web.Application()
    app.router.add_get("/api/v1/webhooks/discord/users/{discord_id}", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret="secret123",
    )
    try:
        user = await client.get_user_by_discord_id("111")
        assert user is not None
        assert user["discord_id"] == "111"
        assert user["roles"] == ["dj"]
    finally:
        await client.close()


async def test_get_user_not_found(aiohttp_server):
    """Client returns None for unknown Discord ID."""

    async def handler(request):
        return web.json_response({"detail": "Not found"}, status=404)

    app = web.Application()
    app.router.add_get("/api/v1/webhooks/discord/users/{discord_id}", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret="secret123",
    )
    try:
        user = await client.get_user_by_discord_id("999")
        assert user is None
    finally:
        await client.close()
```

**Step 2: Run test to verify it fails**

Run: `cd community_bot && python -m pytest tests/test_coordinator_client.py -v`
Expected: FAIL — module doesn't exist

**Step 3: Implement the client**

```python
# community_bot/coordinator_client.py
"""HTTP client for communicating with the MCAV coordinator API."""
from __future__ import annotations

import logging

import aiohttp

log = logging.getLogger(__name__)


class CoordinatorClient:
    """Talks to coordinator REST API for role sync."""

    def __init__(self, *, base_url: str, webhook_secret: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._secret = webhook_secret
        self._session: aiohttp.ClientSession | None = None

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                headers={"X-Webhook-Secret": self._secret},
            )
        return self._session

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()

    async def get_user_by_discord_id(self, discord_id: str) -> dict | None:
        """Lookup user by Discord snowflake. Returns None if not linked."""
        session = await self._get_session()
        url = f"{self._base_url}/api/v1/webhooks/discord/users/{discord_id}"
        async with session.get(url) as resp:
            if resp.status == 404:
                return None
            resp.raise_for_status()
            return await resp.json()

    async def sync_roles(
        self, discord_id: str, roles: list[str]
    ) -> dict | None:
        """Push Discord roles to coordinator (union merge)."""
        session = await self._get_session()
        url = f"{self._base_url}/api/v1/webhooks/discord/role-sync"
        async with session.post(
            url, json={"discord_id": discord_id, "roles": roles}
        ) as resp:
            if resp.status == 404:
                return None
            resp.raise_for_status()
            return await resp.json()

    async def remove_role(
        self, discord_id: str, role: str
    ) -> dict | None:
        """Notify coordinator of explicit role removal."""
        session = await self._get_session()
        url = f"{self._base_url}/api/v1/webhooks/discord/role-sync/{discord_id}/{role}"
        async with session.delete(url) as resp:
            if resp.status == 404:
                return None
            resp.raise_for_status()
            return await resp.json()
```

**Step 4: Run tests**

Run: `cd community_bot && python -m pytest tests/test_coordinator_client.py -v`
Expected: PASS

**Step 5: Commit**

```bash
git add community_bot/coordinator_client.py community_bot/tests/
git commit -m "feat(community-bot): add coordinator API client with tests"
```

---

### Task 7: Community Bot — Core Bot with Role Buttons

**Files:**
- Create: `community_bot/bot.py`
- Create: `community_bot/views.py`

**Step 1: Create the role button view**

```python
# community_bot/views.py
"""Persistent Discord UI views for role management."""
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

import discord

if TYPE_CHECKING:
    from community_bot.bot import CommunityBot

log = logging.getLogger(__name__)

# Map role enum values to display names and emoji/colors
ROLE_CONFIG: dict[str, dict] = {
    "dj": {
        "label": "DJ",
        "style": discord.ButtonStyle.primary,
        "emoji": None,
    },
    "server_owner": {
        "label": "Server Owner",
        "style": discord.ButtonStyle.primary,
        "emoji": None,
    },
    "vj": {
        "label": "VJ",
        "style": discord.ButtonStyle.primary,
        "emoji": None,
    },
    "developer": {
        "label": "Developer",
        "style": discord.ButtonStyle.secondary,
        "emoji": None,
    },
    "beta_tester": {
        "label": "Beta Tester",
        "style": discord.ButtonStyle.secondary,
        "emoji": None,
    },
}

# Map role enum values to Discord role names
DISCORD_ROLE_NAMES: dict[str, str] = {
    "dj": "DJ",
    "server_owner": "Server Owner",
    "vj": "VJ",
    "developer": "Developer",
    "beta_tester": "Beta Tester",
}


class RoleButton(discord.ui.Button["RoleSelectView"]):
    """A single toggle button for one role."""

    def __init__(self, role_key: str, config: dict) -> None:
        super().__init__(
            label=config["label"],
            style=config["style"],
            emoji=config.get("emoji"),
            custom_id=f"mcav_role_{role_key}",
        )
        self.role_key = role_key

    async def callback(self, interaction: discord.Interaction) -> None:
        assert self.view is not None
        bot: CommunityBot = self.view.bot
        guild = interaction.guild
        if not guild:
            return

        discord_role_name = DISCORD_ROLE_NAMES[self.role_key]
        role = discord.utils.get(guild.roles, name=discord_role_name)
        if not role:
            await interaction.response.send_message(
                f"Role `{discord_role_name}` not found. Please contact an admin.",
                ephemeral=True,
            )
            return

        member = interaction.user
        if not isinstance(member, discord.Member):
            return

        if role in member.roles:
            # Toggle off — explicit removal
            await member.remove_roles(role)
            await interaction.response.send_message(
                f"Removed **{discord_role_name}** role.",
                ephemeral=True,
            )
            # Sync removal to coordinator
            await bot.coordinator.remove_role(
                str(member.id), self.role_key
            )
        else:
            # Toggle on
            await member.add_roles(role)
            await interaction.response.send_message(
                f"Added **{discord_role_name}** role!",
                ephemeral=True,
            )
            # Sync addition to coordinator
            await bot.coordinator.sync_roles(
                str(member.id), [self.role_key]
            )


class RoleSelectView(discord.ui.View):
    """Persistent view with role toggle buttons."""

    def __init__(self, bot: CommunityBot) -> None:
        super().__init__(timeout=None)  # Persistent — survives restarts
        self.bot = bot
        for key, config in ROLE_CONFIG.items():
            self.add_item(RoleButton(key, config))
```

**Step 2: Create the bot**

```python
# community_bot/bot.py
"""MCAV Community Bot — role management and welcome messages."""
from __future__ import annotations

import logging

import discord
from discord import app_commands

from community_bot.config import Config
from community_bot.coordinator_client import CoordinatorClient
from community_bot.views import RoleSelectView, DISCORD_ROLE_NAMES

log = logging.getLogger(__name__)


class CommunityBot(discord.Client):
    """Community management bot for the MCAV Discord server."""

    def __init__(self, config: Config) -> None:
        intents = discord.Intents.default()
        intents.members = True  # Required for on_member_join
        super().__init__(intents=intents)

        self.config = config
        self.tree = app_commands.CommandTree(self)
        self.coordinator = CoordinatorClient(
            base_url=config.coordinator_url,
            webhook_secret=config.webhook_secret,
        )
        self._role_view = RoleSelectView(self)

    async def setup_hook(self) -> None:
        """Called once when bot connects. Register commands + persistent views."""
        # Register persistent view so button callbacks work after restart
        self.add_view(self._role_view)

        # Slash commands
        guild = discord.Object(id=self.config.guild_id)

        @self.tree.command(
            name="setup-roles",
            description="Post the role selection embed in this channel (admin only)",
        )
        @app_commands.default_permissions(administrator=True)
        async def setup_roles(interaction: discord.Interaction) -> None:
            embed = discord.Embed(
                title="Choose Your Roles",
                description=(
                    "Click the buttons below to toggle roles.\n"
                    "Link your Discord on [mcav.live](https://mcav.live) "
                    "to get the **Verified** role!"
                ),
                color=0x00CCFF,
            )
            await interaction.channel.send(embed=embed, view=self._role_view)
            await interaction.response.send_message(
                "Role selector posted!", ephemeral=True
            )

        @self.tree.command(
            name="sync-roles",
            description="Re-sync your roles with mcav.live",
        )
        async def sync_roles(interaction: discord.Interaction) -> None:
            member = interaction.user
            if not isinstance(member, discord.Member):
                return
            user_data = await self.coordinator.get_user_by_discord_id(
                str(member.id)
            )
            if not user_data:
                await interaction.response.send_message(
                    "Your Discord isn't linked to an mcav.live account. "
                    "Visit https://mcav.live to link it!",
                    ephemeral=True,
                )
                return

            # Assign roles from coordinator
            guild = interaction.guild
            added = []
            for role_key in user_data["roles"]:
                role_name = DISCORD_ROLE_NAMES.get(role_key)
                if not role_name:
                    continue
                discord_role = discord.utils.get(guild.roles, name=role_name)
                if discord_role and discord_role not in member.roles:
                    await member.add_roles(discord_role)
                    added.append(role_name)

            # Ensure Verified role
            verified = discord.utils.get(guild.roles, name="Verified")
            if verified and verified not in member.roles:
                await member.add_roles(verified)
                added.append("Verified")

            if added:
                await interaction.response.send_message(
                    f"Synced! Added: {', '.join(added)}", ephemeral=True
                )
            else:
                await interaction.response.send_message(
                    "All roles already in sync!", ephemeral=True
                )

        self.tree.copy_global_to(guild=guild)
        await self.tree.sync(guild=guild)
        log.info("Slash commands synced to guild %s", self.config.guild_id)

    async def on_ready(self) -> None:
        log.info("Community bot ready as %s", self.user)

    async def on_member_join(self, member: discord.Member) -> None:
        """Welcome new members and check if they have a linked account."""
        if member.guild.id != self.config.guild_id:
            return

        # Check if user has a linked mcav.live account
        user_data = await self.coordinator.get_user_by_discord_id(
            str(member.id)
        )

        welcome_msg = (
            f"Welcome to MCAV, {member.mention}!\n\n"
            "Head to <#ROLES_CHANNEL_ID> to pick your roles.\n"
        )
        if user_data:
            welcome_msg += (
                "Your mcav.live account is linked — "
                "syncing your roles now!"
            )
            # Auto-assign roles from coordinator
            guild = member.guild
            for role_key in user_data["roles"]:
                role_name = DISCORD_ROLE_NAMES.get(role_key)
                if role_name:
                    discord_role = discord.utils.get(
                        guild.roles, name=role_name
                    )
                    if discord_role:
                        await member.add_roles(discord_role)
            verified = discord.utils.get(guild.roles, name="Verified")
            if verified:
                await member.add_roles(verified)
        else:
            welcome_msg += (
                "Link your Discord at https://mcav.live to get "
                "the **Verified** role!"
            )

        # Send to system channel or first available text channel
        channel = member.guild.system_channel
        if channel:
            await channel.send(welcome_msg)

    async def close(self) -> None:
        await self.coordinator.close()
        await super().close()
```

Note: The `ROLES_CHANNEL_ID` placeholder in the welcome message should be replaced with the actual channel mention. You can make this dynamic by looking up the channel by name in `on_member_join`, or store the channel ID in config.

**Step 3: Commit**

```bash
git add community_bot/bot.py community_bot/views.py
git commit -m "feat(community-bot): core bot with role buttons, welcome, and slash commands"
```

---

### Task 8: Community Bot — Webhook Server for Coordinator Notifications

**Files:**
- Create: `community_bot/webhook_server.py`
- Modify: `community_bot/__main__.py` (start webhook server alongside bot)

**Step 1: Implement webhook server**

```python
# community_bot/webhook_server.py
"""Lightweight HTTP server for receiving coordinator notifications."""
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from aiohttp import web

if TYPE_CHECKING:
    from community_bot.bot import CommunityBot

from community_bot.views import DISCORD_ROLE_NAMES

log = logging.getLogger(__name__)


def create_webhook_app(bot: CommunityBot) -> web.Application:
    """Create aiohttp app that handles coordinator → bot notifications."""

    async def health(request: web.Request) -> web.Response:
        return web.json_response({"status": "ok"})

    async def role_change(request: web.Request) -> web.Response:
        """Coordinator notifies us that a user's roles changed on mcav.live."""
        secret = request.headers.get("X-Webhook-Secret", "")
        if secret != bot.config.webhook_secret:
            return web.json_response({"error": "unauthorized"}, status=401)

        data = await request.json()
        discord_id = data.get("discord_id")
        new_roles = set(data.get("roles", []))
        user_id = data.get("user_id")

        if not discord_id:
            return web.json_response(
                {"error": "discord_id required"}, status=400
            )

        guild = bot.get_guild(bot.config.guild_id)
        if not guild:
            return web.json_response(
                {"error": "guild not found"}, status=503
            )

        member = guild.get_member(int(discord_id))
        if not member:
            # User isn't in the Discord server
            return web.json_response(
                {"status": "user_not_in_guild"}, status=200
            )

        # Sync roles — add missing, remove explicitly removed
        current_role_keys = set()
        for role_key, role_name in DISCORD_ROLE_NAMES.items():
            discord_role = discord.utils.get(guild.roles, name=role_name)
            if discord_role and discord_role in member.roles:
                current_role_keys.add(role_key)

        # Add new roles
        import discord as _discord

        for role_key in new_roles - current_role_keys:
            role_name = DISCORD_ROLE_NAMES.get(role_key)
            if role_name:
                discord_role = _discord.utils.get(
                    guild.roles, name=role_name
                )
                if discord_role:
                    await member.add_roles(discord_role)

        # Remove roles that were explicitly removed on mcav.live
        for role_key in current_role_keys - new_roles:
            role_name = DISCORD_ROLE_NAMES.get(role_key)
            if role_name:
                discord_role = _discord.utils.get(
                    guild.roles, name=role_name
                )
                if discord_role:
                    await member.remove_roles(discord_role)

        # Ensure Verified role for linked users
        verified = _discord.utils.get(guild.roles, name="Verified")
        if verified and verified not in member.roles:
            await member.add_roles(verified)

        log.info(
            "Synced roles for %s (%s): %s",
            member.display_name,
            discord_id,
            new_roles,
        )
        return web.json_response({"status": "synced"})

    app = web.Application()
    app.router.add_get("/health", health)
    app.router.add_post("/notify/role-change", role_change)
    return app
```

**Step 2: Update `__main__.py` to start both bot and webhook server**

Replace `community_bot/__main__.py` with:

```python
# community_bot/__main__.py
"""Entry point: python -m community_bot"""
from __future__ import annotations

import asyncio
import logging

from aiohttp import web

from community_bot.config import Config

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
log = logging.getLogger("community_bot")


async def main() -> None:
    config = Config.from_env()
    log.info("Starting MCAV Community Bot (guild=%s)", config.guild_id)

    from community_bot.bot import CommunityBot
    from community_bot.webhook_server import create_webhook_app

    bot = CommunityBot(config)

    # Start webhook server
    webhook_app = create_webhook_app(bot)
    runner = web.AppRunner(webhook_app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", config.webhook_port)
    await site.start()
    log.info("Webhook server listening on port %s", config.webhook_port)

    # Start Discord bot (blocks until disconnect)
    try:
        await bot.start(config.bot_token)
    finally:
        await runner.cleanup()
        await bot.close()


def main_sync() -> None:
    asyncio.run(main())


if __name__ == "__main__":
    main_sync()
```

**Step 3: Commit**

```bash
git add community_bot/webhook_server.py community_bot/__main__.py
git commit -m "feat(community-bot): add webhook server for coordinator notifications"
```

---

### Task 9: Coordinator — Notify Bot on Role Changes

**Files:**
- Create: `coordinator/app/services/discord_bot_notifier.py`
- Modify: `coordinator/app/routers/roles.py` (call notifier after role changes)
- Modify: `coordinator/app/routers/auth.py` (notify on first Discord link)

**Step 1: Implement notifier service**

```python
# coordinator/app/services/discord_bot_notifier.py
"""Notify the community bot of role changes via webhook."""
from __future__ import annotations

import logging
import uuid

import httpx

from app.config import Settings

log = logging.getLogger(__name__)


async def notify_role_change(
    *,
    settings: Settings,
    discord_id: str,
    user_id: uuid.UUID,
    roles: list[str],
) -> bool:
    """POST role change notification to community bot. Returns True on success."""
    if not settings.community_bot_url:
        log.debug("community_bot_url not configured, skipping notification")
        return False

    url = f"{settings.community_bot_url.rstrip('/')}/notify/role-change"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(
                url,
                json={
                    "discord_id": discord_id,
                    "user_id": str(user_id),
                    "roles": roles,
                },
                headers={"X-Webhook-Secret": settings.discord_webhook_secret},
            )
            if resp.status_code == 200:
                log.info("Notified bot of role change for %s", discord_id)
                return True
            log.warning(
                "Bot notification failed: %s %s", resp.status_code, resp.text
            )
            return False
    except httpx.HTTPError as exc:
        log.warning("Failed to notify bot: %s", exc)
        return False
```

**Step 2: Wire notifier into roles router**

In `coordinator/app/routers/roles.py`, after `await session.commit()` in both `update_my_roles` and `remove_my_role`, add:

```python
    # Notify community bot (fire-and-forget, don't fail if bot is down)
    if user.discord_id:
        roles_response = await _get_user_roles(session, user.id)
        await notify_role_change(
            settings=Depends(get_settings),  # Already injected
            discord_id=user.discord_id,
            user_id=user.id,
            roles=[r.role.value for r in roles_response.roles],
        )
```

Note: The exact wiring depends on how the settings dependency is structured. Add `settings: Settings = Depends(get_settings)` to the endpoint signature if not already present. The notifier should be fire-and-forget — catch exceptions so a bot outage doesn't break the coordinator.

**Step 3: Wire notifier into auth.py for first Discord link**

Find the Discord OAuth callback handler in `coordinator/app/routers/auth.py`. After the user's `discord_id` is saved (on first link), add a call to `notify_role_change` with the user's existing roles + the Verified status.

Note: Read auth.py carefully to find the exact location. The Discord callback likely calls a service function — the notification should happen after `session.commit()`.

**Step 4: Commit**

```bash
git add coordinator/app/services/discord_bot_notifier.py coordinator/app/routers/roles.py coordinator/app/routers/auth.py
git commit -m "feat(coordinator): notify community bot on role changes and Discord link"
```

---

### Task 10: Migrate Existing `user_type` Data

**Files:**
- Create: `coordinator/alembic/versions/018_migrate_user_type_to_roles.py`

**Step 1: Write data migration**

```python
# coordinator/alembic/versions/018_migrate_user_type_to_roles.py
"""Migrate user_type values to user_roles table."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID
import uuid

revision = "018"
down_revision = "017"


def upgrade() -> None:
    conn = op.get_bind()

    # Map old user_type values to new role enum values
    type_to_role = {
        "dj": "dj",
        "server_owner": "server_owner",
        "vj": "vj",
        "team_member": None,  # No direct mapping — skip
        "generic": None,
    }

    # Read all users with a user_type set
    users = conn.execute(
        sa.text("SELECT id, user_type FROM users WHERE user_type IS NOT NULL")
    ).fetchall()

    for user_id, user_type in users:
        role = type_to_role.get(user_type)
        if role:
            conn.execute(
                sa.text(
                    "INSERT INTO user_roles (id, user_id, role, source, created_at) "
                    "VALUES (:id, :user_id, :role, 'coordinator', NOW()) "
                    "ON CONFLICT (user_id, role) DO NOTHING"
                ),
                {
                    "id": str(uuid.uuid4()),
                    "user_id": str(user_id),
                    "role": role,
                },
            )


def downgrade() -> None:
    # Data migration — downgrade is a no-op (user_type column still exists)
    pass
```

Note: Do NOT drop the `user_type` column in this migration. Keep it for backwards compatibility until the frontend is updated. A future migration can drop it.

**Step 2: Commit**

```bash
git add coordinator/alembic/versions/018_*
git commit -m "feat(coordinator): migrate user_type data to user_roles table"
```

---

### Task 11: Integration Test — Full Sync Flow

**Files:**
- Create: `coordinator/tests/test_role_sync_integration.py`

**Step 1: Write integration test**

```python
# coordinator/tests/test_role_sync_integration.py
"""Integration test for the full role sync flow."""
import uuid
import pytest
from sqlalchemy import select
from app.models.db import User, UserRole, RoleType

pytestmark = pytest.mark.asyncio(loop_scope="session")

WEBHOOK_SECRET = "test-webhook-secret-123"


async def test_full_sync_flow(client, session):
    """
    Simulate: user signs up on mcav.live, self-assigns DJ in Discord,
    then adds VJ on mcav.live. Result: both roles exist with correct sources.
    """
    # 1. Create user with Discord linked
    user = User(
        id=uuid.uuid4(),
        discord_id="555666777",
        discord_username="synctest",
        display_name="Sync Tester",
    )
    session.add(user)
    await session.commit()

    # 2. Bot pushes DJ role from Discord
    resp = await client.post(
        "/api/v1/webhooks/discord/role-sync",
        json={"discord_id": "555666777", "roles": ["dj"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    roles = resp.json()["roles"]
    assert len(roles) == 1
    assert roles[0]["role"] == "dj"
    assert roles[0]["source"] == "discord"

    # 3. User adds VJ via mcav.live (simulate authed request)
    # This would normally go through /users/me/roles but we test
    # the webhook path since we don't have auth fixtures here
    resp = await client.post(
        "/api/v1/webhooks/discord/role-sync",
        json={"discord_id": "555666777", "roles": ["vj"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    roles = {r["role"] for r in resp.json()["roles"]}
    assert roles == {"dj", "vj"}  # Union — both preserved

    # 4. Explicit removal of DJ
    resp = await client.delete(
        "/api/v1/webhooks/discord/role-sync/555666777/dj",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    roles = {r["role"] for r in resp.json()["roles"]}
    assert roles == {"vj"}  # Only VJ remains
```

**Step 2: Run tests**

Run: `cd coordinator && python -m pytest tests/test_role_sync_integration.py -v`
Expected: PASS

**Step 3: Run full test suite**

Run: `cd coordinator && python -m pytest -v`
Expected: All tests pass, including existing tests

**Step 4: Commit**

```bash
git add coordinator/tests/test_role_sync_integration.py
git commit -m "test(coordinator): add integration test for full role sync flow"
```

---

### Task 12: Update CLAUDE.md and Environment Docs

**Files:**
- Modify: `CLAUDE.md` (add community_bot component)
- Modify: `.env.example` (add new env vars)

**Step 1: Add community_bot to CLAUDE.md**

Add after the discord_bot entry in the Core Components section:

```markdown
15. **community_bot/** (Python) - Discord community management bot
    - `bot.py` - Main bot: role buttons, welcome messages, slash commands
    - `views.py` - Persistent Discord UI views (role selection buttons)
    - `coordinator_client.py` - HTTP client for coordinator API
    - `webhook_server.py` - aiohttp server for coordinator notifications
    - `config.py` - Environment variable configuration
    - `pyproject.toml` (name: mcav-community-bot) - Independent package
```

Add port to the Ports section:
```
- 8100: Community bot webhook server (HTTP)
```

**Step 2: Add env vars to `.env.example`**

```bash
# Community Bot
MCAV_COMMUNITY_BOT_TOKEN=
MCAV_DISCORD_GUILD_ID=
MCAV_WEBHOOK_SECRET=
MCAV_WEBHOOK_PORT=8100
MCAV_COMMUNITY_BOT_URL=http://localhost:8100
```

**Step 3: Commit**

```bash
git add CLAUDE.md .env.example
git commit -m "docs: add community bot to project docs and env example"
```
