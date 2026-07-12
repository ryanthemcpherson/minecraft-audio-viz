"""Security tests for the coordinator metrics boundary."""

from __future__ import annotations

from pathlib import Path
from secrets import token_urlsafe

import pytest
from app.config import Settings
from httpx import AsyncClient

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize("environment", ["production", "staging", "development", "test"])
@pytest.mark.parametrize("metrics_token", [None, "", "   "])
def test_every_environment_requires_metrics_token(
    environment: str, metrics_token: str | None
) -> None:
    with pytest.raises(ValueError, match="MCAV_METRICS_TOKEN"):
        Settings(
            mcav_env=environment,
            metrics_token=metrics_token,
            user_jwt_secret=token_urlsafe(32),
        )


def test_default_environment_is_production(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("MCAV_ENV", raising=False)

    settings = Settings(
        _env_file=None,
        metrics_token=token_urlsafe(32),
        user_jwt_secret=token_urlsafe(32),
    )

    assert settings.mcav_env == "production"


def test_root_compose_supplies_required_metrics_token(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    compose = (REPOSITORY_ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    assert "- MCAV_METRICS_TOKEN=${MCAV_METRICS_TOKEN:?Metrics token must be set}" in compose

    expected_token = token_urlsafe(32)
    monkeypatch.setenv("MCAV_METRICS_TOKEN", expected_token)
    settings = Settings(
        _env_file=None,
        user_jwt_secret=token_urlsafe(32),
    )

    assert settings.metrics_token == expected_token


@pytest.mark.parametrize(
    ("authorization", "expected_status"),
    [
        (None, 401),
        ("Bearer wrong-metrics-token", 401),
        ("Basic test-metrics-token", 401),
        ("Bearer", 401),
        ("test-metrics-token", 401),
        ("Bearer test-metrics-token", 200),
    ],
)
@pytest.mark.asyncio
async def test_metrics_endpoint_requires_configured_bearer_token(
    client: AsyncClient,
    authorization: str | None,
    expected_status: int,
) -> None:
    headers = {"Authorization": authorization} if authorization else {}

    response = await client.get("/metrics", headers=headers)

    assert response.status_code == expected_status


@pytest.mark.asyncio
async def test_metrics_endpoint_rejects_non_ascii_bearer_token(
    client: AsyncClient,
) -> None:
    response = await client.get(
        "/metrics",
        headers=[(b"Authorization", b"Bearer \xff")],
    )

    assert response.status_code == 401
