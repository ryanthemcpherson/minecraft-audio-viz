"""Track metadata manager.

Central manager that polls multiple track metadata sources and fires
callbacks when the current track changes.
"""

import logging
from typing import Callable, Dict, List, Optional

from vj_server.track_sources.base import TrackInfo, TrackMetadataSource

logger = logging.getLogger(__name__)


class TrackMetadataManager:
    """Manages multiple track metadata sources and tracks current state.

    Polls all registered sources, tracks current track per deck_id,
    and invokes a callback when the primary track changes.
    """

    def __init__(self, on_track_change: Optional[Callable[[TrackInfo], None]] = None):
        self._sources: List[TrackMetadataSource] = []
        self._on_track_change = on_track_change
        self._tracks_by_deck: Dict[str, TrackInfo] = {}
        self._primary_track: Optional[TrackInfo] = None

    def add_source(self, source: TrackMetadataSource):
        """Register a track metadata source."""
        self._sources.append(source)
        logger.info("Added track metadata source: %s", type(source).__name__)

    def start(self):
        """Start all registered sources."""
        for source in self._sources:
            try:
                source.start()
            except Exception:
                logger.exception("Failed to start source %s", type(source).__name__)

    def stop(self):
        """Stop all registered sources."""
        for source in self._sources:
            try:
                source.stop()
            except Exception:
                logger.exception("Failed to stop source %s", type(source).__name__)

    def poll_all(self) -> Optional[TrackInfo]:
        """Poll all sources. Returns the latest TrackInfo if anything changed, else None."""
        latest: Optional[TrackInfo] = None

        for source in self._sources:
            try:
                track = source.poll()
            except Exception:
                logger.exception("Error polling source %s", type(source).__name__)
                continue

            if track is None:
                continue

            deck = track.deck_id or "_default"
            prev = self._tracks_by_deck.get(deck)

            if track.has_changed(prev):
                self._tracks_by_deck[deck] = track
                latest = track
                logger.info(
                    "Track update [deck=%s]: %s - %s",
                    deck,
                    track.artist,
                    track.title,
                )

        if latest is not None:
            self._primary_track = latest
            if self._on_track_change is not None:
                try:
                    self._on_track_change(latest)
                except Exception:
                    logger.exception("Error in track change callback")

        return latest

    def get_current_track(self) -> Optional[TrackInfo]:
        """Return the most recently changed track (primary)."""
        return self._primary_track
