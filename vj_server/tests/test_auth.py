"""Tests for vj_server.auth password hashing and verification."""

import pytest

from vj_server.auth import generate_api_key, hash_password, verify_password

# ============================================================================
# hash_password + verify_password (bcrypt path)
# ============================================================================


class TestBcryptPath:
    def test_hash_and_verify_roundtrip(self):
        hashed = hash_password("s3cret", method="bcrypt")
        assert hashed.startswith("bcrypt:")
        assert verify_password("s3cret", hashed) is True

    def test_wrong_password_rejected(self):
        hashed = hash_password("correct-horse", method="bcrypt")
        assert verify_password("wrong-horse", hashed) is False

    def test_empty_password_roundtrip(self):
        hashed = hash_password("", method="bcrypt")
        assert verify_password("", hashed) is True
        assert verify_password("notempty", hashed) is False

    def test_long_password_bcrypt_raises(self):
        """bcrypt has a 72-byte limit; verify it raises rather than silently truncating."""
        long_pw = "x" * 200
        with pytest.raises(ValueError):
            hash_password(long_pw, method="bcrypt")

    def test_long_password_within_limit(self):
        pw = "x" * 72
        hashed = hash_password(pw, method="bcrypt")
        assert verify_password(pw, hashed) is True

    def test_unicode_password(self):
        pw = "p\u00e4ssw\u00f6rd\U0001f525"
        hashed = hash_password(pw, method="bcrypt")
        assert verify_password(pw, hashed) is True


# ============================================================================
# hash_password + verify_password (SHA256 path)
# ============================================================================


class TestSha256Path:
    def test_hash_and_verify_roundtrip(self):
        hashed = hash_password("s3cret", method="sha256")
        assert hashed.startswith("sha256:")
        assert verify_password("s3cret", hashed) is True

    def test_wrong_password_rejected(self):
        hashed = hash_password("correct-horse", method="sha256")
        assert verify_password("wrong-horse", hashed) is False

    def test_hash_format_has_salt(self):
        hashed = hash_password("test", method="sha256")
        parts = hashed.split(":")
        assert len(parts) == 3  # sha256:salt:hash
        assert len(parts[1]) == 32  # 16 bytes hex = 32 chars

    def test_empty_password_roundtrip(self):
        hashed = hash_password("", method="sha256")
        assert verify_password("", hashed) is True
        assert verify_password("notempty", hashed) is False

    def test_same_password_different_hashes(self):
        """Each hash should have a unique salt."""
        h1 = hash_password("same", method="sha256")
        h2 = hash_password("same", method="sha256")
        assert h1 != h2
        # But both should verify
        assert verify_password("same", h1) is True
        assert verify_password("same", h2) is True


# ============================================================================
# verify_password edge cases
# ============================================================================


class TestVerifyEdgeCases:
    def test_empty_hash_returns_false(self):
        assert verify_password("anything", "") is False

    def test_none_hash_returns_false(self):
        assert verify_password("anything", None) is False

    def test_plaintext_hash_rejected(self):
        """Plaintext (no prefix) should be rejected."""
        assert verify_password("mypass", "mypass") is False

    def test_unknown_prefix_rejected(self):
        assert verify_password("test", "argon2:somehash") is False

    def test_legacy_unsalted_sha256_rejected(self):
        """Legacy format sha256:hexhash (no salt) should be rejected."""
        import hashlib

        legacy_hash = "sha256:" + hashlib.sha256(b"test").hexdigest()
        assert verify_password("test", legacy_hash) is False

    def test_malformed_bcrypt_hash_returns_false(self):
        assert verify_password("test", "bcrypt:not-a-real-hash") is False


# ============================================================================
# generate_api_key
# ============================================================================


class TestGenerateApiKey:
    def test_returns_string(self):
        key = generate_api_key()
        assert isinstance(key, str)
        assert len(key) > 20  # URL-safe base64 of 32 bytes is ~43 chars

    def test_keys_are_unique(self):
        keys = {generate_api_key() for _ in range(10)}
        assert len(keys) == 10

    def test_unknown_method_raises(self):
        with pytest.raises(ValueError, match="Unknown hashing method"):
            hash_password("test", method="argon2")
