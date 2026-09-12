"""Ensure acceptance gates reject false positives, not only correct output."""

import copy
import math

import pytest
from compare import compare_entities, stats
from paper_compare import gate, match_samples


def frame() -> dict:
    return {
        "frame": 0,
        "entities": [
            {
                "id": "block_0",
                "x": 0.5,
                "y": 0.5,
                "z": 0.5,
                "scale": 0.2,
                "rotation": 0.0,
                "band": 0,
                "visible": True,
            }
        ],
    }


@pytest.mark.parametrize(
    "field,value", [("x", 0.6), ("scale", math.nan), ("visible", False), ("id", "block_1")]
)
def test_parity_rejects_changed_behavior(field: str, value: object) -> None:
    expected = [frame()]
    changed = copy.deepcopy(expected)
    changed[0]["entities"][0][field] = value
    with pytest.raises(AssertionError):
        compare_entities(expected, changed)


def test_parity_rejects_missing_frame() -> None:
    with pytest.raises(AssertionError):
        compare_entities([frame()], [])


def test_entity_observation_must_match_the_visual() -> None:
    with pytest.raises(gate.GateError, match="did not match"):
        match_samples([{"position": [999, 999, 999]}], [frame()])


def test_motion_gate_rejects_frozen_output() -> None:
    with pytest.raises(gate.GateError, match="Too few distinct"):
        match_samples([{"position": [4, 84, 3]}] * 50, [frame()])


@pytest.mark.parametrize("values", [[], [math.nan], [-1.0], [math.inf]])
def test_statistics_do_not_hide_invalid_measurements(values: list[float]) -> None:
    with pytest.raises(ValueError):
        stats(values)


def test_percentiles_use_nearest_rank() -> None:
    assert stats(list(range(1, 101))) == {"samples": 100, "p50": 50, "p95": 95, "max": 100}
