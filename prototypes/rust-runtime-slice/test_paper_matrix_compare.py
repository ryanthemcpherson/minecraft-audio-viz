"""Regression tests for false-positive resistance in multi-zone Paper evidence."""

from __future__ import annotations

import asyncio
import copy
import json
import math
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from paper_matrix_compare import (
    gate,
    match_samples,
    parse_snapshot,
    position,
    queue_counts,
    run_one,
    sample_ids,
    setup_entity,
    snapshot,
    transform_matches,
)


def entity(y: float = 0.5) -> dict:
    return {
        "id": "block_0",
        "x": 0.5,
        "y": y,
        "z": 0.5,
        "scale": 0.2,
        "rotation": 90,
        "visible": True,
    }


def observed(y: float = 0.5, index: int = 0) -> dict:
    return {
        "zone": "matrix_0_main_stage",
        "entity_index": index,
        "Pos": [4, 80 + 8 * y, 3],
        "scale": [0.2] * 3,
        "left_rotation": [0, math.sqrt(0.5), 0, math.sqrt(0.5)],
    }


def records() -> list[dict]:
    return [
        {"frame": frame, "zones": [{"entities": [entity(y), entity(y)]}]}
        for frame, y in enumerate([0.25, 0.5, 0.75])
    ]


CONFIG = {"zones": [{"name": "matrix_0_main_stage", "entity_count": 2}]}


def test_snapshot_is_coherent_and_reads_optional_properties() -> None:
    result = parse_snapshot(
        "Block has the following entity data: {Pos: [4.0d, 84.0d, 3.0d], "
        "transformation: {scale: [0.2f, 0.2f, 0.2f], "
        "left_rotation: [0.0f, 0.70710678f, 0.0f, 0.70710678f]}, "
        'Glowing: 1b, block_state: {Name: "minecraft:sea_lantern"}}'
    )
    assert transform_matches(result, entity(), 0)
    assert result["glow"] is True
    assert result["material"] == "minecraft:sea_lantern"


@pytest.mark.parametrize("text", ["{}", "{Pos: [NaNd, 1d, 2d]}", "{Pos: [1d, 2d]}"])
def test_invalid_snapshot_fails(text: str) -> None:
    with pytest.raises(gate.GateError):
        parse_snapshot(text)


def test_transforms_cover_visibility_rotation_and_zone_anchors() -> None:
    assert position(entity(), 3) == [76, 84, 3]
    actual = observed()
    assert transform_matches(actual, entity(), 0)
    assert not transform_matches(actual, entity(), 1)
    actual["left_rotation"] = [0, 0, 0, 1]
    assert not transform_matches(actual, entity(), 0)
    hidden = {**entity(), "visible": False}
    assert not transform_matches(observed(), hidden, 0)
    actual = observed()
    actual["scale"] = [0, 0, 0]
    assert transform_matches(actual, hidden, 0)


def test_plugin_clamps_and_equivalent_quaternions_are_accepted() -> None:
    extreme = {**entity(), "x": -3, "y": 2, "z": 9, "scale": 8, "rotation": 450}
    actual = {"Pos": [0, 88, 6], "scale": [4, 4, 4], "left_rotation": [0, 0, 0, 1]}
    assert transform_matches(actual, extreme, 0)


def test_samples_establish_ordered_moving_entities() -> None:
    samples = [observed(0.25), observed(0.25, 1), observed(0.5), observed(0.75, 1)]
    result = match_samples(samples, records(), CONFIG)
    assert result["per_zone"]["matrix_0_main_stage"]["changing_ids"] == [0, 1]
    assert [item["ordered_matching_frame"] for item in result["observations"]] == [0, 0, 1, 2]


def test_reordered_matching_frames_fail() -> None:
    with pytest.raises(gate.GateError, match="did not match"):
        match_samples([observed(0.75), observed(0.25, 1)], records(), CONFIG)


def setup_observation(index: int = 0) -> dict:
    return {
        **observed(index=index),
        "Pos": position(setup_entity(index, 2), 0),
        "left_rotation": [0, 0, 0, 1],
    }


def test_initial_setup_samples_are_recorded_separately() -> None:
    startup = [setup_observation(), setup_observation(1)]
    active = [observed(0.25), observed(0.25, 1), observed(0.5), observed(0.75, 1)]
    result = match_samples(startup + active, records(), CONFIG)
    zone = result["per_zone"]["matrix_0_main_stage"]
    assert zone["startup_samples"] == 2
    assert zone["samples"] == 4
    assert zone["changing_ids"] == [0, 1]
    assert result["startup_observations"] == startup


def test_setup_samples_cannot_establish_motion_or_hide_regression() -> None:
    with pytest.raises(gate.GateError, match="Too few"):
        match_samples([setup_observation(), setup_observation(1)] * 3, records(), CONFIG)
    with pytest.raises(gate.GateError, match="Frozen"):
        match_samples(
            [setup_observation(), setup_observation(1), observed(), observed(index=1)],
            records(),
            CONFIG,
        )
    with pytest.raises(gate.GateError, match="did not match"):
        match_samples([observed(0.25), setup_observation(1)], records(), CONFIG)
    unknown = setup_observation()
    unknown["scale"] = [0.3] * 3
    with pytest.raises(gate.GateError, match="did not match"):
        match_samples([unknown, observed(0.25, 1), observed(0.75)], records(), CONFIG)


def test_static_or_missing_zones_cannot_pass() -> None:
    with pytest.raises(gate.GateError, match="Frozen"):
        match_samples([observed(), observed(index=1)] * 3, records(), CONFIG)
    expanded = copy.deepcopy(CONFIG)
    expanded["zones"].append({"name": "matrix_1_main_stage", "entity_count": 2})
    with pytest.raises(gate.GateError, match="Too few"):
        match_samples([observed(0.25), observed(0.5, 1), observed(0.75)], records(), expanded)


def test_snapshot_fields_must_match_the_same_frame() -> None:
    samples = [observed(0.25), observed(0.5, 1), observed(0.75)]
    samples[1]["scale"] = [0.3] * 3
    with pytest.raises(gate.GateError, match="did not match"):
        match_samples(samples, records(), CONFIG)


def test_queue_accounting_includes_every_zone_and_seed() -> None:
    result = queue_counts(
        "MessageQueue stopped. Processed 44 messages in 16 batches (28 dropped)", 10, 4
    )
    assert result["setup_batches"] == 4
    with pytest.raises(gate.GateError):
        queue_counts("MessageQueue stopped. Processed 11 messages in 4 batches (7 dropped)", 10, 4)
    with pytest.raises(gate.GateError):
        queue_counts(
            "MessageQueue stopped. Processed 44 messages in 16 batches (27 dropped)", 10, 4
        )


def test_representative_sampling_covers_pool_extremes() -> None:
    assert sample_ids(64) == [0, 32, 63]
    assert sample_ids(128) == [0, 64, 127]


def test_snapshot_handles_gate_console_line_list() -> None:
    response = "Block has the following entity data: {Pos: [4d, 84d, 3d], scale: [0.2f, 0.2f, 0.2f], left_rotation: [0f, 0.70710678f, 0f, 0.70710678f]}"
    server = SimpleNamespace(command=AsyncMock(return_value=[response]))
    result = asyncio.run(snapshot(server, 0, 0))
    assert transform_matches(result, entity(), 0)
    server.command.assert_awaited_once()


def test_failed_run_retains_evidence_and_stops_owned_server(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    server = SimpleNamespace(
        start=AsyncMock(side_effect=gate.GateError("test startup failure")), stop=AsyncMock()
    )
    monkeypatch.setattr(gate, "PaperProcess", lambda *args: server)
    with pytest.raises(gate.GateError, match="startup failure"):
        asyncio.run(
            run_one(
                "rust-test",
                tmp_path / "binary",
                tmp_path / "features",
                CONFIG,
                tmp_path / "java",
                tmp_path,
                12345,
            )
        )
    evidence = json.loads((tmp_path / "evidence.json").read_text())
    assert evidence["passed"] is False
    assert "test startup failure" in evidence["error"]
    assert evidence["clean_shutdown"] is True
    server.stop.assert_awaited_once()
