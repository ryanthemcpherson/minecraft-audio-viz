"""Tests for the user-session JWT service."""

from __future__ import annotations

import time
import uuid

import jwt as pyjwt
import pytest

from app.services.user_jwt import (
    ISSUER,
    TOKEN_TYPE,
    create_refresh_token_value,
    create_user_token,
    verify_user_token,
)

SECRET = "test-secret-key"


class TestCreateUserToken:
    def test_returns_string(self) -> None:
        token = create_user_token(user_id=uuid.uuid4(), jwt_secret=SECRET)
        assert isinstance(token, str)

    def test_payload_fields(self) -> None:
        uid = uuid.uuid4()
        token = create_user_token(user_id=uid, jwt_secret=SECRET)
        decoded = pyjwt.decode(token, SECRET, algorithms=["HS256"])
        assert decoded["sub"] == str(uid)
        assert decoded["token_type"] == TOKEN_TYPE
        assert decoded["iss"] == ISSUER
        assert "iat" in decoded
        assert "exp" in decoded

    def test_custom_expiry(self) -> None:
        token = create_user_token(
            user_id=uuid.uuid4(), jwt_secret=SECRET, expiry_minutes=5
        )
        decoded = pyjwt.decode(token, SECRET, algorithms=["HS256"])
        assert decoded["exp"] - decoded["iat"] == 5 * 60


class TestVerifyUserToken:
    def test_valid_token(self) -> None:
        uid = uuid.uuid4()
        token = create_user_token(user_id=uid, jwt_secret=SECRET)
        payload = verify_user_token(token, jwt_secret=SECRET)
        assert payload.sub == str(uid)
        assert payload.token_type == TOKEN_TYPE

    def test_wrong_secret_raises(self) -> None:
        token = create_user_token(user_id=uuid.uuid4(), jwt_secret=SECRET)
        with pytest.raises(pyjwt.InvalidTokenError):
            verify_user_token(token, jwt_secret="wrong-secret")

    def test_expired_token_raises(self) -> None:
        token = create_user_token(
            user_id=uuid.uuid4(), jwt_secret=SECRET, expiry_minutes=-1
        )
        with pytest.raises(pyjwt.ExpiredSignatureError):
            verify_user_token(token, jwt_secret=SECRET)

    def test_wrong_token_type_raises(self) -> None:
        """A DJ-session token should be rejected by verify_user_token."""
        payload = {
            "sub": str(uuid.uuid4()),
            "server_id": str(uuid.uuid4()),
            "show_id": str(uuid.uuid4()),
            "iss": ISSUER,
            "iat": int(time.time()),
            "exp": int(time.time()) + 3600,
            "permissions": ["stream"],
        }
        dj_token = pyjwt.encode(payload, SECRET, algorithm="HS256")
        with pytest.raises(pyjwt.InvalidTokenError, match="Not a user-session token"):
            verify_user_token(dj_token, jwt_secret=SECRET)


class TestCreateRefreshTokenValue:
    def test_returns_string(self) -> None:
        value = create_refresh_token_value()
        assert isinstance(value, str)
        assert len(value) > 32

    def test_unique_each_call(self) -> None:
        a = create_refresh_token_value()
        b = create_refresh_token_value()
        assert a != b
