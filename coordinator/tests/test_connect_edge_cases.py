"""Tests for connect code resolution edge cases."""

from __future__ import annotations

import uuid

import pytest
from app.models.db import Show, VJServer
from app.services.metrics import reset as metrics_reset
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_token(client: AsyncClient, email: str) -> str:
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "Edge Tester"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


async def _register_server(
    client: AsyncClient,
    user_token: str,
    name: str = "Edge Server",
    websocket_url: str = "ws://localhost:9200",
    api_key: str = "edge-key",
) -> dict:
    resp = await client.post(
        "/api/v1/servers/register",
        json={"name": name, "websocket_url": websocket_url, "api_key": api_key},
        headers={"Authorization": f"Bearer {user_token}"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_resolve_nonexistent_code_returns_404(client: AsyncClient) -> None:
    """Resolving a code that does not exist should return 404."""
    resp = await client.get("/api/v1/connect/BASS-9999")
    assert resp.status_code == 404
    assert "not found" in resp.json()["detail"].lower()


@pytest.mark.asyncio
async def test_resolve_expired_code_returns_404(client: AsyncClient) -> None:
    """After ending a show, its connect code should no longer resolve."""
    user_token = await _get_user_token(client, "expired@example.com")
    reg = await _register_server(
        client,
        user_token,
        name="Expired Server",
        websocket_url="ws://localhost:9201",
        api_key="expired-key",
    )
    server_id = reg["server_id"]

    # Create and immediately end a show
    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Temp Show", "max_djs": 4},
        headers={"Authorization": "Bearer expired-key"},
    )
    assert show_resp.status_code == 201
    show_data = show_resp.json()
    connect_code = show_data["connect_code"]

    end_resp = await client.delete(
        f"/api/v1/shows/{show_data['show_id']}",
        headers={"Authorization": "Bearer expired-key"},
    )
    assert end_resp.status_code == 200

    # Resolve the now-expired code
    resolve_resp = await client.get(f"/api/v1/connect/{connect_code}")
    assert resolve_resp.status_code == 404


@pytest.mark.asyncio
async def test_resolve_inactive_server_returns_503(
    client: AsyncClient, db_session: AsyncSession
) -> None:
    """Resolving a code whose server is inactive should return 503."""
    # Create server and show directly in the DB, then deactivate the server
    server_id = uuid.uuid4()
    show_id = uuid.uuid4()

    from app.services.password import hash_password

    db_session.add(
        VJServer(
            id=server_id,
            name="Inactive Server",
            websocket_url="ws://inactive:9999",
            api_key_hash=hash_password("inactive-key"),
            jwt_secret="secret-inactive-for-tests-min-32-chars",  # nosec B106
            is_active=False,  # server is offline
        )
    )
    db_session.add(
        Show(
            id=show_id,
            server_id=server_id,
            name="Ghost Show",
            connect_code="BASS-DEAD",
            status="active",
            max_djs=4,
            current_djs=0,
        )
    )
    await db_session.commit()

    resp = await client.get("/api/v1/connect/BASS-DEAD")
    assert resp.status_code == 503
    assert "offline" in resp.json()["detail"].lower()


@pytest.mark.asyncio
async def test_resolve_case_insensitive(client: AsyncClient) -> None:
    """Connect code resolution should be case-insensitive."""
    user_token = await _get_user_token(client, "case@example.com")
    reg = await _register_server(
        client,
        user_token,
        name="Case Server",
        websocket_url="ws://localhost:9202",
        api_key="case-key",
    )
    server_id = reg["server_id"]

    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Case Show", "max_djs": 4},
        headers={"Authorization": "Bearer case-key"},
    )
    assert show_resp.status_code == 201
    connect_code = show_resp.json()["connect_code"]  # e.g. "BASS-A2B3"

    # Resolve with lowercase version
    lower_code = connect_code.lower()
    resp = await client.get(f"/api/v1/connect/{lower_code}")
    assert resp.status_code == 200
    assert resp.json()["show_name"] == "Case Show"


@pytest.mark.asyncio
async def test_resolve_with_whitespace(client: AsyncClient) -> None:
    """Connect code resolution should strip leading/trailing whitespace."""
    user_token = await _get_user_token(client, "ws@example.com")
    reg = await _register_server(
        client, user_token, name="WS Server", websocket_url="ws://localhost:9203", api_key="ws-key"
    )
    server_id = reg["server_id"]

    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "WS Show", "max_djs": 4},
        headers={"Authorization": "Bearer ws-key"},
    )
    assert show_resp.status_code == 201
    connect_code = show_resp.json()["connect_code"]

    # URL-encoded space is %20; in practice the path stripping handles this
    # but we test the normalise_code function via the endpoint
    resp = await client.get(f"/api/v1/connect/ {connect_code} ")
    # FastAPI strips path params, but normalise_code also strips
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_join_idempotency_key_reuses_session(client: AsyncClient) -> None:
    """Repeated join requests with the same Idempotency-Key should not double-count DJs."""
    user_token = await _get_user_token(client, "idem@example.com")
    reg = await _register_server(
        client,
        user_token,
        name="Idem Server",
        websocket_url="ws://localhost:9204",
        api_key="idem-key",
    )
    server_id = reg["server_id"]

    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Idem Show", "max_djs": 4},
        headers={"Authorization": "Bearer idem-key"},
    )
    assert show_resp.status_code == 201
    connect_code = show_resp.json()["connect_code"]
    show_id = show_resp.json()["show_id"]

    headers = {"Idempotency-Key": "same-key-1"}
    join1 = await client.post(f"/api/v1/connect/{connect_code}/join", headers=headers)
    assert join1.status_code == 200
    join2 = await client.post(f"/api/v1/connect/{connect_code}/join", headers=headers)
    assert join2.status_code == 200
    assert join1.json()["dj_session_id"] == join2.json()["dj_session_id"]
    assert join1.json()["dj_count"] == 1
    assert join2.json()["dj_count"] == 1

    detail = await client.get(
        f"/api/v1/shows/{show_id}",
        headers={"Authorization": "Bearer idem-key"},
    )
    assert detail.status_code == 200
    assert detail.json()["current_djs"] == 1


@pytest.mark.asyncio
async def test_join_different_idempotency_keys_create_distinct_sessions(
    client: AsyncClient,
) -> None:
    """Different idempotency keys should create separate sessions."""
    user_token = await _get_user_token(client, "idem2@example.com")
    reg = await _register_server(
        client,
        user_token,
        name="Idem Server 2",
        websocket_url="ws://localhost:9205",
        api_key="idem2-key",
    )
    server_id = reg["server_id"]

    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Idem Show 2", "max_djs": 4},
        headers={"Authorization": "Bearer idem2-key"},
    )
    assert show_resp.status_code == 201
    connect_code = show_resp.json()["connect_code"]
    show_id = show_resp.json()["show_id"]

    join1 = await client.post(
        f"/api/v1/connect/{connect_code}/join",
        headers={"Idempotency-Key": "k1"},
    )
    join2 = await client.post(
        f"/api/v1/connect/{connect_code}/join",
        headers={"Idempotency-Key": "k2"},
    )
    assert join1.status_code == 200
    assert join2.status_code == 200
    assert join1.json()["dj_session_id"] != join2.json()["dj_session_id"]
    assert join2.json()["dj_count"] == 2

    detail = await client.get(
        f"/api/v1/shows/{show_id}",
        headers={"Authorization": "Bearer idem2-key"},
    )
    assert detail.status_code == 200
    assert detail.json()["current_djs"] == 2


@pytest.mark.asyncio
async def test_connect_counters_reported_in_health(client: AsyncClient) -> None:
    """Health endpoint should expose connect flow counters."""
    metrics_reset()
    user_token = await _get_user_token(client, "metrics@example.com")
    reg = await _register_server(
        client,
        user_token,
        name="Metrics Server",
        websocket_url="ws://localhost:9206",
        api_key="metrics-key",
    )
    server_id = reg["server_id"]

    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Metrics Show", "max_djs": 1},
        headers={"Authorization": "Bearer metrics-key"},
    )
    assert show_resp.status_code == 201
    connect_code = show_resp.json()["connect_code"]

    # resolve + join success + join full
    assert (await client.get(f"/api/v1/connect/{connect_code}")).status_code == 200
    assert (await client.post(f"/api/v1/connect/{connect_code}/join")).status_code == 200
    assert (await client.post(f"/api/v1/connect/{connect_code}/join")).status_code == 409

    health = await client.get("/health")
    assert health.status_code == 200
    counters = health.json()["counters"]
    assert counters["connect.resolve.attempt"] >= 1
    assert counters["connect.resolve.success"] >= 1
    assert counters["connect.join.attempt"] >= 2
    assert counters["connect.join.success"] >= 1
    assert counters["connect.join.full"] >= 1
