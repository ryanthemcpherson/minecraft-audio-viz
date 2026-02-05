"""
Show Storage for AudioViz.
Handles saving and loading shows from JSON files.
"""

import json
import os
import logging
from pathlib import Path
from typing import List, Optional, Dict, Any

from .models import Show

logger = logging.getLogger('show_storage')


class ShowStorage:
    """
    Manages show persistence to JSON files.
    """

    def __init__(self, storage_dir: Optional[str] = None):
        """
        Initialize show storage.

        Args:
            storage_dir: Directory for show files. Defaults to 'shows/' in project root.
        """
        if storage_dir:
            self.storage_dir = Path(storage_dir)
        else:
            # Default to shows/ directory in project root
            project_root = Path(__file__).parent.parent.parent
            self.storage_dir = project_root / "shows"

        # Ensure directory exists
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        logger.info(f"Show storage directory: {self.storage_dir}")

    def save(self, show: Show) -> str:
        """
        Save a show to disk.

        Args:
            show: Show to save

        Returns:
            Path to saved file
        """
        # Sanitize filename
        safe_name = "".join(c if c.isalnum() or c in "-_ " else "_" for c in show.name)
        filename = f"{safe_name}_{show.id}.json"
        filepath = self.storage_dir / filename

        # Update modification time
        import time
        show.modified_at = time.time()

        # Write JSON
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(show.to_json(indent=2))

        logger.info(f"Saved show: {filepath}")
        return str(filepath)

    def load(self, show_id: str) -> Optional[Show]:
        """
        Load a show by ID.

        Args:
            show_id: Show ID to load

        Returns:
            Loaded Show or None if not found
        """
        # Search for file with matching ID
        for filepath in self.storage_dir.glob("*.json"):
            if show_id in filepath.stem:
                return self._load_file(filepath)

        logger.warning(f"Show not found: {show_id}")
        return None

    def load_by_name(self, name: str) -> Optional[Show]:
        """
        Load a show by name (partial match).

        Args:
            name: Show name to search for

        Returns:
            First matching Show or None
        """
        name_lower = name.lower()
        for filepath in self.storage_dir.glob("*.json"):
            if name_lower in filepath.stem.lower():
                return self._load_file(filepath)

        logger.warning(f"Show not found: {name}")
        return None

    def load_file(self, filepath: str) -> Optional[Show]:
        """
        Load a show from a specific file path.

        Args:
            filepath: Path to JSON file

        Returns:
            Loaded Show or None if error
        """
        return self._load_file(Path(filepath))

    def _load_file(self, filepath: Path) -> Optional[Show]:
        """Internal method to load a show file."""
        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                data = json.load(f)
            show = Show.from_dict(data)
            logger.info(f"Loaded show: {show.name} from {filepath}")
            return show
        except Exception as e:
            logger.error(f"Error loading {filepath}: {e}")
            return None

    def list_shows(self) -> List[Dict[str, Any]]:
        """
        List all available shows.

        Returns:
            List of show summaries (id, name, duration, modified_at)
        """
        shows = []
        for filepath in self.storage_dir.glob("*.json"):
            try:
                with open(filepath, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                shows.append({
                    "id": data.get("id", ""),
                    "name": data.get("name", "Unknown"),
                    "duration": data.get("duration", 0),
                    "bpm": data.get("bpm", 0),
                    "modified_at": data.get("modified_at", 0),
                    "filepath": str(filepath)
                })
            except Exception as e:
                logger.warning(f"Error reading {filepath}: {e}")

        # Sort by modification time (newest first)
        shows.sort(key=lambda s: s.get("modified_at", 0), reverse=True)
        return shows

    def delete(self, show_id: str) -> bool:
        """
        Delete a show by ID.

        Args:
            show_id: Show ID to delete

        Returns:
            True if deleted, False if not found
        """
        for filepath in self.storage_dir.glob("*.json"):
            if show_id in filepath.stem:
                filepath.unlink()
                logger.info(f"Deleted show: {filepath}")
                return True

        logger.warning(f"Show not found for deletion: {show_id}")
        return False

    def create_demo_show(self) -> Show:
        """
        Create a demo show for testing.

        Returns:
            A pre-populated demo show
        """
        from .models import Cue, CueAction, CueType, TriggerType, Transition, TransitionType

        show = Show(
            name="Demo Show",
            duration=60000,  # 1 minute
            bpm=128.0,
            metadata={
                "author": "AudioViz",
                "song_name": "Demo Track",
                "song_artist": "Test"
            }
        )

        # Add pattern cues
        pattern_track = show.get_track("patterns")
        if pattern_track:
            pattern_track.add_cue(Cue(
                name="Start: Spectrum Bars",
                type=CueType.PATTERN_CHANGE,
                start_time=0,
                duration=15000,
                action=CueAction(pattern="spectrum_bars"),
                transition=Transition(type=TransitionType.INSTANT)
            ))
            pattern_track.add_cue(Cue(
                name="Wave Ring",
                type=CueType.PATTERN_CHANGE,
                start_time=15000,
                duration=15000,
                action=CueAction(pattern="wave_ring")
            ))
            pattern_track.add_cue(Cue(
                name="Pulse Grid",
                type=CueType.PATTERN_CHANGE,
                start_time=30000,
                duration=15000,
                action=CueAction(pattern="pulse_grid")
            ))
            pattern_track.add_cue(Cue(
                name="Helix",
                type=CueType.PATTERN_CHANGE,
                start_time=45000,
                duration=15000,
                action=CueAction(pattern="helix")
            ))

        # Add preset cues
        preset_track = show.get_track("presets")
        if preset_track:
            preset_track.add_cue(Cue(
                name="EDM Mode",
                type=CueType.PRESET_CHANGE,
                start_time=0,
                duration=30000,
                action=CueAction(preset="edm")
            ))
            preset_track.add_cue(Cue(
                name="Chill Mode",
                type=CueType.PRESET_CHANGE,
                start_time=30000,
                duration=30000,
                action=CueAction(preset="chill")
            ))

        # Add effect cues
        effect_track = show.get_track("effects")
        if effect_track:
            effect_track.add_cue(Cue(
                name="Flash",
                type=CueType.EFFECT_TRIGGER,
                start_time=7500,
                duration=500,
                trigger=TriggerType.BEAT,
                action=CueAction(effect={"type": "flash", "intensity": 1.0, "duration": 200})
            ))
            effect_track.add_cue(Cue(
                name="Bass Drop",
                type=CueType.EFFECT_TRIGGER,
                start_time=29500,
                duration=1000,
                action=CueAction(effect={"type": "bass_drop", "intensity": 1.0, "duration": 1000})
            ))

        return show
