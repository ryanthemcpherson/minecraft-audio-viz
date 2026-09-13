"""Isolated, paced multi-zone Rust/Python validation against disposable Paper."""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import re
import tempfile
import time
from pathlib import Path
from typing import Any

import websockets
from compare import HERE, ROOT, read_jsonl, sha256, write_json
from matrix_compare import compare_matrix, make_config, python_matrix, summarize_matrix
from paper_compare import gate


def sample_ids(count: int) -> list[int]:
    return sorted({0, count // 2, count - 1})


def setup_entity(index: int, count: int) -> dict[str, Any]:
    return {
        "id": f"block_{index}",
        "x": (index + 1) / (count + 1),
        "y": 0.1,
        "z": 0.2,
        "scale": 0.2,
        "visible": True,
    }


def position(entity: dict[str, Any], zone_index: int) -> list[float]:
    return [
        24 * zone_index + min(1, max(0, entity["x"])) * 8,
        80 + min(1, max(0, entity["y"])) * 8,
        min(1, max(0, entity["z"])) * 6,
    ]


def parse_snapshot(line: str) -> dict[str, Any]:
    """Parse only inspected fields from one coherent console NBT snapshot."""
    result: dict[str, Any] = {"raw": line}
    for field, length in (("Pos", 3), ("scale", 3), ("left_rotation", 4)):
        match = re.search(rf"\b{field}:\s*\[([^\]]+)\]", line)
        if not match:
            raise gate.GateError(f"Missing NBT {field}: {line}")
        values = [float(value.strip().rstrip("dfDF")) for value in match[1].split(",")]
        if len(values) != length or not all(math.isfinite(value) for value in values):
            raise gate.GateError(f"Invalid NBT {field}: {line}")
        result[field] = values
    glowing = re.search(r"\bGlowing:\s*([01])b", line)
    if glowing:
        result["glow"] = glowing[1] == "1"
    material = re.search(r'\bblock_state:\s*\{[^}]*\bName:\s*"([^"]+)"', line)
    if material:
        result["material"] = material[1]
    return result


def transform_matches(observed: dict[str, Any], entity: dict[str, Any], zone_index: int) -> bool:
    scale = min(4, max(0, entity["scale"])) if entity.get("visible", True) else 0
    radians = math.radians(min(360, max(-360, entity.get("rotation", 0))))
    quaternion = [0, math.sin(radians / 2), 0, math.cos(radians / 2)]

    def close(left: list[float], right: list[float]) -> bool:
        return len(left) == len(right) and all(
            math.isfinite(a) and abs(a - b) <= 0.001 for a, b in zip(left, right, strict=True)
        )

    return (
        close(observed["Pos"], position(entity, zone_index))
        and close(observed["scale"], [scale] * 3)
        and (
            close(observed["left_rotation"], quaternion)
            or close(observed["left_rotation"], [-value for value in quaternion])
        )
    )


def match_samples(
    samples: list[dict[str, Any]], records: list[dict[str, Any]], config: dict[str, Any]
) -> dict[str, Any]:
    observations = []
    startup_observations = []
    per_zone: dict[str, Any] = {}
    for zone_index, zone in enumerate(config["zones"]):
        zone_samples = [sample for sample in samples if sample["zone"] == zone["name"]]
        active_samples = []
        startup_count = 0
        distinct = set()
        previous_frame = -1
        for sample in zone_samples:
            matches = [
                record["frame"]
                for record in records
                if record["frame"] >= previous_frame
                and transform_matches(
                    sample,
                    record["zones"][zone_index]["entities"][sample["entity_index"]],
                    zone_index,
                )
            ]
            if not matches:
                # Process startup and PCM loading can outlast the first console
                # query. Accept only the exact setup state before this zone's
                # first emitted-frame observation, never arbitrary mismatches.
                if previous_frame < 0 and transform_matches(
                    sample,
                    setup_entity(sample["entity_index"], zone["entity_count"]),
                    zone_index,
                ):
                    startup_observations.append(sample)
                    startup_count += 1
                    continue
                raise gate.GateError(f"NBT snapshot did not match any emitted frame: {sample}")
            active_samples.append(sample)
            previous_frame = min(matches)
            distinct.add(tuple(sample["Pos"] + sample["scale"] + sample["left_rotation"]))
            observations.append(
                {**sample, "matching_frames": matches, "ordered_matching_frame": previous_frame}
            )
        identities = {sample["entity_index"] for sample in active_samples}
        if len(identities) < 2:
            raise gate.GateError(f"Too few sampled identities for {zone['name']}")
        # Setup observations cannot establish sampled identities or motion.
        # Two different fixed entities do not establish changing visual output.
        moving_ids = [
            index
            for index in identities
            if len(
                {
                    tuple(s["Pos"] + s["scale"] + s["left_rotation"])
                    for s in active_samples
                    if s["entity_index"] == index
                }
            )
            >= 2
        ]
        if not moving_ids:
            raise gate.GateError(f"Frozen sampled output for {zone['name']}")
        per_zone[zone["name"]] = {
            "samples": len(active_samples),
            "startup_samples": startup_count,
            "sampled_ids": sorted(identities),
            "changing_ids": sorted(moving_ids),
            "distinct_transforms": len(distinct),
        }
    return {
        "per_zone": per_zone,
        "observations": observations,
        "startup_observations": startup_observations,
    }


def queue_counts(log: str, frames: int, zones: int) -> dict[str, int]:
    match = re.search(
        r"MessageQueue stopped\. Processed (\d+) messages in (\d+) batches(?: \((\d+) dropped\))?",
        log,
    )
    if not match:
        raise gate.GateError("Missing final Paper queue counters")
    processed, applied, dropped = (int(value or 0) for value in match.groups())
    if processed != (frames + 1) * zones or applied + dropped != processed:
        raise gate.GateError(f"Unexpected aggregate queue accounting: {match[0]}")
    return {
        "processed": processed,
        "applied_batches_including_seed": applied,
        "coalesced_or_dropped": dropped,
        "setup_batches": zones,
    }


async def pool_count(ws: Any, name: str, count: int) -> int:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        response = await gate.request(ws, {"type": "get_zone", "zone": name}, "zone")
        if response.get("entity_count") == count:
            return count
        await asyncio.sleep(0.1)
    raise gate.GateError(f"Pool {name} did not reach {count} entities")


async def setup(server: gate.PaperProcess, ws: Any, config: dict[str, Any]) -> dict[str, int]:
    counts = {}
    for zone_index, zone in enumerate(config["zones"]):
        stage = zone["name"].removesuffix("_main_stage")
        await gate.request(
            ws,
            {
                "type": "create_stage",
                "name": stage,
                "template": "club",
                "anchor": {"world": "world", "x": 24 * zone_index, "y": 80, "z": 0},
            },
            "stage_created",
        )
        # Only the measured main pool is initialized. No active decorators or
        # unused audience pools are introduced by activating the club template.
        await gate.request(
            ws,
            {
                "type": "init_pool",
                "zone": zone["name"],
                "count": zone["entity_count"],
                "material": "GLOWSTONE",
            },
            "pool_initialized",
        )
        counts[zone["name"]] = await pool_count(ws, zone["name"], zone["entity_count"])
        entities = [
            setup_entity(index, zone["entity_count"]) for index in range(zone["entity_count"])
        ]
        await ws.send(
            json.dumps({"type": "batch_update", "zone": zone["name"], "entities": entities})
        )
        await asyncio.sleep(0.3)
        for index in sample_ids(zone["entity_count"]):
            x, y, z = position(entities[index], zone_index)
            tag = f"matrix_{zone_index}_{index}"
            await server.command(
                f"tag @e[type=minecraft:block_display,x={x},y={y},z={z},distance=..0.015,limit=1] add {tag}",
                rf"Added tag '{tag}' to",
            )
    return counts


async def snapshot(server: gate.PaperProcess, zone_index: int, entity_index: int) -> dict[str, Any]:
    lines = await server.command(
        f"data get entity @e[tag=matrix_{zone_index}_{entity_index},limit=1]",
        r"has the following entity data: \{",
    )
    for line in lines:
        if "has the following entity data: {" in line:
            return parse_snapshot(line)
    raise gate.GateError(f"Missing coherent NBT response: {lines}")


async def rust_stream(
    binary: Path, features: Path, config_path: Path, output: Path, port: int, pcm: Path | None
) -> None:
    process = await asyncio.create_subprocess_exec(
        str(binary),
        "matrix-pcm" if pcm else "matrix",
        str(pcm or features),
        str(config_path),
        str(output),
        str(port),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    try:
        stdout, _ = await asyncio.wait_for(process.communicate(), 90)
    except BaseException:
        if process.returncode is None:
            process.kill()
            await process.wait()
        raise
    if process.returncode:
        raise gate.GateError(f"Rust sender failed ({process.returncode}): {stdout.decode()}")


async def run_one(
    label: str,
    binary: Path,
    features_path: Path,
    config: dict[str, Any],
    java: Path,
    directory: Path,
    port: int,
    pcm: Path | None = None,
) -> dict[str, Any]:
    server = gate.PaperProcess(java, directory, label, 240)
    evidence: dict[str, Any] = {
        "passed": False,
        "runtime": label,
        "directory": str(directory),
        "config": config,
    }
    stream = None
    try:
        await server.start()
        await server.command(
            "forceload add -16 -16 96 32",
            r"Marked .*force loaded|No chunks were marked for force loading",
        )
        async with websockets.connect(f"ws://127.0.0.1:{port}", open_timeout=15) as ws:
            evidence["pool_counts_before"] = await setup(server, ws, config)
            config_path, records_path = directory / "matrix.json", directory / "frames.jsonl"
            write_json(config_path, config)
            features = read_jsonl(features_path)
            if label.startswith("rust"):
                stream = asyncio.create_task(
                    rust_stream(binary, features_path, config_path, records_path, port, pcm)
                )
            else:
                stream = asyncio.create_task(python_matrix(features, config, records_path, port))
            epoch = time.perf_counter()
            samples = []
            await asyncio.sleep(0.4)
            while not stream.done():
                for zone_index, zone in enumerate(config["zones"]):
                    for index in sample_ids(zone["entity_count"])[:2]:
                        observed = await snapshot(server, zone_index, index)
                        samples.append(
                            {
                                "zone": zone["name"],
                                "entity_index": index,
                                "observer_offset_ms": (time.perf_counter() - epoch) * 1000,
                                **observed,
                            }
                        )
                await asyncio.sleep(0.1)
            await stream
            records = read_jsonl(records_path)
            if len(records) != len(features):
                raise gate.GateError("Incomplete sender frame output")
            evidence["sent_frames"] = len(records)
            evidence["processing"] = summarize_matrix(records)
            evidence["stream_state"] = match_samples(samples, records, config)
            final = []
            evidence["pool_counts_after"] = {}
            # Ping/pong in senders fences receipt, not application; give the
            # main-thread queue time to apply its final batch before sampling.
            await asyncio.sleep(0.3)
            for zone_index, zone in enumerate(config["zones"]):
                evidence["pool_counts_after"][zone["name"]] = await pool_count(
                    ws, zone["name"], zone["entity_count"]
                )
                for index in sample_ids(zone["entity_count"]):
                    entity = records[-1]["zones"][zone_index]["entities"][index]
                    observed = await snapshot(server, zone_index, index)
                    if not transform_matches(observed, entity, zone_index):
                        raise gate.GateError(
                            f"Final transform mismatch {zone['name']} {index}: {observed}"
                        )
                    if "glow" in entity and observed.get("glow") != entity["glow"]:
                        raise gate.GateError(
                            f"Final glow mismatch: {observed}, expected {entity['glow']}"
                        )
                    requested_materials = sorted(
                        {
                            "minecraft:" + item["material"].lower().removeprefix("minecraft:")
                            for record in records
                            for item in [record["zones"][zone_index]["entities"][index]]
                            if item.get("material")
                        }
                    )
                    # The pinned queued renderer drops material updates. Keep a
                    # deliberately different base material to expose this known
                    # renderer limitation without changing production Java.
                    observed["material_application"] = {
                        "base": "minecraft:glowstone",
                        "requested": requested_materials,
                        "observed": observed.get("material"),
                        "requested_material_observed": observed.get("material")
                        in requested_materials
                        if requested_materials
                        else None,
                        "status": "known_queued_renderer_limitation"
                        if requested_materials
                        else "not_requested",
                    }
                    final.append({"zone": zone["name"], "id": entity["id"], **observed})
            evidence["final_sampled_entities"] = final
            evidence["frames_sha256"] = sha256(records_path)
    except Exception as error:
        evidence["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        try:
            if stream is not None and not stream.done():
                stream.cancel()
                try:
                    await stream
                except asyncio.CancelledError:
                    pass
            await server.stop()
            evidence["clean_shutdown"] = True
            log_path = directory / f"{label}.log"
            if log_path.exists():
                log = log_path.read_text()
                errors = [line for line in log.splitlines() if " ERROR]" in line]
                evidence["error_log_entries"] = len(errors)
                if errors:
                    raise gate.GateError(f"Paper logged errors: {errors}")
                if "error" not in evidence:
                    evidence["queue"] = queue_counts(
                        log, evidence["sent_frames"], len(config["zones"])
                    )
                    evidence["passed"] = True
        except Exception as error:
            evidence["shutdown_or_gate_error"] = f"{type(error).__name__}: {error}"
            raise
        finally:
            write_json(directory / "evidence.json", evidence)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=HERE / "target/release/mcav-runtime-slice")
    parser.add_argument("--features", type=Path, required=True)
    parser.add_argument(
        "--pcm", type=Path, help="Also run connected PCM at four zones, 128 entities each"
    )
    parser.add_argument("--plugin", type=Path, required=True)
    parser.add_argument("--plugin-sha256", required=True)
    parser.add_argument("--cache", type=Path, default=ROOT / "minecraft_plugin/target/paper-cache")
    parser.add_argument("--output", type=Path, default=HERE / "results/paper-matrix")
    parser.add_argument("--trials", type=int, default=2)
    parser.add_argument("--zones", type=int, nargs="+", default=[1, 2, 4], choices=[1, 2, 4])
    parser.add_argument("--entities", type=int, nargs="+", default=[64, 128], choices=[64, 128])
    parser.add_argument("--accept-eula", action="store_true", required=True)
    parser.add_argument("--pcm-only", action="store_true", help="Run only the connected PCM pair")
    args = parser.parse_args()
    if args.trials < 1:
        parser.error("--trials must be positive")
    if args.pcm_only and not args.pcm:
        parser.error("--pcm-only requires --pcm")
    if sha256(args.plugin) != args.plugin_sha256:
        raise gate.GateError("Plugin digest mismatch")
    lock = json.loads(gate.LOCK_PATH.read_text())
    paper, java = gate.prepare(args.cache, lock)
    args.output.mkdir(parents=True, exist_ok=False)
    evidence: dict[str, Any] = {
        "passed": False,
        "pcm_only": args.pcm_only,
        "plugin_sha256": args.plugin_sha256,
        "binary_sha256": sha256(args.binary),
        "features_sha256": sha256(args.features),
        "pcm_sha256": sha256(args.pcm) if args.pcm else None,
        "lock": lock,
        "runs": [],
        "parity": [],
        "limitations": [
            "Queue counts are aggregate, not per-zone delivery counts.",
            "Three stable identities per zone sampled at completion; full pool counts checked.",
            "Transient optional-field persistence after coalescing is not asserted.",
            "Pinned queued renderer ignores material; observed/requested material recorded separately.",
            "Clock-local processing and observation offsets are not one-way latency.",
        ],
    }

    def trial(
        label: str, config: dict[str, Any], features: Path, pcm: Path | None = None
    ) -> dict[str, Any]:
        directory = Path(tempfile.mkdtemp(prefix=f"{label}-", dir=args.output.resolve()))
        ports = gate.free_ports(2)
        gate.write_fixture(directory, args.plugin.resolve(), paper, ports)
        count = max(zone["entity_count"] for zone in config["zones"])
        (directory / "plugins/AudioViz/config.yml").write_text(
            "websocket:\n  address: '127.0.0.1'\n"
            + f"  port: {ports[1]}\nws-secret: ''\n"
            + "runtime:\n  enabled: false\n"
            + f"defaults:\n  entity_count: {count}\n"
            + f"max-entities-per-zone: {count}\nperformance:\n  max_entities_per_zone: {count}\n",
            encoding="utf-8",
        )
        result = asyncio.run(
            run_one(
                label,
                args.binary.resolve(),
                features.resolve(),
                config,
                java,
                directory,
                ports[1],
                pcm.resolve() if pcm else None,
            )
        )
        evidence["runs"].append(result)
        write_json(args.output / "summary.json", evidence)
        print(json.dumps({"completed": label, "queue": result["queue"]}), flush=True)
        return result

    try:
        replay_zone_counts = [] if args.pcm_only else args.zones
        for zone_count in replay_zone_counts:
            for entity_count in args.entities:
                config = make_config(zone_count, entity_count)
                for index in range(args.trials):
                    pair = {}
                    for runtime in ["rust", "python"] if index % 2 == 0 else ["python", "rust"]:
                        result = trial(
                            f"{runtime}-z{zone_count}-e{entity_count}-t{index}",
                            config,
                            args.features,
                        )
                        pair[runtime] = read_jsonl(Path(result["directory"]) / "frames.jsonl")
                    evidence["parity"].append(
                        {
                            "zones": zone_count,
                            "entities": entity_count,
                            "trial": index,
                            **compare_matrix(pair["python"], pair["rust"]),
                        }
                    )
        if args.pcm:
            config = make_config(4, 128)
            rust = trial("rust-pcm", config, args.features, args.pcm)
            live_features = Path(rust["directory"]) / "frames.jsonl"
            python = trial("python-pcm", config, live_features)
            evidence["pcm_parity"] = compare_matrix(
                read_jsonl(live_features), read_jsonl(Path(python["directory"]) / "frames.jsonl")
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
