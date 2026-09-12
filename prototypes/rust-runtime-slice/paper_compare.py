"""Paced Rust/Python Lua output -> disposable real Paper entities; no managed VJ."""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import tempfile
import time
from pathlib import Path
from typing import Any

import websockets
from compare import (
    HERE,
    ROOT,
    ZONE,
    compare_entities,
    python_render,
    read_jsonl,
    sha256,
    stats,
    summarize,
    write_json,
)

from scripts.release import paper_matrix as gate


async def vector(server: gate.PaperProcess, tag: str, field: str) -> list[float]:
    return gate.parse_vector(
        await server.command(
            f"data get entity @e[tag={tag},limit=1] {field}", r"has the following entity data: \["
        )
    )


def position(entity: dict[str, Any]) -> list[float]:
    return [entity["x"] * 8, 80 + entity["y"] * 8, entity["z"] * 6]


async def seed_and_tag(server: gate.PaperProcess, ws: Any) -> None:
    # Unique positions let console tags identify each stable pool ID, not a
    # randomly selected BlockDisplay. This setup batch is counted separately.
    await ws.send(
        json.dumps(
            {
                "type": "batch_update",
                "zone": ZONE,
                "entities": [
                    {
                        "id": f"block_{index}",
                        "x": (index + 1) / 10,
                        "y": 0.1,
                        "z": 0.2,
                        "scale": 0.2,
                        "visible": True,
                    }
                    for index in range(8)
                ],
            }
        )
    )
    await asyncio.sleep(0.3)
    for index in range(8):
        await server.command(
            f"tag @e[type=minecraft:block_display,x={(index + 1) * 0.8},y=80.8,z=1.2,distance=..0.05,limit=1] add slice_{index}",
            rf"Added tag 'slice_{index}' to",
        )


async def rust_stream(
    binary: Path, features: Path, output: Path, port: int, pcm: Path | None = None
) -> None:
    process = await asyncio.create_subprocess_exec(
        str(binary),
        "stream-pcm" if pcm else "render",
        str(pcm or features),
        str(output),
        "8",
        str(port),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    try:
        stdout, _ = await asyncio.wait_for(process.communicate(), timeout=45)
    except BaseException:
        if process.returncode is None:
            process.kill()
            await process.wait()
        raise
    if process.returncode != 0:
        raise gate.GateError(f"Rust sender failed ({process.returncode}): {stdout.decode()}")


def match_samples(samples: list[dict[str, Any]], records: list[dict[str, Any]]) -> dict[str, Any]:
    matches = []
    for sample in samples:
        matching = []
        for record in records:
            expected = position(record["entities"][0])
            if all(
                abs(left - right) <= 0.001
                for left, right in zip(expected, sample["position"], strict=True)
            ):
                matching.append(record["frame"])
        if not matching:
            raise gate.GateError(f"Sampled position did not match any visual frame: {sample}")
        matches.append({**sample, "matching_frames": matching})
    if len({tuple(sample["position"]) for sample in samples}) < 10:
        raise gate.GateError("Too few distinct real entity positions during the stream")
    return {
        "samples": len(samples),
        "distinct_positions": len({tuple(sample["position"]) for sample in samples}),
        "observations": matches,
    }


async def run_one(
    label: str, binary: Path, features_path: Path, java: Path, directory: Path, port: int
) -> dict[str, Any]:
    server = gate.PaperProcess(java, directory, label, 240)
    evidence: dict[str, Any] = {"passed": False, "runtime": label, "directory": str(directory)}
    stream = None
    try:
        await server.start()
        await server.command(
            "forceload add -32 -32 32 32",
            r"Marked .*force loaded|No chunks were marked for force loading",
        )
        async with websockets.connect(f"ws://127.0.0.1:{port}", open_timeout=15) as ws:
            await gate.request(
                ws,
                {
                    "type": "create_stage",
                    "name": gate.STAGE,
                    "template": "club",
                    "anchor": {"world": "world", "x": 0, "y": 80, "z": 0},
                },
                "stage_created",
            )
            await gate.request(
                ws, {"type": "activate_stage", "name": gate.STAGE}, "stage_activated"
            )
            await gate.wait_pool(ws)
            await seed_and_tag(server, ws)
            records_path = directory / "frames.jsonl"
            features = read_jsonl(features_path)
            if label.startswith("rust"):
                pcm = features_path.parent / "fixture.f32le" if label == "rust-pcm" else None
                stream = asyncio.create_task(
                    rust_stream(binary, features_path, records_path, port, pcm)
                )
            else:
                stream = asyncio.create_task(python_render(features, records_path, 8, port))
            epoch = time.perf_counter()
            samples = []
            # Serial console queries avoid the asynchronous-response bug already
            # fixed in the reused harness. Offsets are observer-local, not latency.
            await asyncio.sleep(0.4)
            while not stream.done():
                observed = await vector(server, "slice_0", "Pos")
                samples.append(
                    {
                        "observer_offset_ms": (time.perf_counter() - epoch) * 1000,
                        "position": observed,
                    }
                )
                await asyncio.sleep(0.2)
            await stream
            records = read_jsonl(records_path)
            assert len(records) == len(features)
            evidence["sent_frames"] = len(records)
            evidence["processing"] = summarize(records)
            if label == "rust-pcm":
                evidence["live_dsp_us"] = stats([record["dsp_us"] for record in records[60:]])
            evidence["stream_positions"] = match_samples(samples, records)
            final = []
            for index, entity in enumerate(records[-1]["entities"]):
                observed_position = await vector(server, f"slice_{index}", "Pos")
                observed_scale = await vector(server, f"slice_{index}", "transformation.scale")
                gate.assert_vector(observed_position, position(entity))
                gate.assert_vector(observed_scale, [entity["scale"]] * 3)
                final.append(
                    {"id": entity["id"], "position": observed_position, "scale": observed_scale}
                )
            evidence["final_entities"] = final
            evidence["frames_sha256"] = sha256(records_path)
    finally:
        if stream is not None and not stream.done():
            stream.cancel()
            try:
                await stream
            except asyncio.CancelledError:
                pass
        await server.stop()
    evidence["clean_shutdown"] = True
    log = (directory / f"{label}.log").read_text()
    errors = [line for line in log.splitlines() if " ERROR]" in line]
    if errors:
        raise gate.GateError(f"Paper logged errors: {errors}")
    match = re.search(
        r"MessageQueue stopped\. Processed (\d+) messages in (\d+) batches(?: \((\d+) dropped\))?",
        log,
    )
    if not match:
        raise gate.GateError("Missing final Paper queue counters")
    processed, batches, dropped = (int(value or 0) for value in match.groups())
    if processed != evidence["sent_frames"] + 1 or batches + dropped != processed:
        raise gate.GateError(f"Unexpected queue accounting: {match.group(0)}")
    evidence["queue"] = {
        "processed": processed,
        "applied_batches_including_seed": batches,
        "coalesced_or_dropped": dropped,
        "setup_batches": 1,
    }
    evidence["error_log_entries"] = 0
    evidence["passed"] = True
    write_json(directory / "evidence.json", evidence)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=HERE / "target/release/mcav-runtime-slice")
    parser.add_argument("--features", type=Path, default=HERE / "results/features.jsonl")
    parser.add_argument("--plugin", type=Path, required=True)
    parser.add_argument("--plugin-sha256", required=True)
    parser.add_argument("--cache", type=Path, default=ROOT / "minecraft_plugin/target/paper-cache")
    parser.add_argument("--output", type=Path, default=HERE / "results/paper")
    parser.add_argument("--accept-eula", action="store_true", required=True)
    args = parser.parse_args()
    if sha256(args.plugin) != args.plugin_sha256:
        raise gate.GateError("Plugin digest mismatch")
    lock = json.loads(gate.LOCK_PATH.read_text())
    paper, java = gate.prepare(args.cache, lock)
    args.output.mkdir(parents=True, exist_ok=True)
    evidence: dict[str, Any] = {
        "passed": False,
        "plugin_sha256": args.plugin_sha256,
        "feature_sha256": sha256(args.features),
        "lock": lock,
        "runs": [],
    }
    try:
        features_path = args.features.resolve()
        for label in ("rust-1", "python-1", "python-2", "rust-2", "rust-pcm", "python-pcm"):
            directory = Path(tempfile.mkdtemp(prefix=f"{label}-", dir=args.output.resolve()))
            ports = gate.free_ports(2)
            gate.write_fixture(directory, args.plugin.resolve(), paper, ports)
            result = asyncio.run(
                run_one(label, args.binary.resolve(), features_path, java, directory, ports[1])
            )
            evidence["runs"].append(result)
            if label == "rust-pcm":
                # Replay exactly the live DSP outputs, including its wall-clock
                # FFT beat state, through the Python wrapper in the next run.
                features_path = directory / "frames.jsonl"
            print(json.dumps({"completed": label, "queue": result["queue"]}), flush=True)
        reference = read_jsonl(Path(evidence["runs"][0]["directory"]) / "frames.jsonl")
        evidence["live_parity"] = [
            compare_entities(reference, read_jsonl(Path(run["directory"]) / "frames.jsonl"))
            for run in evidence["runs"][1:4]
        ]
        evidence["pcm_path_parity"] = compare_entities(
            read_jsonl(Path(evidence["runs"][4]["directory"]) / "frames.jsonl"),
            read_jsonl(Path(evidence["runs"][5]["directory"]) / "frames.jsonl"),
        )
        evidence["passed"] = True
    except Exception as error:
        evidence["error"] = f"{type(error).__name__}: {error}"
    finally:
        write_json(args.output / "summary.json", evidence)
        print(
            json.dumps({"passed": evidence["passed"], "summary": str(args.output / "summary.json")})
        )
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
