"""MCAV Community Bot — role management and Discord-coordinator sync."""

from __future__ import annotations

import logging

import discord
from discord import app_commands

from community_bot.config import Config
from community_bot.coordinator_client import CoordinatorClient
from community_bot.views import (
    CHANNEL_STRUCTURE,
    DISCORD_ROLE_NAMES,
    ROLE_COLORS,
    RoleSelectView,
)

_logger = logging.getLogger(__name__)


class CommunityBot(discord.Client):
    """Discord bot for MCAV community role management and coordinator sync."""

    def __init__(self, config: Config) -> None:
        intents = discord.Intents.default()
        intents.members = True  # Required for on_member_join
        super().__init__(intents=intents)

        self.config = config
        self.tree = app_commands.CommandTree(self)
        self.coordinator = CoordinatorClient(
            base_url=config.coordinator_url,
            webhook_secret=config.webhook_secret,
        )
        self._role_view = RoleSelectView(self)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    async def setup_hook(self) -> None:
        """Register slash commands, persistent views, and sync to guild."""
        # Re-register the persistent view so button callbacks survive restarts
        self.add_view(self._role_view)

        self._register_commands()

        guild = discord.Object(id=self.config.guild_id)
        self.tree.copy_global_to(guild=guild)
        await self.tree.sync(guild=guild)
        _logger.info("Slash commands synced to guild %s", self.config.guild_id)

    async def on_ready(self) -> None:
        _logger.info("Logged in as %s (ID: %s)", self.user, self.user.id)

    async def on_member_join(self, member: discord.Member) -> None:
        """Welcome new members and auto-assign roles if linked."""
        channel = member.guild.system_channel
        if channel is None:
            return

        try:
            user_data = await self.coordinator.get_user_by_discord_id(str(member.id))
        except Exception:
            _logger.warning("Failed to check coordinator for member %s", member.id, exc_info=True)
            user_data = None

        if user_data is not None:
            # User has linked their mcav.live account — auto-assign roles
            assigned = await self._assign_coordinator_roles(member, user_data)
            roles_text = ", ".join(f"**{r}**" for r in assigned) if assigned else "none yet"
            await channel.send(
                f"Welcome {member.mention}! Syncing your roles from mcav.live: {roles_text}"
            )
        else:
            await channel.send(
                f"Welcome {member.mention}! "
                "Link your Discord at **mcav.live** to get your roles automatically."
            )

    async def close(self) -> None:
        await self.coordinator.close()
        await super().close()

    # ------------------------------------------------------------------
    # Slash commands
    # ------------------------------------------------------------------

    def _register_commands(self) -> None:
        @self.tree.command(
            name="setup-roles",
            description="Post the role selection panel in this channel (admin only)",
        )
        @app_commands.default_permissions(administrator=True)
        async def setup_roles(interaction: discord.Interaction) -> None:
            embed = discord.Embed(
                title="Choose Your Roles",
                description=(
                    "Click the buttons below to toggle community roles.\n\n"
                    "These roles help us know what you're interested in and unlock "
                    "relevant channels. Link your Discord at [mcav.live](https://mcav.live) "
                    "to sync roles across platforms."
                ),
                color=0x00CCFF,
            )
            await interaction.channel.send(embed=embed, view=self._role_view)
            await interaction.response.send_message("Role selection panel posted!", ephemeral=True)

        @self.tree.command(
            name="setup-server",
            description="Create all MCAV roles and channels (admin only)",
        )
        @app_commands.default_permissions(administrator=True)
        async def setup_server(interaction: discord.Interaction) -> None:
            guild = interaction.guild
            if guild is None:
                return
            await interaction.response.defer(ephemeral=True)

            try:
                # Create roles
                roles = await self.ensure_roles(guild)
                role_names = list(roles.keys())

                # Create channels
                channels = await self.setup_channels(guild, roles)
                channel_names = [
                    n for n, c in channels.items() if not isinstance(c, discord.CategoryChannel)
                ]

                # Post role selector in #roles if it exists
                roles_channel = channels.get("roles")
                if roles_channel and isinstance(roles_channel, discord.TextChannel):
                    # Check if we already posted a role selector
                    async for msg in roles_channel.history(limit=10):
                        if msg.author == self.user and msg.embeds:
                            break
                    else:
                        embed = discord.Embed(
                            title="Choose Your Roles",
                            description=(
                                "Click the buttons below to toggle community roles.\n\n"
                                "These roles help us know what you're interested in and "
                                "unlock relevant channels. Link your Discord at "
                                "[mcav.live](https://mcav.live) to sync roles across "
                                "platforms."
                            ),
                            color=0x00CCFF,
                        )
                        await roles_channel.send(embed=embed, view=self._role_view)

                await interaction.followup.send(
                    f"Server setup complete!\n"
                    f"**Roles:** {', '.join(role_names)}\n"
                    f"**Channels:** {len(channel_names)} channels created/verified",
                    ephemeral=True,
                )
            except Exception:
                _logger.exception("Error during server setup")
                await interaction.followup.send(
                    "Setup partially completed but hit an error. "
                    "Check bot logs and try running `/setup-server` again — "
                    "it skips anything already created.",
                    ephemeral=True,
                )

        @self.tree.command(
            name="sync-roles",
            description="Re-sync your roles with mcav.live",
        )
        async def sync_roles(interaction: discord.Interaction) -> None:
            member = interaction.user
            if not isinstance(member, discord.Member):
                await interaction.response.send_message(
                    "This can only be used in a server.", ephemeral=True
                )
                return

            await interaction.response.defer(ephemeral=True)

            try:
                user_data = await self.coordinator.get_user_by_discord_id(str(member.id))
            except Exception:
                _logger.warning(
                    "Failed to fetch user from coordinator for %s", member.id, exc_info=True
                )
                await interaction.followup.send(
                    "Something went wrong contacting mcav.live. Try again later.",
                    ephemeral=True,
                )
                return

            if user_data is None:
                await interaction.followup.send(
                    "Your Discord isn't linked to an mcav.live account yet. "
                    "Visit **https://mcav.live** to link your account.",
                    ephemeral=True,
                )
                return

            assigned = await self._assign_coordinator_roles(member, user_data)
            if assigned:
                roles_text = ", ".join(f"**{r}**" for r in assigned)
                await interaction.followup.send(
                    f"Synced! Added roles: {roles_text}", ephemeral=True
                )
            else:
                await interaction.followup.send(
                    "Your roles are already up to date!", ephemeral=True
                )

    # ------------------------------------------------------------------
    # Server setup
    # ------------------------------------------------------------------

    async def ensure_roles(self, guild: discord.Guild) -> dict[str, discord.Role]:
        """Create any missing MCAV roles. Returns name→Role mapping."""
        all_role_names = list(DISCORD_ROLE_NAMES.values()) + ["Verified"]
        existing = {r.name: r for r in guild.roles}
        result: dict[str, discord.Role] = {}

        for name in all_role_names:
            if name in existing:
                result[name] = existing[name]
            else:
                color = ROLE_COLORS.get(name, discord.Colour.default())
                role = await guild.create_role(
                    name=name,
                    colour=color,
                    mentionable=True,
                    reason="MCAV community bot setup",
                )
                result[name] = role
                _logger.info("Created role: %s", name)

        return result

    async def setup_channels(
        self, guild: discord.Guild, roles: dict[str, discord.Role]
    ) -> dict[str, discord.abc.GuildChannel]:
        """Create categories and channels from CHANNEL_STRUCTURE. Skips existing."""
        existing_channels = {c.name: c for c in guild.channels}
        created: dict[str, discord.abc.GuildChannel] = {}

        for section in CHANNEL_STRUCTURE:
            cat_name = section["category"]

            # Find or create category
            category = existing_channels.get(cat_name)
            if category is None or not isinstance(category, discord.CategoryChannel):
                category = await guild.create_category(cat_name, reason="MCAV community bot setup")
                _logger.info("Created category: %s", cat_name)
            created[cat_name] = category

            for ch_def in section["channels"]:
                ch_name = ch_def["name"]
                ch_type = ch_def.get("type", "text")

                # Check if channel already exists in this category
                existing_in_cat = discord.utils.get(
                    category.channels, name=ch_name.lower().replace(" ", "-")
                ) or discord.utils.get(category.channels, name=ch_name)
                if existing_in_cat:
                    created[ch_name] = existing_in_cat
                    continue

                # Build permission overwrites
                overwrites: dict[discord.Role | discord.Member, discord.PermissionOverwrite] = {}

                if ch_def.get("read_only"):
                    # Everyone can read but not send; bot can still post
                    overwrites[guild.default_role] = discord.PermissionOverwrite(
                        send_messages=False
                    )
                    overwrites[guild.me] = discord.PermissionOverwrite(send_messages=True)

                if ch_def.get("require_roles"):
                    # Hide from everyone, show to specific roles
                    overwrites[guild.default_role] = discord.PermissionOverwrite(
                        view_channel=False
                    )
                    for role_name in ch_def["require_roles"]:
                        if role_name in roles:
                            overwrites[roles[role_name]] = discord.PermissionOverwrite(
                                view_channel=True
                            )

                if ch_type == "voice":
                    channel = await guild.create_voice_channel(
                        ch_name,
                        category=category,
                        overwrites=overwrites or discord.utils.MISSING,
                        reason="MCAV community bot setup",
                    )
                else:
                    channel = await guild.create_text_channel(
                        ch_name,
                        category=category,
                        overwrites=overwrites or discord.utils.MISSING,
                        reason="MCAV community bot setup",
                    )
                created[ch_name] = channel
                _logger.info("Created channel: #%s in %s", ch_name, cat_name)

        return created

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    async def _assign_coordinator_roles(
        self, member: discord.Member, user_data: dict
    ) -> list[str]:
        """Assign Discord roles based on coordinator user data.

        Returns list of role names that were newly added.
        """
        guild = member.guild
        coordinator_roles: list[str] = user_data.get("roles", [])
        added: list[str] = []

        # Assign community roles from coordinator
        for role_key, discord_name in DISCORD_ROLE_NAMES.items():
            if role_key not in coordinator_roles:
                continue
            role = discord.utils.get(guild.roles, name=discord_name)
            if role is None:
                _logger.debug("Role %r not found in guild %s", discord_name, guild.id)
                continue
            if role not in member.roles:
                await member.add_roles(role, reason="MCAV coordinator role sync")
                added.append(discord_name)

        # Also assign Verified role if it exists
        verified_role = discord.utils.get(guild.roles, name="Verified")
        if verified_role is not None and verified_role not in member.roles:
            await member.add_roles(verified_role, reason="MCAV account linked")
            added.append("Verified")

        return added
