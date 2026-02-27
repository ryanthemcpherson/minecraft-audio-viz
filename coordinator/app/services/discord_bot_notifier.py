"""Fire-and-forget notifications to the community Discord bot."""

from __future__ import annotations

import logging
import uuid

import httpx

from app.config import Settings

logger = logging.getLogger(__name__)


async def notify_role_change(
    *,
    settings: Settings,
    discord_id: str,
    user_id: uuid.UUID,
    roles: list[str],
) -> bool:
    """POST role change notification to community bot. Returns True on success."""
    if not settings.community_bot_url:
        logger.debug("community_bot_url not configured — skipping role change notification")
        return False

    url = f"{settings.community_bot_url}/notify/role-change"
    payload = {
        "discord_id": discord_id,
        "user_id": str(user_id),
        "roles": roles,
    }
    headers = {}
    if settings.discord_webhook_secret:
        headers["X-Webhook-Secret"] = settings.discord_webhook_secret

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(url, json=payload, headers=headers)

        if resp.status_code == 200:
            logger.debug("Notified community bot of role change for user %s", user_id)
            return True

        logger.warning(
            "Community bot returned %d for role change notification (user=%s)",
            resp.status_code,
            user_id,
        )
        return False

    except httpx.HTTPError as exc:
        logger.warning(
            "Failed to notify community bot of role change (user=%s): %s",
            user_id,
            exc,
        )
        return False
