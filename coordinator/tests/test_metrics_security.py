"""Security tests for the coordinator metrics boundary."""

from __future__ import annotations

from secrets import token_urlsafe

import pytest
from app.config import Settings
from httpx import AsyncClient


@pytest.mark.parametrize("environment", ["production", "staging", "development"])
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


@pytest.mark.parametrize(
    ("authorization", "expected_status"),
    [
        (None, 401),
        ("Bearer wrong-metrics-token", 401),
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
