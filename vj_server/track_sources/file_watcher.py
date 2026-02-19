"""File-based track metadata source.

Watches a text/CSV file written by DJ software for track changes.
Supports common formats:
- Single-line: "Artist - Title"
- CSV: timestamp,deck,artist,title
- JSON: {"artist": "...", "title": "...", ...}
"""

import json
import logging
import os
from pathlib import Path
from typing import Optional

from .base import TrackInfo, TrackMetadataSource

logger = logging.getLogger(__name__)


class FileWatcherSource(TrackMetadataSource):
    """Watches a file for track metadata updates.

    Compatible with Serato history files, VirtualDJ nowplaying.txt,
    MIXXX broadcast metadata, and similar DJ software output.
    """

    def __init__(self, path: str | Path):
        self._path = Path(path)
        self._last_mtime: float = 0.0
        self._last_track: Optional[TrackInfo] = None

    def poll(self) -> Optional[TrackInfo]:
        """Check file mtime and parse last line if changed."""
        if not self._path.exists():
            return None

        try:
            mtime = os.path.getmtime(self._path)
        except OSError:
            return None

        if mtime <= self._last_mtime:
            return None

        self._last_mtime = mtime

        try:
            text = self._path.read_text(encoding="utf-8", errors="replace").strip()
        except OSError as e:
            logger.warning("Failed to read track file %s: %s", self._path, e)
            return None

        if not text:
            return None

        last_line = text.splitlines()[-1].strip()
        if not last_line:
            return None

        track = self._parse_line(last_line)
        if track is None:
            return None

        if track.has_changed(self._last_track):
            self._last_track = track
            logger.info("Track change detected: %s - %s", track.artist, track.title)
            return track

        return None

    def _parse_line(self, line: str) -> Optional[TrackInfo]:
        """Auto-detect format and parse a line into TrackInfo."""
        # Try JSON first
        if line.startswith("{"):
            return self._parse_json(line)

        # Try CSV (at least 3 commas suggests CSV)
        if line.count(",") >= 3:
            return self._parse_csv(line)

        # Fall back to "Artist - Title" format
        return self._parse_artist_title(line)

    def _parse_json(self, line: str) -> Optional[TrackInfo]:
        """Parse a JSON line."""
        try:
            data = json.loads(line)
        except json.JSONDecodeError:
            logger.debug("Failed to parse JSON track line: %s", line[:100])
            return None

        if not isinstance(data, dict):
            return None

        return TrackInfo(
            title=data.get("title"),
            artist=data.get("artist"),
            album=data.get("album"),
            bpm=float(data["bpm"]) if "bpm" in data else None,
            key=data.get("key"),
            deck_id=data.get("deck") or data.get("deck_id"),
            software_name=data.get("software"),
        )

    def _parse_csv(self, line: str) -> Optional[TrackInfo]:
        """Parse a CSV line: timestamp,deck,artist,title."""
        parts = line.split(",")
        if len(parts) < 4:
            return None

        # timestamp,deck,artist,title (title may contain commas)
        deck = parts[1].strip() or None
        artist = parts[2].strip() or None
        title = ",".join(parts[3:]).strip() or None

        return TrackInfo(title=title, artist=artist, deck_id=deck)

    def _parse_artist_title(self, line: str) -> Optional[TrackInfo]:
        """Parse 'Artist - Title' format."""
        if " - " in line:
            artist, _, title = line.partition(" - ")
            return TrackInfo(title=title.strip(), artist=artist.strip())

        # No separator found - treat entire line as title
        return TrackInfo(title=line)
