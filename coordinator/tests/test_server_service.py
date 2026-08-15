"""Unit tests for app.services.server_service.

Tests server authentication (Bearer key verification), registration,
heartbeat updates, and key prefix computation.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import VJServer
from app.services.exceptions import AuthenticationError
from app.services.password import hash_password
from app.services.server_service import (
    authenticate_server,
    compute_key_prefix,
    register_server,
    update_heartbeat,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _insert_server(
    session: AsyncSession,
    *,
    api_key: str = "test-api-key-12345",
    name: str = "Test Server",
    is_active: bool = True,
    key_prefix: str | None = None,
) -> VJServer:
    """Insert a VJServer row directly (bypassing register_server)."""
    prefix = key_prefix if key_prefix is not None else compute_key_prefix(api_key)
    server = VJServer(
        id=uuid.uuid4(),
        name=name,
        websocket_url="ws://localhost:9000",
        api_key_hash=hash_password(api_key),
        key_prefix=prefix,
        jwt_secret="jwt-placeholder",
        is_active=is_active,
    )
    session.add(server)
    await session.flush()
    return server


# ---------------------------------------------------------------------------
# compute_key_prefix
# ---------------------------------------------------------------------------


class TestComputeKeyPrefix:
    def test_returns_16_char_hex(self) -> None:
        prefix = compute_key_prefix("my-secret-key")
        assert isinstance(prefix, str)
        assert len(prefix) == 16
        # Should be valid hex
        int(prefix, 16)

    def test_deterministic(self) -> None:
        p1 = compute_key_prefix("same-key")
        p2 = compute_key_prefix("same-key")
        assert p1 == p2

    def test_different_keys_different_prefixes(self) -> None:
        p1 = compute_key_prefix("key-alpha")
        p2 = compute_key_prefix("key-beta")
        assert p1 != p2


# ---------------------------------------------------------------------------
# authenticate_server
# ---------------------------------------------------------------------------


class TestAuthenticateServer:
    async def test_valid_bearer_key(self, db_session: AsyncSession) -> None:
        server = await _insert_server(db_session, api_key="valid-key-123")
        result = await authenticate_server("Bearer valid-key-123", db_session)
        assert result.id == server.id

    async def test_invalid_header_format_raises(self, db_session: AsyncSession) -> None:
        with pytest.raises(AuthenticationError, match="Invalid Authorization header"):
            await authenticate_server("Basic user:pass", db_session)

    async def test_no_bearer_prefix_raises(self, db_session: AsyncSession) -> None:
        with pytest.raises(AuthenticationError, match="Invalid Authorization header"):
            await authenticate_server("wrong-key", db_session)

    async def test_wrong_key_raises(self, db_session: AsyncSession) -> None:
        await _insert_server(db_session, api_key="correct-key")
        with pytest.raises(AuthenticationError, match="Invalid API key"):
            await authenticate_server("Bearer wrong-key", db_session)

    async def test_inactive_server_not_found(self, db_session: AsyncSession) -> None:
        await _insert_server(db_session, api_key="inactive-key", is_active=False)
        with pytest.raises(AuthenticationError, match="Invalid API key"):
            await authenticate_server("Bearer inactive-key", db_session)

    async def test_legacy_server_without_key_prefix(self, db_session: AsyncSession) -> None:
        """Servers migrated before key_prefix was added should still authenticate."""
        server = VJServer(
            id=uuid.uuid4(),
            name="Legacy Server",
            websocket_url="ws://localhost:9000",
            api_key_hash=hash_password("legacy-key"),
            key_prefix=None,  # No prefix — legacy
            jwt_secret="jwt-placeholder",
            is_active=True,
        )
        db_session.add(server)
        await db_session.flush()

        result = await authenticate_server("Bearer legacy-key", db_session)
        assert result.id == server.id

    async def test_multiple_servers_correct_match(self, db_session: AsyncSession) -> None:
        """When multiple servers exist, authenticate returns the correct one."""
        s1 = await _insert_server(db_session, api_key="key-server-1", name="Server 1")
        s2 = await _insert_server(db_session, api_key="key-server-2", name="Server 2")
        result = await authenticate_server("Bearer key-server-2", db_session)
        assert result.id == s2.id
        assert result.name == "Server 2"


# ---------------------------------------------------------------------------
# register_server
# ---------------------------------------------------------------------------


class TestRegisterServer:
    async def test_creates_server_with_hashed_key(self, db_session: AsyncSession) -> None:
        server = await register_server(
            name="New Server",
            websocket_url="ws://example.com:9000",
            api_key="my-new-api-key",
            jwt_secret="jwt-secret-xyz",
            session=db_session,
        )
        assert server.name == "New Server"
        assert server.websocket_url == "ws://example.com:9000"
        assert server.api_key_hash != "my-new-api-key"  # hashed, not plain
        assert server.api_key_hash.startswith("$2b$")  # bcrypt
        assert server.key_prefix == compute_key_prefix("my-new-api-key")
        assert server.jwt_secret == "jwt-secret-xyz"
        assert server.is_active is True

    async def test_registered_server_authenticates(self, db_session: AsyncSession) -> None:
        server = await register_server(
            name="Auth Test",
            websocket_url="ws://localhost:8080",
            api_key="register-then-auth-key",
            jwt_secret="jwt-test",
            session=db_session,
        )
        result = await authenticate_server("Bearer register-then-auth-key", db_session)
        assert result.id == server.id

    async def test_register_with_org_id(self, db_session: AsyncSession) -> None:
        org_id = uuid.uuid4()
        # We can't create a real org due to FK constraints in some DBs,
        # but for SQLite in tests this works since FK enforcement is off by default.
        server = await register_server(
            name="Org Server",
            websocket_url="ws://localhost:9000",
            api_key="org-key",
            jwt_secret="jwt-org",
            session=db_session,
            org_id=org_id,
        )
        assert server.org_id == org_id


# ---------------------------------------------------------------------------
# update_heartbeat
# ---------------------------------------------------------------------------


class TestUpdateHeartbeat:
    async def test_updates_heartbeat_timestamp(self, db_session: AsyncSession) -> None:
        server = await _insert_server(db_session)
        assert server.last_heartbeat is None

        ts = await update_heartbeat(server_id=server.id, session=db_session)
        assert isinstance(ts, datetime)
        assert ts.tzinfo is not None  # timezone-aware

    async def test_subsequent_heartbeats_advance_timestamp(self, db_session: AsyncSession) -> None:
        server = await _insert_server(db_session)
        ts1 = await update_heartbeat(server_id=server.id, session=db_session)
        ts2 = await update_heartbeat(server_id=server.id, session=db_session)
        assert ts2 >= ts1
