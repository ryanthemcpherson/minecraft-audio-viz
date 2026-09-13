"""Expanded gates must detect optional-field drift and shared zone state."""

import asyncio
import copy
from pathlib import Path

import pytest
from matrix_compare import compare_matrix, make_config, python_matrix, validate_config


def features(count: int = 96) -> list[dict]:
    return [
        {
            "audio": {
                "bands": [0.2, 0.4, 0.6, 0.8, 0.3],
                "amplitude": 0.7,
                "is_beat": index % 30 == 0,
                "beat_intensity": 0.8,
                "frame": index,
                "bpm": 120.0,
                "beat_phase": (index % 30) / 30,
            }
        }
        for index in range(count)
    ]


def record() -> dict:
    return {
        "frame": 0,
        "zones": [
            {
                "zone": "test",
                "pattern": "shockwave",
                "entities": [
                    {
                        "id": "block_0",
                        "x": 0.5,
                        "y": 0.5,
                        "z": 0.5,
                        "scale": 0.4,
                        "rotation": 10.0,
                        "band": 0,
                        "visible": True,
                        "glow": True,
                        "brightness": 10,
                        "material": "SEA_LANTERN",
                        "interpolation": 1,
                    }
                ],
            }
        ],
    }


@pytest.mark.parametrize(
    "field,value",
    [
        ("glow", False),
        ("brightness", 9),
        ("material", "STONE"),
        ("interpolation", 2),
        ("visible", 1),
        ("rotation", 359.0),
    ],
)
def test_compare_checks_every_render_field(field: str, value: object) -> None:
    expected = [record()]
    actual = copy.deepcopy(expected)
    actual[0]["zones"][0]["entities"][0][field] = value
    with pytest.raises(AssertionError):
        compare_matrix(expected, actual)


def test_optional_field_presence_is_part_of_contract() -> None:
    expected = [record()]
    actual = copy.deepcopy(expected)
    del actual[0]["zones"][0]["entities"][0]["glow"]
    with pytest.raises(AssertionError):
        compare_matrix(expected, actual)


def test_rotation_comparison_respects_full_turn_equivalence() -> None:
    expected = [record()]
    actual = copy.deepcopy(expected)
    expected[0]["zones"][0]["entities"][0]["rotation"] = 1e-15
    actual[0]["zones"][0]["entities"][0]["rotation"] = 360.0
    result = compare_matrix(expected, actual)
    assert result["equivalent_rotation_wraps"] == 1
    assert result["maximum_raw_rotation_difference"] == 360.0


def test_rotation_cannot_hide_out_of_range_output() -> None:
    expected = [record()]
    actual = copy.deepcopy(expected)
    actual[0]["zones"][0]["entities"][0]["rotation"] = 370.0
    with pytest.raises(AssertionError, match="rotation range"):
        compare_matrix(expected, actual)


@pytest.mark.parametrize(
    "field,value",
    [
        ("name", "../bad"),
        ("name", "Upper"),
        ("seed", -1),
        ("seed", True),
        ("seed", 2**31),
        ("entity_count", 0),
        ("entity_count", 257),
        ("pattern", "unknown"),
    ],
)
def test_bad_config_is_rejected(field: str, value: object) -> None:
    config = make_config(1, 64)
    config["zones"][0][field] = value
    with pytest.raises(ValueError):
        validate_config(config)


def test_duplicate_zone_names_are_rejected() -> None:
    config = make_config(2, 64)
    config["zones"][1]["name"] = config["zones"][0]["name"]
    with pytest.raises(ValueError, match="Duplicate"):
        validate_config(config)


def test_random_state_is_independent_of_zone_order_and_other_instances(tmp_path: Path) -> None:
    config = make_config(2, 64)
    for zone in config["zones"]:
        zone["pattern"] = "aurora"
    data = features()
    together = asyncio.run(python_matrix(data, config, tmp_path / "together.jsonl"))
    reordered_config = {"zones": list(reversed(config["zones"]))}
    reordered = asyncio.run(python_matrix(data, reordered_config, tmp_path / "reordered.jsonl"))
    for trace in reordered:
        trace["zones"].reverse()
    compare_matrix(together, reordered)
    for zone_index, zone in enumerate(config["zones"]):
        alone = asyncio.run(
            python_matrix(data, {"zones": [zone]}, tmp_path / f"alone-{zone_index}.jsonl")
        )
        projected = [
            {"frame": frame["frame"], "zones": [frame["zones"][zone_index]]} for frame in together
        ]
        compare_matrix(projected, alone)
    assert any(frame["zones"][0]["entities"] != frame["zones"][1]["entities"] for frame in together)


def test_padded_flat_output_and_dual_optional_output_are_preserved(tmp_path: Path) -> None:
    config = make_config(2, 64)
    config["zones"][0]["pattern"] = "bars"
    config["zones"][1]["pattern"] = "shockwave"
    trace = asyncio.run(python_matrix(features(4), config, tmp_path / "fields.jsonl"))
    bars = trace[0]["zones"][0]["entities"]
    assert [entity["id"] for entity in bars] == [f"block_{index}" for index in range(64)]
    assert all(not entity["visible"] and entity["scale"] == 0.0 for entity in bars[-4:])
    shockwave = trace[0]["zones"][1]["entities"]
    assert any(
        entity.get("material") == "SEA_LANTERN" and entity.get("glow") is True
        for entity in shockwave
    )
