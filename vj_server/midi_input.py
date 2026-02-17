"""MIDI input manager with soft import of mido."""

import asyncio
import logging
import time
from collections import deque
from typing import Dict, List, Optional

from .midi_config import MidiCCMapping, MidiConfig

try:
    import mido

    HAS_MIDI = True
except ImportError:
    mido = None
    HAS_MIDI = False

logger = logging.getLogger(__name__)


class MidiInputManager:
    """Manages MIDI input and maps messages to VJ parameters."""

    def __init__(
        self,
        config: Optional[MidiConfig] = None,
        loop: Optional[asyncio.AbstractEventLoop] = None,
    ):
        self._config = config or MidiConfig.default()
        self._loop = loop
        self._queue: asyncio.Queue = asyncio.Queue(maxsize=1024)
        self._port = None
        self._port_name: Optional[str] = None
        self._connected = False
        self._params: Dict[str, float] = {}
        self._triggered_actions: List[dict] = []
        self._clock_ticks: deque = deque(maxlen=48)
        self._clock_bpm: float = 0.0

        # Build lookup dicts from config
        self._cc_map: Dict[int, MidiCCMapping] = {m.cc_number: m for m in self._config.cc_mappings}
        self._note_map: Dict[int, dict] = {
            m.note_number: {"action": m.action, "velocity_sensitive": m.velocity_sensitive}
            for m in self._config.note_mappings
        }

    @staticmethod
    def list_ports() -> List[str]:
        """Enumerate available MIDI input ports."""
        if not HAS_MIDI:
            return []
        try:
            return mido.get_input_names()
        except Exception as e:
            logger.warning("Failed to enumerate MIDI ports: %s", e)
            return []

    def connect(self, port_name: Optional[str] = None) -> None:
        """Open a MIDI input port with callback.

        If port_name is None, uses the first available port.
        """
        if not HAS_MIDI:
            raise RuntimeError(
                "mido is not installed. Install with: pip install mido python-rtmidi"
            )

        if self._connected:
            self.disconnect()

        if port_name is None:
            ports = self.list_ports()
            if not ports:
                raise RuntimeError("No MIDI input ports available")
            port_name = ports[0]
            logger.info("Auto-selecting MIDI port: %s", port_name)

        self._port = mido.open_input(port_name, callback=self._midi_callback)
        self._port_name = port_name
        self._connected = True
        logger.info("Connected to MIDI port: %s", port_name)

    def _midi_callback(self, msg) -> None:
        """Callback from rtmidi C++ thread. Enqueues message thread-safely."""
        if self._loop is not None:
            try:
                self._loop.call_soon_threadsafe(self._queue.put_nowait, msg)
            except asyncio.QueueFull:
                pass  # Drop message if queue is full
        else:
            try:
                self._queue.put_nowait(msg)
            except asyncio.QueueFull:
                pass

    async def process_events(self) -> dict:
        """Drain queue without blocking, process MIDI messages.

        Returns dict with updated params and any triggered actions.
        """
        self._triggered_actions = []
        messages = []

        # Drain all available messages
        while not self._queue.empty():
            try:
                msg = self._queue.get_nowait()
                messages.append(msg)
            except asyncio.QueueEmpty:
                break

        for msg in messages:
            if msg.type == "control_change":
                mapping = self._cc_map.get(msg.control)
                if mapping:
                    value = self._cc_to_value(msg.value, mapping)
                    self._params[mapping.target] = value

            elif msg.type == "note_on" and msg.velocity > 0:
                note_info = self._note_map.get(msg.note)
                if note_info:
                    action = {
                        "action": note_info["action"],
                        "velocity": msg.velocity / 127.0
                        if note_info["velocity_sensitive"]
                        else 1.0,
                    }
                    self._triggered_actions.append(action)

            elif msg.type == "clock":
                self._clock_ticks.append(time.monotonic())
                if len(self._clock_ticks) >= 2:
                    intervals = [
                        self._clock_ticks[i] - self._clock_ticks[i - 1]
                        for i in range(1, len(self._clock_ticks))
                    ]
                    intervals.sort()
                    median = intervals[len(intervals) // 2]
                    if median > 0:
                        self._clock_bpm = 60.0 / (median * 24)

        return {
            "params": dict(self._params),
            "triggered_actions": list(self._triggered_actions),
        }

    def get_status(self) -> dict:
        """Return current MIDI status."""
        return {
            "connected": self._connected,
            "port": self._port_name,
            "clock_bpm": self._clock_bpm,
            "params": dict(self._params),
            "ports": self.list_ports(),
        }

    def disconnect(self) -> None:
        """Close the MIDI port."""
        if self._port is not None:
            try:
                self._port.close()
            except Exception as e:
                logger.warning("Error closing MIDI port: %s", e)
        self._port = None
        self._port_name = None
        self._connected = False
        self._clock_ticks.clear()
        self._clock_bpm = 0.0
        logger.info("MIDI disconnected")

    @staticmethod
    def _cc_to_value(cc_val: int, mapping: MidiCCMapping) -> float:
        """Convert 0-127 CC value to mapped range, handling invert."""
        normalized = cc_val / 127.0
        if mapping.invert:
            normalized = 1.0 - normalized
        return mapping.min_value + normalized * (mapping.max_value - mapping.min_value)
