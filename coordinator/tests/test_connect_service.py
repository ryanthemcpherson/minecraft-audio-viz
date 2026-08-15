"""Unit tests for app.services.connect_service.

Tests connect-code resolution, DJ join, and disconnect business logic
using the async test database.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import DJSession, Show, VJServer
from app.services.connect_service import (
    JoinResult,
    ResolveResult,
    disconnect_dj,
    join_connect_code,
    resolve_connect_code,
)
from app.services.exceptions import (
    ConnectCodeNotFoundError,
    OwnershipError,
    ServerOfflineError,
    ShowFullError,
)
from app.services.password import hash_password


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _create_server(
    session: AsyncSession,
    *,
    name: str = "Test VJ",
    is_active: bool = True,
    websocket_url: str = "ws://localhost:9000",
) -> VJServer:
    server = VJServer(
        id=uuid.uuid4(),
        name=name,
        websocket_url=websocket_url,
        api_key_hash=hash_password("test-key"),
        jwt_secret="test-jwt-secret-placeholder",
        is_active=is_active,
    )
    session.add(server)
    await session.flush()
    return server


async def _create_show(
    session: AsyncSession,
    server: VJServer,
    *,
    connect_code: str = "BASS-1234",
    status: str = "active",
    max_djs: int = 8,
    current_djs: int = 0,
    name: str = "Test Show",
) -> Show:
    show = Show(
        id=uuid.uuid4(),
        server_id=server.id,
        name=name,
        connect_code=connect_code,
        status=status,
        max_djs=max_djs,
        current_djs=current_djs,
    )
    session.add(show)
    await session.flush()
    return show


# ---------------------------------------------------------------------------
# resolve_connect_code
# ---------------------------------------------------------------------------


class TestResolveConnectCode:
    async def test_resolves_active_show(self, db_session: AsyncSession) -> None:
        server = await _create_server(db_session)
        show = await _create_show(db_session, server, connect_code="BEAT-ABCD")

        result = await resolve_connect_code(code="beat-abcd", session=db_session)

        assert isinstance(result, ResolveResult)
        assert result.websocket_url == "ws://localhost:9000"
        assert result.show_name == "Test Show"
        assert result.dj_count == 0
        assert result.max_djs == 8

    async def test_raises_when_code_not_found(self, db_session: AsyncSession) -> None:
        with pytest.raises(ConnectCodeNotFoundError):
            await resolve_connect_code(code="NOPE-9999", session=db_session)

    async def test_raises_when_show_ended(self, db_session: AsyncSession) -> None:
        server = await _create_server(db_session)
        await _create_show(
            db_session, server, connect_code="FADE-0001", status="ended"
        )

        with pytest.raises(ConnectCodeNotFoundError):
            await resolve_connect_code(code="FADE-0001", session=db_session)

    async def test_raises_when_server_offline(self, db_session: AsyncSession) -> None:
        server = await _create_server(db_session, is_active=False)
        await _create_show(db_session, server, connect_code="DROP-1111")

        with pytest.raises(ServerOfflineError):
            await resolve_connect_code(code="DROP-1111", session=db_session)

    async def test_normalises_input_case(self, db_session: AsyncSession) -> None:
        server = await _create_server(db_session)
        await _create_show(db_session, server, connect_code="VIBE-WXYZ")

        # Mixed case input should still resolve
        result = await resolve_connect_code(code="  vibe-wxyz  ", session=db_session)
        assert result.show_name == "Test Show"


# ---------------------------------------------------------------------------
# join_connect_code
# ---------------------------------------------------------------------------


class TestJoinConnectCode:
    async def test_join_creates_session_and_increments_djs(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        show = await _create_show(db_session, server, connect_code="KICK-JOIN")

        result = await join_connect_code(
            code="KICK-JOIN",
            session=db_session,
            jwt_secret_setting="unused",
            jwt_expiry_minutes=15,
            client_ip="10.0.0.1",
        )

        assert isinstance(result, JoinResult)
        assert result.websocket_url == "ws://localhost:9000"
        assert result.show_name == "Test Show"
        assert result.dj_count == 1
        assert result.token  # JWT was minted
        assert result.dj_session_id  # UUID string

        # Verify DJ session was persisted
        stmt = select(DJSession).where(DJSession.id == uuid.UUID(result.dj_session_id))
        dj_row = (await db_session.execute(stmt)).scalar_one()
        assert dj_row.ip_address == "10.0.0.1"
        assert dj_row.show_id == show.id

    async def test_join_with_user_id(self, db_session: AsyncSession) -> None:
        server = await _create_server(db_session)
        await _create_show(db_session, server, connect_code="LOOP-USER")
        user_id = uuid.uuid4()

        result = await join_connect_code(
            code="LOOP-USER",
            session=db_session,
            jwt_secret_setting="unused",
            jwt_expiry_minutes=15,
            client_ip="10.0.0.2",
            user_id=user_id,
        )

        stmt = select(DJSession).where(DJSession.id == uuid.UUID(result.dj_session_id))
        dj_row = (await db_session.execute(stmt)).scalar_one()
        assert dj_row.user_id == user_id

    async def test_join_raises_when_code_not_found(
        self, db_session: AsyncSession
    ) -> None:
        with pytest.raises(ConnectCodeNotFoundError):
            await join_connect_code(
                code="NOPE-0000",
                session=db_session,
                jwt_secret_setting="unused",
                jwt_expiry_minutes=15,
                client_ip="10.0.0.1",
            )

    async def test_join_raises_when_server_offline(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session, is_active=False)
        await _create_show(db_session, server, connect_code="ECHO-DOWN")

        with pytest.raises(ServerOfflineError):
            await join_connect_code(
                code="ECHO-DOWN",
                session=db_session,
                jwt_secret_setting="unused",
                jwt_expiry_minutes=15,
                client_ip="10.0.0.1",
            )

    async def test_join_raises_when_show_full(self, db_session: AsyncSession) -> None:
        server = await _create_server(db_session)
        await _create_show(
            db_session, server, connect_code="FULL-SHOW", max_djs=2, current_djs=2
        )

        with pytest.raises(ShowFullError):
            await join_connect_code(
                code="FULL-SHOW",
                session=db_session,
                jwt_secret_setting="unused",
                jwt_expiry_minutes=15,
                client_ip="10.0.0.1",
            )

    async def test_join_increments_counter_atomically(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        show = await _create_show(
            db_session, server, connect_code="SYNC-ATOM", max_djs=4, current_djs=1
        )

        result = await join_connect_code(
            code="SYNC-ATOM",
            session=db_session,
            jwt_secret_setting="unused",
            jwt_expiry_minutes=15,
            client_ip="10.0.0.1",
        )

        assert result.dj_count == 2  # was 1, now 2

        # Refresh the show to verify DB state
        await db_session.refresh(show)
        assert show.current_djs == 2


# ---------------------------------------------------------------------------
# disconnect_dj
# ---------------------------------------------------------------------------


class TestDisconnectDj:
    async def test_disconnect_sets_timestamp_and_decrements(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        show = await _create_show(
            db_session, server, connect_code="DISC-TEST", current_djs=1
        )

        dj = DJSession(
            id=uuid.uuid4(),
            show_id=show.id,
            dj_name="DJ-Test",
            ip_address="10.0.0.1",
        )
        db_session.add(dj)
        await db_session.flush()

        result = await disconnect_dj(
            dj_session_id=dj.id,
            server_id=server.id,
            session=db_session,
        )

        assert result is True

        await db_session.refresh(dj)
        assert dj.disconnected_at is not None

        await db_session.refresh(show)
        assert show.current_djs == 0

    async def test_disconnect_already_disconnected_returns_false(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        show = await _create_show(
            db_session, server, connect_code="DISC-IDEM"
        )

        # Session that doesn't exist
        result = await disconnect_dj(
            dj_session_id=uuid.uuid4(),
            server_id=server.id,
            session=db_session,
        )
        assert result is False

    async def test_disconnect_wrong_server_raises_ownership_error(
        self, db_session: AsyncSession
    ) -> None:
        server_a = await _create_server(db_session, name="Server A")
        server_b = await _create_server(db_session, name="Server B")
        show = await _create_show(
            db_session, server_a, connect_code="DISC-OWNR", current_djs=1
        )

        dj = DJSession(
            id=uuid.uuid4(),
            show_id=show.id,
            dj_name="DJ-Wrong",
            ip_address="10.0.0.1",
        )
        db_session.add(dj)
        await db_session.flush()

        with pytest.raises(OwnershipError, match="does not belong"):
            await disconnect_dj(
                dj_session_id=dj.id,
                server_id=server_b.id,
                session=db_session,
            )

    async def test_disconnect_idempotent_second_call(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        show = await _create_show(
            db_session, server, connect_code="DISC-TWIC", current_djs=1
        )

        dj = DJSession(
            id=uuid.uuid4(),
            show_id=show.id,
            dj_name="DJ-Twice",
            ip_address="10.0.0.1",
        )
        db_session.add(dj)
        await db_session.flush()

        # First disconnect
        first = await disconnect_dj(
            dj_session_id=dj.id, server_id=server.id, session=db_session
        )
        assert first is True

        # Second disconnect — already done, returns False
        second = await disconnect_dj(
            dj_session_id=dj.id, server_id=server.id, session=db_session
        )
        assert second is False
