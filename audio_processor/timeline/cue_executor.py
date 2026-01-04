"""
Cue Executor for AudioViz.
Translates cue actions into actual parameter changes.
"""

import logging
from typing import Callable, Dict, Any, Optional

from .models import Cue, CueType, CueAction

logger = logging.getLogger('cue_executor')


class CueExecutor:
    """
    Executes cue actions by calling registered handlers.

    The executor doesn't know about specific implementations -
    it just calls registered callbacks for each action type.
    """

    def __init__(self):
        # Handlers for different action types
        self._pattern_handler: Optional[Callable[[str], None]] = None
        self._preset_handler: Optional[Callable[[str], None]] = None
        self._parameter_handler: Optional[Callable[[str, float], None]] = None
        self._effect_handler: Optional[Callable[[str, float, int], None]] = None

        # Active effects (for duration tracking)
        self._active_effects: Dict[str, float] = {}

    def set_handlers(
        self,
        pattern_handler: Optional[Callable[[str], None]] = None,
        preset_handler: Optional[Callable[[str], None]] = None,
        parameter_handler: Optional[Callable[[str, float], None]] = None,
        effect_handler: Optional[Callable[[str, float, int], None]] = None
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
                    action.parameter.get("target", ""),
                    action.parameter.get("value", 0)
                )

            elif cue_type == CueType.EFFECT_TRIGGER and action.effect:
                self._execute_effect_trigger(
                    action.effect.get("type", ""),
                    action.effect.get("intensity", 1.0),
                    action.effect.get("duration", 500)
                )

            logger.debug(f"Executed cue: {cue.name} ({cue_type.value})")

        except Exception as e:
            logger.error(f"Error executing cue {cue.id}: {e}")

    def _execute_pattern_change(self, pattern: str, transition):
        """Execute a pattern change."""
        if self._pattern_handler:
            # TODO: Handle crossfade transitions
            self._pattern_handler(pattern)
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
            logger.info(f"Effect triggered: {effect_type} (intensity={intensity}, duration={duration}ms)")


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
        if hasattr(capture_instance, 'current_pattern'):
            capture_instance.current_pattern = pattern_name

    def set_preset(preset_name: str):
        """Handler to apply preset."""
        if hasattr(capture_instance, 'apply_preset'):
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
        if hasattr(capture_instance, 'trigger_effect'):
            capture_instance.trigger_effect(effect_type, intensity, duration)

    executor.set_handlers(
        pattern_handler=set_pattern,
        preset_handler=set_preset,
        parameter_handler=set_parameter,
        effect_handler=trigger_effect
    )

    return executor
