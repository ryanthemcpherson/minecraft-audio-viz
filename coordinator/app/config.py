"""Coordinator configuration via pydantic-settings."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Coordinator settings loaded from environment variables.

    All variables are prefixed with ``MCAV_`` (e.g. ``MCAV_DATABASE_URL``).
    """

    # Database
    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/mcav"

    def model_post_init(self, __context: object) -> None:
        """Ensure the database URL uses the asyncpg driver."""
        if self.database_url.startswith("postgresql://"):
            self.database_url = self.database_url.replace(
                "postgresql://", "postgresql+asyncpg://", 1
            )

    # DJ-session JWT (per-server secrets – unchanged)
    jwt_default_expiry_minutes: int = 15

    # User-session JWT (global secret)
    user_jwt_secret: str = "CHANGE-ME-in-production"
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

    # CORS
    cors_origins: list[str] = ["https://mcav.live", "http://localhost:3000"]

    model_config = SettingsConfigDict(
        env_prefix="MCAV_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


def get_settings() -> Settings:
    """Return a cached ``Settings`` instance."""
    return Settings()
