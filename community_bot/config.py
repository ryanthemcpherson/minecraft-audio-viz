"""Configuration loaded from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Config:
    bot_token: str
    guild_id: int
    coordinator_url: str  # e.g. http://localhost:8090
    webhook_secret: str
    webhook_port: int = 8100

    @classmethod
    def from_env(cls) -> Config:
        token = os.environ.get("MCAV_COMMUNITY_BOT_TOKEN", "")
        if not token:
            raise RuntimeError("MCAV_COMMUNITY_BOT_TOKEN is required")
        guild_id = os.environ.get("MCAV_DISCORD_GUILD_ID", "")
        if not guild_id:
            raise RuntimeError("MCAV_DISCORD_GUILD_ID is required")
        return cls(
            bot_token=token,
            guild_id=int(guild_id),
            coordinator_url=os.environ.get("MCAV_COORDINATOR_URL", "http://localhost:8090"),
            webhook_secret=os.environ.get("MCAV_WEBHOOK_SECRET", ""),
            webhook_port=int(os.environ.get("MCAV_WEBHOOK_PORT", "8100")),
        )
