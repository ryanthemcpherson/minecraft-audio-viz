"""Coordinator configuration via pydantic-settings.

Deployed on Railway with auto-deploy from main branch.
"""

from __future__ import annotations

import json
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
    db_pool_size: int = 10
    db_max_overflow: int = 20

    def model_post_init(self, __context: object) -> None:
        """Ensure the database URL uses the asyncpg driver and validate secrets."""
        if self.database_url.startswith("postgresql://"):
            self.database_url = self.database_url.replace(
                "postgresql://", "postgresql+asyncpg://", 1
            )
        # Validate JWT secret strength
        self._validate_jwt_secret()

    def _validate_jwt_secret(self) -> None:
        """Reject insecure JWT secrets.

        - Default placeholder or secrets < 32 chars always fail in production/staging.
        - In development, they emit a loud warning via the ``warnings`` module so
          it shows up even if logging isn't configured yet.
        """
        import warnings

        is_default = self.user_jwt_secret == _INSECURE_DEFAULT_SECRET
        is_short = len(self.user_jwt_secret) < 32
        insecure = is_default or is_short

        if not insecure:
            return

        hint = (
            "Set MCAV_USER_JWT_SECRET to a random 32+ character string. "
            'Generate one with: python -c "import secrets; print(secrets.token_urlsafe(64))"'
        )

        env = os.environ.get("MCAV_ENV", "development").lower()
        if env in ("production", "prod", "staging"):
            raise ValueError(f"Insecure JWT secret in {env}! {hint}")

        # Development: warn loudly but allow startup for local work
        warnings.warn(
            f"JWT secret is insecure (default={is_default}, length={len(self.user_jwt_secret)}). {hint}",
            UserWarning,
            stacklevel=2,
        )

    # DJ-session JWT (per-server secrets – unchanged)
    jwt_default_expiry_minutes: int = 15

    # User-session JWT (global secret)
    user_jwt_secret: str = _INSECURE_DEFAULT_SECRET
    user_jwt_expiry_minutes: int = 60
    refresh_token_expiry_days: int = 7

    # Discord OAuth
    discord_client_id: str = ""
    discord_client_secret: str = ""
    discord_redirect_uri: str = "https://mcav.live/auth/callback"

    # Google OAuth
    google_client_id: str = ""
    google_client_secret: str = ""
    google_redirect_uri: str = "https://mcav.live/auth/callback"

    # Account lockout
    max_failed_login_attempts: int = 5
    lockout_duration_minutes: int = 15

    # Email (Resend)
    resend_api_key: str = ""
    email_from: str = "MCAV <noreply@mcav.live>"
    password_reset_expiry_minutes: int = 30

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

    # Community bot webhook
    discord_webhook_secret: str = ""
    discord_guild_id: str = ""
    community_bot_url: str = ""  # e.g. http://localhost:8100

    # Desktop deep link
    desktop_deep_link_scheme: str = "mcav"

    # CORS — stored as str to avoid pydantic-settings JSON-parsing env vars.
    # Use get_cors_origins() to get the parsed list.
    cors_origins: str = "https://mcav.live,http://localhost:3000,http://localhost:5173,tauri://localhost,http://tauri.localhost,https://tauri.localhost"

    def get_cors_origins(self) -> list[str]:
        """Parse CORS origins — handles JSON arrays, bracket lists, and comma-separated strings."""
        v = self.cors_origins.strip()
        if v.startswith("["):
            try:
                parsed = json.loads(v)
                if isinstance(parsed, list):
                    return [str(item).strip() for item in parsed]
            except json.JSONDecodeError:
                pass
            # Railway strips inner quotes: [https://mcav.live,http://localhost:3000]
            inner = v[1:-1] if v.endswith("]") else v[1:]
            return [item.strip() for item in inner.split(",") if item.strip()]
        # Plain comma-separated: 'https://mcav.live,http://localhost:3000'
        return [item.strip() for item in v.split(",") if item.strip()]

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
