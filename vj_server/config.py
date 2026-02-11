"""
AudioViz Configuration - Centralized configuration management.

Provides:
- Audio presets for different music genres
- Type-safe configuration dataclasses
- Loading/saving from JSON/environment
"""

import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Dict, List, Optional


@dataclass
class AudioConfig:
    """Audio processing configuration."""

    # Envelope settings
    attack: float = 0.35  # Attack time (0-1, higher = faster response)
    release: float = 0.08  # Release time (0-1, higher = faster decay)

    # Beat detection
    beat_threshold: float = 1.3  # Multiplier over average for beat detection
    beat_sensitivity: float = 1.0  # Overall beat response strength

    # Gain control
    agc_max_gain: float = 8.0  # Maximum automatic gain

    # Frequency weighting
    bass_weight: float = 0.7  # Weight for bass in beat detection (0-1)

    # Per-band sensitivity [bass, low_mid, mid, high_mid, high]
    band_sensitivity: List[float] = field(default_factory=lambda: [1.0, 1.0, 1.0, 1.0, 1.0])

    # Auto-calibration
    auto_calibrate: bool = True

    def to_dict(self) -> dict:
        """Convert to dictionary for serialization."""
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "AudioConfig":
        """Create from dictionary."""
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


# Pre-tuned presets for different music styles
PRESETS: Dict[str, AudioConfig] = {
    "auto": AudioConfig(
        attack=0.35,
        release=0.08,
        beat_threshold=1.3,
        agc_max_gain=8.0,
        beat_sensitivity=1.0,
        bass_weight=0.7,
        band_sensitivity=[1.0, 1.0, 1.0, 1.0, 1.0],
        auto_calibrate=True,
    ),
    "edm": AudioConfig(
        attack=0.7,  # Fast attack for punchy beats
        release=0.15,  # Quick decay for fast BPM
        beat_threshold=1.1,  # Lower threshold = more beats detected
        agc_max_gain=10.0,  # Higher gain for dynamic range
        beat_sensitivity=1.5,  # Stronger beat response
        bass_weight=0.85,  # Heavy bass focus for EDM kicks
        band_sensitivity=[1.5, 0.8, 0.9, 1.2, 1.0],  # Boost bass
        auto_calibrate=False,
    ),
    "chill": AudioConfig(
        attack=0.25,  # Slower attack for smoother response
        release=0.05,  # Smooth decay
        beat_threshold=1.6,  # Higher threshold = fewer beats
        agc_max_gain=6.0,
        beat_sensitivity=0.7,
        bass_weight=0.5,  # Less bass focus, more balanced
        band_sensitivity=[0.9, 1.0, 1.1, 1.2, 1.3],  # Boost highs
        auto_calibrate=False,
    ),
    "rock": AudioConfig(
        attack=0.5,
        release=0.12,
        beat_threshold=1.3,
        agc_max_gain=8.0,
        beat_sensitivity=1.2,
        bass_weight=0.65,  # Drum-focused
        band_sensitivity=[1.2, 1.0, 1.0, 0.9, 0.8],  # Guitar/drums focus
        auto_calibrate=False,
    ),
    "hiphop": AudioConfig(
        attack=0.6,
        release=0.1,
        beat_threshold=1.2,
        agc_max_gain=9.0,
        beat_sensitivity=1.3,
        bass_weight=0.8,  # Strong 808 focus
        band_sensitivity=[1.4, 0.9, 1.0, 1.1, 0.9],  # Heavy bass
        auto_calibrate=False,
    ),
    "classical": AudioConfig(
        attack=0.2,  # Very smooth
        release=0.04,
        beat_threshold=1.8,  # Few beats
        agc_max_gain=5.0,
        beat_sensitivity=0.5,
        bass_weight=0.4,
        band_sensitivity=[0.8, 1.0, 1.2, 1.3, 1.4],  # Boost mids/highs
        auto_calibrate=False,
    ),
}


def get_preset(name: str) -> AudioConfig:
    """Get a preset by name, returns 'auto' if not found."""
    return PRESETS.get(name.lower(), PRESETS["auto"])


def list_presets() -> List[str]:
    """List available preset names."""
    return list(PRESETS.keys())


@dataclass
class PerformanceConfig:
    """Performance tuning configuration for audio capture and processing."""

    # MMCSS thread priority
    use_mmcss: bool = True
    mmcss_task_name: str = "Pro Audio"

    # Lock-free ring buffer
    use_lockfree_buffer: bool = True
    buffer_capacity: int = 16

    # WASAPI native format (disable AUTOCONVERTPCM)
    native_format: bool = True

    # QPC high-precision timestamps
    use_qpc_timestamps: bool = True

    # Bass lane for instant kick detection
    enable_bass_lane: bool = True
    bass_cutoff_hz: float = 120.0

    # Per-app capture fallback
    per_app_fallback_retries: int = 5
    per_app_retry_interval: float = 2.0
    fallback_to_loopback: bool = True

    def to_dict(self) -> dict:
        """Convert to dictionary for serialization."""
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "PerformanceConfig":
        """Create from dictionary."""
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


# Pre-defined performance profiles
PERFORMANCE_PROFILES = {
    "default": PerformanceConfig(),
    "low_latency": PerformanceConfig(
        use_mmcss=True,
        use_lockfree_buffer=True,
        native_format=True,
        use_qpc_timestamps=True,
        enable_bass_lane=True,
        buffer_capacity=8,  # Smaller buffer for lower latency
    ),
    "high_compatibility": PerformanceConfig(
        use_mmcss=False,
        use_lockfree_buffer=False,
        native_format=False,  # Use AUTOCONVERTPCM for compatibility
        use_qpc_timestamps=False,
        enable_bass_lane=True,
        buffer_capacity=32,  # Larger buffer for stability
    ),
}


def get_performance_profile(name: str) -> PerformanceConfig:
    """Get a performance profile by name."""
    return PERFORMANCE_PROFILES.get(name.lower(), PERFORMANCE_PROFILES["default"])


@dataclass
class ServerConfig:
    """Server connection configuration."""

    # Minecraft connection
    minecraft_host: str = "localhost"
    minecraft_port: int = 8765

    # VJ server
    vj_server_port: int = 9000

    # Preview server
    preview_port: int = 8766
    http_port: int = 8080

    # Authentication
    dj_auth_file: Optional[str] = "configs/dj_auth.json"

    @classmethod
    def from_env(cls) -> "ServerConfig":
        """Load configuration from environment variables."""
        return cls(
            minecraft_host=os.environ.get("MINECRAFT_HOST", "localhost"),
            minecraft_port=int(os.environ.get("MINECRAFT_PORT", "8765")),
            vj_server_port=int(os.environ.get("VJ_SERVER_PORT", "9000")),
            preview_port=int(os.environ.get("PREVIEW_PORT", "8766")),
            http_port=int(os.environ.get("HTTP_PORT", "8080")),
            dj_auth_file=os.environ.get("DJ_AUTH_FILE", "configs/dj_auth.json"),
        )


@dataclass
class AppConfig:
    """Complete application configuration."""

    audio: AudioConfig = field(default_factory=AudioConfig)
    server: ServerConfig = field(default_factory=ServerConfig)
    performance: PerformanceConfig = field(default_factory=PerformanceConfig)

    # Display settings
    show_spectrograph: bool = True
    compact_spectrograph: bool = False

    # Performance settings
    low_latency: bool = False
    tick_aligned: bool = False
    use_fft: bool = True

    # Zone settings
    zone: str = "main"
    entity_count: int = 16

    def save(self, path: Path) -> None:
        """Save configuration to JSON file."""
        data = {
            "audio": self.audio.to_dict(),
            "server": asdict(self.server),
            "performance_tuning": self.performance.to_dict(),
            "display": {
                "show_spectrograph": self.show_spectrograph,
                "compact_spectrograph": self.compact_spectrograph,
            },
            "latency": {
                "low_latency": self.low_latency,
                "tick_aligned": self.tick_aligned,
                "use_fft": self.use_fft,
            },
            "zone": self.zone,
            "entity_count": self.entity_count,
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w") as f:
            json.dump(data, f, indent=2)

    @classmethod
    def load(cls, path: Path) -> "AppConfig":
        """Load configuration from JSON file."""
        if not path.exists():
            return cls()

        with open(path) as f:
            data = json.load(f)

        config = cls()

        if "audio" in data:
            config.audio = AudioConfig.from_dict(data["audio"])

        if "server" in data:
            config.server = ServerConfig(**data["server"])

        if "performance_tuning" in data:
            config.performance = PerformanceConfig.from_dict(data["performance_tuning"])

        if "display" in data:
            config.show_spectrograph = data["display"].get("show_spectrograph", True)
            config.compact_spectrograph = data["display"].get("compact_spectrograph", False)

        # Support both old 'performance' and new 'latency' keys for backwards compatibility
        latency_data = data.get("latency", data.get("performance", {}))
        if latency_data:
            config.low_latency = latency_data.get("low_latency", False)
            config.tick_aligned = latency_data.get("tick_aligned", False)
            config.use_fft = latency_data.get("use_fft", True)

        config.zone = data.get("zone", "main")
        config.entity_count = data.get("entity_count", 16)

        return config


# Default config file location
DEFAULT_CONFIG_PATH = Path.home() / ".config" / "audioviz" / "config.json"


def load_config(path: Optional[Path] = None) -> AppConfig:
    """Load configuration from file or return defaults."""
    if path is None:
        path = DEFAULT_CONFIG_PATH
    return AppConfig.load(path)


def save_config(config: AppConfig, path: Optional[Path] = None) -> None:
    """Save configuration to file."""
    if path is None:
        path = DEFAULT_CONFIG_PATH
    config.save(path)
