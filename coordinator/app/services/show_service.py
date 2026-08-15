"""Show creation, retrieval, and ending business logic.

Framework-agnostic: raises domain exceptions from ``app.services.exceptions``
rather than ``HTTPException``.  The router layer catches these and converts
them into the appropriate HTTP responses.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import DJSession, Show
from app.services.code_generator import generate_unique_code
from app.services.exceptions import (
    OwnershipError,
    ShowAlreadyEndedError,
    ShowNotFoundError,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Data transfer objects
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class CreateShowResult:
    """Data returned by :func:`create_show`."""

    show_id: uuid.UUID
    connect_code: str
    name: str
    server_id: uuid.UUID
    created_at: datetime


@dataclass(frozen=True)
class EndShowResult:
    """Data returned by :func:`end_show`."""

    show_id: uuid.UUID
    status: str
    ended_at: datetime


@dataclass(frozen=True)
class ShowDetail:
    """Data returned by :func:`get_show`."""

    show_id: uuid.UUID
    name: str
    server_id: uuid.UUID
    status: str
    connect_code: str | None
    max_djs: int
    current_djs: int
    created_at: datetime
    ended_at: datetime | None


# ---------------------------------------------------------------------------
# create
# ---------------------------------------------------------------------------


async def create_show(
    *,
    server_id: uuid.UUID,
    requesting_server_id: uuid.UUID,
    name: str,
    max_djs: int,
    session: AsyncSession,
) -> CreateShowResult:
    """Create a new show for the given server.

    Parameters
    ----------
    server_id:
        The server ID from the request body.
    requesting_server_id:
        The authenticated server's ID (from the API key).
    name:
        Show display name.
    max_djs:
        Maximum number of DJs allowed.
    session:
        Active SQLAlchemy async session.

    Raises
    ------
    OwnershipError
        If *requesting_server_id* does not match *server_id*.
    """
    if requesting_server_id != server_id:
        raise OwnershipError("API key does not own this server_id")

    connect_code = await generate_unique_code(session)

    show = Show(
        id=uuid.uuid4(),
        server_id=server_id,
        name=name,
        connect_code=connect_code,
        max_djs=max_djs,
    )
    session.add(show)
    await session.commit()
    await session.refresh(show)

    logger.info("Show created: id=%s name=%s code=%s", show.id, show.name, connect_code)

    return CreateShowResult(
        show_id=show.id,
        connect_code=show.connect_code,
        name=show.name,
        server_id=show.server_id,
        created_at=show.created_at,
    )


# ---------------------------------------------------------------------------
# end
# ---------------------------------------------------------------------------


async def end_show(
    *,
    show_id: uuid.UUID,
    requesting_server_id: uuid.UUID,
    session: AsyncSession,
) -> EndShowResult:
    """End an active show, clearing its connect code and disconnecting DJs.

    Raises
    ------
    ShowNotFoundError
        If *show_id* does not exist.
    OwnershipError
        If the authenticated server does not own this show.
    ShowAlreadyEndedError
        If the show has already been ended.
    """
    stmt = select(Show).where(Show.id == show_id)
    result = await session.execute(stmt)
    show = result.scalar_one_or_none()

    if show is None:
        raise ShowNotFoundError("Show not found")

    if show.server_id != requesting_server_id:
        raise OwnershipError("API key does not own this show")

    if show.status == "ended":
        raise ShowAlreadyEndedError("Show already ended")

    now = datetime.now(timezone.utc)
    stmt_update = (
        update(Show)
        .where(Show.id == show_id)
        .values(status="ended", ended_at=now, connect_code=None, current_djs=0)
    )
    await session.execute(stmt_update)

    # Disconnect all active DJ sessions in this show
    await session.execute(
        update(DJSession)
        .where(DJSession.show_id == show_id, DJSession.disconnected_at.is_(None))
        .values(disconnected_at=now)
    )
    await session.commit()

    logger.info("Show ended: id=%s", show_id)

    return EndShowResult(show_id=show_id, status="ended", ended_at=now)


# ---------------------------------------------------------------------------
# get
# ---------------------------------------------------------------------------


async def get_show(
    *,
    show_id: uuid.UUID,
    requesting_server_id: uuid.UUID,
    session: AsyncSession,
) -> ShowDetail:
    """Return full details for a show owned by the authenticated server.

    Raises
    ------
    ShowNotFoundError
        If *show_id* does not exist.
    OwnershipError
        If the authenticated server does not own this show.
    """
    stmt = select(Show).where(Show.id == show_id)
    result = await session.execute(stmt)
    show = result.scalar_one_or_none()

    if show is None:
        raise ShowNotFoundError("Show not found")

    if show.server_id != requesting_server_id:
        raise OwnershipError("API key does not own this show")

    return ShowDetail(
        show_id=show.id,
        name=show.name,
        server_id=show.server_id,
        status=show.status,
        connect_code=show.connect_code,
        max_djs=show.max_djs,
        current_djs=show.current_djs,
        created_at=show.created_at,
        ended_at=show.ended_at,
    )
