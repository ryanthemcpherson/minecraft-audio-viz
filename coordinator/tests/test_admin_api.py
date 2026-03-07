"""Integration tests for the admin router (/api/v1/admin/)."""

from __future__ import annotations

import pytest
from app.models.db import User
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _register_user(
    client: AsyncClient,
    email: str = "admin@example.com",
    display_name: str = "Admin User",
) -> tuple[str, dict]:
    """Register a user and return (access_token, user_data)."""
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": display_name},
    )
    assert resp.status_code == 201, resp.text
    data = resp.json()
    return data["access_token"], data["user"]


async def _make_admin(db_session: AsyncSession, email: str) -> None:
    """Promote a user to admin directly in the database."""
    result = await db_session.execute(select(User).where(User.email == email))
    user = result.scalar_one()
    user.is_admin = True
    await db_session.commit()


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# Stats endpoint
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_admin_stats(client: AsyncClient, db_session: AsyncSession) -> None:
    token, _ = await _register_user(client, "stats-admin@example.com")
    await _make_admin(db_session, "stats-admin@example.com")

    resp = await client.get("/api/v1/admin/stats", headers=_auth(token))
    assert resp.status_code == 200
    data = resp.json()
    assert "total_users" in data
    assert "total_organizations" in data
    assert "total_servers" in data
    assert "total_shows" in data
    assert "active_shows" in data
    assert "users_last_30_days" in data
    assert "dj_profiles" in data
    assert data["total_users"] >= 1


@pytest.mark.asyncio
async def test_admin_stats_non_admin_returns_403(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "nonadmin-stats@example.com")
    resp = await client.get("/api/v1/admin/stats", headers=_auth(token))
    assert resp.status_code == 403


@pytest.mark.asyncio
async def test_admin_stats_no_auth_returns_error(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/admin/stats")
    assert resp.status_code in (401, 422)


# ---------------------------------------------------------------------------
# List users
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_admin_list_users(client: AsyncClient, db_session: AsyncSession) -> None:
    token, _ = await _register_user(client, "list-admin@example.com")
    await _make_admin(db_session, "list-admin@example.com")

    # Create a second user so the list has multiple entries
    await _register_user(client, "regular@example.com", "Regular User")

    resp = await client.get("/api/v1/admin/users", headers=_auth(token))
    assert resp.status_code == 200
    data = resp.json()
    assert isinstance(data, list)
    assert len(data) >= 2

    # Verify response shape
    row = data[0]
    assert "id" in row
    assert "display_name" in row
    assert "email" in row
    assert "is_active" in row
    assert "is_admin" in row
    assert "org_count" in row
    assert "has_dj_profile" in row


@pytest.mark.asyncio
async def test_admin_list_users_with_search(client: AsyncClient, db_session: AsyncSession) -> None:
    token, _ = await _register_user(client, "search-admin@example.com")
    await _make_admin(db_session, "search-admin@example.com")
    await _register_user(client, "findme@example.com", "Findable User")

    resp = await client.get(
        "/api/v1/admin/users", params={"search": "Findable"}, headers=_auth(token)
    )
    assert resp.status_code == 200
    data = resp.json()
    assert any(u["display_name"] == "Findable User" for u in data)


@pytest.mark.asyncio
async def test_admin_list_users_pagination(client: AsyncClient, db_session: AsyncSession) -> None:
    token, _ = await _register_user(client, "page-admin@example.com")
    await _make_admin(db_session, "page-admin@example.com")

    resp = await client.get(
        "/api/v1/admin/users", params={"limit": 1, "offset": 0}, headers=_auth(token)
    )
    assert resp.status_code == 200
    assert len(resp.json()) <= 1


@pytest.mark.asyncio
async def test_admin_list_users_non_admin_returns_403(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "nonadmin-list@example.com")
    resp = await client.get("/api/v1/admin/users", headers=_auth(token))
    assert resp.status_code == 403


# ---------------------------------------------------------------------------
# Update user (PATCH)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_admin_update_user_deactivate(client: AsyncClient, db_session: AsyncSession) -> None:
    admin_token, _ = await _register_user(client, "patch-admin@example.com")
    await _make_admin(db_session, "patch-admin@example.com")

    _, target_user = await _register_user(client, "target@example.com", "Target User")
    target_id = target_user["id"]

    resp = await client.patch(
        f"/api/v1/admin/users/{target_id}",
        json={"is_active": False},
        headers=_auth(admin_token),
    )
    assert resp.status_code == 200
    assert resp.json()["is_active"] is False


@pytest.mark.asyncio
async def test_admin_update_user_promote_to_admin(
    client: AsyncClient, db_session: AsyncSession
) -> None:
    admin_token, _ = await _register_user(client, "promote-admin@example.com")
    await _make_admin(db_session, "promote-admin@example.com")

    _, target_user = await _register_user(client, "promote-target@example.com", "Promote Me")
    target_id = target_user["id"]

    resp = await client.patch(
        f"/api/v1/admin/users/{target_id}",
        json={"is_admin": True},
        headers=_auth(admin_token),
    )
    assert resp.status_code == 200
    assert resp.json()["is_admin"] is True


@pytest.mark.asyncio
async def test_admin_cannot_remove_own_admin(client: AsyncClient, db_session: AsyncSession) -> None:
    admin_token, admin_user = await _register_user(client, "selfremove@example.com")
    await _make_admin(db_session, "selfremove@example.com")
    admin_id = admin_user["id"]

    resp = await client.patch(
        f"/api/v1/admin/users/{admin_id}",
        json={"is_admin": False},
        headers=_auth(admin_token),
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_admin_cannot_deactivate_self(client: AsyncClient, db_session: AsyncSession) -> None:
    admin_token, admin_user = await _register_user(client, "selfdeact@example.com")
    await _make_admin(db_session, "selfdeact@example.com")
    admin_id = admin_user["id"]

    resp = await client.patch(
        f"/api/v1/admin/users/{admin_id}",
        json={"is_active": False},
        headers=_auth(admin_token),
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_admin_update_nonexistent_user(client: AsyncClient, db_session: AsyncSession) -> None:
    admin_token, _ = await _register_user(client, "patch404@example.com")
    await _make_admin(db_session, "patch404@example.com")

    resp = await client.patch(
        "/api/v1/admin/users/00000000-0000-0000-0000-000000000000",
        json={"is_active": False},
        headers=_auth(admin_token),
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_admin_update_user_non_admin_returns_403(client: AsyncClient) -> None:
    token, target_user = await _register_user(client, "noadmin-patch@example.com")
    resp = await client.patch(
        f"/api/v1/admin/users/{target_user['id']}",
        json={"is_admin": True},
        headers=_auth(token),
    )
    assert resp.status_code == 403


# ---------------------------------------------------------------------------
# List organizations
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_admin_list_organizations(client: AsyncClient, db_session: AsyncSession) -> None:
    admin_token, _ = await _register_user(client, "orglist-admin@example.com")
    await _make_admin(db_session, "orglist-admin@example.com")

    resp = await client.get("/api/v1/admin/organizations", headers=_auth(admin_token))
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


# ---------------------------------------------------------------------------
# List servers
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_admin_list_servers(client: AsyncClient, db_session: AsyncSession) -> None:
    admin_token, _ = await _register_user(client, "srvlist-admin@example.com")
    await _make_admin(db_session, "srvlist-admin@example.com")

    resp = await client.get("/api/v1/admin/servers", headers=_auth(admin_token))
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


# ---------------------------------------------------------------------------
# List shows
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_admin_list_shows(client: AsyncClient, db_session: AsyncSession) -> None:
    admin_token, _ = await _register_user(client, "showlist-admin@example.com")
    await _make_admin(db_session, "showlist-admin@example.com")

    resp = await client.get("/api/v1/admin/shows", headers=_auth(admin_token))
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


@pytest.mark.asyncio
async def test_admin_list_shows_with_status_filter(
    client: AsyncClient, db_session: AsyncSession
) -> None:
    admin_token, _ = await _register_user(client, "showfilter-admin@example.com")
    await _make_admin(db_session, "showfilter-admin@example.com")

    resp = await client.get(
        "/api/v1/admin/shows", params={"status": "active"}, headers=_auth(admin_token)
    )
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)
