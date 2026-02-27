"""Tests for Discord webhook endpoints."""

from __future__ import annotations

import uuid

import pytest
import pytest_asyncio
from app.models.db import RoleSource, RoleType, User, UserRole
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

WEBHOOK_SECRET = "test-webhook-secret-for-discord"  # nosec B105


@pytest_asyncio.fixture()
async def discord_user(db_session: AsyncSession) -> User:
    """Create a user with a discord_id for webhook tests."""
    user = User(  # nosec B106
        id=uuid.uuid4(),
        display_name="DiscordTestUser",
        discord_id="123456789012345678",
        discord_username="testuser#1234",
        email="discord-test@example.com",
        password_hash="unused",
    )
    db_session.add(user)
    await db_session.commit()
    await db_session.refresh(user)
    return user


@pytest_asyncio.fixture()
async def webhook_client(settings, db_session: AsyncSession, discord_user: User) -> AsyncClient:
    """Client with webhook secret configured."""
    from typing import AsyncIterator

    from app.config import Settings, get_settings
    from app.database import get_session
    from app.main import create_app
    from app.middleware.rate_limit import get_auth_limiter, get_connect_limiter, get_write_limiter
    from httpx import ASGITransport

    # Create settings with webhook secret set
    webhook_settings = Settings(  # nosec B106
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

    app = create_app(settings=webhook_settings)

    async def _override_get_session() -> AsyncIterator[AsyncSession]:
        yield db_session

    app.dependency_overrides[get_session] = _override_get_session
    app.dependency_overrides[get_settings] = lambda: webhook_settings

    get_connect_limiter().reset()
    get_auth_limiter().reset()
    get_write_limiter().reset()

    transport = ASGITransport(app=app)  # type: ignore[arg-type]
    async with AsyncClient(transport=transport, base_url="http://testserver") as ac:
        yield ac


# ---------------------------------------------------------------------------
# POST /webhooks/discord/role-sync
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_role_sync_creates_roles(webhook_client: AsyncClient, discord_user: User) -> None:
    """POST role-sync should create roles for a linked user."""
    resp = await webhook_client.post(
        "/webhooks/discord/role-sync",
        json={"discord_id": discord_user.discord_id, "roles": ["dj", "beta_tester"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["user_id"] == str(discord_user.id)
    role_names = {r["role"] for r in data["roles"]}
    assert "dj" in role_names
    assert "beta_tester" in role_names
    # All should have DISCORD source
    for role in data["roles"]:
        assert role["source"] == "discord"


@pytest.mark.asyncio
async def test_role_sync_upgrades_source_to_both(
    webhook_client: AsyncClient, discord_user: User, db_session: AsyncSession
) -> None:
    """If a role exists with source=COORDINATOR, POST role-sync should upgrade to BOTH."""
    # Pre-create a role with COORDINATOR source
    existing_role = UserRole(
        user_id=discord_user.id,
        role=RoleType.DJ,
        source=RoleSource.COORDINATOR,
    )
    db_session.add(existing_role)
    await db_session.commit()

    resp = await webhook_client.post(
        "/webhooks/discord/role-sync",
        json={"discord_id": discord_user.discord_id, "roles": ["dj", "vj"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    data = resp.json()
    role_map = {r["role"]: r["source"] for r in data["roles"]}
    assert role_map["dj"] == "both"  # upgraded
    assert role_map["vj"] == "discord"  # new


@pytest.mark.asyncio
async def test_role_sync_wrong_secret(webhook_client: AsyncClient, discord_user: User) -> None:
    """POST role-sync with wrong secret returns 401."""
    resp = await webhook_client.post(
        "/webhooks/discord/role-sync",
        json={"discord_id": discord_user.discord_id, "roles": ["dj"]},
        headers={"X-Webhook-Secret": "wrong-secret"},
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_role_sync_missing_secret(webhook_client: AsyncClient, discord_user: User) -> None:
    """POST role-sync with no secret header returns 401."""
    resp = await webhook_client.post(
        "/webhooks/discord/role-sync",
        json={"discord_id": discord_user.discord_id, "roles": ["dj"]},
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_role_sync_unknown_discord_id(webhook_client: AsyncClient) -> None:
    """POST role-sync for unknown discord_id returns 404."""
    resp = await webhook_client.post(
        "/webhooks/discord/role-sync",
        json={"discord_id": "999999999999999999", "roles": ["dj"]},
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_role_sync_secret_not_configured(client: AsyncClient) -> None:
    """POST role-sync when secret is not configured returns 503."""
    resp = await client.post(
        "/webhooks/discord/role-sync",
        json={"discord_id": "123456789012345678", "roles": ["dj"]},
        headers={"X-Webhook-Secret": "anything"},
    )
    assert resp.status_code == 503


# ---------------------------------------------------------------------------
# GET /webhooks/discord/users/{discord_id}
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_user_by_discord_id(webhook_client: AsyncClient, discord_user: User) -> None:
    """GET user by discord_id returns user data."""
    resp = await webhook_client.get(
        f"/webhooks/discord/users/{discord_user.discord_id}",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["user_id"] == str(discord_user.id)
    assert data["discord_id"] == discord_user.discord_id
    assert data["discord_username"] == discord_user.discord_username
    assert isinstance(data["roles"], list)


@pytest.mark.asyncio
async def test_get_user_unknown_discord_id(webhook_client: AsyncClient) -> None:
    """GET user by unknown discord_id returns 404."""
    resp = await webhook_client.get(
        "/webhooks/discord/users/999999999999999999",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 404


# ---------------------------------------------------------------------------
# DELETE /webhooks/discord/role-sync/{discord_id}/{role}
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_delete_role_removes_role(
    webhook_client: AsyncClient, discord_user: User, db_session: AsyncSession
) -> None:
    """DELETE role-sync removes a role and returns updated list."""
    # First create a role
    role = UserRole(user_id=discord_user.id, role=RoleType.DJ, source=RoleSource.DISCORD)
    db_session.add(role)
    await db_session.commit()

    resp = await webhook_client.delete(
        f"/webhooks/discord/role-sync/{discord_user.discord_id}/dj",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["user_id"] == str(discord_user.id)
    role_names = {r["role"] for r in data["roles"]}
    assert "dj" not in role_names


@pytest.mark.asyncio
async def test_delete_role_unknown_discord_id(webhook_client: AsyncClient) -> None:
    """DELETE role-sync for unknown discord_id returns 404."""
    resp = await webhook_client.delete(
        "/webhooks/discord/role-sync/999999999999999999/dj",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_delete_role_not_assigned(webhook_client: AsyncClient, discord_user: User) -> None:
    """DELETE role-sync for a role not assigned returns 404."""
    resp = await webhook_client.delete(
        f"/webhooks/discord/role-sync/{discord_user.discord_id}/developer",
        headers={"X-Webhook-Secret": WEBHOOK_SECRET},
    )
    assert resp.status_code == 404
