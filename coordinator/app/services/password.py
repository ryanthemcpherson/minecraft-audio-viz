"""Password hashing and verification using bcrypt."""

from __future__ import annotations

import bcrypt


def hash_password(plain: str) -> str:
    """Bcrypt-hash a password for storage."""
    return bcrypt.hashpw(plain.encode(), bcrypt.gensalt()).decode()


def verify_password(plain: str, hashed: str) -> bool:
    """Constant-time comparison of a plaintext password against a bcrypt hash."""
    return bcrypt.checkpw(plain.encode(), hashed.encode())
