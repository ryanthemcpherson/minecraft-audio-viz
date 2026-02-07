"""Coordinator configuration via pydantic-settings."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Coordinator settings loaded from environment variables.

    All variables are prefixed with ``MCAV_`` (e.g. ``MCAV_DATABASE_URL``).
    """

    # Database
    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/mcav"

    # JWT
    jwt_default_expiry_minutes: int = 15

    # Rate limiting
    rate_limit_resolve_per_minute: int = 10
    rate_limit_register_per_hour: int = 5

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
