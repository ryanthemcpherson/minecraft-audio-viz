"""Seeded visual and zone matrix against the unchanged production Python wrapper."""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import re
import subprocess  # nosec B404 - explicit local prototype executable, never shell input
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import websockets
from compare import DT, HERE, patterns, read_jsonl, sha256, stats, write_json

from vj_server.viz_client import VizClient

PATTERNS = ("spectrum", "bars", "aurora", "shockwave")
FLOAT_FIELDS = ("x", "y", "z", "scale", "rotation")
EXACT_FIELDS = ("id", "band", "visible", "glow", "brightness", "material", "interpolation")


def validate_config(config: dict[str, Any]) -> None:
    zones = config.get("zones")
    if not isinstance(zones, list) or not 1 <= len(zones) <= 4:
        raise ValueError("Expected one to four zones")
    names = set()
    for zone in zones:
        if not isinstance(zone, dict) or set(zone) != {"name", "pattern", "entity_count", "seed"}:
            raise ValueError("Unexpected zone fields")
        name = zone["name"]
        if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", name):
            raise ValueError("Invalid zone name")
        if name in names:
            raise ValueError("Duplicate zone name")
        names.add(name)
        if zone["pattern"] not in PATTERNS:
            raise ValueError("Unsupported pattern")
        if type(zone["entity_count"]) is not int or not 1 <= zone["entity_count"] <= 256:
            raise ValueError("Entity count must be 1..256")
        if type(zone["seed"]) is not int or not 0 <= zone["seed"] <= 2**31 - 1:
            raise ValueError("Seed must be an integer in 0..2147483647")


def make_config(zone_count: int, entity_count: int) -> dict[str, Any]:
    selected = {1: ("shockwave",), 2: ("bars", "aurora"), 4: PATTERNS}[zone_count]
    config = {
        "zones": [
            {
                "name": f"matrix_{index}_main_stage",
                "pattern": pattern,
                "entity_count": entity_count,
                "seed": 1234 + index,
            }
            for index, pattern in enumerate(selected)
        ]
    }
    validate_config(config)
    return config


async def python_matrix(
    features: list[dict[str, Any]], config: dict[str, Any], output: Path, port: int | None = None
) -> list[dict[str, Any]]:
    validate_config(config)
    if not 1 <= len(features) <= 7200:
        raise ValueError("Expected 1..7200 feature frames")
    runtimes = []
    for zone in config["zones"]:
        runtime = patterns.LuaPattern(
            zone["pattern"], patterns.PatternConfig(entity_count=zone["entity_count"])
        )
        if runtime._calculate is None or runtime._flat_mode not in {"flat", "dual"}:
            raise RuntimeError("Expected active production flat/dual Lua wrapper")
        runtime._lua.globals().math.randomseed(zone["seed"], 0)
        runtimes.append(runtime)
    encode = VizClient()._encode
    original_clock = patterns.time
    fixture_clock = SimpleNamespace(monotonic=lambda: 0.0)
    records = []
    ws = None
    try:
        if port is not None:
            ws = await websockets.connect(f"ws://127.0.0.1:{port}", open_timeout=5)
        patterns.time = fixture_clock
        epoch = time.perf_counter()
        for index, feature in enumerate(features):
            deadline = epoch + index * DT
            if ws is not None:
                await asyncio.sleep(max(0.0, deadline - time.perf_counter()))
            lateness_us = max(0, time.perf_counter() - deadline) * 1e6 if ws else 0.0
            fixture_clock.monotonic = lambda index=index: index * DT
            frame_start = time.perf_counter_ns()
            audio = patterns.AudioState(**feature["audio"])
            zone_results = []
            for zone, runtime in zip(config["zones"], runtimes, strict=True):
                start = time.perf_counter_ns()
                entities = runtime.calculate_entities(audio)
                runtime_us = (time.perf_counter_ns() - start) / 1000
                if len(entities) != zone["entity_count"]:
                    raise RuntimeError(f"Failed entity budget in {zone['name']}")
                start = time.perf_counter_ns()
                payload = encode(
                    {"type": "batch_update", "zone": zone["name"], "entities": entities}
                )
                # All selected pattern strings and validated zone names are ASCII.
                payload_bytes = len(payload)
                serialize_us = (time.perf_counter_ns() - start) / 1000
                start = time.perf_counter_ns()
                if ws is not None:
                    await asyncio.wait_for(ws.send(payload), timeout=5)
                send_us = (time.perf_counter_ns() - start) / 1000 if ws else 0.0
                zone_results.append((entities, runtime_us, serialize_us, send_us, payload_bytes))
            frame_work_us = (time.perf_counter_ns() - frame_start) / 1000
            send_offset_ms = (time.perf_counter() - epoch) * 1000
            zone_records = [
                {
                    "zone": zone["name"],
                    "pattern": zone["pattern"],
                    "entities": values[0],
                    "runtime_us": values[1],
                    "serialize_us": values[2],
                    "send_us": values[3],
                    "bytes": values[4],
                }
                for zone, values in zip(config["zones"], zone_results, strict=True)
            ]
            records.append(
                {
                    "frame": index,
                    "audio": feature["audio"],
                    "zones": zone_records,
                    "lateness_us": lateness_us,
                    "frame_work_us": frame_work_us,
                    "send_offset_ms": send_offset_ms,
                }
            )
        if ws is not None:
            await asyncio.wait_for(await ws.ping(b"matrix-fence"), timeout=5)
            await asyncio.sleep(0.15)
    finally:
        patterns.time = original_clock
        if ws is not None:
            await ws.close()
    with output.open("w") as writer:
        for record in records:
            writer.write(json.dumps(record, allow_nan=False) + "\n")
    return records


def compare_matrix(
    reference: list[dict[str, Any]], candidate: list[dict[str, Any]], tolerance: float = 1e-9
) -> dict[str, Any]:
    if not reference or len(reference) != len(candidate):
        raise AssertionError("Frame count mismatch or empty trace")
    maximum = 0.0
    numeric = exact = wrap_equivalent = 0
    maximum_raw_rotation_error = 0.0
    for expected_frame, actual_frame in zip(reference, candidate, strict=True):
        assert expected_frame["frame"] == actual_frame["frame"]
        assert len(expected_frame["zones"]) == len(actual_frame["zones"])
        for expected_zone, actual_zone in zip(
            expected_frame["zones"], actual_frame["zones"], strict=True
        ):
            assert expected_zone["zone"] == actual_zone["zone"]
            assert expected_zone["pattern"] == actual_zone["pattern"]
            assert len(expected_zone["entities"]) == len(actual_zone["entities"])
            for expected, actual in zip(
                expected_zone["entities"], actual_zone["entities"], strict=True
            ):
                context = (expected_frame["frame"], expected_zone["zone"], expected["id"])
                assert expected.keys() == actual.keys(), (context, "entity keys")
                for field in EXACT_FIELDS:
                    if field in expected:
                        assert (
                            type(expected[field]) is type(actual[field])
                            and expected[field] == actual[field]
                        ), (context, field, expected[field], actual[field])
                        exact += 1
                for field in FLOAT_FIELDS:
                    assert math.isfinite(expected[field]) and math.isfinite(actual[field]), (
                        context,
                        field,
                    )
                    error = abs(expected[field] - actual[field])
                    if field == "rotation":
                        assert 0.0 <= expected[field] <= 360.0 and 0.0 <= actual[field] <= 360.0, (
                            context,
                            "rotation range",
                        )
                        maximum_raw_rotation_error = max(maximum_raw_rotation_error, error)
                        angular_error = abs(
                            (expected[field] - actual[field] + 180.0) % 360.0 - 180.0
                        )
                        if error > tolerance and angular_error <= tolerance:
                            wrap_equivalent += 1
                        error = angular_error
                    assert error <= tolerance, (context, field, error)
                    maximum = max(maximum, error)
                    numeric += 1
    return {
        "frames": len(reference),
        "zones": len(reference[0]["zones"]),
        "numeric_fields_compared": numeric,
        "exact_fields_compared": exact,
        "maximum_absolute_error": maximum,
        "tolerance": tolerance,
        "rotation_metric": "shortest angular distance in degrees",
        "maximum_raw_rotation_difference": maximum_raw_rotation_error,
        "equivalent_rotation_wraps": wrap_equivalent,
    }


def summarize_matrix(records: list[dict[str, Any]], warmup: int = 60) -> dict[str, Any]:
    measured = records[warmup:]
    result = {
        field: stats([record[field] for record in measured])
        for field in ("frame_work_us", "lateness_us")
    }
    result["all_zones_runtime_and_serialize_us"] = stats(
        [
            sum(zone["runtime_us"] + zone["serialize_us"] for zone in record["zones"])
            for record in measured
        ]
    )
    result["frame_budget_us"] = DT * 1e6
    result["work_over_budget_frames"] = sum(
        record["frame_work_us"] > DT * 1e6 for record in measured
    )
    result["late_over_budget_frames"] = sum(record["lateness_us"] > DT * 1e6 for record in measured)
    result["zones"] = {
        zone["zone"]: {
            field: stats([record["zones"][index][field] for record in measured])
            for field in ("runtime_us", "serialize_us", "send_us")
        }
        for index, zone in enumerate(records[0]["zones"])
    }
    return result


def run_rust(binary: Path, features: Path, config: Path, output: Path) -> list[dict[str, Any]]:
    subprocess.run(  # nosec B603 - user-selected local binary and explicit argument vector
        [str(binary), "matrix", str(features), str(config), str(output)], check=True, timeout=90
    )
    return read_jsonl(output)


def check_isolation(
    binary: Path,
    feature_path: Path,
    features: list[dict[str, Any]],
    config: dict[str, Any],
    name: str,
    output: Path,
    rust_records: list[dict[str, Any]],
    python_records: list[dict[str, Any]],
) -> dict[str, Any]:
    """Compare each mixed zone with a fresh standalone process at its exact seed."""
    evidence = {}
    for index, zone in enumerate(config["zones"]):
        isolated_config = {"zones": [zone]}
        config_path = output / f"{name}-isolated-{index}.json"
        write_json(config_path, isolated_config)
        rust_alone = run_rust(
            binary, feature_path, config_path, output / f"{name}-isolated-rust-{index}.jsonl"
        )
        python_alone = asyncio.run(
            python_matrix(
                features, isolated_config, output / f"{name}-isolated-python-{index}.jsonl"
            )
        )
        evidence[zone["name"]] = {
            "rust": compare_matrix(
                [
                    {"frame": record["frame"], "zones": [record["zones"][index]]}
                    for record in rust_records
                ],
                rust_alone,
            ),
            "python": compare_matrix(
                [
                    {"frame": record["frame"], "zones": [record["zones"][index]]}
                    for record in python_records
                ],
                python_alone,
            ),
        }
    return evidence


def offline_matrix(
    binary: Path, feature_path: Path, output: Path, counts: list[int], trials: int
) -> dict[str, Any]:
    # Preserve previous evidence; each complete experiment owns a new directory.
    output.mkdir(parents=True, exist_ok=False)
    features = read_jsonl(feature_path)
    if len(features) <= 60:
        raise ValueError("Need more than 60 frames for warmup and measurement")
    evidence: dict[str, Any] = {
        "passed": False,
        "binary_sha256": sha256(binary),
        "feature_path": str(feature_path),
        "feature_sha256": sha256(feature_path),
        "cases": {},
    }
    try:
        for count in counts:
            cases = {
                f"{pattern}-{count}": {
                    "zones": [
                        {
                            "name": "matrix_0_main_stage",
                            "pattern": pattern,
                            "entity_count": count,
                            "seed": 1234,
                        }
                    ]
                }
                for pattern in PATTERNS
            }
            for zone_count in (2, 4):
                cases[f"mixed-{zone_count}x{count}"] = make_config(zone_count, count)
            for name, config in cases.items():
                config_path = output / f"{name}.json"
                write_json(config_path, config)
                case: dict[str, Any] = {"config": config, "trials": []}
                for trial in range(trials):
                    rust_path = output / f"{name}-rust-{trial}.jsonl"
                    python_path = output / f"{name}-python-{trial}.jsonl"
                    if trial % 2 == 0:
                        rust_records = run_rust(binary, feature_path, config_path, rust_path)
                        python_records = asyncio.run(python_matrix(features, config, python_path))
                    else:
                        python_records = asyncio.run(python_matrix(features, config, python_path))
                        rust_records = run_rust(binary, feature_path, config_path, rust_path)
                    case["trials"].append(
                        {
                            "parity": compare_matrix(python_records, rust_records),
                            "rust": summarize_matrix(rust_records),
                            "python": summarize_matrix(python_records),
                            "rust_sha256": sha256(rust_path),
                            "python_sha256": sha256(python_path),
                        }
                    )
                if len(config["zones"]) > 1:
                    case["isolation"] = check_isolation(
                        binary,
                        feature_path,
                        features,
                        config,
                        name,
                        output,
                        rust_records,
                        python_records,
                    )
                evidence["cases"][name] = case
                print(json.dumps({"completed": name, "trials": trials}), flush=True)
        evidence["passed"] = True
    except Exception as error:
        evidence["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        write_json(output / "summary.json", evidence)
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=HERE / "target/release/mcav-runtime-slice")
    parser.add_argument(
        "--features", type=Path, default=HERE / "results/final-quiet/features.jsonl"
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--counts", type=int, nargs="+", choices=(64, 128), default=[64, 128])
    parser.add_argument("--trials", type=int, choices=(1, 2, 3), default=3)
    args = parser.parse_args()
    offline_matrix(
        args.binary.resolve(),
        args.features.resolve(),
        args.output.resolve(),
        args.counts,
        args.trials,
    )
    print(json.dumps({"passed": True, "summary": str(args.output / "summary.json")}))


if __name__ == "__main__":
    main()
