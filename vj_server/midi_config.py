"""MIDI controller mapping configuration."""

from dataclasses import dataclass, field
from typing import List


@dataclass
class MidiCCMapping:
    """Maps a MIDI CC number to a VJ parameter."""

    cc_number: int
    target: str  # e.g. "band_sensitivity_0", "master_intensity", "pattern_param_1"
    min_value: float = 0.0
    max_value: float = 1.0
    invert: bool = False


@dataclass
class MidiNoteMapping:
    """Maps a MIDI note number to a VJ action."""

    note_number: int
    action: str  # e.g. "effect_flash", "pattern_select_0"
    velocity_sensitive: bool = False


@dataclass
class MidiConfig:
    """Complete MIDI mapping configuration."""

    cc_mappings: List[MidiCCMapping] = field(default_factory=list)
    note_mappings: List[MidiNoteMapping] = field(default_factory=list)

    @classmethod
    def default(cls) -> "MidiConfig":
        """Create default MIDI mapping configuration."""
        cc = [
            MidiCCMapping(7, "master_intensity"),
            MidiCCMapping(1, "pattern_param_1"),
            MidiCCMapping(2, "pattern_param_2"),
            MidiCCMapping(71, "band_sensitivity_0"),  # Bass
            MidiCCMapping(72, "band_sensitivity_1"),  # Low-mid
            MidiCCMapping(73, "band_sensitivity_2"),  # Mid
            MidiCCMapping(74, "band_sensitivity_3"),  # High-mid
            MidiCCMapping(75, "band_sensitivity_4"),  # High
        ]
        notes = [
            MidiNoteMapping(36, "effect_flash"),
            MidiNoteMapping(37, "effect_strobe"),
            MidiNoteMapping(38, "effect_blackout"),
            MidiNoteMapping(39, "effect_freeze"),
            MidiNoteMapping(40, "effect_bass_drop"),
            MidiNoteMapping(41, "effect_spin_up"),
            MidiNoteMapping(42, "effect_spin_down"),
            MidiNoteMapping(43, "effect_color_wash"),
            MidiNoteMapping(48, "pattern_select_0"),
            MidiNoteMapping(49, "pattern_select_1"),
            MidiNoteMapping(50, "pattern_select_2"),
            MidiNoteMapping(51, "pattern_select_3"),
            MidiNoteMapping(52, "pattern_select_4"),
            MidiNoteMapping(53, "pattern_select_5"),
            MidiNoteMapping(54, "pattern_select_6"),
            MidiNoteMapping(55, "pattern_select_7"),
        ]
        return cls(cc_mappings=cc, note_mappings=notes)

    @classmethod
    def from_dict(cls, data: dict) -> "MidiConfig":
        """Load from dict (JSON config)."""
        cc = [MidiCCMapping(**m) for m in data.get("cc_mappings", [])]
        notes = [MidiNoteMapping(**m) for m in data.get("note_mappings", [])]
        return cls(cc_mappings=cc, note_mappings=notes)

    def to_dict(self) -> dict:
        """Serialize to dict."""
        return {
            "cc_mappings": [
                {
                    "cc_number": m.cc_number,
                    "target": m.target,
                    "min_value": m.min_value,
                    "max_value": m.max_value,
                    "invert": m.invert,
                }
                for m in self.cc_mappings
            ],
            "note_mappings": [
                {
                    "note_number": m.note_number,
                    "action": m.action,
                    "velocity_sensitive": m.velocity_sensitive,
                }
                for m in self.note_mappings
            ],
        }
