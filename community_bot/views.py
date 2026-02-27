"""Persistent Discord UI views for role selection."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

import discord

if TYPE_CHECKING:
    from community_bot.bot import CommunityBot

_logger = logging.getLogger(__name__)

ROLE_CONFIG: dict[str, dict] = {
    "dj": {"label": "DJ", "style": discord.ButtonStyle.primary},
    "server_owner": {"label": "Server Owner", "style": discord.ButtonStyle.primary},
    "vj": {"label": "VJ", "style": discord.ButtonStyle.primary},
    "developer": {"label": "Developer", "style": discord.ButtonStyle.secondary},
    "beta_tester": {"label": "Beta Tester", "style": discord.ButtonStyle.secondary},
}

DISCORD_ROLE_NAMES: dict[str, str] = {
    "dj": "DJ",
    "server_owner": "Server Owner",
    "vj": "VJ",
    "developer": "Developer",
    "beta_tester": "Beta Tester",
}

# Role colors (cyan-ish palette matching MCAV brand)
ROLE_COLORS: dict[str, discord.Colour] = {
    "DJ": discord.Colour(0x00CCFF),  # cyan
    "Server Owner": discord.Colour(0x5B6AFF),  # indigo
    "VJ": discord.Colour(0xFFAA00),  # amber
    "Developer": discord.Colour(0x2FE098),  # green
    "Beta Tester": discord.Colour(0xA78BFA),  # purple
    "Verified": discord.Colour(0x43C5FF),  # light cyan
}

# Full server structure definition
CHANNEL_STRUCTURE: list[dict] = [
    {
        "category": "WELCOME",
        "channels": [
            {"name": "rules", "read_only": True},
            {"name": "welcome", "read_only": True},
            {"name": "roles", "read_only": True},  # Bot posts here, users click buttons
        ],
    },
    {
        "category": "GENERAL",
        "channels": [
            {"name": "general"},
            {"name": "announcements", "read_only": True},
            {"name": "dev-progress", "read_only": True},
            {"name": "showcase"},
            {"name": "support"},
            {"name": "ideas"},
        ],
    },
    {
        "category": "ROLE-GATED",
        "channels": [
            {"name": "dj-lounge", "require_roles": ["DJ", "Verified"]},
            {"name": "server-owners", "require_roles": ["Server Owner", "Verified"]},
            {"name": "vj-lab", "require_roles": ["VJ", "Verified"]},
            {"name": "dev-chat", "require_roles": ["Developer"]},
        ],
    },
    {
        "category": "VOICE",
        "channels": [
            {"name": "General Voice", "type": "voice"},
            {"name": "Live DJ", "type": "voice"},
        ],
    },
]


class RoleButton(discord.ui.Button["RoleSelectView"]):
    """Toggle button for a single community role."""

    def __init__(self, role_key: str, bot: CommunityBot) -> None:
        cfg = ROLE_CONFIG[role_key]
        super().__init__(
            label=cfg["label"],
            style=cfg["style"],
            custom_id=f"mcav_role_{role_key}",
        )
        self.role_key = role_key
        self.bot = bot

    async def callback(self, interaction: discord.Interaction) -> None:
        guild = interaction.guild
        member = interaction.user
        if guild is None or not isinstance(member, discord.Member):
            await interaction.response.send_message(
                "This can only be used in a server.", ephemeral=True
            )
            return

        discord_role_name = DISCORD_ROLE_NAMES[self.role_key]
        role = discord.utils.get(guild.roles, name=discord_role_name)

        if role is None:
            # Auto-create the role if it doesn't exist
            color = ROLE_COLORS.get(discord_role_name, discord.Colour.default())
            role = await guild.create_role(
                name=discord_role_name,
                colour=color,
                mentionable=True,
                reason="MCAV role auto-created on first use",
            )
            _logger.info("Auto-created role: %s", discord_role_name)

        if role in member.roles:
            # Remove role
            await member.remove_roles(role, reason="MCAV role button toggle")
            await interaction.response.send_message(
                f"Removed **{discord_role_name}** role!", ephemeral=True
            )
            # Sync removal to coordinator
            try:
                await self.bot.coordinator.remove_role(str(member.id), self.role_key)
            except Exception:
                _logger.warning(
                    "Failed to sync role removal to coordinator for %s", member.id, exc_info=True
                )
        else:
            # Add role
            await member.add_roles(role, reason="MCAV role button toggle")
            await interaction.response.send_message(
                f"Added **{discord_role_name}** role!", ephemeral=True
            )
            # Sync addition to coordinator
            try:
                await self.bot.coordinator.sync_roles(str(member.id), [self.role_key])
            except Exception:
                _logger.warning(
                    "Failed to sync role addition to coordinator for %s", member.id, exc_info=True
                )


class RoleSelectView(discord.ui.View):
    """Persistent view with toggle buttons for each community role."""

    def __init__(self, bot: CommunityBot) -> None:
        super().__init__(timeout=None)
        for role_key in ROLE_CONFIG:
            self.add_item(RoleButton(role_key, bot))
