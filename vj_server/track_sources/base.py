"""Track metadata source base classes and data types."""

import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class TrackInfo:
    """Current track information from DJ software."""

    title: Optional[str] = None
    artist: Optional[str] = None
    album: Optional[str] = None
    bpm: Optional[float] = None
    key: Optional[str] = None
    deck_id: Optional[str] = None
    software_name: Optional[str] = None
    elapsed_ms: Optional[int] = None
    duration_ms: Optional[int] = None
    timestamp: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        """Return dict with only non-None fields."""
        d = {"type": "track_metadata"}
        for k in (
            "title",
            "artist",
            "album",
            "bpm",
            "key",
            "deck_id",
            "software_name",
            "elapsed_ms",
            "duration_ms",
        ):
            v = getattr(self, k)
            if v is not None:
                d[k] = v
        return d

    def has_changed(self, other: Optional["TrackInfo"]) -> bool:
        """Check if the track identity has changed (title + artist + deck_id)."""
        if other is None:
            return True
        return (
            self.title != other.title
            or self.artist != other.artist
            or self.deck_id != other.deck_id
        )


class TrackMetadataSource(ABC):
    """Abstract base class for track metadata sources."""

    @abstractmethod
    def poll(self) -> Optional[TrackInfo]:
        """Poll for current track info. Returns None if no update."""
        ...

    def start(self):
        """Start the source (optional setup)."""
        pass

    def stop(self):
        """Stop the source (optional cleanup)."""
        pass
