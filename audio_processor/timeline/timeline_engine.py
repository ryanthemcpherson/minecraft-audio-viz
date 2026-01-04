"""
Timeline Engine for AudioViz show playback.
Handles timeline state, cue scheduling, and playback control.
"""

import time
import logging
from enum import Enum
from typing import Optional, Callable, List, Dict, Any
from dataclasses import dataclass, field

from .models import Show, Cue, CueType, TriggerType

logger = logging.getLogger('timeline')


class TimelineState(Enum):
    STOPPED = "stopped"
    PLAYING = "playing"
    PAUSED = "paused"


@dataclass
class PendingCue:
    """A cue waiting to be executed."""
    cue: Cue
    scheduled_time: float  # When to fire (real time)


class TimelineEngine:
    """
    Core timeline playback engine.

    Manages show playback, cue scheduling, and state.
    Call update() regularly (e.g., every frame) to process cues.
    """

    def __init__(self):
        self.show: Optional[Show] = None
        self.state: TimelineState = TimelineState.STOPPED

        # Playback timing
        self.position: int = 0          # Current position in ms
        self._play_start_time: float = 0  # Real time when playback started
        self._play_start_pos: int = 0     # Position when playback started

        # Cue management
        self._pending_cues: List[PendingCue] = []
        self._next_cue_index: int = 0

        # Beat tracking (from external audio analysis)
        self._last_beat_time: float = 0
        self._beat_pending_cues: List[Cue] = []  # Cues waiting for beat

        # Callbacks
        self._on_cue_fire: Optional[Callable[[Cue], None]] = None
        self._on_state_change: Optional[Callable[[TimelineState], None]] = None
        self._on_position_change: Optional[Callable[[int], None]] = None

        # Loop settings
        self.loop: bool = False
        self.loop_start: int = 0
        self.loop_end: int = 0

    def set_callbacks(
        self,
        on_cue_fire: Optional[Callable[[Cue], None]] = None,
        on_state_change: Optional[Callable[[TimelineState], None]] = None,
        on_position_change: Optional[Callable[[int], None]] = None
    ):
        """Set callback functions for timeline events."""
        self._on_cue_fire = on_cue_fire
        self._on_state_change = on_state_change
        self._on_position_change = on_position_change

    def load_show(self, show: Show):
        """Load a show for playback."""
        self.stop()
        self.show = show
        self.position = 0
        self._next_cue_index = 0
        self._pending_cues.clear()
        self._beat_pending_cues.clear()
        show.reset_cues()
        logger.info(f"Loaded show: {show.name} ({show.duration}ms, {len(show.get_all_cues())} cues)")

    def play(self):
        """Start or resume playback."""
        if not self.show:
            logger.warning("No show loaded")
            return False

        if self.state == TimelineState.PLAYING:
            return True

        self._play_start_time = time.time()
        self._play_start_pos = self.position
        self.state = TimelineState.PLAYING

        # Schedule upcoming cues
        self._schedule_upcoming_cues()

        logger.info(f"Playing from {self.position}ms")
        self._notify_state_change()
        return True

    def pause(self):
        """Pause playback."""
        if self.state != TimelineState.PLAYING:
            return

        # Update position to current time
        self.position = self._calculate_position()
        self.state = TimelineState.PAUSED

        logger.info(f"Paused at {self.position}ms")
        self._notify_state_change()

    def stop(self):
        """Stop playback and reset to beginning."""
        self.state = TimelineState.STOPPED
        self.position = 0
        self._next_cue_index = 0
        self._pending_cues.clear()
        self._beat_pending_cues.clear()

        if self.show:
            self.show.reset_cues()

        logger.info("Stopped")
        self._notify_state_change()

    def seek(self, position_ms: int):
        """Seek to a specific position."""
        if not self.show:
            return

        # Clamp to valid range
        position_ms = max(0, min(position_ms, self.show.duration))

        was_playing = self.state == TimelineState.PLAYING

        # Update position
        self.position = position_ms
        self._play_start_time = time.time()
        self._play_start_pos = position_ms

        # Reset cue states and find next cue
        self.show.reset_cues()
        self._pending_cues.clear()
        self._beat_pending_cues.clear()
        self._find_next_cue_index()

        # Mark already-passed cues as fired
        all_cues = self.show.get_all_cues()
        for i in range(self._next_cue_index):
            if i < len(all_cues):
                all_cues[i].fired = True

        if was_playing:
            self._schedule_upcoming_cues()

        logger.info(f"Seeked to {position_ms}ms")
        self._notify_position_change()

    def update(self, dt: float = None):
        """
        Update timeline state. Call this every frame.

        Args:
            dt: Time delta (unused, we use real time)
        """
        if self.state != TimelineState.PLAYING or not self.show:
            return

        # Calculate current position
        current_pos = self._calculate_position()

        # Check for end of show
        if current_pos >= self.show.duration:
            if self.loop:
                self.seek(self.loop_start)
            else:
                self.position = self.show.duration
                self.state = TimelineState.STOPPED
                self._notify_state_change()
                logger.info("Playback complete")
            return

        self.position = current_pos

        # Process pending time-based cues
        self._process_pending_cues()

        # Schedule more cues if needed
        self._schedule_upcoming_cues()

    def on_beat(self, intensity: float = 1.0):
        """
        Called when a beat is detected.
        Fires any beat-triggered cues that are ready.
        """
        self._last_beat_time = time.time()

        # Fire pending beat-triggered cues
        for cue in self._beat_pending_cues[:]:
            if not cue.fired:
                cue.fired = True
                self._beat_pending_cues.remove(cue)
                self._fire_cue(cue)

    def fire_cue(self, cue_id: str) -> bool:
        """Manually fire a cue by ID."""
        if not self.show:
            return False

        for cue in self.show.get_all_cues():
            if cue.id == cue_id:
                self._fire_cue(cue)
                return True
        return False

    def arm_cue(self, cue_id: str, armed: bool = True) -> bool:
        """Arm or disarm a manual cue."""
        if not self.show:
            return False

        for cue in self.show.get_all_cues():
            if cue.id == cue_id:
                cue.armed = armed
                logger.info(f"Cue {cue_id} {'armed' if armed else 'disarmed'}")
                return True
        return False

    def get_status(self) -> Dict[str, Any]:
        """Get current timeline status for broadcasting."""
        return {
            "state": self.state.value,
            "position": self.position,
            "duration": self.show.duration if self.show else 0,
            "show_name": self.show.name if self.show else None,
            "bpm": self.show.bpm if self.show else 0,
            "loop": self.loop,
            "next_cue": self._get_next_cue_info()
        }

    def get_cue_list(self) -> List[Dict[str, Any]]:
        """Get list of all cues with current states."""
        if not self.show:
            return []
        return [
            {
                **cue.to_dict(),
                "fired": cue.fired,
                "armed": cue.armed
            }
            for cue in self.show.get_all_cues()
        ]

    # === Private Methods ===

    def _calculate_position(self) -> int:
        """Calculate current position based on play time."""
        if self.state != TimelineState.PLAYING:
            return self.position
        elapsed = (time.time() - self._play_start_time) * 1000
        return int(self._play_start_pos + elapsed)

    def _find_next_cue_index(self):
        """Find the index of the next cue to schedule."""
        if not self.show:
            self._next_cue_index = 0
            return

        all_cues = self.show.get_all_cues()
        for i, cue in enumerate(all_cues):
            if cue.start_time > self.position:
                self._next_cue_index = i
                return
        self._next_cue_index = len(all_cues)

    def _schedule_upcoming_cues(self, lookahead_ms: int = 2000):
        """Schedule cues within the lookahead window."""
        if not self.show:
            return

        all_cues = self.show.get_all_cues()
        current_pos = self.position

        while self._next_cue_index < len(all_cues):
            cue = all_cues[self._next_cue_index]

            # Stop if beyond lookahead
            if cue.start_time > current_pos + lookahead_ms:
                break

            # Skip already-fired cues
            if cue.fired:
                self._next_cue_index += 1
                continue

            # Handle different trigger types
            if cue.trigger == TriggerType.TIME:
                # Schedule for exact time
                delay_ms = cue.start_time - current_pos
                scheduled_time = time.time() + (delay_ms / 1000)
                self._pending_cues.append(PendingCue(cue, scheduled_time))

            elif cue.trigger == TriggerType.BEAT:
                # Add to beat-pending list
                if cue not in self._beat_pending_cues:
                    self._beat_pending_cues.append(cue)

            elif cue.trigger == TriggerType.MANUAL:
                # Only fire if armed
                if cue.armed:
                    self._fire_cue(cue)

            # FOLLOW cues are handled when previous cue ends

            self._next_cue_index += 1

    def _process_pending_cues(self):
        """Process and fire pending cues that are due."""
        now = time.time()
        fired = []

        for pending in self._pending_cues:
            if now >= pending.scheduled_time and not pending.cue.fired:
                self._fire_cue(pending.cue)
                fired.append(pending)

        for pending in fired:
            self._pending_cues.remove(pending)

    def _fire_cue(self, cue: Cue):
        """Execute a cue and notify listeners."""
        cue.fired = True
        logger.debug(f"Firing cue: {cue.name} ({cue.type.value})")

        if self._on_cue_fire:
            self._on_cue_fire(cue)

    def _get_next_cue_info(self) -> Optional[Dict[str, Any]]:
        """Get info about the next upcoming cue."""
        if not self.show:
            return None

        all_cues = self.show.get_all_cues()
        for cue in all_cues:
            if not cue.fired and cue.start_time > self.position:
                return {
                    "id": cue.id,
                    "name": cue.name,
                    "start_time": cue.start_time,
                    "time_until": cue.start_time - self.position
                }
        return None

    def _notify_state_change(self):
        """Notify listeners of state change."""
        if self._on_state_change:
            self._on_state_change(self.state)

    def _notify_position_change(self):
        """Notify listeners of position change."""
        if self._on_position_change:
            self._on_position_change(self.position)
