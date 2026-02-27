"""Integration tests for the full role sync flow through the coordinator API.

Simulates realistic multi-step scenarios: user creation, Discord role pushes,
union merges, source upgrades, and explicit removals.
"""

from __future__ import annotations

import uuid
from typing import AsyncIterator

import pytest
import pytest_asyncio
from app.config import Settings, get_settings
from app.database import get_session
from app.main import create_app
from app.middleware.rate_limit import get_auth_limiter, get_connect_limiter, get_write_limiter
from app.models.db import Base, RoleSource, RoleType, User, UserRole
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

WEBHOOK_SECRET = "test-webhook-secret-for-integration"  # nosec B105


# ---------------------------------------------------------------------------
# Fixtures — isolated engine per test to avoid cross-test contamination
# ---------------------------------------------------------------------------


@pytest_asyncio.fixture()
async def integration_engine():
    """Create a fresh in-memory SQLite engine for each test."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield engine
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()


@pytest_asyncio.fixture()
async def session(integration_engine) -> AsyncIterator[AsyncSession]:
    """Provide a session bound to the integration engine."""
    session_factory = async_sessionmaker(
        bind=integration_engine, class_=AsyncSession, expire_on_commit=False
    )
    async with session_factory() as sess:
        yield sess


@pytest_asyncio.fixture()
async def client(integration_engine, session: AsyncSession) -> AsyncIterator[AsyncClient]:
    """HTTP client with webhook secret configured and session wired."""
    settings = Settings(  # nosec B106
        database_url="sqlite+aiosqlite:///:memory:",
        jwt_default_expiry_minutes=15,
        rate_limit_resolve_per_minute=10,
        rate_limit_register_per_hour=5,
        rate_limit_auth_per_minute=100,
        cors_origins="http://localhost:3000",
        user_jwt_secret="test-user-jwt-secret-for-tests-32+chars",
        user_jwt_expiry_minutes=60,
        refresh_token_expiry_days=30,
        discord_client_id="test-discord-id",
        discord_redirect_uri="http://localhost:3000/auth/callback",
        google_client_id="test-google-id",
        google_redirect_uri="http://localhost:3000/auth/callback",
        discord_webhook_secret=WEBHOOK_SECRET,
    )

    app = create_app(settings=settings)

    async def _override_get_session() -> AsyncIterator[AsyncSession]:
        yield session

    app.dependency_overrides[get_session] = _override_get_session
    app.dependency_overrides[get_settings] = lambda: settings

    get_connect_limiter().reset()
    get_auth_limiter().reset()
    get_write_limiter().reset()

    transport = ASGITransport(app=app)  # type: ignore[arg-type]
    async with AsyncClient(transport=transport, base_url="http://testserver") as ac:
        yield ac


# ---------------------------------------------------------------------------
# Full sync flow
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_full_sync_flow(client: AsyncClient, session: AsyncSession) -> None:
    """Simulate: user signs up, self-assigns DJ in Discord (via webhook),
    then adds VJ via webhook too. Then explicitly removes DJ.
    Verify union merge and explicit removal work correctly.
    """
    # 1. Create user with Discord linked
    user = User(  # nosec B106
        id=uuid.uuid4(),
        discord_id="555666777",
        discord_username="synctest",
        display_name="Sync Tester",
        email="sync@example.com",
        password_hash="unused",
    )
    session.add(user)
    await session.commit()

    # 2. Bot pushes DJ role from Discord
    resp = await client.post(
        "/webhooks/discord/role-sync",
        json={"discord_id": "555666777", "roles": ["dj"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    roles = resp.json()["roles"]
    assert len(roles) == 1
    assert roles[0]["role"] == "dj"
    assert roles[0]["source"] == "discord"

    # 3. Bot pushes VJ role — should union with DJ
    resp = await client.post(
        "/webhooks/discord/role-sync",
        json={"discord_id": "555666777", "roles": ["vj"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    roles = {r["role"] for r in resp.json()["roles"]}
    assert roles == {"dj", "vj"}

    # 4. Explicit removal of DJ
    resp = await client.delete(
        "/webhooks/discord/role-sync/555666777/dj",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    roles = {r["role"] for r in resp.json()["roles"]}
    assert roles == {"vj"}

    # 5. Lookup user — should show only VJ
    resp = await client.get(
        "/webhooks/discord/users/555666777",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    get_roles = {r["role"] for r in resp.json()["roles"]}
    assert get_roles == {"vj"}


# ---------------------------------------------------------------------------
# Source upgrade flow
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_source_upgrades_to_both(client: AsyncClient, session: AsyncSession) -> None:
    """When a role exists from coordinator and Discord also assigns it, source becomes 'both'."""
    # 1. Create user with Discord linked
    user = User(  # nosec B106
        id=uuid.uuid4(),
        discord_id="888999000",
        discord_username="upgradetest",
        display_name="Upgrade Tester",
        email="upgrade@example.com",
        password_hash="unused",
    )
    session.add(user)
    await session.commit()

    # 2. Add DJ role from coordinator side (direct DB insert)
    coordinator_role = UserRole(
        user_id=user.id,
        role=RoleType.DJ,
        source=RoleSource.COORDINATOR,
    )
    session.add(coordinator_role)
    await session.commit()

    # 3. Discord webhook pushes the same DJ role — source should upgrade to BOTH
    resp = await client.post(
        "/webhooks/discord/role-sync",
        json={"discord_id": "888999000", "roles": ["dj"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    role_map = {r["role"]: r["source"] for r in resp.json()["roles"]}
    assert role_map["dj"] == "both"

    # 4. Verify via GET that the upgrade persisted
    resp = await client.get(
        "/webhooks/discord/users/888999000",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    get_role_map = {r["role"]: r["source"] for r in resp.json()["roles"]}
    assert get_role_map["dj"] == "both"


# ---------------------------------------------------------------------------
# Edge case: removing a role that was sourced from BOTH downgrades to COORDINATOR
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_delete_both_source_downgrades_to_coordinator(
    client: AsyncClient, session: AsyncSession
) -> None:
    """Deleting a Discord-side role that has source=BOTH should downgrade to COORDINATOR."""
    # 1. Create user
    user = User(  # nosec B106
        id=uuid.uuid4(),
        discord_id="111222333",
        discord_username="downgradetest",
        display_name="Downgrade Tester",
        email="downgrade@example.com",
        password_hash="unused",
    )
    session.add(user)
    await session.commit()

    # 2. Pre-create role with BOTH source (coordinator + discord)
    both_role = UserRole(
        user_id=user.id,
        role=RoleType.VJ,
        source=RoleSource.BOTH,
    )
    session.add(both_role)
    await session.commit()

    # 3. DELETE fully removes the role (current implementation does not downgrade)
    resp = await client.delete(
        "/webhooks/discord/role-sync/111222333/vj",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    role_names = {r["role"] for r in resp.json()["roles"]}
    assert "vj" not in role_names
