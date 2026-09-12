"""Deterministic PCM -> production DSP -> unchanged Lua comparison (WSL/Linux)."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import math
import platform
import struct
import subprocess  # nosec B404 - invoke the user-selected local build; never a shell command
import sys
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import lupa
import websockets

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT))
from vj_server import patterns  # noqa: E402
from vj_server.viz_client import VizClient  # noqa: E402

DT = 1 / 60
ZONE = "acceptance_main_stage"


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines()]


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stats(values: list[float]) -> dict[str, float | int]:
    if not values or not all(math.isfinite(value) and value >= 0 for value in values):
        raise ValueError("Expected nonempty finite, nonnegative measurements")
    values = sorted(values)
    return {
        "samples": len(values),
        "p50": values[math.ceil(len(values) * 0.50) - 1],
        "p95": values[math.ceil(len(values) * 0.95) - 1],
        "max": values[-1],
    }


def make_pcm(path: Path) -> None:
    """2s silence, 6s decaying 60Hz kicks, 4s chirp, all at 48kHz mono."""
    with path.open("wb") as output:
        for index in range(48_000 * 12):
            seconds = index / 48_000
            if seconds < 2:
                sample = 0.0
            elif seconds < 8:
                since_kick = (seconds - 2) % 0.5
                sample = (
                    (0.85 * math.sin(2 * math.pi * 60 * since_kick) * math.exp(-since_kick * 30))
                    if since_kick < 0.16
                    else 0.0
                )
            else:
                elapsed = seconds - 8
                sample = 0.65 * math.sin(
                    2 * math.pi * (40 * elapsed + (20_000 - 40) * elapsed**2 / 8)
                )
            output.write(struct.pack("<f", sample))


async def python_render(
    features: list[dict[str, Any]], output: Path, count: int, port: int | None = None
) -> list[dict[str, Any]]:
    """Run the actual production wrapper, substituting only its dt clock."""
    if not 1 <= count <= 256:
        raise ValueError("entity count must be 1..256")
    pattern = patterns.LuaPattern("spectrum", patterns.PatternConfig(entity_count=count))
    encode = VizClient()._encode
    if pattern._calculate is None or pattern._flat_mode != "flat":
        raise RuntimeError("Expected production PUC Lua flat-pack baseline")
    original_clock = patterns.time
    clock = SimpleNamespace(monotonic=lambda: 0.0)
    patterns.time = clock
    records = []
    ws = None
    try:
        if port is not None:
            ws = await websockets.connect(f"ws://127.0.0.1:{port}", open_timeout=5)
        epoch = time.perf_counter()
        for index, feature in enumerate(features):
            deadline = epoch + index * DT
            if ws is not None:
                await asyncio.sleep(max(0, deadline - time.perf_counter()))
            lateness_us = max(0, time.perf_counter() - deadline) * 1e6 if ws else 0
            clock.monotonic = lambda index=index: index * DT
            audio = patterns.AudioState(**feature["audio"])
            start = time.perf_counter_ns()
            entities = pattern.calculate_entities(audio)
            runtime_us = (time.perf_counter_ns() - start) / 1000
            if len(entities) != count:
                raise RuntimeError("Python pattern failed or returned an incorrect entity count")
            start = time.perf_counter_ns()
            payload = encode({"type": "batch_update", "zone": ZONE, "entities": entities})
            serialize_us = (time.perf_counter_ns() - start) / 1000
            start = time.perf_counter_ns()
            if ws is not None:
                await asyncio.wait_for(ws.send(payload), timeout=5)
            send_us = (time.perf_counter_ns() - start) / 1000 if ws else 0
            records.append(
                {
                    "frame": index,
                    "entities": entities,
                    "runtime_us": runtime_us,
                    "serialize_us": serialize_us,
                    "send_us": send_us,
                    "lateness_us": lateness_us,
                    "bytes": len(payload.encode()),
                    "send_offset_ms": (time.perf_counter() - epoch) * 1000,
                }
            )
        if ws is not None:
            pong = await ws.ping(b"slice-fence")
            await asyncio.wait_for(pong, timeout=5)
            await asyncio.sleep(0.15)
    finally:
        patterns.time = original_clock
        if ws is not None:
            await ws.close()
    output.write_text("".join(json.dumps(record, allow_nan=False) + "\n" for record in records))
    return records


def compare_entities(
    reference: list[dict[str, Any]], candidate: list[dict[str, Any]], tolerance: float = 1e-9
) -> dict[str, Any]:
    if len(reference) != len(candidate) or not reference:
        raise AssertionError("Frame count mismatch or empty output")
    maximum = 0.0
    compared = 0
    for expected_frame, actual_frame in zip(reference, candidate, strict=True):
        assert expected_frame["frame"] == actual_frame["frame"]
        assert len(expected_frame["entities"]) == len(actual_frame["entities"])
        for expected, actual in zip(
            expected_frame["entities"], actual_frame["entities"], strict=True
        ):
            assert expected.keys() == actual.keys()
            for field in ("id", "band", "visible"):
                assert expected[field] == actual[field], (expected_frame["frame"], field)
            for field in ("x", "y", "z", "scale", "rotation"):
                assert math.isfinite(expected[field]) and math.isfinite(actual[field])
                error = abs(expected[field] - actual[field])
                maximum = max(maximum, error)
                assert error <= tolerance, (expected_frame["frame"], expected["id"], field, error)
                compared += 1
    return {
        "frames": len(reference),
        "numeric_fields_compared": compared,
        "maximum_absolute_error": maximum,
        "tolerance": tolerance,
    }


def summarize(records: list[dict[str, Any]], warmup: int = 60) -> dict[str, Any]:
    summary = {
        field: stats([record[field] for record in records[warmup:]])
        for field in ("runtime_us", "serialize_us", "send_us", "lateness_us")
    }
    summary["runtime_and_serialize_us"] = stats(
        [record["runtime_us"] + record["serialize_us"] for record in records[warmup:]]
    )
    return summary


def offline(binary: Path, output: Path) -> dict[str, Any]:
    output.mkdir(parents=True, exist_ok=True)
    pcm = output / "fixture.f32le"
    make_pcm(pcm)
    feature_path = output / "features.jsonl"
    repeat_path = output / "features-repeat.jsonl"
    for destination in (feature_path, repeat_path):
        subprocess.run(  # nosec B603 - local prototype executable and explicit argument vector
            [str(binary), "analyze", str(pcm), str(destination)], check=True, timeout=30
        )
    features, repeated = read_jsonl(feature_path), read_jsonl(repeat_path)
    assert len(features) == len(repeated) == 719
    for left, right in zip(features, repeated, strict=True):
        for field in ("instant_bass", "instant_kick"):
            assert left[field] == right[field]
        assert left["audio"]["bands"] == right["audio"]["bands"]
        assert left["audio"]["amplitude"] == right["audio"]["amplitude"]
    assert all(
        not feature["instant_kick"] and feature["audio"]["bands"] == [0.0] * 5
        for feature in features[:118]
    )
    kick_frames = [
        index
        for index, feature in enumerate(features)
        if feature["instant_kick"] and 118 <= index < 478
    ]
    # One onset per known burst, including a narrow analysis-window bound.
    assert len(kick_frames) == 12, kick_frames
    assert all(abs(frame - (120 + index * 30)) <= 2 for index, frame in enumerate(kick_frames)), (
        kick_frames
    )
    result: dict[str, Any] = {
        "environment": {
            "platform": platform.platform(),
            "python": sys.version,
            "lupa": lupa.__version__,
            "lua": str(lupa.LUA_VERSION),
            "rust": subprocess.check_output(  # nosec B603 - fixed repository script, no shell interpolation
                ["bash", str(HERE / "cargo-local.sh"), "--version"], text=True, timeout=10
            ).strip(),
        },
        "pcm_sha256": sha256(pcm),
        "feature_sha256": sha256(feature_path),
        "source_sha256": {
            str(path.relative_to(ROOT)): sha256(path)
            for path in [
                ROOT / "dj_client/src-tauri/src/audio/fft.rs",
                ROOT / "patterns/lib.lua",
                ROOT / "patterns/spectrum.lua",
                ROOT / "vj_server/patterns.py",
                ROOT / "vj_server/viz_client.py",
                HERE / "Cargo.lock",
            ]
        },
        "dsp_us": stats([feature["dsp_us"] for feature in features[60:]]),
        "fixture": {
            "sample_rate_hz": 48000,
            "window_samples": 1024,
            "hop_samples": 800,
            "frames": len(features),
            "seconds": 12,
            "preset": "AudioConfig::default",
            "dt_seconds": DT,
            "first_dt_seconds": 0.016,
        },
        "repeatable_dsp_fields": ["bands", "amplitude", "instant_bass", "instant_kick"],
        "kick_frames": kick_frames,
        "comparisons": {},
    }
    for count in (8, 64, 128):
        trials = []
        for trial in range(3):
            rust_path = output / f"rust-{count}-{trial}.jsonl"
            python_path = output / f"python-{count}-{trial}.jsonl"
            subprocess.run(  # nosec B603 - local prototype executable and explicit argument vector
                [str(binary), "render", str(feature_path), str(rust_path), str(count)],
                check=True,
                timeout=30,
            )
            python_records = asyncio.run(python_render(features, python_path, count))
            rust_records = read_jsonl(rust_path)
            assert all(
                entity["scale"] == 0.2
                for record in python_records[:118]
                for entity in record["entities"]
            )
            assert (
                max(
                    entity["scale"]
                    for record in python_records[120:478]
                    for entity in record["entities"]
                )
                > 0.5
            )
            trials.append(
                {
                    "parity": compare_entities(python_records, rust_records),
                    "rust": summarize(rust_records),
                    "python": summarize(python_records),
                }
            )
        result["comparisons"][str(count)] = trials
    write_json(output / "offline-summary.json", result)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=HERE / "target/release/mcav-runtime-slice")
    parser.add_argument("--output", type=Path, default=HERE / "results")
    args = parser.parse_args()
    result = offline(args.binary.resolve(), args.output.resolve())
    print(
        json.dumps(
            {
                "passed": True,
                "summary": str(args.output / "offline-summary.json"),
                "comparisons": list(result["comparisons"]),
            }
        )
    )


if __name__ == "__main__":
    main()
