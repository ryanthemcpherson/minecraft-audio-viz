"""
AudioViz Discord Bot — Audio Output

Connects to the VJ server as a browser client, subscribes to voice_audio
frames from the active DJ, and plays them into a Discord voice channel.
This lets Discord users hear the DJ's music alongside Minecraft players
using Simple Voice Chat.

Usage:
    python -m discord_bot.bot
"""

import asyncio
import base64
import json
import logging
import os
import time
from collections import deque
from typing import Optional

import discord
from discord import app_commands
from dotenv import load_dotenv

# Load .env from project root
load_dotenv(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env"))

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("audioviz.discord")

# Attempt to import opuslib for Opus decoding
try:
    import opuslib

    HAS_OPUSLIB = True
except ImportError:
    HAS_OPUSLIB = False
    logger.warning("opuslib not installed — Opus-encoded voice frames will be dropped")


# ---------------------------------------------------------------------------
# Audio source for discord.py voice playback
# ---------------------------------------------------------------------------


class VJAudioSource(discord.AudioSource):
    """Custom audio source that feeds PCM frames from the VJ server to Discord.

    discord.py calls ``read()`` every 20 ms expecting 3840 bytes of signed
    16-bit stereo PCM at 48 kHz (960 samples × 2 channels × 2 bytes).
    """

    FRAME_SIZE = 3840  # 20 ms of 48 kHz stereo s16le
    SILENCE = b"\x00" * FRAME_SIZE

    def __init__(self):
        self._buffer: deque[bytes] = deque(maxlen=50)  # ~1 s ring buffer

    def push_frame(self, stereo_pcm: bytes) -> None:
        """Thread-safe push of one 20 ms stereo PCM frame."""
        if len(stereo_pcm) == self.FRAME_SIZE:
            self._buffer.append(stereo_pcm)

    def read(self) -> bytes:
        """Return next frame or silence."""
        try:
            return self._buffer.popleft()
        except IndexError:
            return self.SILENCE

    def is_opus(self) -> bool:
        return False

    def cleanup(self) -> None:
        self._buffer.clear()


# ---------------------------------------------------------------------------
# Mono-to-stereo conversion
# ---------------------------------------------------------------------------


def mono_to_stereo(mono: bytes) -> bytes:
    """Convert 1920-byte mono s16le to 3840-byte stereo by duplicating samples."""
    if len(mono) != 1920:
        return VJAudioSource.SILENCE
    stereo = bytearray(3840)
    for i in range(0, 1920, 2):
        sample = mono[i : i + 2]
        j = i * 2
        stereo[j : j + 2] = sample
        stereo[j + 2 : j + 4] = sample
    return bytes(stereo)


# ---------------------------------------------------------------------------
# Main bot
# ---------------------------------------------------------------------------


class AudioVizBot(discord.Client):
    """Discord bot that plays VJ server audio into a voice channel."""

    def __init__(self):
        intents = discord.Intents.default()
        intents.voice_states = True
        intents.guilds = True
        super().__init__(intents=intents)

        self.tree = app_commands.CommandTree(self)

        # VJ server connection
        self._vj_host = os.getenv("VJ_SERVER_HOST", "localhost")
        self._vj_port = int(os.getenv("VJ_BROADCAST_PORT", "8766"))
        self._vj_ws = None
        self._vj_task: Optional[asyncio.Task] = None

        # Audio source (created when joining a voice channel)
        self._audio_source: Optional[VJAudioSource] = None

        # Opus decoder (lazy init)
        self._opus_decoder = None

        # Tracked state from VJ server
        self._current_pattern: str = "—"
        self._current_bpm: float = 0.0
        self._active_dj: Optional[str] = None
        self._vj_connected: bool = False

        # Presence throttle
        self._last_presence_update: float = 0.0

    # ------------------------------------------------------------------
    # Opus decoding
    # ------------------------------------------------------------------

    def _get_opus_decoder(self):
        if self._opus_decoder is None and HAS_OPUSLIB:
            self._opus_decoder = opuslib.Decoder(48000, 1)  # 48 kHz mono
        return self._opus_decoder

    def _decode_opus(self, opus_bytes: bytes) -> Optional[bytes]:
        """Decode Opus to mono PCM. Returns None on failure."""
        decoder = self._get_opus_decoder()
        if decoder is None:
            return None
        try:
            return decoder.decode(opus_bytes, 960)  # 960 samples = 20 ms
        except Exception:
            return None

    # ------------------------------------------------------------------
    # Voice frame processing
    # ------------------------------------------------------------------

    def _process_voice_frame(self, data: dict) -> None:
        """Decode a voice_audio message and push to the audio source buffer."""
        if self._audio_source is None:
            return

        raw_data = data.get("data")
        if not isinstance(raw_data, str):
            return

        try:
            pcm_bytes = base64.b64decode(raw_data)
        except Exception:
            return

        codec = data.get("codec", "pcm")

        if codec == "opus":
            pcm_bytes = self._decode_opus(pcm_bytes)
            if pcm_bytes is None:
                return  # Drop frame — 20 ms silence gap

        # At this point we have mono 48 kHz s16le PCM (1920 bytes)
        if len(pcm_bytes) == 1920:
            stereo = mono_to_stereo(pcm_bytes)
            self._audio_source.push_frame(stereo)
        elif len(pcm_bytes) == 3840:
            # Already stereo
            self._audio_source.push_frame(pcm_bytes)

    # ------------------------------------------------------------------
    # VJ server WebSocket connection
    # ------------------------------------------------------------------

    async def _vj_connect_loop(self):
        """Connect to VJ server with exponential backoff and process messages."""
        import websockets

        backoff = 1.0
        max_backoff = 30.0

        while True:
            url = f"ws://{self._vj_host}:{self._vj_port}"
            try:
                async with websockets.connect(url, ping_interval=20, ping_timeout=10) as ws:
                    self._vj_ws = ws
                    self._vj_connected = True
                    backoff = 1.0
                    logger.info(f"Connected to VJ server at {url}")

                    # Subscribe to voice audio frames
                    await ws.send(json.dumps({"type": "subscribe_voice"}))

                    async for message in ws:
                        try:
                            msg = json.loads(message)
                            msg_type = msg.get("type")

                            if msg_type == "ping":
                                await ws.send(json.dumps({"type": "pong"}))

                            elif msg_type == "voice_audio":
                                self._process_voice_frame(msg)

                            elif msg_type == "vj_state":
                                self._current_pattern = msg.get("current_pattern", "—")
                                self._active_dj = msg.get("active_dj")
                                await self._maybe_update_presence()

                            elif msg_type == "state":
                                bpm = msg.get("bpm") or msg.get("audio", {}).get("bpm")
                                if bpm is not None:
                                    self._current_bpm = float(bpm)
                                    await self._maybe_update_presence()

                            elif msg_type == "pattern_changed":
                                self._current_pattern = msg.get("pattern", "—")
                                await self._maybe_update_presence()

                            elif msg_type == "active_dj_changed":
                                self._active_dj = msg.get("dj_id")
                                await self._maybe_update_presence()

                        except json.JSONDecodeError:
                            pass
                        except Exception as e:
                            logger.debug(f"Error processing VJ message: {e}")

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.warning(f"VJ server connection lost: {e}")
            finally:
                self._vj_ws = None
                self._vj_connected = False

            logger.info(f"Reconnecting to VJ server in {backoff:.0f}s...")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, max_backoff)

    # ------------------------------------------------------------------
    # Discord presence
    # ------------------------------------------------------------------

    async def _maybe_update_presence(self):
        """Update Discord presence at most every 15 seconds."""
        now = time.monotonic()
        if now - self._last_presence_update < 15.0:
            return
        self._last_presence_update = now

        if self._active_dj:
            bpm_str = f" | {self._current_bpm:.0f} BPM" if self._current_bpm > 0 else ""
            name = f"{self._current_pattern}{bpm_str}"
        else:
            name = "Waiting for DJ..."

        try:
            await self.change_presence(
                activity=discord.Activity(
                    type=discord.ActivityType.listening,
                    name=name,
                ),
            )
        except Exception:
            pass

    # ------------------------------------------------------------------
    # Slash commands
    # ------------------------------------------------------------------

    async def setup_hook(self):
        """Register slash commands and start VJ connection."""
        self._register_commands()
        self._vj_task = asyncio.create_task(self._vj_connect_loop())
        logger.info("Commands registered, VJ connect loop started")

    def _register_commands(self):
        @self.tree.command(
            name="join", description="Join your voice channel and start playing DJ audio"
        )
        async def join_cmd(interaction: discord.Interaction):
            if not interaction.user.voice:
                await interaction.response.send_message(
                    "You need to be in a voice channel!", ephemeral=True
                )
                return

            channel = interaction.user.voice.channel
            vc = interaction.guild.voice_client

            if vc:
                await vc.move_to(channel)
            else:
                vc = await channel.connect()

            # Create audio source and start playback
            self._audio_source = VJAudioSource()
            if vc.is_playing():
                vc.stop()
            vc.play(self._audio_source)

            await interaction.response.send_message(
                f"Joined **{channel.name}** — streaming DJ audio"
            )

        @self.tree.command(name="leave", description="Leave the voice channel")
        async def leave_cmd(interaction: discord.Interaction):
            vc = interaction.guild.voice_client
            if vc:
                vc.stop()
                await vc.disconnect()
                self._audio_source = None
                await interaction.response.send_message("Left voice channel")
            else:
                await interaction.response.send_message(
                    "I'm not in a voice channel", ephemeral=True
                )

        @self.tree.command(name="status", description="Show bot status")
        async def status_cmd(interaction: discord.Interaction):
            vc = interaction.guild.voice_client
            voice_info = vc.channel.name if vc else "Not connected"
            vj_info = "Connected" if self._vj_connected else "Disconnected"
            dj_info = self._active_dj or "None"
            pattern_info = self._current_pattern
            bpm_info = f"{self._current_bpm:.0f}" if self._current_bpm > 0 else "—"
            buf_depth = len(self._audio_source._buffer) if self._audio_source else 0

            embed = discord.Embed(
                title="AudioViz Bot Status",
                color=0x00FF88 if self._vj_connected else 0x888888,
            )
            embed.add_field(name="VJ Server", value=vj_info, inline=True)
            embed.add_field(name="Voice Channel", value=voice_info, inline=True)
            embed.add_field(name="Active DJ", value=dj_info, inline=True)
            embed.add_field(name="Pattern", value=pattern_info, inline=True)
            embed.add_field(name="BPM", value=bpm_info, inline=True)
            embed.add_field(name="Buffer", value=f"{buf_depth}/50 frames", inline=True)

            await interaction.response.send_message(embed=embed)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    async def on_ready(self):
        logger.info(f"Logged in as {self.user} (ID: {self.user.id})")
        logger.info(f"Connected to {len(self.guilds)} guild(s)")

        try:
            synced = await self.tree.sync()
            logger.info(f"Synced {len(synced)} command(s)")
        except Exception as e:
            logger.error(f"Failed to sync commands: {e}")

        await self._maybe_update_presence()

    async def close(self):
        if self._vj_task and not self._vj_task.done():
            self._vj_task.cancel()
            try:
                await self._vj_task
            except asyncio.CancelledError:
                pass
        await super().close()


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


async def main():
    token = os.getenv("DISCORD_BOT_TOKEN")
    if not token:
        logger.error("DISCORD_BOT_TOKEN not set — add it to .env or environment")
        return

    bot = AudioVizBot()
    vj_host = bot._vj_host
    vj_port = bot._vj_port
    logger.info(f"Starting bot (VJ server: {vj_host}:{vj_port})")
    await bot.start(token)


if __name__ == "__main__":
    asyncio.run(main())
