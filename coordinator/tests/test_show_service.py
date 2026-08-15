"""Unit tests for app.services.show_service.

Tests show creation, ending, and retrieval business logic using the
async test database.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import DJSession, Show, VJServer
from app.services.exceptions import (
    OwnershipError,
    ShowAlreadyEndedError,
    ShowNotFoundError,
)
from app.services.password import hash_password
from app.services.show_service import (
    CreateShowResult,
    EndShowResult,
    ShowDetail,
    create_show,
    end_show,
    get_show,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _create_server(
    session: AsyncSession,
    *,
    name: str = "Test VJ",
) -> VJServer:
    server = VJServer(
        id=uuid.uuid4(),
        name=name,
        websocket_url="ws://localhost:9000",
        api_key_hash=hash_password("test-key"),
        jwt_secret="test-jwt-secret-placeholder",
        is_active=True,
    )
    session.add(server)
    await session.flush()
    return server


# ---------------------------------------------------------------------------
# create_show
# ---------------------------------------------------------------------------


class TestCreateShow:
    async def test_creates_show_with_connect_code(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)

        result = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="Friday Night",
            max_djs=4,
            session=db_session,
        )

        assert isinstance(result, CreateShowResult)
        assert result.name == "Friday Night"
        assert result.server_id == server.id
        assert result.connect_code  # Non-empty WORD-XXXX code
        assert "-" in result.connect_code  # WORD-XXXX format
        assert result.show_id is not None
        assert result.created_at is not None

    async def test_ownership_error_when_ids_mismatch(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)

        with pytest.raises(OwnershipError, match="does not own"):
            await create_show(
                server_id=server.id,
                requesting_server_id=uuid.uuid4(),  # different server
                name="Bad Show",
                max_djs=4,
                session=db_session,
            )

    async def test_show_persisted_in_db(self, db_session: AsyncSession) -> None:
        server = await _create_server(db_session)

        result = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="DB Check",
            max_djs=2,
            session=db_session,
        )

        stmt = select(Show).where(Show.id == result.show_id)
        show = (await db_session.execute(stmt)).scalar_one()
        assert show.name == "DB Check"
        assert show.max_djs == 2
        assert show.current_djs == 0
        assert show.status == "active"

    async def test_multiple_shows_get_unique_codes(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)

        r1 = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="Show 1",
            max_djs=4,
            session=db_session,
        )
        r2 = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="Show 2",
            max_djs=4,
            session=db_session,
        )

        assert r1.connect_code != r2.connect_code


# ---------------------------------------------------------------------------
# end_show
# ---------------------------------------------------------------------------


class TestEndShow:
    async def test_ends_active_show(self, db_session: AsyncSession) -> None:
        server = await _create_server(db_session)
        created = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="To End",
            max_djs=4,
            session=db_session,
        )

        result = await end_show(
            show_id=created.show_id,
            requesting_server_id=server.id,
            session=db_session,
        )

        assert isinstance(result, EndShowResult)
        assert result.status == "ended"
        assert result.ended_at is not None
        assert result.show_id == created.show_id

    async def test_end_clears_connect_code_and_resets_djs(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        created = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="Clear Code",
            max_djs=4,
            session=db_session,
        )

        await end_show(
            show_id=created.show_id,
            requesting_server_id=server.id,
            session=db_session,
        )

        # Verify DB state
        stmt = select(Show).where(Show.id == created.show_id)
        show = (await db_session.execute(stmt)).scalar_one()
        assert show.connect_code is None
        assert show.current_djs == 0
        assert show.status == "ended"

    async def test_end_disconnects_active_dj_sessions(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        created = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="With DJs",
            max_djs=4,
            session=db_session,
        )

        # Add an active DJ session
        dj = DJSession(
            id=uuid.uuid4(),
            show_id=created.show_id,
            dj_name="DJ-Active",
            ip_address="10.0.0.1",
        )
        db_session.add(dj)
        await db_session.flush()

        await end_show(
            show_id=created.show_id,
            requesting_server_id=server.id,
            session=db_session,
        )

        await db_session.refresh(dj)
        assert dj.disconnected_at is not None

    async def test_end_nonexistent_show_raises(
        self, db_session: AsyncSession
    ) -> None:
        with pytest.raises(ShowNotFoundError):
            await end_show(
                show_id=uuid.uuid4(),
                requesting_server_id=uuid.uuid4(),
                session=db_session,
            )

    async def test_end_wrong_server_raises_ownership_error(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        created = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="Wrong Owner",
            max_djs=4,
            session=db_session,
        )

        with pytest.raises(OwnershipError, match="does not own"):
            await end_show(
                show_id=created.show_id,
                requesting_server_id=uuid.uuid4(),
                session=db_session,
            )

    async def test_end_already_ended_show_raises(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        created = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="Double End",
            max_djs=4,
            session=db_session,
        )

        await end_show(
            show_id=created.show_id,
            requesting_server_id=server.id,
            session=db_session,
        )

        with pytest.raises(ShowAlreadyEndedError):
            await end_show(
                show_id=created.show_id,
                requesting_server_id=server.id,
                session=db_session,
            )


# ---------------------------------------------------------------------------
# get_show
# ---------------------------------------------------------------------------


class TestGetShow:
    async def test_returns_show_detail(self, db_session: AsyncSession) -> None:
        server = await _create_server(db_session)
        created = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="Detail Show",
            max_djs=6,
            session=db_session,
        )

        result = await get_show(
            show_id=created.show_id,
            requesting_server_id=server.id,
            session=db_session,
        )

        assert isinstance(result, ShowDetail)
        assert result.show_id == created.show_id
        assert result.name == "Detail Show"
        assert result.max_djs == 6
        assert result.current_djs == 0
        assert result.status == "active"
        assert result.connect_code is not None
        assert result.ended_at is None

    async def test_get_nonexistent_show_raises(
        self, db_session: AsyncSession
    ) -> None:
        with pytest.raises(ShowNotFoundError):
            await get_show(
                show_id=uuid.uuid4(),
                requesting_server_id=uuid.uuid4(),
                session=db_session,
            )

    async def test_get_wrong_server_raises_ownership_error(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        created = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="Wrong Get",
            max_djs=4,
            session=db_session,
        )

        with pytest.raises(OwnershipError, match="does not own"):
            await get_show(
                show_id=created.show_id,
                requesting_server_id=uuid.uuid4(),
                session=db_session,
            )

    async def test_get_ended_show_still_works(
        self, db_session: AsyncSession
    ) -> None:
        server = await _create_server(db_session)
        created = await create_show(
            server_id=server.id,
            requesting_server_id=server.id,
            name="Ended Get",
            max_djs=4,
            session=db_session,
        )

        await end_show(
            show_id=created.show_id,
            requesting_server_id=server.id,
            session=db_session,
        )

        result = await get_show(
            show_id=created.show_id,
            requesting_server_id=server.id,
            session=db_session,
        )

        assert result.status == "ended"
        assert result.connect_code is None
        assert result.ended_at is not None
