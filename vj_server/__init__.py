"""
MCAV VJ Server - Multi-DJ visualization server for Minecraft.

Central server that accepts connections from remote DJs,
manages active DJ selection, and forwards visualization data
to Minecraft and browser clients.
"""

from .config import AudioConfig, ServerConfig, get_preset, list_presets
from .patterns import AudioState, PatternConfig, VisualizationPattern, get_pattern, list_patterns
from .vj_server import DJAuthConfig, DJConnection, VJServer

__all__ = [
    "VJServer",
    "DJConnection",
    "DJAuthConfig",
    "AudioConfig",
    "AudioState",
    "PatternConfig",
    "ServerConfig",
    "VisualizationPattern",
    "get_pattern",
    "list_patterns",
    "get_preset",
    "list_presets",
]
