"""OSC-based track metadata source.

Listens for OSC messages from DJ software (Traktor, Ableton, etc.)
on a configurable UDP port.

Requires the optional `python-osc` package:
    pip install python-osc
"""

import logging
import threading
from typing import Optional

from .base import TrackInfo, TrackMetadataSource

logger = logging.getLogger(__name__)

try:
    from pythonosc import dispatcher as osc_dispatcher
    from pythonosc import osc_server

    HAS_OSC = True
except ImportError:
    HAS_OSC = False


class OSCSource(TrackMetadataSource):
    """Receives track metadata via OSC messages.

    Default address handlers:
        /track/title   - Track title (string)
        /track/artist  - Track artist (string)
        /track/bpm     - BPM (float)
        /track/key     - Musical key (string)
        /track/deck    - Deck identifier (string)

    Traktor-specific:
        /traktor/deck/*/track/title  - Per-deck title
    """

    def __init__(self, port: int = 9002, host: str = "0.0.0.0"):
        if not HAS_OSC:
            raise ImportError(
                "python-osc is required for OSC track source. "
                "Install it with: pip install python-osc"
            )

        self._host = host
        self._port = port
        self._server: Optional[osc_server.ThreadingOSCUDPServer] = None
        self._thread: Optional[threading.Thread] = None

        # Current values (updated by OSC handlers)
        self._title: Optional[str] = None
        self._artist: Optional[str] = None
        self._bpm: Optional[float] = None
        self._key: Optional[str] = None
        self._deck: Optional[str] = None

        self._changed = False
        self._lock = threading.Lock()

    def start(self):
        """Create dispatcher and start OSC server in a background thread."""
        disp = osc_dispatcher.Dispatcher()

        # Standard handlers
        disp.map("/track/title", self._handle_title)
        disp.map("/track/artist", self._handle_artist)
        disp.map("/track/bpm", self._handle_bpm)
        disp.map("/track/key", self._handle_key)
        disp.map("/track/deck", self._handle_deck)

        # Traktor-specific per-deck handlers
        disp.map("/traktor/deck/*/track/title", self._handle_traktor_title)

        self._server = osc_server.ThreadingOSCUDPServer((self._host, self._port), disp)
        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name="osc-track-source",
            daemon=True,
        )
        self._thread.start()
        logger.info("OSC track source listening on %s:%d", self._host, self._port)

    def stop(self):
        """Shut down the OSC server."""
        if self._server is not None:
            self._server.shutdown()
            self._server = None
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            self._thread = None
        logger.info("OSC track source stopped")

    def poll(self) -> Optional[TrackInfo]:
        """Return TrackInfo if any value changed since last poll."""
        with self._lock:
            if not self._changed:
                return None
            self._changed = False
            return TrackInfo(
                title=self._title,
                artist=self._artist,
                bpm=self._bpm,
                key=self._key,
                deck_id=self._deck,
                software_name="osc",
            )

    # -- OSC message handlers --------------------------------------------------

    def _handle_title(self, address: str, *args):
        if args:
            with self._lock:
                self._title = str(args[0])
                self._changed = True

    def _handle_artist(self, address: str, *args):
        if args:
            with self._lock:
                self._artist = str(args[0])
                self._changed = True

    def _handle_bpm(self, address: str, *args):
        if args:
            try:
                bpm = float(args[0])
            except (TypeError, ValueError):
                return
            with self._lock:
                self._bpm = bpm
                self._changed = True

    def _handle_key(self, address: str, *args):
        if args:
            with self._lock:
                self._key = str(args[0])
                self._changed = True

    def _handle_deck(self, address: str, *args):
        if args:
            with self._lock:
                self._deck = str(args[0])
                self._changed = True

    def _handle_traktor_title(self, address: str, *args):
        """Handle Traktor per-deck title: /traktor/deck/<N>/track/title."""
        if not args:
            return
        # Extract deck number from address
        parts = address.split("/")
        deck_id = None
        for i, part in enumerate(parts):
            if part == "deck" and i + 1 < len(parts):
                deck_id = parts[i + 1]
                break
        with self._lock:
            self._title = str(args[0])
            if deck_id is not None:
                self._deck = deck_id
            self._changed = True
