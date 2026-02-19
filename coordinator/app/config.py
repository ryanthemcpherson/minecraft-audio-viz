"""Coordinator configuration via pydantic-settings."""

from __future__ import annotations

import logging
import os
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

_logger = logging.getLogger(__name__)

_INSECURE_DEFAULT_SECRET = "CHANGE-ME-in-production"  # nosec B105 — intentional; validated in model_post_init


class Settings(BaseSettings):
    """Coordinator settings loaded from environment variables.

    All variables are prefixed with ``MCAV_`` (e.g. ``MCAV_DATABASE_URL``).
    """

    # Database
    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/mcav"

    def model_post_init(self, __context: object) -> None:
        """Ensure the database URL uses the asyncpg driver and validate secrets."""
        if self.database_url.startswith("postgresql://"):
            self.database_url = self.database_url.replace(
                "postgresql://", "postgresql+asyncpg://", 1
            )
        # Refuse to start with the default JWT secret in production
        if self.user_jwt_secret == _INSECURE_DEFAULT_SECRET:
            env = os.environ.get("MCAV_ENV", "development").lower()
            if env in ("production", "prod", "staging"):
                raise ValueError(
                    "MCAV_USER_JWT_SECRET must be set to a secure value in production. "
                    'Generate one with: python -c "import secrets; print(secrets.token_urlsafe(64))"'
                )
            _logger.warning(
                "Using insecure default JWT secret. Set MCAV_USER_JWT_SECRET for production."
            )

    # DJ-session JWT (per-server secrets – unchanged)
    jwt_default_expiry_minutes: int = 15

    # User-session JWT (global secret)
    user_jwt_secret: str = _INSECURE_DEFAULT_SECRET
    user_jwt_expiry_minutes: int = 60
    refresh_token_expiry_days: int = 30

    # Discord OAuth
    discord_client_id: str = ""
    discord_client_secret: str = ""
    discord_redirect_uri: str = "https://mcav.live/auth/callback"

    # URLs
    base_url: str = "https://mcav.live"
    coordinator_url: str = "https://api.mcav.live"

    # Rate limiting
    rate_limit_resolve_per_minute: int = 10
    rate_limit_register_per_hour: int = 5
    rate_limit_auth_per_minute: int = 20

    # R2 Storage (Cloudflare R2, S3-compatible)
    r2_account_id: str = ""
    r2_access_key_id: str = ""
    r2_secret_access_key: str = ""
    r2_bucket_name: str = ""
    r2_public_url: str = ""

    # CORS
    cors_origins: list[str] = ["https://mcav.live", "http://localhost:3000"]

    model_config = SettingsConfigDict(
        env_prefix="MCAV_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return a cached ``Settings`` instance."""
    return Settings()
