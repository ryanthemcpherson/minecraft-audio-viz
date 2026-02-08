"""
AudioViz Audio Processor
Real-time audio processing for visualization.
"""

from .processor import AudioFrame, AudioProcessor
from .spectrograph import TerminalSpectrograph

__all__ = [
    "AudioProcessor",
    "AudioFrame",
    "TerminalSpectrograph",
]
