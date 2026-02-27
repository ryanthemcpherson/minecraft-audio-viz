"""Integration tests for the role CRUD endpoints."""

from __future__ import annotations

from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _register_and_auth(client: AsyncClient, email: str = "roles@example.com") -> dict:
    """Register a user and return the full auth response."""
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "Roles Tester"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def _auth_header(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# GET /users/me/roles
# ---------------------------------------------------------------------------


class TestGetRoles:
    async def test_get_roles_empty_for_new_user(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        token = auth["access_token"]
        user_id = auth["user"]["id"]

        resp = await client.get("/api/v1/users/me/roles", headers=_auth_header(token))
        assert resp.status_code == 200
        data = resp.json()
        assert data["user_id"] == user_id
        assert data["roles"] == []

    async def test_get_roles_unauthenticated(self, client: AsyncClient) -> None:
        resp = await client.get("/api/v1/users/me/roles")
        assert resp.status_code in (401, 422)


# ---------------------------------------------------------------------------
# PUT /users/me/roles
# ---------------------------------------------------------------------------


class TestPutRoles:
    async def test_put_adds_roles(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        token = auth["access_token"]

        resp = await client.put(
            "/api/v1/users/me/roles",
            json={"roles": ["dj"]},
            headers=_auth_header(token),
        )
        assert resp.status_code == 200
        data = resp.json()
        role_names = [r["role"] for r in data["roles"]]
        assert "dj" in role_names

    async def test_put_is_union_based(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        token = auth["access_token"]
        headers = _auth_header(token)

        # First PUT: add dj
        resp = await client.put(
            "/api/v1/users/me/roles",
            json={"roles": ["dj"]},
            headers=headers,
        )
        assert resp.status_code == 200

        # Second PUT: add vj
        resp = await client.put(
            "/api/v1/users/me/roles",
            json={"roles": ["vj"]},
            headers=headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        role_names = sorted([r["role"] for r in data["roles"]])
        assert role_names == ["dj", "vj"]

    async def test_put_duplicate_role_is_idempotent(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        token = auth["access_token"]
        headers = _auth_header(token)

        # Add dj twice
        await client.put("/api/v1/users/me/roles", json={"roles": ["dj"]}, headers=headers)
        resp = await client.put(
            "/api/v1/users/me/roles",
            json={"roles": ["dj"]},
            headers=headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        dj_roles = [r for r in data["roles"] if r["role"] == "dj"]
        assert len(dj_roles) == 1

    async def test_put_roles_source_is_coordinator(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        token = auth["access_token"]

        resp = await client.put(
            "/api/v1/users/me/roles",
            json={"roles": ["dj"]},
            headers=_auth_header(token),
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["roles"][0]["source"] == "coordinator"


# ---------------------------------------------------------------------------
# DELETE /users/me/roles/{role}
# ---------------------------------------------------------------------------


class TestDeleteRole:
    async def test_delete_removes_role(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        token = auth["access_token"]
        headers = _auth_header(token)

        # Add dj and vj
        await client.put(
            "/api/v1/users/me/roles",
            json={"roles": ["dj", "vj"]},
            headers=headers,
        )

        # Delete dj
        resp = await client.delete("/api/v1/users/me/roles/dj", headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        role_names = [r["role"] for r in data["roles"]]
        assert "dj" not in role_names
        assert "vj" in role_names

    async def test_delete_nonexistent_role_returns_404(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        token = auth["access_token"]

        resp = await client.delete(
            "/api/v1/users/me/roles/dj",
            headers=_auth_header(token),
        )
        assert resp.status_code == 404
