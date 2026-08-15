"""Connect-code resolution, DJ join, and disconnect business logic.

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

from app.models.db import DJSession, Show, VJServer
from app.services.code_generator import normalise_code
from app.services.exceptions import (
    ConnectCodeNotFoundError,
    OwnershipError,
    ServerOfflineError,
    ShowFullError,
)
from app.services.jwt_service import create_token

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Data transfer objects
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ResolveResult:
    """Data returned by :func:`resolve_connect_code`."""

    websocket_url: str
    show_name: str
    dj_count: int
    max_djs: int


@dataclass(frozen=True)
class JoinResult:
    """Data returned by :func:`join_connect_code`."""

    websocket_url: str
    token: str
    show_name: str
    dj_count: int
    dj_session_id: str


# ---------------------------------------------------------------------------
# resolve
# ---------------------------------------------------------------------------


async def resolve_connect_code(
    *,
    code: str,
    session: AsyncSession,
) -> ResolveResult:
    """Resolve a WORD-XXXX connect code to websocket metadata.

    Raises
    ------
    ConnectCodeNotFoundError
        If no active show uses this code.
    ServerOfflineError
        If the owning VJ server is inactive.
    """
    normalised = normalise_code(code)

    stmt = select(Show).where(Show.connect_code == normalised, Show.status == "active")
    result = await session.execute(stmt)
    show = result.scalar_one_or_none()

    if show is None:
        raise ConnectCodeNotFoundError("Connect code not found or expired")

    stmt_srv = select(VJServer).where(VJServer.id == show.server_id, VJServer.is_active.is_(True))
    result_srv = await session.execute(stmt_srv)
    server = result_srv.scalar_one_or_none()

    if server is None:
        raise ServerOfflineError("Server registered but currently offline")

    return ResolveResult(
        websocket_url=server.websocket_url,
        show_name=show.name,
        dj_count=show.current_djs,
        max_djs=show.max_djs,
    )


# ---------------------------------------------------------------------------
# join
# ---------------------------------------------------------------------------


async def join_connect_code(
    *,
    code: str,
    session: AsyncSession,
    jwt_secret_setting: str,
    jwt_expiry_minutes: int,
    client_ip: str,
    user_id: uuid.UUID | None = None,
) -> JoinResult:
    """Join a show via connect code: reserve a DJ slot, create a session, mint a JWT.

    Parameters
    ----------
    code:
        Raw connect code (will be normalised internally).
    session:
        Active SQLAlchemy async session.
    jwt_secret_setting:
        Not used directly — the JWT is signed with the *server's* ``jwt_secret``.
        Kept for future use / config forwarding.
    jwt_expiry_minutes:
        Token lifetime in minutes.
    client_ip:
        IP address of the connecting client.
    user_id:
        Optional authenticated user UUID (extracted from bearer token by the caller).

    Raises
    ------
    ConnectCodeNotFoundError
        If no active show uses this code.
    ServerOfflineError
        If the owning VJ server is inactive.
    ShowFullError
        If the show has reached its max DJ capacity.
    """
    normalised = normalise_code(code)

    # Find active show
    stmt = select(Show).where(Show.connect_code == normalised, Show.status == "active")
    result = await session.execute(stmt)
    show = result.scalar_one_or_none()

    if show is None:
        raise ConnectCodeNotFoundError("Connect code not found or expired")

    # Fetch owning server
    stmt_srv = select(VJServer).where(VJServer.id == show.server_id, VJServer.is_active.is_(True))
    result_srv = await session.execute(stmt_srv)
    server = result_srv.scalar_one_or_none()

    if server is None:
        raise ServerOfflineError("Server registered but currently offline")

    # Reserve a DJ slot atomically
    updated_count = await session.scalar(
        update(Show)
        .where(
            Show.id == show.id,
            Show.status == "active",
            Show.current_djs < Show.max_djs,
        )
        .values(current_djs=Show.current_djs + 1)
        .returning(Show.current_djs)
    )
    if updated_count is None:
        raise ShowFullError("Show is full — maximum DJ limit reached")

    # Create DJ session record
    dj_session_id = uuid.uuid4()
    dj_session = DJSession(
        id=dj_session_id,
        show_id=show.id,
        user_id=user_id,
        dj_name=f"DJ-{normalised}",
        ip_address=client_ip,
    )
    session.add(dj_session)
    await session.commit()

    # Mint JWT signed with the target server's secret
    token = create_token(
        dj_session_id=dj_session_id,
        show_id=show.id,
        server_id=server.id,
        jwt_secret=server.jwt_secret,
        expiry_minutes=jwt_expiry_minutes,
    )

    return JoinResult(
        websocket_url=server.websocket_url,
        token=token,
        show_name=show.name,
        dj_count=int(updated_count),
        dj_session_id=str(dj_session_id),
    )


# ---------------------------------------------------------------------------
# disconnect
# ---------------------------------------------------------------------------


async def disconnect_dj(
    *,
    dj_session_id: uuid.UUID,
    server_id: uuid.UUID,
    session: AsyncSession,
) -> bool:
    """Record a DJ disconnect and decrement the show's ``current_djs`` counter.

    Returns ``True`` if the session was found and disconnected, ``False`` if
    already disconnected or not found (idempotent).

    Raises
    ------
    OwnershipError
        If the DJ session belongs to a show not owned by *server_id*.
    """
    stmt = select(DJSession).where(
        DJSession.id == dj_session_id,
        DJSession.disconnected_at.is_(None),
    )
    dj_session_row = (await session.execute(stmt)).scalar_one_or_none()
    if dj_session_row is None:
        return False  # Already disconnected or not found — idempotent

    # Verify the session belongs to a show owned by the authenticated server
    show_stmt = select(Show).where(Show.id == dj_session_row.show_id)
    show_row = (await session.execute(show_stmt)).scalar_one_or_none()
    if show_row is None or show_row.server_id != server_id:
        raise OwnershipError("Session does not belong to this server")

    dj_session_row.disconnected_at = datetime.now(timezone.utc)

    # Decrement current_djs (floor at 0)
    await session.execute(
        update(Show)
        .where(Show.id == dj_session_row.show_id, Show.current_djs > 0)
        .values(current_djs=Show.current_djs - 1)
    )
    await session.commit()

    return True
