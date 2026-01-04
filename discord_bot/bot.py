"""
AudioViz Discord Bot
Joins voice channels, captures audio, and sends visualization data to Minecraft.
Uses slash commands for modern Discord integration.
"""

import discord
from discord import app_commands
import asyncio
import sys
import os
import logging
from typing import Optional
from dotenv import load_dotenv

# Load environment variables
load_dotenv(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '.env'))

# Add parent directory to path for imports
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from audio_processor.processor import AudioProcessor, AudioFrame
from python_client.viz_client import VizClient

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger('audioviz')


class AudioVizBot(discord.Client):
    """Discord bot for audio visualization using slash commands."""

    def __init__(self):
        intents = discord.Intents.default()
        intents.voice_states = True
        intents.guilds = True

        super().__init__(intents=intents)

        # Slash command tree
        self.tree = app_commands.CommandTree(self)

        # Minecraft connection
        self.viz_client: Optional[VizClient] = None
        self.minecraft_host = os.getenv('MINECRAFT_HOST', '192.168.208.1')
        self.minecraft_port = int(os.getenv('MINECRAFT_PORT', '8765'))
        self.active_zone = "main"

        # Audio processing
        self.audio_processor = AudioProcessor(
            sample_rate=48000,
            channels=2,
            smoothing=0.2,
            beat_sensitivity=1.3
        )

        # State
        self.is_visualizing = False
        self.entity_count = 16

        logger.info("Bot initialized")

    async def setup_hook(self):
        """Set up slash commands."""
        await self.register_commands()
        logger.info("Commands registered")

    async def register_commands(self):
        """Register all slash commands."""

        @self.tree.command(name="join", description="Join your voice channel")
        async def join_cmd(interaction: discord.Interaction):
            if not interaction.user.voice:
                await interaction.response.send_message("You need to be in a voice channel!", ephemeral=True)
                return

            channel = interaction.user.voice.channel

            if interaction.guild.voice_client:
                await interaction.guild.voice_client.move_to(channel)
            else:
                await channel.connect()

            await interaction.response.send_message(f"✅ Joined **{channel.name}**")

        @self.tree.command(name="leave", description="Leave the voice channel")
        async def leave_cmd(interaction: discord.Interaction):
            if interaction.guild.voice_client:
                await self.stop_visualization()
                await interaction.guild.voice_client.disconnect()
                await interaction.response.send_message("👋 Left voice channel")
            else:
                await interaction.response.send_message("I'm not in a voice channel", ephemeral=True)

        @self.tree.command(name="viz", description="Start audio visualization")
        @app_commands.describe(zone="Minecraft zone name to visualize in")
        async def viz_cmd(interaction: discord.Interaction, zone: str = "main"):
            if not interaction.guild.voice_client:
                await interaction.response.send_message("I need to be in a voice channel first! Use `/join`", ephemeral=True)
                return

            await interaction.response.defer()

            # Connect to Minecraft
            if not await self.connect_minecraft():
                await interaction.followup.send("❌ Failed to connect to Minecraft server!")
                return

            # Check if zone exists
            zones = await self.viz_client.get_zones()
            zone_names = [z['name'] for z in zones]

            if zone not in zone_names:
                await interaction.followup.send(f"❌ Zone `{zone}` not found. Available: {', '.join(zone_names)}")
                return

            self.active_zone = zone

            # Initialize entity pool
            await self.viz_client.init_pool(zone, self.entity_count, "SEA_LANTERN")
            await asyncio.sleep(0.5)

            # Start visualization
            self.is_visualizing = True
            self.audio_processor.add_callback(self.on_audio_frame)

            # Start audio simulation (since we can't capture Discord audio directly)
            asyncio.create_task(self.run_audio_simulation())

            await interaction.followup.send(
                f"🎵 **Visualization started on zone `{zone}`!**\n"
                f"Playing simulated audio visualization.\n"
                f"Use `/stop` to end."
            )

        @self.tree.command(name="stop", description="Stop visualization")
        async def stop_cmd(interaction: discord.Interaction):
            await self.stop_visualization()
            await interaction.response.send_message("🛑 Visualization stopped")

        @self.tree.command(name="status", description="Show bot status")
        async def status_cmd(interaction: discord.Interaction):
            mc_status = "🟢 Connected" if (self.viz_client and self.viz_client.connected) else "🔴 Disconnected"
            voice_status = interaction.guild.voice_client.channel.name if interaction.guild.voice_client else "Not connected"
            viz_status = "🎵 Active" if self.is_visualizing else "⏹️ Inactive"

            embed = discord.Embed(title="AudioViz Status", color=0x00ff00 if self.is_visualizing else 0x888888)
            embed.add_field(name="Voice Channel", value=voice_status, inline=True)
            embed.add_field(name="Minecraft", value=mc_status, inline=True)
            embed.add_field(name="Visualization", value=viz_status, inline=True)
            embed.add_field(name="Active Zone", value=self.active_zone, inline=True)
            embed.add_field(name="Entities", value=str(self.entity_count), inline=True)

            await interaction.response.send_message(embed=embed)

        @self.tree.command(name="zone", description="Set the visualization zone")
        @app_commands.describe(name="Zone name in Minecraft")
        async def zone_cmd(interaction: discord.Interaction, name: str):
            self.active_zone = name
            await interaction.response.send_message(f"✅ Active zone set to `{name}`")

        @self.tree.command(name="entities", description="Set number of visualization entities")
        @app_commands.describe(count="Number of entities (4-64)")
        async def entities_cmd(interaction: discord.Interaction, count: int):
            count = max(4, min(64, count))
            self.entity_count = count
            await interaction.response.send_message(f"✅ Entity count set to {count}")

        @self.tree.command(name="sync", description="Sync slash commands (admin)")
        async def sync_cmd(interaction: discord.Interaction):
            await interaction.response.defer(ephemeral=True)
            synced = await self.tree.sync()
            await interaction.followup.send(f"✅ Synced {len(synced)} commands!")

    async def on_ready(self):
        """Called when bot is connected to Discord."""
        logger.info(f'Logged in as {self.user} (ID: {self.user.id})')
        logger.info(f'Connected to {len(self.guilds)} guild(s)')
        for guild in self.guilds:
            logger.info(f'  - {guild.name} (ID: {guild.id})')

        # Set presence
        await self.change_presence(
            activity=discord.Activity(
                type=discord.ActivityType.listening,
                name="/help for commands"
            ),
            status=discord.Status.online
        )

        # Sync commands globally
        try:
            synced = await self.tree.sync()
            logger.info(f'Synced {len(synced)} command(s) globally')
        except Exception as e:
            logger.error(f'Failed to sync commands: {e}')

    async def connect_minecraft(self) -> bool:
        """Connect to Minecraft server."""
        if self.viz_client and self.viz_client.connected:
            return True

        self.viz_client = VizClient(self.minecraft_host, self.minecraft_port)
        if await self.viz_client.connect():
            logger.info(f"Connected to Minecraft at {self.minecraft_host}:{self.minecraft_port}")
            return True
        else:
            logger.error("Failed to connect to Minecraft")
            return False

    async def disconnect_minecraft(self):
        """Disconnect from Minecraft server."""
        if self.viz_client:
            await self.viz_client.disconnect()
            self.viz_client = None

    async def stop_visualization(self):
        """Stop the visualization."""
        self.is_visualizing = False
        self.audio_processor.reset()
        self.audio_processor._callbacks.clear()

        if self.viz_client and self.viz_client.connected:
            await self.viz_client.set_visible(self.active_zone, False)

    def on_audio_frame(self, frame: AudioFrame):
        """Called for each processed audio frame."""
        if not self.is_visualizing or not self.viz_client:
            return
        asyncio.create_task(self._update_visualization(frame))

    async def _update_visualization(self, frame: AudioFrame):
        """Send visualization update to Minecraft."""
        if not self.viz_client or not self.viz_client.connected:
            return

        try:
            import math
            entities = []
            grid_size = int(math.ceil(math.sqrt(self.entity_count)))

            for i in range(self.entity_count):
                grid_x = i % grid_size
                grid_z = i // grid_size

                x = (grid_x + 0.5) / grid_size
                z = (grid_z + 0.5) / grid_size

                band_index = i % len(frame.bands)
                band_value = frame.bands[band_index]

                y = band_value * 0.8
                scale = 0.3 + band_value * 0.4

                if frame.is_beat:
                    scale = min(1.0, scale * 1.3)
                    y = min(1.0, y + 0.1)

                entities.append({
                    "id": f"block_{i}",
                    "x": x,
                    "y": y,
                    "z": z,
                    "scale": scale,
                    "visible": True
                })

            particles = []
            if frame.is_beat and frame.beat_intensity > 0.3:
                particles.append({
                    "particle": "NOTE",
                    "x": 0.5,
                    "y": 0.5,
                    "z": 0.5,
                    "count": int(15 * frame.beat_intensity)
                })

            await self.viz_client.batch_update(self.active_zone, entities, particles)

        except Exception as e:
            logger.error(f"Visualization update error: {e}")

    async def run_audio_simulation(self):
        """Run simulated audio for visualization demo."""
        import numpy as np
        import time

        logger.info("Starting audio simulation...")
        sample_rate = 48000
        frame_samples = 960
        beat_interval = 60.0 / 128  # 128 BPM

        start_time = time.time()
        last_beat = 0
        t = 0

        while self.is_visualizing:
            frame_t = np.linspace(t, t + frame_samples / sample_rate, frame_samples)

            # Generate audio
            bass = np.sin(2 * np.pi * 60 * frame_t) * 0.3
            bass += np.sin(2 * np.pi * 120 * frame_t) * 0.2
            mid = np.sin(2 * np.pi * 440 * frame_t) * 0.15
            high = np.sin(2 * np.pi * 2000 * frame_t) * 0.08

            samples = bass + mid + high

            current_time = time.time() - start_time
            if current_time - last_beat >= beat_interval:
                last_beat = current_time
                kick_env = np.exp(-frame_t * 50) * 0.8
                kick = np.sin(2 * np.pi * 80 * frame_t * np.exp(-frame_t * 30)) * kick_env
                samples += kick

            envelope = 0.5 + 0.3 * np.sin(current_time * 0.5)
            samples *= envelope

            samples_int = (samples * 32000).astype(np.int16)
            stereo = np.zeros(frame_samples * 2, dtype=np.int16)
            stereo[0::2] = samples_int
            stereo[1::2] = samples_int

            self.audio_processor.process_pcm(stereo.tobytes())

            t += frame_samples / sample_rate
            await asyncio.sleep(0.018)

        logger.info("Audio simulation stopped")


async def main():
    """Run the bot."""
    token = os.getenv('DISCORD_BOT_TOKEN')

    if not token:
        logger.error("DISCORD_BOT_TOKEN not found in environment!")
        return

    bot = AudioVizBot()
    logger.info(f"Starting bot with Minecraft at {bot.minecraft_host}:{bot.minecraft_port}")
    await bot.start(token)


if __name__ == '__main__':
    asyncio.run(main())
