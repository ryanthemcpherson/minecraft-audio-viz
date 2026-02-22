"""Shared fixtures for vj_server tests."""

import sys
from dataclasses import dataclass
from pathlib import Path

# Ensure vj_server package is importable
PROJECT_ROOT = Path(__file__).parent.parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


@dataclass
class FakeDJConnection:
    """Minimal stand-in for DJConnection used by _stabilize_bpm tests.

    Only includes fields that _stabilize_bpm actually reads/writes.
    """

    dj_id: str = "test_dj"
    dj_name: str = "Test DJ"
    bpm: float = 120.0
    tempo_confidence: float = 0.0
    beat_phase: float = 0.0
    phase_assist_last_time: float = 0.0


def make_audio_frame(**overrides) -> dict:
    """Build a valid audio frame dict with sensible defaults."""
    frame = {
        "bands": [0.5, 0.4, 0.3, 0.2, 0.1],
        "peak": 0.7,
        "beat": False,
        "beat_i": 0.0,
        "bpm": 128.0,
        "tempo_conf": 0.5,
        "beat_phase": 0.0,
        "seq": 1,
        "i_bass": 0.3,
        "i_kick": False,
        "ts": None,
    }
    frame.update(overrides)
    return frame
