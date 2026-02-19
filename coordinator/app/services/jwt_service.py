"""JWT creation and validation for coordinator-minted tokens."""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass

import jwt as pyjwt

ISSUER = "mcav-coordinator"
ALGORITHM = "HS256"


@dataclass(frozen=True)
class TokenPayload:
    """Decoded contents of a coordinator JWT."""

    sub: str
    show_id: str
    server_id: str
    iss: str
    exp: int
    iat: int
    permissions: list[str]


def create_token(
    *,
    dj_session_id: str | uuid.UUID,
    show_id: str | uuid.UUID,
    server_id: str | uuid.UUID,
    jwt_secret: str,
    expiry_minutes: int = 15,
) -> str:
    """Mint a short-lived JWT for a DJ session.

    The token is signed with the target VJ server's ``jwt_secret`` so only
    that server can verify it.
    """
    now = int(time.time())
    payload = {
        "sub": str(dj_session_id),
        "show_id": str(show_id),
        "server_id": str(server_id),
        "iss": ISSUER,
        "iat": now,
        "exp": now + expiry_minutes * 60,
        "permissions": ["stream"],
    }
    return pyjwt.encode(payload, jwt_secret, algorithm=ALGORITHM)


def verify_token(token: str, *, jwt_secret: str, server_id: str | uuid.UUID) -> TokenPayload:
    """Decode and verify a coordinator-minted JWT.

    Raises ``pyjwt.InvalidTokenError`` (or a subclass) on failure.
    """
    decoded = pyjwt.decode(
        token,
        jwt_secret,
        algorithms=[ALGORITHM],
        issuer=ISSUER,
        # We verify server_id manually rather than using the 'audience' claim
        # because our payload uses 'server_id', not 'aud'.
    )

    if decoded.get("server_id") != str(server_id):
        raise pyjwt.InvalidTokenError("server_id mismatch")

    return TokenPayload(
        sub=decoded["sub"],
        show_id=decoded["show_id"],
        server_id=decoded["server_id"],
        iss=decoded["iss"],
        exp=decoded["exp"],
        iat=decoded["iat"],
        permissions=decoded.get("permissions", []),
    )
