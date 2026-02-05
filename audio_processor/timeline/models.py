"""
Data models for the timeline system.
"""

from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any
from enum import Enum
import json
import uuid
import time


class CueType(Enum):
    PATTERN_CHANGE = "pattern_change"
    PRESET_CHANGE = "preset_change"
    PARAMETER_SET = "parameter_set"
    EFFECT_TRIGGER = "effect_trigger"


class TriggerType(Enum):
    TIME = "time"           # Fire at specific time
    BEAT = "beat"           # Fire on next beat after time
    FOLLOW = "follow"       # Fire after previous cue ends
    MANUAL = "manual"       # Fire only when manually triggered


class TransitionType(Enum):
    INSTANT = "instant"
    CROSSFADE = "crossfade"


@dataclass
class Transition:
    """Transition configuration for a cue."""
    type: TransitionType = TransitionType.INSTANT
    duration: int = 0  # ms
    easing: str = "linear"

    def to_dict(self) -> dict:
        return {
            "type": self.type.value,
            "duration": self.duration,
            "easing": self.easing
        }

    @classmethod
    def from_dict(cls, data: dict) -> 'Transition':
        return cls(
            type=TransitionType(data.get("type", "instant")),
            duration=data.get("duration", 0),
            easing=data.get("easing", "linear")
        )


@dataclass
class CueAction:
    """Action to perform when a cue fires."""
    pattern: Optional[str] = None
    preset: Optional[str] = None
    parameter: Optional[Dict[str, Any]] = None  # {"target": str, "value": float}
    effect: Optional[Dict[str, Any]] = None     # {"type": str, "intensity": float, "duration": int}

    def to_dict(self) -> dict:
        result = {}
        if self.pattern:
            result["pattern"] = self.pattern
        if self.preset:
            result["preset"] = self.preset
        if self.parameter:
            result["parameter"] = self.parameter
        if self.effect:
            result["effect"] = self.effect
        return result

    @classmethod
    def from_dict(cls, data: dict) -> 'CueAction':
        return cls(
            pattern=data.get("pattern"),
            preset=data.get("preset"),
            parameter=data.get("parameter"),
            effect=data.get("effect")
        )


@dataclass
class Cue:
    """A single cue in the timeline."""
    id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    name: str = ""
    type: CueType = CueType.PATTERN_CHANGE
    track: str = "patterns"  # Which track this cue belongs to
    start_time: int = 0      # ms from start
    duration: int = 1000     # ms
    trigger: TriggerType = TriggerType.TIME
    action: CueAction = field(default_factory=CueAction)
    transition: Transition = field(default_factory=Transition)
    armed: bool = False      # For manual trigger cues
    fired: bool = False      # Has this cue been executed in current playback

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "type": self.type.value,
            "track": self.track,
            "start_time": self.start_time,
            "duration": self.duration,
            "trigger": self.trigger.value,
            "action": self.action.to_dict(),
            "transition": self.transition.to_dict()
        }

    @classmethod
    def from_dict(cls, data: dict) -> 'Cue':
        return cls(
            id=data.get("id", str(uuid.uuid4())[:8]),
            name=data.get("name", ""),
            type=CueType(data.get("type", "pattern_change")),
            track=data.get("track", "patterns"),
            start_time=data.get("start_time", 0),
            duration=data.get("duration", 1000),
            trigger=TriggerType(data.get("trigger", "time")),
            action=CueAction.from_dict(data.get("action", {})),
            transition=Transition.from_dict(data.get("transition", {}))
        )

    def reset(self):
        """Reset cue state for new playback."""
        self.fired = False
        self.armed = False


@dataclass
class Track:
    """A track in the timeline containing cues of a specific type."""
    id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    name: str = "Track"
    type: str = "patterns"  # patterns, presets, effects, parameters
    color: str = "#4CAF50"
    cues: List[Cue] = field(default_factory=list)
    muted: bool = False
    solo: bool = False

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "type": self.type,
            "color": self.color,
            "cues": [cue.to_dict() for cue in self.cues],
            "muted": self.muted,
            "solo": self.solo
        }

    @classmethod
    def from_dict(cls, data: dict) -> 'Track':
        track = cls(
            id=data.get("id", str(uuid.uuid4())[:8]),
            name=data.get("name", "Track"),
            type=data.get("type", "patterns"),
            color=data.get("color", "#4CAF50"),
            muted=data.get("muted", False),
            solo=data.get("solo", False)
        )
        track.cues = [Cue.from_dict(c) for c in data.get("cues", [])]
        return track

    def add_cue(self, cue: Cue):
        """Add a cue to this track."""
        cue.track = self.type
        self.cues.append(cue)
        self.cues.sort(key=lambda c: c.start_time)

    def remove_cue(self, cue_id: str) -> bool:
        """Remove a cue by ID."""
        for i, cue in enumerate(self.cues):
            if cue.id == cue_id:
                self.cues.pop(i)
                return True
        return False


@dataclass
class Show:
    """A complete show with timeline and metadata."""
    id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    name: str = "Untitled Show"
    duration: int = 180000   # ms (3 minutes default)
    bpm: float = 128.0
    tracks: List[Track] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: float = field(default_factory=time.time)
    modified_at: float = field(default_factory=time.time)

    def __post_init__(self):
        # Create default tracks if empty
        if not self.tracks:
            self.tracks = [
                Track(name="Patterns", type="patterns", color="#4CAF50"),
                Track(name="Presets", type="presets", color="#2196F3"),
                Track(name="Effects", type="effects", color="#FF9800"),
                Track(name="Parameters", type="parameters", color="#9C27B0"),
            ]

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "duration": self.duration,
            "bpm": self.bpm,
            "tracks": [track.to_dict() for track in self.tracks],
            "metadata": self.metadata,
            "created_at": self.created_at,
            "modified_at": self.modified_at
        }

    @classmethod
    def from_dict(cls, data: dict) -> 'Show':
        show = cls(
            id=data.get("id", str(uuid.uuid4())[:8]),
            name=data.get("name", "Untitled Show"),
            duration=data.get("duration", 180000),
            bpm=data.get("bpm", 128.0),
            metadata=data.get("metadata", {}),
            created_at=data.get("created_at", time.time()),
            modified_at=data.get("modified_at", time.time())
        )
        # Don't use default tracks if data has tracks
        if "tracks" in data:
            show.tracks = [Track.from_dict(t) for t in data["tracks"]]
        return show

    def to_json(self, indent: int = 2) -> str:
        return json.dumps(self.to_dict(), indent=indent)

    @classmethod
    def from_json(cls, json_str: str) -> 'Show':
        return cls.from_dict(json.loads(json_str))

    def get_track(self, track_type: str) -> Optional[Track]:
        """Get a track by type."""
        for track in self.tracks:
            if track.type == track_type:
                return track
        return None

    def get_all_cues(self) -> List[Cue]:
        """Get all cues from all tracks, sorted by start time."""
        all_cues = []
        for track in self.tracks:
            if not track.muted:
                all_cues.extend(track.cues)
        return sorted(all_cues, key=lambda c: c.start_time)

    def reset_cues(self):
        """Reset all cue states for new playback."""
        for track in self.tracks:
            for cue in track.cues:
                cue.reset()

    def ms_to_beats(self, ms: int) -> float:
        """Convert milliseconds to beats."""
        return (ms / 60000) * self.bpm

    def beats_to_ms(self, beats: float) -> int:
        """Convert beats to milliseconds."""
        return int((beats / self.bpm) * 60000)
