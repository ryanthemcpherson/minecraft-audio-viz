"""
Per-Application Audio Capture using pycaw
Captures audio levels from a specific Windows application (e.g., Spotify).

Requirements:
    pip install pycaw comtypes websockets

Usage:
    python -m audio_processor.app_capture --app spotify
"""

import argparse
import asyncio
import sys
import os
import signal
import math
import time
import json
import logging
import threading
import http.server
import socketserver
from pathlib import Path
from typing import Optional, List, Dict, Set
from dataclasses import dataclass

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from audio_processor.spectrograph import TerminalSpectrograph
from audio_processor.patterns import (
    PatternConfig, AudioState, get_pattern, list_patterns, PATTERNS
)
from python_client.viz_client import VizClient

try:
    import websockets
    from websockets.server import serve as ws_serve
    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False

# Optional FFT analyzer
try:
    from audio_processor.fft_analyzer import HybridAnalyzer, FFTResult
    HAS_FFT = True
except ImportError:
    HAS_FFT = False
    HybridAnalyzer = None
    FFTResult = None

# Optional timeline engine
try:
    from audio_processor.timeline import (
        TimelineEngine, TimelineState, Show, Cue, CueType
    )
    from audio_processor.timeline.cue_executor import CueExecutor
    from audio_processor.timeline.show_storage import ShowStorage
    HAS_TIMELINE = True
except ImportError:
    HAS_TIMELINE = False
    TimelineEngine = None
    TimelineState = None
    Show = None

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('app_capture')


# === AUDIO PRESETS ===
# Pre-tuned settings for different music genres/styles
PRESETS = {
    "auto": {
        "attack": 0.35,
        "release": 0.08,
        "beat_threshold": 1.3,
        "agc_max_gain": 8.0,
        "beat_sensitivity": 1.0,
        "bass_weight": 0.7,      # Balanced bass weight
        "band_sensitivity": [1.0, 1.0, 1.0, 1.0, 1.0, 1.0],
        "auto_calibrate": True  # Future: self-tuning
    },
    "edm": {
        "attack": 0.7,           # Fast attack for punchy beats
        "release": 0.15,         # Quick decay for fast BPM
        "beat_threshold": 1.1,   # Lower threshold = more beats detected
        "agc_max_gain": 10.0,    # Higher gain for dynamic range
        "beat_sensitivity": 1.5, # Stronger beat response
        "bass_weight": 0.85,     # Heavy bass focus for EDM kicks
        "band_sensitivity": [1.5, 1.3, 0.8, 0.9, 1.2, 1.0],  # Boost bass
        "auto_calibrate": False
    },
    "chill": {
        "attack": 0.25,          # Slower attack for smoother response
        "release": 0.05,         # Smooth decay
        "beat_threshold": 1.6,   # Higher threshold = fewer beats
        "agc_max_gain": 6.0,
        "beat_sensitivity": 0.7,
        "bass_weight": 0.5,      # Less bass focus, more balanced
        "band_sensitivity": [0.8, 0.9, 1.0, 1.1, 1.2, 1.3],  # Boost highs
        "auto_calibrate": False
    },
    "rock": {
        "attack": 0.5,
        "release": 0.12,
        "beat_threshold": 1.3,
        "agc_max_gain": 8.0,
        "beat_sensitivity": 1.2,
        "bass_weight": 0.65,     # Drum-focused
        "band_sensitivity": [1.2, 1.1, 1.0, 1.0, 0.9, 0.8],  # Guitar/drums focus
        "auto_calibrate": False
    }
}


@dataclass
class AppAudioFrame:
    """Audio frame from application capture."""
    timestamp: float
    peak: float           # Peak level (0-1)
    channels: List[float] # Per-channel levels
    is_beat: bool
    beat_intensity: float


class AppAudioCapture:
    """
    Captures audio levels from a specific Windows application using pycaw.

    Uses Windows Audio Session API (WASAPI) to get real-time audio meters
    from individual applications.
    """

    def __init__(self, app_name: str = "spotify"):
        """
        Initialize app audio capture.

        Args:
            app_name: Part of the process name to match (case-insensitive)
        """
        self.app_name = app_name.lower()
        self._session = None
        self._meter = None

        # === IMPROVED BEAT DETECTION STATE ===

        # Short-term energy (recent ~0.1 seconds for onset detection)
        self._short_history = []
        self._short_history_size = 6  # ~100ms at 60fps

        # Long-term energy (background noise floor, ~1.5 seconds)
        self._long_history = []
        self._long_history_size = 90

        # Spectral flux (rate of change detection)
        self._flux_history = []
        self._flux_history_size = 30
        self._prev_energy = 0.0

        # Bass-specific flux tracking (for EDM/electronic)
        self._bass_flux_history = []
        self._bass_flux_history_size = 20
        self._prev_bass_energy = 0.0

        # Beat timing
        self._last_beat_time = 0
        self._min_beat_interval = 0.08  # Allow up to ~180 BPM (faster for EDM)
        self._beat_cooldown = 0.0

        # Adaptive threshold (adjustable via UI)
        self._beat_threshold = 1.3  # Multiplier above average flux

        # Onset detection state
        self._onset_strength = 0.0
        self._onset_history = []
        self._onset_history_size = 15

        # Bass weight for beat detection (higher = more bass-focused)
        self._bass_weight = 0.7  # 70% bass, 30% full spectrum

        # Smoothing - lower = more reactive
        self._prev_peak = 0.0
        self._smoothing = 0.05  # Less smoothing for faster response

    def find_session(self) -> bool:
        """Find the audio session for the target application."""
        try:
            from pycaw.pycaw import AudioUtilities, IAudioMeterInformation
            from comtypes import CLSCTX_ALL

            sessions = AudioUtilities.GetAllSessions()

            for session in sessions:
                if session.Process:
                    process_name = session.Process.name().lower()
                    if self.app_name in process_name:
                        logger.info(f"Found audio session: {session.Process.name()} (PID: {session.Process.pid})")
                        self._session = session

                        # Get the audio meter interface
                        self._meter = session._ctl.QueryInterface(IAudioMeterInformation)
                        return True

            logger.warning(f"No audio session found for '{self.app_name}'")
            return False

        except Exception as e:
            logger.error(f"Error finding session: {e}")
            return False

    def list_sessions(self) -> List[Dict]:
        """List all active audio sessions."""
        try:
            from pycaw.pycaw import AudioUtilities

            sessions = AudioUtilities.GetAllSessions()
            result = []

            for session in sessions:
                if session.Process:
                    result.append({
                        'name': session.Process.name(),
                        'pid': session.Process.pid
                    })

            return result

        except Exception as e:
            logger.error(f"Error listing sessions: {e}")
            return []

    def get_peak(self) -> float:
        """Get the current peak audio level (0-1)."""
        if not self._meter:
            return 0.0

        try:
            return self._meter.GetPeakValue()
        except Exception:
            # Session may have ended
            return 0.0

    def get_channel_peaks(self) -> List[float]:
        """Get peak levels for each channel."""
        if not self._meter:
            return [0.0, 0.0]

        try:
            count = self._meter.GetMeteringChannelCount()
            peaks = self._meter.GetChannelsPeakValues(count)
            return list(peaks)
        except Exception:
            return [0.0, 0.0]

    def get_frame(self) -> AppAudioFrame:
        """Get current audio frame with beat detection."""
        peak = self.get_peak()
        channels = self.get_channel_peaks()

        # Apply smoothing
        smoothed_peak = self._prev_peak * self._smoothing + peak * (1 - self._smoothing)
        self._prev_peak = smoothed_peak

        # Estimate bass energy from stereo channels
        # Left channel often has more bass due to stereo panning conventions
        bass_estimate = max(channels) if channels else smoothed_peak

        # Beat detection with bass estimate
        is_beat, beat_intensity = self._detect_beat(smoothed_peak, bass_estimate)

        return AppAudioFrame(
            timestamp=time.time(),
            peak=smoothed_peak,
            channels=channels,
            is_beat=is_beat,
            beat_intensity=beat_intensity
        )

    def _detect_beat(self, energy: float, bass_energy: float = None) -> tuple:
        """
        Bass-weighted beat detection using spectral flux.
        Optimized for EDM/electronic music by emphasizing bass frequencies.
        Works in noisy environments by detecting CHANGES rather than absolute levels.
        """
        import statistics
        current_time = time.time()

        # Use bass energy if provided, otherwise fall back to total energy
        if bass_energy is None:
            bass_energy = energy

        # === 1. FULL SPECTRUM FLUX ===
        full_flux = max(0, energy - self._prev_energy)
        self._prev_energy = energy

        # === 2. BASS-SPECIFIC FLUX (key for EDM) ===
        bass_flux = max(0, bass_energy - self._prev_bass_energy)
        self._prev_bass_energy = bass_energy

        # === 3. COMBINED BASS-WEIGHTED FLUX ===
        # Weight heavily towards bass for EDM detection
        combined_flux = (bass_flux * self._bass_weight) + (full_flux * (1 - self._bass_weight))

        # === 4. UPDATE HISTORIES ===
        # Short-term (recent energy)
        self._short_history.append(energy)
        if len(self._short_history) > self._short_history_size:
            self._short_history.pop(0)

        # Long-term (noise floor reference)
        self._long_history.append(energy)
        if len(self._long_history) > self._long_history_size:
            self._long_history.pop(0)

        # Full spectrum flux history
        self._flux_history.append(full_flux)
        if len(self._flux_history) > self._flux_history_size:
            self._flux_history.pop(0)

        # Bass-specific flux history
        self._bass_flux_history.append(bass_flux)
        if len(self._bass_flux_history) > self._bass_flux_history_size:
            self._bass_flux_history.pop(0)

        # Onset strength history (using combined flux)
        self._onset_history.append(combined_flux)
        if len(self._onset_history) > self._onset_history_size:
            self._onset_history.pop(0)

        # Need enough history
        if len(self._long_history) < 20 or len(self._flux_history) < 10:
            return False, 0.0

        # === 5. CALCULATE ADAPTIVE THRESHOLD ===
        # Use bass flux for threshold calculation (more stable for EDM)
        avg_bass_flux = statistics.mean(self._bass_flux_history) if self._bass_flux_history else 0.01
        avg_full_flux = statistics.mean(self._flux_history)

        # Combined average weighted towards bass
        avg_flux = (avg_bass_flux * self._bass_weight) + (avg_full_flux * (1 - self._bass_weight))

        # Variance from bass flux (EDM should have consistent kick patterns)
        flux_variance = statistics.variance(self._bass_flux_history) if len(self._bass_flux_history) > 1 else 0.01

        # Adaptive threshold: EDM with consistent kicks = lower variance = lower threshold
        # Noisy/varied music = higher variance = higher threshold
        variance_factor = max(0.8, min(1.5, 1.0 + flux_variance * 8))
        threshold = avg_flux * self._beat_threshold * variance_factor

        # Minimum threshold to avoid false positives in silence
        threshold = max(threshold, 0.015)

        # === 6. ONSET STRENGTH (local peak in flux) ===
        # A beat should be a LOCAL MAXIMUM in onset strength
        if len(self._onset_history) >= 3:
            recent = self._onset_history[-3:]
            is_local_peak = combined_flux >= recent[0] and combined_flux >= recent[1]
        else:
            is_local_peak = True

        # === 7. BEAT DETECTION ===
        # Cooldown decay
        self._beat_cooldown = max(0, self._beat_cooldown - 0.016)

        # Check for beat
        is_beat = False
        intensity = 0.0

        time_since_last = current_time - self._last_beat_time
        cooldown_ready = self._beat_cooldown <= 0

        if combined_flux > threshold and is_local_peak and time_since_last > self._min_beat_interval and cooldown_ready:
            is_beat = True
            self._last_beat_time = current_time

            # Intensity based on how much we exceeded threshold (bass-weighted)
            intensity = min(1.0, (combined_flux - avg_flux) / max(0.03, avg_flux))

            # Dynamic cooldown based on beat strength
            # Strong beats = longer cooldown (prevents double-triggers)
            self._beat_cooldown = 0.04 + intensity * 0.06

        return is_beat, intensity


class MultiDirectoryHandler(http.server.SimpleHTTPRequestHandler):
    """HTTP handler that serves from multiple directories based on URL path."""
    directory_map = {}

    def translate_path(self, path):
        """Translate URL path to file system path."""
        path = path.split('?')[0].split('#')[0]
        for url_prefix, fs_directory in self.directory_map.items():
            if path.startswith(url_prefix):
                relative_path = path[len(url_prefix):].lstrip('/')
                return os.path.join(fs_directory, relative_path)
        if '/' in self.directory_map:
            return os.path.join(self.directory_map['/'], path.lstrip('/'))
        return super().translate_path(path)

    def log_message(self, format, *args):
        pass  # Suppress logging


def run_http_server(port: int, directory: str):
    """Run HTTP server for static files in a separate thread."""
    project_root = Path(directory).parent.parent
    admin_dir = project_root / 'admin_panel'

    # Configure directory mapping
    MultiDirectoryHandler.directory_map = {
        '/admin': str(admin_dir) if admin_dir.exists() else str(directory),
        '/': str(directory),
    }

    os.chdir(str(project_root))

    with socketserver.TCPServer(("", port), MultiDirectoryHandler) as httpd:
        httpd.serve_forever()


class AppCaptureAgent:
    """
    Captures audio from a specific application and sends visualization
    data to Minecraft and browser preview.
    """

    def __init__(self,
                 app_name: str = "spotify",
                 minecraft_host: str = "localhost",
                 minecraft_port: int = 8765,
                 zone: str = "main",
                 entity_count: int = 16,
                 show_spectrograph: bool = True,
                 compact_spectrograph: bool = False,
                 broadcast_port: int = 8766,
                 http_port: int = 8080,
                 vscode_mode: bool = False,
                 use_fft: bool = True,
                 low_latency: bool = False,
                 ultra_low_latency: bool = False):

        self.app_name = app_name
        self.minecraft_host = minecraft_host
        self.minecraft_port = minecraft_port
        self.zone = zone
        self.entity_count = entity_count
        self.show_spectrograph = show_spectrograph
        self.compact_spectrograph = compact_spectrograph
        self.broadcast_port = broadcast_port
        self.http_port = http_port
        self.vscode_mode = vscode_mode
        self.use_fft = use_fft
        self.low_latency = low_latency
        self.ultra_low_latency = ultra_low_latency

        # Frame timing based on latency mode
        if ultra_low_latency:
            self._frame_interval = 0.008  # 125 FPS for ultra-low latency
        elif low_latency:
            self._frame_interval = 0.012  # ~83 FPS for low latency
        else:
            self._frame_interval = 0.016  # ~60 FPS normal

        # Components
        self.capture = AppAudioCapture(app_name)
        self.viz_client: Optional[VizClient] = None

        if show_spectrograph:
            if compact_spectrograph:
                from audio_processor.spectrograph import CompactSpectrograph
                self.spectrograph = CompactSpectrograph()
            else:
                # Pass vscode_mode - None means auto-detect, True forces VS Code mode
                self.spectrograph = TerminalSpectrograph(
                    vscode_mode=True if vscode_mode else None
                )
        else:
            self.spectrograph = None

        # Initialize FFT analyzer if available and requested
        self.fft_analyzer = None
        self._using_fft = False
        if use_fft and HAS_FFT and HybridAnalyzer is not None:
            try:
                self.fft_analyzer = HybridAnalyzer(
                    low_latency=low_latency,
                    ultra_low_latency=ultra_low_latency
                )
                self._using_fft = self.fft_analyzer.using_fft
                if self._using_fft:
                    stats = self.fft_analyzer.latency_stats
                    fft_ms = stats.get('fft_latency_ms', 0)
                    hop_ms = stats.get('hop_interval_ms', 0)
                    if ultra_low_latency:
                        mode = "ULTRA-LOW-LATENCY"
                    elif low_latency:
                        mode = "LOW-LATENCY"
                    else:
                        mode = "NORMAL"
                    logger.info(f"FFT analyzer active [{mode}] - FFT: {fft_ms:.0f}ms, Update: {hop_ms:.0f}ms, Loop: {self._frame_interval*1000:.0f}ms")
                else:
                    logger.info("FFT analyzer initialized - will use when audio available")
            except Exception as e:
                logger.warning(f"Failed to initialize FFT analyzer: {e}")
        elif use_fft and not HAS_FFT:
            logger.info("FFT not available (install pyaudiowpatch). Using synthetic bands.")

        self._running = False
        self._frame_count = 0

        # WebSocket broadcast clients (browser previews)
        self._broadcast_clients: Set = set()

        # Pattern system
        self._pattern_config = PatternConfig(entity_count=entity_count)
        self._current_pattern = get_pattern("spectrum", self._pattern_config)
        self._pattern_name = "spectrum"

        # === MODERN AUDIO VISUALIZATION STATE ===

        # Rolling energy history for AGC (Auto-Gain Control)
        self._agc_history_size = 90  # ~1.5 seconds at 60fps
        self._energy_history = []     # Rolling peak values
        self._agc_gain = 1.0          # Current gain multiplier
        self._agc_target = 0.85       # Target output level (85% of range)
        self._agc_attack = 0.15       # Fast attack - respond quickly to loud
        self._agc_release = 0.008     # Very slow release - don't drop too fast
        self._agc_min_gain = 1.0      # Minimum gain (never reduce below input)
        self._agc_max_gain = 8.0      # Maximum gain boost

        # Per-band rolling history for adaptive normalization
        self._band_history_size = 45  # ~0.75 seconds per band
        self._band_histories = [[] for _ in range(6)]
        self._band_max_history = [[] for _ in range(6)]  # Track per-band peaks

        # Temporal smoothing (exponential moving average)
        self._smoothed_bands = [0.0] * 6
        self._smooth_attack = 0.35    # Fast attack (respond to increases)
        self._smooth_release = 0.08   # Slower release (decay smoothly)

        # Band simulation state
        self._prev_bands = [0.0] * 6
        self._band_velocities = [0.0] * 6

        # Per-band characteristics
        self._band_phases = [0.0, 0.3, 0.7, 1.1, 1.6, 2.2]  # Phase offsets
        self._band_speeds = [0.8, 1.2, 1.8, 2.5, 3.5, 4.5]  # Oscillation speeds
        self._band_decay = [0.94, 0.90, 0.85, 0.78, 0.68, 0.55]  # Energy decay rates
        self._band_energy = [0.0] * 6
        self._band_drift = [0.0] * 6

        # Transient detection
        self._last_peak = 0.0
        self._peak_delta = 0.0
        self._transient_history = []
        self._transient_history_size = 10

        # Beat sensitivity multiplier (adjustable via UI)
        self._beat_sensitivity = 1.0

        # Per-band sensitivity multipliers (adjustable via UI)
        self._band_sensitivity = [1.0, 1.0, 1.0, 1.0, 1.0, 1.0]

        # Current preset
        self._current_preset = "auto"

        # === AUTO-CALIBRATION STATE ===
        self._auto_calibrate_enabled = True  # Enabled by default with "auto" preset
        self._calibration_frame_count = 0
        self._calibration_warmup_frames = 180  # 3 seconds at 60fps

        # Statistics for auto-calibration
        self._calibration_energy_history = []
        self._calibration_flux_history = []
        self._calibration_beat_times = []
        self._calibration_history_size = 300  # 5 seconds of history

        # Estimated music characteristics
        self._estimated_bpm = 120.0
        self._music_variance = 0.5  # 0 = static, 1 = very dynamic
        self._last_calibration_frame = 0
        self._calibration_interval = 120  # Recalibrate every 2 seconds

        # === TIMELINE ENGINE ===
        self.timeline = None
        self.cue_executor = None
        self.show_storage = None
        self._active_effects = {}  # Active effects with end times

        if HAS_TIMELINE:
            self.timeline = TimelineEngine()
            self.show_storage = ShowStorage()
            self.cue_executor = CueExecutor()

            # Wire up cue executor handlers
            self.cue_executor.set_handlers(
                pattern_handler=self._set_pattern_from_cue,
                preset_handler=self._apply_preset,
                parameter_handler=self._set_parameter_from_cue,
                effect_handler=self._trigger_effect
            )

            # Wire up timeline callbacks
            self.timeline.set_callbacks(
                on_cue_fire=self._on_cue_fire,
                on_state_change=self._on_timeline_state_change
            )

            logger.info("Timeline engine initialized")

    def _set_pattern_from_cue(self, pattern_name: str):
        """Set pattern from a cue action."""
        if pattern_name in PATTERNS:
            self._pattern_name = pattern_name
            self._current_pattern = get_pattern(pattern_name, self._pattern_config)
            logger.info(f"Cue: Pattern changed to {pattern_name}")

    def _set_parameter_from_cue(self, param_name: str, value: float):
        """Set a parameter from a cue action."""
        param_map = {
            "attack": "_smooth_attack",
            "release": "_smooth_release",
            "agc_max_gain": "_agc_max_gain",
            "beat_sensitivity": "_beat_sensitivity",
        }
        if param_name in param_map:
            setattr(self, param_map[param_name], value)
            logger.info(f"Cue: Parameter {param_name} set to {value}")

    def _trigger_effect(self, effect_type: str, intensity: float, duration: int):
        """Trigger a visual effect."""
        end_time = time.time() + (duration / 1000)
        self._active_effects[effect_type] = {
            "intensity": intensity,
            "end_time": end_time,
            "duration": duration
        }
        logger.info(f"Effect triggered: {effect_type} (intensity={intensity}, duration={duration}ms)")

    def _on_cue_fire(self, cue):
        """Called when a cue fires."""
        if self.cue_executor:
            self.cue_executor.execute(cue)

    def _on_timeline_state_change(self, state):
        """Called when timeline state changes."""
        logger.info(f"Timeline state: {state.value}")

    def _apply_preset(self, preset_name: str):
        """Apply a preset configuration."""
        if preset_name not in PRESETS:
            logger.warning(f"Unknown preset: {preset_name}")
            return

        preset = PRESETS[preset_name]
        logger.info(f"Applying preset: {preset_name}")

        # Apply all preset settings
        self._smooth_attack = preset["attack"]
        self._smooth_release = preset["release"]
        self.capture._beat_threshold = preset["beat_threshold"]
        self._agc_max_gain = preset["agc_max_gain"]
        self._beat_sensitivity = preset["beat_sensitivity"]
        self._band_sensitivity = preset["band_sensitivity"].copy()

        # Apply bass weight for beat detection
        if "bass_weight" in preset:
            self.capture._bass_weight = preset["bass_weight"]

        # Store current preset name
        self._current_preset = preset_name

        logger.info(f"  Attack: {self._smooth_attack}, Release: {self._smooth_release}")
        logger.info(f"  Beat threshold: {self.capture._beat_threshold}, Bass weight: {self.capture._bass_weight}")

        # Enable/disable auto-calibration based on preset
        self._auto_calibrate_enabled = preset.get("auto_calibrate", False)
        if self._auto_calibrate_enabled:
            logger.info("  Auto-calibration: ENABLED")

    def _auto_calibrate(self, energy: float, is_beat: bool):
        """
        Auto-calibration system that adapts to music characteristics.
        Called every frame when auto_calibrate is enabled.
        """
        import statistics

        self._calibration_frame_count += 1

        # Update calibration history
        self._calibration_energy_history.append(energy)
        if len(self._calibration_energy_history) > self._calibration_history_size:
            self._calibration_energy_history.pop(0)

        # Track beat times for tempo estimation
        if is_beat:
            self._calibration_beat_times.append(self._calibration_frame_count)
            # Keep only recent beats
            cutoff = self._calibration_frame_count - self._calibration_history_size
            self._calibration_beat_times = [t for t in self._calibration_beat_times if t > cutoff]

        # Don't calibrate during warmup
        if self._calibration_frame_count < self._calibration_warmup_frames:
            return

        # Only recalibrate periodically
        frames_since_calibration = self._calibration_frame_count - self._last_calibration_frame
        if frames_since_calibration < self._calibration_interval:
            return

        self._last_calibration_frame = self._calibration_frame_count

        # Need enough data
        if len(self._calibration_energy_history) < 60:
            return

        # === ESTIMATE MUSIC CHARACTERISTICS ===

        # 1. Energy statistics
        energy_mean = statistics.mean(self._calibration_energy_history)
        energy_stdev = statistics.stdev(self._calibration_energy_history) if len(self._calibration_energy_history) > 1 else 0.1
        energy_max = max(self._calibration_energy_history)

        # Variance ratio: high = dynamic music, low = static
        self._music_variance = min(1.0, energy_stdev / max(0.01, energy_mean) * 2)

        # 2. Tempo estimation from beat intervals
        if len(self._calibration_beat_times) >= 4:
            intervals = []
            for i in range(1, len(self._calibration_beat_times)):
                interval_frames = self._calibration_beat_times[i] - self._calibration_beat_times[i-1]
                if 15 <= interval_frames <= 90:  # Valid range: 40-240 BPM
                    intervals.append(interval_frames)

            if intervals:
                avg_interval_frames = statistics.median(intervals)
                # Convert frames to BPM (at 60fps)
                self._estimated_bpm = 60 * 60 / avg_interval_frames

        # === APPLY AUTO-ADJUSTMENTS ===

        # 3. Adjust attack/release based on tempo
        if self._estimated_bpm > 150:
            # Fast music (EDM, drum & bass): fast attack, quick release
            target_attack = 0.6
            target_release = 0.12
        elif self._estimated_bpm > 110:
            # Medium tempo (house, pop): balanced
            target_attack = 0.4
            target_release = 0.08
        else:
            # Slow music (chill, ambient): slow attack, smooth release
            target_attack = 0.25
            target_release = 0.05

        # Smooth transition to new values
        self._smooth_attack += (target_attack - self._smooth_attack) * 0.1
        self._smooth_release += (target_release - self._smooth_release) * 0.1

        # 4. Adjust beat threshold based on music variance
        # High variance = need higher threshold to avoid false positives
        # Low variance = can use lower threshold for more sensitivity
        target_threshold = 1.1 + self._music_variance * 0.5  # Range: 1.1 to 1.6

        self.capture._beat_threshold += (target_threshold - self.capture._beat_threshold) * 0.15

        # 5. Adjust AGC based on overall energy levels
        if energy_max < 0.3:
            # Quiet music: boost more
            target_agc = min(12.0, self._agc_max_gain + 1)
        elif energy_max > 0.8:
            # Loud music: reduce boost
            target_agc = max(4.0, self._agc_max_gain - 1)
        else:
            target_agc = 8.0

        self._agc_max_gain += (target_agc - self._agc_max_gain) * 0.1

        # Log calibration results periodically
        if self._calibration_frame_count % 300 == 0:  # Every 5 seconds
            logger.debug(f"Auto-cal: BPM≈{self._estimated_bpm:.0f}, var={self._music_variance:.2f}, "
                        f"thresh={self.capture._beat_threshold:.2f}, attack={self._smooth_attack:.2f}")

    def _apply_audio_setting(self, setting: str, value: float):
        """Apply an audio reactivity setting from the UI."""
        if setting == "attack":
            # Attack: how fast bands respond to increases (0.1 to 0.9)
            self._smooth_attack = max(0.1, min(0.9, value))
            logger.info(f"Attack set to: {self._smooth_attack:.2f}")

        elif setting == "release":
            # Release: how fast bands decay (0.02 to 0.3)
            self._smooth_release = max(0.02, min(0.3, value))
            logger.info(f"Release set to: {self._smooth_release:.2f}")

        elif setting == "agc_max_gain":
            # Max AGC gain boost (1x to 12x)
            self._agc_max_gain = max(1.0, min(12.0, value))
            logger.info(f"AGC max gain set to: {self._agc_max_gain:.1f}x")

        elif setting == "beat_sensitivity":
            # Beat response multiplier (0.5x to 2x)
            self._beat_sensitivity = max(0.5, min(2.0, value))
            logger.info(f"Beat sensitivity set to: {self._beat_sensitivity:.2f}x")

        elif setting == "beat_threshold":
            # Beat detection threshold (0.8x to 2.0x) - higher = fewer beats detected
            self.capture._beat_threshold = max(0.8, min(2.0, value))
            logger.info(f"Beat threshold set to: {self.capture._beat_threshold:.2f}x")

    async def _handle_broadcast_client(self, websocket):
        """Handle a browser preview WebSocket connection."""
        self._broadcast_clients.add(websocket)
        logger.info(f"Browser preview connected. Clients: {len(self._broadcast_clients)}")

        # Send available patterns on connect
        await websocket.send(json.dumps({
            "type": "patterns",
            "patterns": list_patterns(),
            "current": self._pattern_name
        }))

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get("type")

                    if msg_type == "ping":
                        await websocket.send(json.dumps({"type": "pong"}))

                    elif msg_type == "set_pattern":
                        # Switch visualization pattern
                        pattern_name = data.get("pattern", "spectrum")
                        if pattern_name in PATTERNS:
                            self._pattern_name = pattern_name
                            self._current_pattern = get_pattern(pattern_name, self._pattern_config)
                            logger.info(f"Switched to pattern: {pattern_name}")
                            # Broadcast pattern change to all clients
                            await self._broadcast_pattern_change()

                    elif msg_type == "set_block_count":
                        # Change entity count dynamically
                        count = data.get("count", 16)
                        count = max(8, min(64, count))  # Clamp between 8 and 64
                        self.entity_count = count
                        self._pattern_config = PatternConfig(entity_count=count)
                        self._current_pattern = get_pattern(self._pattern_name, self._pattern_config)
                        logger.info(f"Set block count to: {count}")

                    elif msg_type == "set_audio_setting":
                        # Update audio reactivity settings
                        setting = data.get("setting")
                        value = data.get("value")
                        self._apply_audio_setting(setting, value)

                    elif msg_type == "set_band_sensitivity":
                        # Update per-band sensitivity
                        band = data.get("band", 0)
                        sensitivity = data.get("sensitivity", 1.0)
                        if 0 <= band < 6:
                            self._band_sensitivity[band] = max(0.0, min(2.0, sensitivity))
                            band_names = ["Sub", "Bass", "Low", "Mid", "High", "Air"]
                            logger.info(f"{band_names[band]} sensitivity: {self._band_sensitivity[band]:.0%}")

                    elif msg_type == "set_preset":
                        # Apply preset configuration
                        preset_name = data.get("preset", "auto")
                        self._apply_preset(preset_name)
                        # Broadcast preset change to all clients
                        await self._broadcast_preset_change(preset_name)

                    elif msg_type == "get_patterns":
                        await websocket.send(json.dumps({
                            "type": "patterns",
                            "patterns": list_patterns(),
                            "current": self._pattern_name
                        }))

                    elif msg_type == "get_state":
                        # Send full state snapshot
                        await websocket.send(json.dumps({
                            "type": "state_snapshot",
                            "pattern": self._pattern_name,
                            "preset": self._current_preset,
                            "patterns": list_patterns(),
                            "timeline": self.timeline.get_status() if self.timeline else None,
                            "settings": {
                                "attack": self._smooth_attack,
                                "release": self._smooth_release,
                                "agc_max_gain": self._agc_max_gain,
                                "beat_sensitivity": self._beat_sensitivity,
                                "beat_threshold": self.capture._beat_threshold,
                                "band_sensitivity": self._band_sensitivity
                            }
                        }))

                    # === TIMELINE MESSAGES ===
                    elif msg_type == "timeline_play":
                        if self.timeline:
                            self.timeline.play()
                            await self._broadcast_timeline_status()

                    elif msg_type == "timeline_pause":
                        if self.timeline:
                            self.timeline.pause()
                            await self._broadcast_timeline_status()

                    elif msg_type == "timeline_stop":
                        if self.timeline:
                            self.timeline.stop()
                            await self._broadcast_timeline_status()

                    elif msg_type == "timeline_seek":
                        if self.timeline:
                            position = data.get("position", 0)
                            self.timeline.seek(position)
                            await self._broadcast_timeline_status()

                    elif msg_type == "load_show":
                        if self.timeline and self.show_storage:
                            show_id = data.get("show_id")
                            show_data = data.get("show")
                            if show_data:
                                # Load from provided data
                                show = Show.from_dict(show_data)
                            elif show_id:
                                # Load from storage
                                show = self.show_storage.load(show_id)
                            else:
                                show = None

                            if show:
                                self.timeline.load_show(show)
                                await websocket.send(json.dumps({
                                    "type": "show_loaded",
                                    "show": show.to_dict()
                                }))
                            await self._broadcast_timeline_status()

                    elif msg_type == "save_show":
                        if self.timeline and self.show_storage and self.timeline.show:
                            filepath = self.show_storage.save(self.timeline.show)
                            await websocket.send(json.dumps({
                                "type": "show_saved",
                                "filepath": filepath
                            }))

                    elif msg_type == "list_shows":
                        if self.show_storage:
                            shows = self.show_storage.list_shows()
                            await websocket.send(json.dumps({
                                "type": "show_list",
                                "shows": shows
                            }))

                    elif msg_type == "new_show":
                        if self.timeline:
                            name = data.get("name", "New Show")
                            duration = data.get("duration", 180000)
                            bpm = data.get("bpm", 128.0)
                            show = Show(name=name, duration=duration, bpm=bpm)
                            self.timeline.load_show(show)
                            await websocket.send(json.dumps({
                                "type": "show_loaded",
                                "show": show.to_dict()
                            }))
                            await self._broadcast_timeline_status()

                    elif msg_type == "create_demo_show":
                        if self.timeline and self.show_storage:
                            show = self.show_storage.create_demo_show()
                            self.timeline.load_show(show)
                            await websocket.send(json.dumps({
                                "type": "show_loaded",
                                "show": show.to_dict()
                            }))
                            await self._broadcast_timeline_status()

                    elif msg_type == "fire_cue":
                        if self.timeline:
                            cue_id = data.get("cue_id")
                            if cue_id:
                                self.timeline.fire_cue(cue_id)

                    elif msg_type == "arm_cue":
                        if self.timeline:
                            cue_id = data.get("cue_id")
                            armed = data.get("armed", True)
                            if cue_id:
                                self.timeline.arm_cue(cue_id, armed)

                    elif msg_type == "trigger_effect":
                        # Instant effect trigger from UI
                        effect_type = data.get("effect", "flash")
                        intensity = data.get("intensity", 1.0)
                        duration = data.get("duration", 500)
                        self._trigger_effect(effect_type, intensity, duration)

                    elif msg_type == "add_cue":
                        if self.timeline and self.timeline.show:
                            cue_data = data.get("cue", {})
                            track_type = cue_data.get("track", "patterns")
                            track = self.timeline.show.get_track(track_type)
                            if track:
                                cue = Cue.from_dict(cue_data)
                                track.add_cue(cue)
                                await websocket.send(json.dumps({
                                    "type": "cue_added",
                                    "cue": cue.to_dict()
                                }))

                    elif msg_type == "update_cue":
                        if self.timeline and self.timeline.show:
                            cue_id = data.get("cue_id")
                            updates = data.get("updates", {})
                            for track in self.timeline.show.tracks:
                                for cue in track.cues:
                                    if cue.id == cue_id:
                                        # Apply updates
                                        if "start_time" in updates:
                                            cue.start_time = updates["start_time"]
                                        if "duration" in updates:
                                            cue.duration = updates["duration"]
                                        if "name" in updates:
                                            cue.name = updates["name"]
                                        await websocket.send(json.dumps({
                                            "type": "cue_updated",
                                            "cue": cue.to_dict()
                                        }))
                                        break

                    elif msg_type == "delete_cue":
                        if self.timeline and self.timeline.show:
                            cue_id = data.get("cue_id")
                            for track in self.timeline.show.tracks:
                                if track.remove_cue(cue_id):
                                    await websocket.send(json.dumps({
                                        "type": "cue_deleted",
                                        "cue_id": cue_id
                                    }))
                                    break

                except json.JSONDecodeError:
                    pass
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self._broadcast_clients.discard(websocket)
            logger.info(f"Browser preview disconnected. Clients: {len(self._broadcast_clients)}")

    async def _broadcast_pattern_change(self):
        """Broadcast pattern change to all connected clients."""
        message = json.dumps({
            "type": "pattern_changed",
            "pattern": self._pattern_name,
            "patterns": list_patterns()
        })
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except:
                self._broadcast_clients.discard(client)

    async def _broadcast_preset_change(self, preset_name: str):
        """Broadcast preset change to all connected clients."""
        preset = PRESETS.get(preset_name, PRESETS["auto"])
        message = json.dumps({
            "type": "preset_changed",
            "preset": preset_name,
            "settings": {
                "attack": preset["attack"],
                "release": preset["release"],
                "beat_threshold": preset["beat_threshold"],
                "agc_max_gain": preset["agc_max_gain"],
                "beat_sensitivity": preset["beat_sensitivity"],
                "band_sensitivity": preset["band_sensitivity"]
            }
        })
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except:
                self._broadcast_clients.discard(client)

    async def _broadcast_timeline_status(self):
        """Broadcast timeline status to all connected clients."""
        if not self._broadcast_clients or not self.timeline:
            return

        message = json.dumps({
            "type": "timeline_status",
            **self.timeline.get_status()
        })
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except:
                self._broadcast_clients.discard(client)

    async def _broadcast_state(self, entities: List[dict], bands: List[float], frame: AppAudioFrame):
        """Broadcast visualization state to all connected browser previews."""
        if not self._broadcast_clients:
            return

        message = json.dumps({
            "type": "state",
            "entities": entities,
            "bands": bands,
            "amplitude": frame.peak,
            "is_beat": frame.is_beat,
            "beat_intensity": frame.beat_intensity,
            "frame": self._frame_count,
            "pattern": self._pattern_name
        })

        # Send to all clients
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except:
                self._broadcast_clients.discard(client)

    async def _start_broadcast_server(self):
        """Start WebSocket server for browser previews."""
        if not HAS_WEBSOCKETS:
            logger.warning("websockets not installed, browser preview disabled")
            return

        try:
            server = await ws_serve(
                self._handle_broadcast_client,
                "0.0.0.0",
                self.broadcast_port
            )
            logger.info(f"Browser preview server at ws://localhost:{self.broadcast_port}")
            return server
        except Exception as e:
            logger.error(f"Failed to start broadcast server: {e}")
            return None

    async def connect_minecraft(self) -> bool:
        """Connect to Minecraft server."""
        self.viz_client = VizClient(self.minecraft_host, self.minecraft_port)

        if not await self.viz_client.connect():
            logger.error(f"Failed to connect to Minecraft at {self.minecraft_host}:{self.minecraft_port}")
            return False

        logger.info(f"Connected to Minecraft at {self.minecraft_host}:{self.minecraft_port}")

        # Check zone
        zones = await self.viz_client.get_zones()
        zone_names = [z['name'] for z in zones]

        if self.zone not in zone_names:
            if zone_names:
                self.zone = zone_names[0]
                logger.info(f"Using zone: {self.zone}")
            else:
                logger.error("No zones available!")
                return False

        # Initialize entity pool
        await self.viz_client.init_pool(self.zone, self.entity_count, "SEA_LANTERN")
        await asyncio.sleep(0.5)

        return True

    def _update_agc(self, peak: float) -> float:
        """
        Auto-Gain Control: Dynamically adjust gain based on rolling history.
        Returns the gain-adjusted peak value.
        """
        # Add to energy history
        self._energy_history.append(peak)
        if len(self._energy_history) > self._agc_history_size:
            self._energy_history.pop(0)

        if len(self._energy_history) < 10:
            # Not enough history yet - use moderate boost
            return min(1.0, peak * 2.0)

        # Calculate rolling statistics
        import statistics
        rolling_max = max(self._energy_history)
        rolling_avg = statistics.mean(self._energy_history)
        rolling_p90 = sorted(self._energy_history)[int(len(self._energy_history) * 0.9)]

        # Use 90th percentile as reference (ignores occasional spikes)
        reference = max(rolling_p90, rolling_avg * 1.2, 0.05)  # Floor at 0.05

        # Calculate ideal gain to reach target level
        ideal_gain = self._agc_target / reference if reference > 0 else 1.0
        ideal_gain = max(self._agc_min_gain, min(self._agc_max_gain, ideal_gain))

        # Apply attack/release smoothing
        if ideal_gain > self._agc_gain:
            # Attack: increase gain quickly
            self._agc_gain += (ideal_gain - self._agc_gain) * self._agc_attack
        else:
            # Release: decrease gain slowly
            self._agc_gain += (ideal_gain - self._agc_gain) * self._agc_release

        # Apply gain and clamp
        return min(1.0, peak * self._agc_gain)

    def _generate_bands(self, peak: float, channels: List[float], is_beat: bool) -> List[float]:
        """
        Generate reactive frequency bands using modern visualization techniques:
        - Auto-Gain Control (AGC) for consistent output levels
        - Per-band rolling history for adaptive normalization
        - Temporal smoothing with attack/release
        - Logarithmic-inspired scaling
        - Variance-based beat response
        """
        import random
        import statistics

        # === 1. AUTO-GAIN CONTROL ===
        # Normalize input based on rolling history
        agc_peak = self._update_agc(peak)

        # === 2. TRANSIENT DETECTION ===
        self._peak_delta = peak - self._last_peak
        self._last_peak = peak

        # Track transient history for variance-based response
        self._transient_history.append(abs(self._peak_delta))
        if len(self._transient_history) > self._transient_history_size:
            self._transient_history.pop(0)

        transient_energy = sum(self._transient_history) / max(1, len(self._transient_history))

        # === 3. STEREO ANALYSIS ===
        stereo_spread = abs(channels[0] - channels[1]) if len(channels) >= 2 else 0

        bands = []

        for i in range(6):
            # === 4. PHASE MODULATION (organic movement) ===
            self._band_phases[i] += self._band_speeds[i] * 0.016
            phase_value = math.sin(self._band_phases[i])  # -1 to 1

            # === 5. BASE ENERGY CALCULATION ===
            # Different frequency bands respond differently to the signal
            if i < 2:
                # Sub-bass and bass: sustained, powerful
                base_energy = agc_peak * 1.0
                transient_mult = 3.0  # Less transient sensitive
            elif i < 4:
                # Low-mid and mid: balanced
                base_energy = agc_peak * 0.9
                transient_mult = 6.0  # More transient sensitive
            else:
                # High-mid and high: sparkly, transient-heavy
                base_energy = agc_peak * 0.75
                transient_mult = 10.0  # Very transient sensitive

            # Add transient energy
            base_energy += transient_energy * transient_mult

            # === 6. PHASE MODULATION ===
            phase_influence = 0.15 + (i * 0.05)  # 0.15 to 0.4
            modulated_energy = base_energy + phase_value * phase_influence * agc_peak

            # === 7. PER-BAND HISTORY & ADAPTIVE NORMALIZATION ===
            self._band_histories[i].append(modulated_energy)
            if len(self._band_histories[i]) > self._band_history_size:
                self._band_histories[i].pop(0)

            # Normalize band based on its own recent history
            if len(self._band_histories[i]) >= 5:
                band_max = max(self._band_histories[i])
                band_avg = statistics.mean(self._band_histories[i])
                # Use average of max and current for smoother normalization
                norm_reference = max(band_max * 0.7 + band_avg * 0.3, 0.1)
                normalized = modulated_energy / norm_reference
            else:
                normalized = modulated_energy

            # === 8. ENERGY DECAY (peak hold with decay) ===
            decay = self._band_decay[i]
            self._band_energy[i] = max(normalized, self._band_energy[i] * decay)

            # === 9. ORGANIC DRIFT (subtle random walk) ===
            drift_speed = 0.08 + (i * 0.03)
            self._band_drift[i] += random.uniform(-drift_speed, drift_speed) * 0.016
            self._band_drift[i] *= 0.95  # Dampen drift
            self._band_drift[i] = max(-0.15, min(0.15, self._band_drift[i]))

            # === 10. BEAT RESPONSE ===
            beat_boost = 0.0
            if is_beat:
                # Variance-based beat intensity (more dynamic music = stronger beats)
                if len(self._energy_history) > 5:
                    variance = statistics.variance(self._energy_history[-15:]) if len(self._energy_history) >= 15 else 0.01
                    # Adaptive threshold from research: threshold = -15 * var + 1.55
                    beat_multiplier = max(0.5, min(1.5, 1.0 + variance * 5))
                else:
                    beat_multiplier = 1.0

                # Apply user-adjustable beat sensitivity
                beat_multiplier *= self._beat_sensitivity

                if i == 0:      # Sub-bass: massive boom
                    beat_boost = 0.5 * beat_multiplier
                elif i == 1:    # Bass: powerful punch
                    beat_boost = 0.45 * beat_multiplier
                elif i == 2:    # Low-mid: solid hit
                    beat_boost = 0.35 * beat_multiplier
                elif i == 3:    # Mid: clear flash
                    beat_boost = 0.28 * beat_multiplier
                elif i == 4:    # High-mid: sparkle
                    beat_boost = 0.2 * beat_multiplier + random.uniform(0, 0.1)
                else:           # High: shimmer
                    beat_boost = 0.15 * beat_multiplier + random.uniform(0, 0.12)

            # === 11. STEREO VARIATION ===
            stereo_var = stereo_spread * (0.15 if i % 2 == 0 else 0.2)

            # === 12. COMBINE ALL FACTORS ===
            target = self._band_energy[i] + self._band_drift[i] + beat_boost + stereo_var

            # === 13. TEMPORAL SMOOTHING (EMA with attack/release) ===
            # Attack fast (react to increases), release slow (smooth decay)
            if target > self._smoothed_bands[i]:
                smooth_factor = self._smooth_attack
            else:
                smooth_factor = self._smooth_release

            self._smoothed_bands[i] += (target - self._smoothed_bands[i]) * smooth_factor

            # === 14. SPRING PHYSICS FOR FINAL OUTPUT ===
            spring = 50.0 + (i * 10)  # 50 to 100
            damping = 6.0 + (i * 0.5)
            dt = 0.016

            displacement = self._smoothed_bands[i] - self._prev_bands[i]
            force = spring * displacement - damping * self._band_velocities[i]

            self._band_velocities[i] += force * dt
            new_value = self._prev_bands[i] + self._band_velocities[i] * dt

            # === 15. APPLY PER-BAND SENSITIVITY ===
            new_value *= self._band_sensitivity[i]

            # === 16. FINAL CLAMPING (prevent stuck at ceiling) ===
            # Use soft ceiling to prevent constant max
            if new_value > 0.92:
                new_value = 0.92 + (new_value - 0.92) * 0.3  # Compress above 92%
            new_value = max(0, min(0.98, new_value))  # Hard limit at 98%

            self._prev_bands[i] = new_value
            bands.append(new_value)

        return bands

    async def run(self):
        """Run the capture agent."""
        # Start HTTP server for frontend in background thread (unless disabled)
        if self.http_port > 0:
            frontend_dir = Path(__file__).parent.parent / "preview_tool" / "frontend"
            if frontend_dir.exists():
                http_thread = threading.Thread(
                    target=run_http_server,
                    args=(self.http_port, str(frontend_dir)),
                    daemon=True
                )
                http_thread.start()
                logger.info(f"HTTP server at http://localhost:{self.http_port}")
            else:
                logger.warning(f"Frontend directory not found: {frontend_dir}")

        # Start broadcast server for browser previews
        broadcast_server = await self._start_broadcast_server()

        # Find the app's audio session
        logger.info(f"Looking for '{self.app_name}' audio session...")

        if not self.capture.find_session():
            logger.info("\nAvailable audio sessions:")
            sessions = self.capture.list_sessions()
            for s in sessions:
                logger.info(f"  - {s['name']} (PID: {s['pid']})")

            if not sessions:
                logger.error("No audio sessions found. Make sure the app is playing audio.")
            return

        self._running = True

        logger.info("Starting capture... Press Ctrl+C to stop")
        logger.info(f"Open http://localhost:{self.http_port} in your browser for visualization")

        try:
            while self._running:
                self._frame_count += 1

                # Get audio frame from pycaw (peak levels + synthetic beat detection)
                frame = self.capture.get_frame()

                # Try to get FFT data for real frequency analysis
                fft_result = None
                if self.fft_analyzer is not None:
                    fft_result = self.fft_analyzer.analyze(
                        synthetic_peak=frame.peak,
                        synthetic_bands=None  # Will generate if needed
                    )
                    # Update using_fft status
                    self._using_fft = self.fft_analyzer.using_fft

                # Use FFT bands if available, otherwise synthetic
                if fft_result is not None and self._using_fft:
                    bands = fft_result.bands
                    # Sanitize bands - protect against NaN/Inf
                    bands = [max(0.0, min(1.0, b)) if isinstance(b, (int, float)) and -1e10 < b < 1e10 else 0.0 for b in bands]

                    # Enhance beat detection with FFT onset info
                    if fft_result.kick_onset and not frame.is_beat:
                        # FFT detected a kick that pycaw missed
                        frame.is_beat = True
                        frame.beat_intensity = max(frame.beat_intensity, 0.8)
                else:
                    # Fall back to synthetic bands
                    bands = self._generate_bands(frame.peak, frame.channels, frame.is_beat)

                # Auto-calibrate if enabled (adjusts parameters based on music)
                if self._auto_calibrate_enabled:
                    self._auto_calibrate(frame.peak, frame.is_beat)

                # Update timeline engine
                if self.timeline:
                    self.timeline.update()
                    # Notify timeline of beats for beat-triggered cues
                    if frame.is_beat:
                        self.timeline.on_beat(frame.beat_intensity)

                # Clean up expired effects
                now = time.time()
                expired = [k for k, v in self._active_effects.items() if now >= v["end_time"]]
                for k in expired:
                    del self._active_effects[k]

                # Calculate entity positions ONCE (single source of truth)
                entities = self._calculate_entities(bands, frame)

                # Update spectrograph
                if self.spectrograph:
                    # Update stats for display
                    self.spectrograph.set_stats(
                        preset=self._current_preset,
                        bpm=self._estimated_bpm,
                        variance=self._music_variance,
                        attack=self._smooth_attack,
                        release=self._smooth_release,
                        threshold=self.capture._beat_threshold,
                        clients=len(self._broadcast_clients),
                        using_fft=self._using_fft
                    )
                    self.spectrograph.display(
                        bands=bands,
                        amplitude=frame.peak,
                        is_beat=frame.is_beat,
                        beat_intensity=frame.beat_intensity
                    )

                # Send same entities to Minecraft
                if self.viz_client and self.viz_client.connected:
                    await self._update_minecraft(entities, frame)

                # Send same entities to browser previews
                await self._broadcast_state(entities, bands, frame)

                await asyncio.sleep(self._frame_interval)  # FPS based on latency mode

        except Exception as e:
            logger.error(f"Capture error: {e}")
            raise

    def _calculate_entities(self, bands: List[float], frame: AppAudioFrame) -> List[dict]:
        """Calculate entity positions using the current pattern."""
        # Create audio state for pattern
        audio_state = AudioState(
            bands=bands,
            amplitude=frame.peak,
            is_beat=frame.is_beat,
            beat_intensity=frame.beat_intensity,
            frame=self._frame_count
        )

        # Use pattern to calculate entity positions
        return self._current_pattern.calculate_entities(audio_state)

    async def _update_minecraft(self, entities: List[dict], frame: AppAudioFrame):
        """Send pre-calculated entities to Minecraft."""
        try:
            particles = []
            if frame.is_beat and frame.beat_intensity > 0.2:
                particles.append({
                    "particle": "NOTE",
                    "x": 0.5,
                    "y": 0.5,
                    "z": 0.5,
                    "count": int(20 * frame.beat_intensity)
                })

            await self.viz_client.batch_update_fast(self.zone, entities, particles)

        except Exception as e:
            logger.error(f"Minecraft update error: {e}")

    def stop(self):
        """Stop the capture agent."""
        self._running = False

    async def cleanup(self):
        """Clean up resources."""
        if self.viz_client and self.viz_client.connected:
            await self.viz_client.set_visible(self.zone, False)
            await self.viz_client.disconnect()

        if self.fft_analyzer:
            self.fft_analyzer.stop()

        if self.spectrograph:
            self.spectrograph.clear()


async def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description='AudioViz Per-App Capture - Captures audio from a specific application'
    )
    parser.add_argument('--app', type=str, default='spotify',
                        help='Application name to capture (default: spotify)')
    parser.add_argument('--host', type=str, default='192.168.208.1',
                        help='Minecraft server IP (default: 192.168.208.1)')
    parser.add_argument('--port', type=int, default=8765,
                        help='WebSocket port (default: 8765)')
    parser.add_argument('--zone', type=str, default='main',
                        help='Visualization zone name (default: main)')
    parser.add_argument('--entities', type=int, default=16,
                        help='Number of visualization entities (default: 16)')
    parser.add_argument('--no-spectrograph', action='store_true',
                        help='Disable terminal spectrograph')
    parser.add_argument('--compact', action='store_true',
                        help='Use compact single-line spectrograph instead of TUI')
    parser.add_argument('--broadcast-port', type=int, default=8766,
                        help='WebSocket port for browser preview (default: 8766)')
    parser.add_argument('--http-port', type=int, default=8080,
                        help='HTTP port for web interface (default: 8080)')
    parser.add_argument('--no-http', action='store_true',
                        help='Disable built-in HTTP server (use external dev server)')
    parser.add_argument('--list', action='store_true',
                        help='List active audio sessions and exit')
    parser.add_argument('--no-minecraft', action='store_true',
                        help='Run without Minecraft connection (spectrograph only)')
    parser.add_argument('--vscode', action='store_true',
                        help='VS Code terminal compatibility mode (auto-detected usually)')
    parser.add_argument('--no-fft', action='store_true',
                        help='Disable FFT analysis (use synthetic bands only)')
    parser.add_argument('--low-latency', action='store_true',
                        help='Use low-latency FFT mode (~20ms vs ~45ms, trades bass resolution)')
    parser.add_argument('--ultra-low-latency', action='store_true',
                        help='Use ultra-low-latency mode (~10ms FFT, 125 FPS, minimal bass)')
    parser.add_argument('--list-audio', action='store_true',
                        help='List available audio devices and exit')

    args = parser.parse_args()

    # Check for pycaw
    try:
        from pycaw.pycaw import AudioUtilities
    except ImportError:
        logger.error("pycaw not installed. Run: pip install pycaw comtypes")
        sys.exit(1)

    # List audio devices mode
    if args.list_audio:
        if HAS_FFT:
            from audio_processor.fft_analyzer import list_audio_devices
            list_audio_devices()
        else:
            print("FFT not available. Install sounddevice: pip install sounddevice")
        sys.exit(0)

    # List mode
    if args.list:
        print("\nActive audio sessions:")
        print("-" * 40)
        capture = AppAudioCapture("")
        sessions = capture.list_sessions()
        if sessions:
            for s in sessions:
                print(f"  {s['name']:30} (PID: {s['pid']})")
        else:
            print("  No active audio sessions found.")
            print("  Make sure an application is playing audio.")
        print("-" * 40)
        sys.exit(0)

    # Create agent
    agent = AppCaptureAgent(
        app_name=args.app,
        minecraft_host=args.host,
        minecraft_port=args.port,
        zone=args.zone,
        entity_count=args.entities,
        show_spectrograph=not args.no_spectrograph,
        compact_spectrograph=args.compact,
        broadcast_port=args.broadcast_port,
        http_port=0 if args.no_http else args.http_port,
        vscode_mode=args.vscode,
        use_fft=not args.no_fft,
        low_latency=args.low_latency,
        ultra_low_latency=args.ultra_low_latency
    )

    # Signal handler
    def signal_handler(sig, frame):
        agent.stop()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    # Connect to Minecraft (optional)
    if not args.no_minecraft:
        logger.info(f"Connecting to Minecraft at {args.host}:{args.port}...")
        if not await agent.connect_minecraft():
            logger.warning("Continuing without Minecraft connection...")

    # Run
    try:
        await agent.run()
    finally:
        await agent.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
