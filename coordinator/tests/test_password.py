"""Tests for the password hashing service."""

from __future__ import annotations

from app.services.password import hash_password, verify_password


class TestHashPassword:
    def test_returns_string(self) -> None:
        result = hash_password("secret123")
        assert isinstance(result, str)

    def test_different_hashes_for_same_input(self) -> None:
        h1 = hash_password("secret123")
        h2 = hash_password("secret123")
        assert h1 != h2  # bcrypt salts differ

    def test_hash_starts_with_bcrypt_prefix(self) -> None:
        result = hash_password("test")
        assert result.startswith("$2b$")


class TestVerifyPassword:
    def test_correct_password(self) -> None:
        hashed = hash_password("mypassword")
        assert verify_password("mypassword", hashed) is True

    def test_wrong_password(self) -> None:
        hashed = hash_password("mypassword")
        assert verify_password("wrongpassword", hashed) is False
