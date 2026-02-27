"""Webhook server — receives notifications from the coordinator."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

import discord
from aiohttp import web

from community_bot.views import DISCORD_ROLE_NAMES

if TYPE_CHECKING:
    from community_bot.bot import CommunityBot

_logger = logging.getLogger(__name__)


def create_webhook_app(bot: CommunityBot) -> web.Application:
    """Create the aiohttp application that handles coordinator webhooks."""
    app = web.Application()
    app["bot"] = bot

    app.router.add_get("/health", _handle_health)
    app.router.add_post("/notify/role-change", _handle_role_change)

    return app


async def _handle_health(_request: web.Request) -> web.Response:
    return web.json_response({"status": "ok"})


async def _handle_role_change(request: web.Request) -> web.Response:
    bot: CommunityBot = request.app["bot"]

    # Authenticate
    secret = request.headers.get("X-Webhook-Secret", "")
    if secret != bot.config.webhook_secret:
        return web.json_response({"error": "unauthorized"}, status=401)

    # Parse body
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "invalid json"}, status=400)

    discord_id: str | None = body.get("discord_id")
    if not discord_id:
        return web.json_response({"error": "missing discord_id"}, status=400)

    new_roles: list[str] = body.get("roles", [])

    # Get guild
    guild = bot.get_guild(bot.config.guild_id)
    if guild is None:
        return web.json_response({"error": "guild not available"}, status=503)

    # Get member
    try:
        member = guild.get_member(int(discord_id))
        if member is None:
            member = await guild.fetch_member(int(discord_id))
    except (discord.NotFound, discord.HTTPException):
        member = None

    if member is None:
        return web.json_response({"status": "user_not_in_guild"})

    # Sync roles: add missing, remove explicitly removed
    for role_key, discord_name in DISCORD_ROLE_NAMES.items():
        role = discord.utils.get(guild.roles, name=discord_name)
        if role is None:
            _logger.debug("Role %r not found in guild %s", discord_name, guild.id)
            continue

        should_have = role_key in new_roles

        if should_have and role not in member.roles:
            await member.add_roles(role, reason="MCAV coordinator role sync (webhook)")
            _logger.info("Added role %s to %s", discord_name, member)
        elif not should_have and role in member.roles:
            await member.remove_roles(role, reason="MCAV coordinator role sync (webhook)")
            _logger.info("Removed role %s from %s", discord_name, member)

    # Ensure Verified role is assigned (user is linked if coordinator is calling)
    verified_role = discord.utils.get(guild.roles, name="Verified")
    if verified_role is not None and verified_role not in member.roles:
        await member.add_roles(verified_role, reason="MCAV account linked (webhook)")
        _logger.info("Added Verified role to %s", member)

    return web.json_response({"status": "synced"})
