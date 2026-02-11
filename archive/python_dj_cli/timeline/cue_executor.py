"""
Cue Executor for AudioViz.
Translates cue actions into actual parameter changes.
Supports crossfade transitions between patterns.
"""

import logging
import math
import time
from typing import Callable, Dict, List, Optional

from .models import Cue, CueType, Transition, TransitionType

logger = logging.getLogger("cue_executor")


# Easing functions for crossfade transitions
def _ease_linear(t: float) -> float:
    return t


def _ease_in_quad(t: float) -> float:
    return t * t


def _ease_out_quad(t: float) -> float:
    return t * (2 - t)


def _ease_in_out_quad(t: float) -> float:
    return 2 * t * t if t < 0.5 else -1 + (4 - 2 * t) * t


def _ease_in_out_sine(t: float) -> float:
    return 0.5 * (1 - math.cos(math.pi * t))


EASING_FUNCTIONS = {
    "linear": _ease_linear,
    "ease_in": _ease_in_quad,
    "ease_out": _ease_out_quad,
    "ease_in_out": _ease_in_out_quad,
    "sine": _ease_in_out_sine,
}


class CrossfadeState:
    """
    Tracks the state of an active crossfade transition between two patterns.

    During a crossfade, both the old and new patterns are computed,
    and their entity outputs are interpolated based on the crossfade progress.
    """

    def __init__(
        self, from_pattern: str, to_pattern: str, duration_ms: int, easing: str = "linear"
    ):
        self.from_pattern = from_pattern
        self.to_pattern = to_pattern
        self.duration_ms = max(1, duration_ms)  # Prevent division by zero
        self.easing = easing
        self.start_time = time.time()
        self._easing_fn = EASING_FUNCTIONS.get(easing, _ease_linear)

    @property
    def progress(self) -> float:
        """Get crossfade progress (0.0 = fully old pattern, 1.0 = fully new pattern)."""
        elapsed_ms = (time.time() - self.start_time) * 1000
        raw_progress = min(1.0, elapsed_ms / self.duration_ms)
        return self._easing_fn(raw_progress)

    @property
    def is_complete(self) -> bool:
        """Check if the crossfade transition has completed."""
        elapsed_ms = (time.time() - self.start_time) * 1000
        return elapsed_ms >= self.duration_ms

    def interpolate_entities(
        self, old_entities: List[Dict], new_entities: List[Dict]
    ) -> List[Dict]:
        """
        Interpolate entity data between old and new pattern outputs.

        Blends positions, scales, and colors based on crossfade progress.
        Handles mismatched entity counts by fading entities in/out.

        Args:
            old_entities: Entity list from the outgoing pattern
            new_entities: Entity list from the incoming pattern

        Returns:
            Blended entity list
        """
        t = self.progress
        result = []

        max_count = max(len(old_entities), len(new_entities))

        for i in range(max_count):
            if i < len(old_entities) and i < len(new_entities):
                # Both patterns have this entity — interpolate all properties
                old = old_entities[i]
                new = new_entities[i]
                blended = {
                    "id": new.get("id", old.get("id", f"block_{i}")),
                    "x": old.get("x", 0) * (1 - t) + new.get("x", 0) * t,
                    "y": old.get("y", 0) * (1 - t) + new.get("y", 0) * t,
                    "z": old.get("z", 0) * (1 - t) + new.get("z", 0) * t,
                    "scale": old.get("scale", 1) * (1 - t) + new.get("scale", 1) * t,
                    "visible": True,
                }
                # Interpolate optional properties if present
                if "brightness" in old or "brightness" in new:
                    blended["brightness"] = (
                        old.get("brightness", 1.0) * (1 - t) + new.get("brightness", 1.0) * t
                    )
                result.append(blended)

            elif i < len(new_entities):
                # New pattern has extra entities — fade in
                new = new_entities[i].copy()
                new["scale"] = new.get("scale", 1) * t  # Grow from 0
                result.append(new)

            else:
                # Old pattern has extra entities — fade out
                old = old_entities[i].copy()
                old["scale"] = old.get("scale", 1) * (1 - t)  # Shrink to 0
                if old.get("scale", 0) > 0.01:  # Only include if still visible
                    result.append(old)

        return result


class CueExecutor:
    """
    Executes cue actions by calling registered handlers.

    Supports instant and crossfade transitions between patterns.
    The executor tracks active crossfades so the rendering loop
    can query the current blend state.
    """

    def __init__(self):
        # Handlers for different action types
        self._pattern_handler: Optional[Callable[[str], None]] = None
        self._preset_handler: Optional[Callable[[str], None]] = None
        self._parameter_handler: Optional[Callable[[str, float], None]] = None
        self._effect_handler: Optional[Callable[[str, float, int], None]] = None

        # Active crossfade state (None when no crossfade is in progress)
        self._crossfade: Optional[CrossfadeState] = None

        # Track current pattern for crossfade source
        self._current_pattern: Optional[str] = None

        # Active effects (for duration tracking)
        self._active_effects: Dict[str, float] = {}

    @property
    def crossfade(self) -> Optional[CrossfadeState]:
        """Get the active crossfade state, or None if no crossfade is in progress."""
        if self._crossfade and self._crossfade.is_complete:
            # Crossfade finished — commit to the new pattern
            logger.info(
                f"Crossfade complete: {self._crossfade.from_pattern} → {self._crossfade.to_pattern}"
            )
            self._current_pattern = self._crossfade.to_pattern
            self._crossfade = None
        return self._crossfade

    @property
    def is_crossfading(self) -> bool:
        """Check if a crossfade transition is currently active."""
        return self.crossfade is not None

    def set_handlers(
        self,
        pattern_handler: Optional[Callable[[str], None]] = None,
        preset_handler: Optional[Callable[[str], None]] = None,
        parameter_handler: Optional[Callable[[str, float], None]] = None,
        effect_handler: Optional[Callable[[str, float, int], None]] = None,
    ):
        """
        Set handler functions for cue actions.

        Args:
            pattern_handler: fn(pattern_name) - Switch to a pattern
            preset_handler: fn(preset_name) - Apply a preset
            parameter_handler: fn(param_name, value) - Set a parameter
            effect_handler: fn(effect_type, intensity, duration_ms) - Trigger effect
        """
        self._pattern_handler = pattern_handler
        self._preset_handler = preset_handler
        self._parameter_handler = parameter_handler
        self._effect_handler = effect_handler

    def execute(self, cue: Cue):
        """Execute a cue's action."""
        action = cue.action
        cue_type = cue.type

        try:
            if cue_type == CueType.PATTERN_CHANGE and action.pattern:
                self._execute_pattern_change(action.pattern, cue.transition)

            elif cue_type == CueType.PRESET_CHANGE and action.preset:
                self._execute_preset_change(action.preset)

            elif cue_type == CueType.PARAMETER_SET and action.parameter:
                self._execute_parameter_set(
                    action.parameter.get("target", ""), action.parameter.get("value", 0)
                )

            elif cue_type == CueType.EFFECT_TRIGGER and action.effect:
                self._execute_effect_trigger(
                    action.effect.get("type", ""),
                    action.effect.get("intensity", 1.0),
                    action.effect.get("duration", 500),
                )

            logger.debug(f"Executed cue: {cue.name} ({cue_type.value})")

        except Exception as e:
            logger.error(f"Error executing cue {cue.id}: {e}")

    def _execute_pattern_change(self, pattern: str, transition: Transition):
        """
        Execute a pattern change with optional crossfade transition.

        For INSTANT transitions, immediately switches to the new pattern.
        For CROSSFADE transitions, starts a crossfade that interpolates
        between the old and new pattern outputs over the transition duration.
        """
        if self._pattern_handler:
            if (
                transition
                and transition.type == TransitionType.CROSSFADE
                and transition.duration > 0
                and self._current_pattern is not None
            ):
                # Start crossfade transition
                self._crossfade = CrossfadeState(
                    from_pattern=self._current_pattern,
                    to_pattern=pattern,
                    duration_ms=transition.duration,
                    easing=transition.easing,
                )
                logger.info(
                    f"Crossfade started: {self._current_pattern} → {pattern} "
                    f"({transition.duration}ms, {transition.easing})"
                )
                # Immediately switch the underlying pattern so the new pattern
                # starts generating entities. The crossfade blending happens
                # at the render layer using interpolate_entities().
                self._pattern_handler(pattern)
            else:
                # Instant transition
                self._pattern_handler(pattern)
                self._current_pattern = pattern
                logger.info(f"Pattern changed to: {pattern}")

    def _execute_preset_change(self, preset: str):
        """Execute a preset change."""
        if self._preset_handler:
            self._preset_handler(preset)
            logger.info(f"Preset changed to: {preset}")

    def _execute_parameter_set(self, target: str, value: float):
        """Execute a parameter change."""
        if self._parameter_handler:
            self._parameter_handler(target, value)
            logger.info(f"Parameter {target} set to: {value}")

    def _execute_effect_trigger(self, effect_type: str, intensity: float, duration: int):
        """Execute an effect trigger."""
        if self._effect_handler:
            self._effect_handler(effect_type, intensity, duration)
            logger.info(
                f"Effect triggered: {effect_type} (intensity={intensity}, duration={duration}ms)"
            )


def create_cue_executor_for_capture(capture_instance) -> CueExecutor:
    """
    Factory function to create a CueExecutor wired to an AppCapture instance.

    Args:
        capture_instance: The AppAudioCapture instance from app_capture.py

    Returns:
        Configured CueExecutor
    """
    executor = CueExecutor()

    def set_pattern(pattern_name: str):
        """Handler to change pattern."""
        if hasattr(capture_instance, "current_pattern"):
            capture_instance.current_pattern = pattern_name

    def set_preset(preset_name: str):
        """Handler to apply preset."""
        if hasattr(capture_instance, "apply_preset"):
            capture_instance.apply_preset(preset_name)

    def set_parameter(param_name: str, value: float):
        """Handler to set a parameter."""
        # Map parameter names to capture instance attributes
        param_map = {
            "attack": "attack",
            "release": "release",
            "agc_max_gain": "agc_max_gain",
            "beat_threshold": "beat_threshold",
            "beat_sensitivity": "beat_sensitivity",
            "master_gain": "master_gain",
        }
        if param_name in param_map:
            attr = param_map[param_name]
            if hasattr(capture_instance, attr):
                setattr(capture_instance, attr, value)

    def trigger_effect(effect_type: str, intensity: float, duration: int):
        """Handler to trigger an effect."""
        if hasattr(capture_instance, "trigger_effect"):
            capture_instance.trigger_effect(effect_type, intensity, duration)

    executor.set_handlers(
        pattern_handler=set_pattern,
        preset_handler=set_preset,
        parameter_handler=set_parameter,
        effect_handler=trigger_effect,
    )

    return executor
