"""Tests for JWT creation and validation."""

from __future__ import annotations

import time
import uuid

import jwt as pyjwt
import pytest
from app.services.jwt_service import (
    ALGORITHM,
    ISSUER,
    create_token,
    verify_token,
)


@pytest.fixture()
def server_id() -> uuid.UUID:
    return uuid.uuid4()


@pytest.fixture()
def jwt_secret() -> str:
    return "test-secret-value-for-unit-tests"


@pytest.fixture()
def show_id() -> uuid.UUID:
    return uuid.uuid4()


@pytest.fixture()
def dj_session_id() -> uuid.UUID:
    return uuid.uuid4()


class TestCreateToken:
    """Tests for ``create_token()``."""

    def test_returns_valid_jwt(
        self,
        dj_session_id: uuid.UUID,
        show_id: uuid.UUID,
        server_id: uuid.UUID,
        jwt_secret: str,
    ) -> None:
        token = create_token(
            dj_session_id=dj_session_id,
            show_id=show_id,
            server_id=server_id,
            jwt_secret=jwt_secret,
        )
        assert isinstance(token, str)
        # Should be decodable
        decoded = pyjwt.decode(token, jwt_secret, algorithms=[ALGORITHM])
        assert decoded["sub"] == str(dj_session_id)
        assert decoded["iss"] == ISSUER

    def test_payload_fields(
        self,
        dj_session_id: uuid.UUID,
        show_id: uuid.UUID,
        server_id: uuid.UUID,
        jwt_secret: str,
    ) -> None:
        token = create_token(
            dj_session_id=dj_session_id,
            show_id=show_id,
            server_id=server_id,
            jwt_secret=jwt_secret,
            expiry_minutes=10,
        )
        decoded = pyjwt.decode(token, jwt_secret, algorithms=[ALGORITHM])

        assert decoded["sub"] == str(dj_session_id)
        assert decoded["show_id"] == str(show_id)
        assert decoded["server_id"] == str(server_id)
        assert decoded["iss"] == ISSUER
        assert decoded["permissions"] == ["stream"]
        assert decoded["exp"] - decoded["iat"] == 600  # 10 minutes

    def test_custom_expiry(
        self,
        dj_session_id: uuid.UUID,
        show_id: uuid.UUID,
        server_id: uuid.UUID,
        jwt_secret: str,
    ) -> None:
        token = create_token(
            dj_session_id=dj_session_id,
            show_id=show_id,
            server_id=server_id,
            jwt_secret=jwt_secret,
            expiry_minutes=30,
        )
        decoded = pyjwt.decode(token, jwt_secret, algorithms=[ALGORITHM])
        assert decoded["exp"] - decoded["iat"] == 1800


class TestVerifyToken:
    """Tests for ``verify_token()``."""

    def test_valid_token(
        self,
        dj_session_id: uuid.UUID,
        show_id: uuid.UUID,
        server_id: uuid.UUID,
        jwt_secret: str,
    ) -> None:
        token = create_token(
            dj_session_id=dj_session_id,
            show_id=show_id,
            server_id=server_id,
            jwt_secret=jwt_secret,
        )
        payload = verify_token(token, jwt_secret=jwt_secret, server_id=server_id)
        assert payload.sub == str(dj_session_id)
        assert payload.show_id == str(show_id)
        assert payload.server_id == str(server_id)
        assert payload.iss == ISSUER
        assert payload.permissions == ["stream"]

    def test_wrong_secret_raises(
        self,
        dj_session_id: uuid.UUID,
        show_id: uuid.UUID,
        server_id: uuid.UUID,
        jwt_secret: str,
    ) -> None:
        token = create_token(
            dj_session_id=dj_session_id,
            show_id=show_id,
            server_id=server_id,
            jwt_secret=jwt_secret,
        )
        with pytest.raises(pyjwt.InvalidSignatureError):
            verify_token(token, jwt_secret="wrong-secret", server_id=server_id)  # nosec B106

    def test_wrong_server_id_raises(
        self,
        dj_session_id: uuid.UUID,
        show_id: uuid.UUID,
        server_id: uuid.UUID,
        jwt_secret: str,
    ) -> None:
        token = create_token(
            dj_session_id=dj_session_id,
            show_id=show_id,
            server_id=server_id,
            jwt_secret=jwt_secret,
        )
        wrong_server = uuid.uuid4()
        with pytest.raises(pyjwt.InvalidTokenError, match="server_id mismatch"):
            verify_token(token, jwt_secret=jwt_secret, server_id=wrong_server)

    def test_expired_token_raises(
        self,
        dj_session_id: uuid.UUID,
        show_id: uuid.UUID,
        server_id: uuid.UUID,
        jwt_secret: str,
    ) -> None:
        # Manually craft an expired token
        now = int(time.time())
        payload = {
            "sub": str(dj_session_id),
            "show_id": str(show_id),
            "server_id": str(server_id),
            "iss": ISSUER,
            "iat": now - 3600,
            "exp": now - 1,  # already expired
            "permissions": ["stream"],
        }
        token = pyjwt.encode(payload, jwt_secret, algorithm=ALGORITHM)
        with pytest.raises(pyjwt.ExpiredSignatureError):
            verify_token(token, jwt_secret=jwt_secret, server_id=server_id)
