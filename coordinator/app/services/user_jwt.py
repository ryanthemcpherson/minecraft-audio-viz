"""User-session JWT creation and verification.

These tokens are distinct from DJ-session JWTs (``jwt_service.py``):

* Signed with a **global** secret (``MCAV_USER_JWT_SECRET``), not a per-server
  secret.
* Contain ``token_type="user_session"`` so they cannot be confused with
  DJ-session tokens (which carry ``permissions`` and ``server_id`` instead).
* Subject (``sub``) is the **user_id**, not a ``dj_session_id``.
"""

from __future__ import annotations

import secrets
import time
import uuid
from dataclasses import dataclass

import jwt as pyjwt

ISSUER = "mcav-coordinator"
ALGORITHM = "HS256"
TOKEN_TYPE = "user_session"  # nosec B105


@dataclass(frozen=True)
class UserTokenPayload:
    """Decoded contents of a user-session JWT."""

    sub: str  # user_id
    token_type: str  # always "user_session"
    iss: str
    exp: int
    iat: int


def create_user_token(
    *,
    user_id: str | uuid.UUID,
    jwt_secret: str,
    expiry_minutes: int = 60,
) -> str:
    """Mint a user-session JWT."""
    now = int(time.time())
    payload = {
        "sub": str(user_id),
        "token_type": TOKEN_TYPE,
        "iss": ISSUER,
        "iat": now,
        "exp": now + expiry_minutes * 60,
    }
    return pyjwt.encode(payload, jwt_secret, algorithm=ALGORITHM)


def verify_user_token(token: str, *, jwt_secret: str) -> UserTokenPayload:
    """Decode and verify a user-session JWT.

    Raises ``pyjwt.InvalidTokenError`` (or a subclass) on failure.
    """
    decoded = pyjwt.decode(token, jwt_secret, algorithms=[ALGORITHM], issuer=ISSUER)

    if decoded.get("token_type") != TOKEN_TYPE:
        raise pyjwt.InvalidTokenError("Not a user-session token")

    return UserTokenPayload(
        sub=decoded["sub"],
        token_type=decoded["token_type"],
        iss=decoded["iss"],
        exp=decoded["exp"],
        iat=decoded["iat"],
    )


def create_refresh_token_value() -> str:
    """Generate a cryptographically random refresh token value."""
    return secrets.token_urlsafe(48)
