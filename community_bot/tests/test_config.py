"""Tests for community bot environment configuration."""

from __future__ import annotations

import pytest

from community_bot.config import Config


def _set_required_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MCAV_COMMUNITY_BOT_TOKEN", "test-token")
    monkeypatch.setenv("MCAV_DISCORD_GUILD_ID", "123")


def test_missing_webhook_secret_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_required_environment(monkeypatch)
    monkeypatch.delenv("MCAV_WEBHOOK_SECRET", raising=False)

    with pytest.raises(RuntimeError, match="MCAV_WEBHOOK_SECRET is required"):
        Config.from_env()


def test_whitespace_only_webhook_secret_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_required_environment(monkeypatch)
    monkeypatch.setenv("MCAV_WEBHOOK_SECRET", " \t ")

    with pytest.raises(RuntimeError, match="MCAV_WEBHOOK_SECRET is required"):
        Config.from_env()
