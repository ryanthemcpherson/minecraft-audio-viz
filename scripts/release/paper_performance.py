"""Measure sustained Paper 26.2 visualization performance and soak health."""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import re
import subprocess  # nosec B404
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from scripts.release.paper_e2e import (
    DEFAULT_JAVA_CONTAINER_IMAGE,
    DEFAULT_MANIFEST,
    DEFAULT_PAPER_CACHE,
    E2EArguments,
    ScenarioAssertion,
    _entity_updates,
    _new_server,
    _read_generated_secret,
    _register_secret_after_leak_check,
    _write_primary_zone,
)
from scripts.release.paper_harness import (
    PaperManifest,
    PaperServer,
    calculate_sha256,
    download_paper,
)
from vj_server.viz_client import VizClient

MIN_TPS = 19.8
MAX_APPLIED_P95_MS = 100.0
MAX_MAIN_THREAD_P95_MS = 10.0
REQUIRED_ENTITY_COUNT = 256
REQUIRED_SOAK_SECONDS = 8 * 60 * 60
PARSED_QUEUE_CAP = 1000
RAW_QUEUE_CAP = 64
FRAME_INTERVAL_SECONDS = 1.0 / 20.0
TPS_INTERVAL_SECONDS = 60.0
RESOURCE_INTERVAL_SECONDS = 60.0 * 60.0
APPLIED_FRAME_TIMEOUT_SECONDS = 10.0

ANSI_ESCAPE_PATTERN = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
TPS_VALUE_PATTERN = re.compile(r"\*?([0-9]+(?:\.[0-9]+)?)")


def percentile(samples: Sequence[float], quantile: float) -> float:
    """Return a linearly interpolated percentile for finite numeric samples."""

    if not samples:
        raise ValueError("Percentile requires at least one sample")
    if quantile < 0 or quantile > 100:
        raise ValueError("Percentile quantile must be between 0 and 100")
    ordered = sorted(float(sample) for sample in samples)
    if not all(math.isfinite(sample) for sample in ordered):
        raise ValueError("Percentile samples must be finite")
    position = (len(ordered) - 1) * quantile / 100
    lower_index = math.floor(position)
    upper_index = math.ceil(position)
    if lower_index == upper_index:
        return ordered[lower_index]
    fraction = position - lower_index
    return ordered[lower_index] + (ordered[upper_index] - ordered[lower_index]) * fraction


def parse_tps_line(line: str) -> tuple[float, float, float]:
    """Parse Paper's one-, five-, and fifteen-minute TPS output."""

    sanitized = ANSI_ESCAPE_PATTERN.sub("", line)
    marker = "TPS from last 1m, 5m, 15m:"
    if marker not in sanitized:
        raise ValueError("Paper TPS values were not present")
    values = TPS_VALUE_PATTERN.findall(sanitized.split(marker, maxsplit=1)[1])
    if len(values) < 3:
        raise ValueError("Paper TPS values were incomplete")
    return tuple(float(value) for value in values[:3])  # type: ignore[return-value]


def minimum_one_minute_tps(samples: Sequence[tuple[float, float, float]]) -> float:
    """Return the minimum current-load TPS without startup-skewed long windows."""

    if not samples:
        raise ValueError("At least one TPS sample is required")
    return min(sample[0] for sample in samples)


def assert_queue_caps(
    metrics: Mapping[str, Any],
    *,
    parsed_cap: int = PARSED_QUEUE_CAP,
    raw_cap: int = RAW_QUEUE_CAP,
) -> None:
    """Fail when either bounded WebSocket queue exceeds its configured cap."""

    parsed_depth = int(metrics["parsedQueueDepth"])
    raw_depth = int(metrics["rawQueueDepth"])
    if parsed_depth > parsed_cap:
        raise AssertionError(f"parsedQueueDepth exceeded cap: {parsed_depth} > {parsed_cap}")
    if raw_depth > raw_cap:
        raise AssertionError(f"rawQueueDepth exceeded cap: {raw_depth} > {raw_cap}")


def resource_delta(
    baseline: Mapping[str, Any], current: Mapping[str, Any]
) -> dict[str, int | float]:
    """Calculate deltas for numeric keys present in both snapshots."""

    deltas: dict[str, int | float] = {}
    for key in sorted(baseline.keys() & current.keys()):
        before = baseline[key]
        after = current[key]
        if (
            isinstance(before, (int, float))
            and not isinstance(before, bool)
            and isinstance(after, (int, float))
            and not isinstance(after, bool)
        ):
            deltas[key] = after - before
    return deltas


def assert_minimum_samples(samples: Sequence[float], minimum_samples: int) -> None:
    """Require enough applied-frame latency samples for release evidence."""

    if len(samples) < minimum_samples:
        raise AssertionError(
            f"Collected {len(samples)} latency samples; required {minimum_samples}"
        )


def is_exact_release_soak(duration_seconds: int) -> bool:
    """Return whether a requested duration is the fixed eight-hour release soak."""

    return duration_seconds == REQUIRED_SOAK_SECONDS


@dataclass(frozen=True)
class PerformanceArguments:
    """Resolved inputs for one sustained performance run."""

    plugin: Path
    report: Path
    manifest: Path
    paper_cache: Path
    java: str
    java_container_image: str
    duration_seconds: int
    minimum_samples: int
    reconnect_interval_seconds: int


def _parse_arguments(argv: Sequence[str] | None) -> PerformanceArguments:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--paper-cache", type=Path, default=DEFAULT_PAPER_CACHE)
    parser.add_argument("--java", default="java")
    parser.add_argument(
        "--java-container-image",
        default=DEFAULT_JAVA_CONTAINER_IMAGE,
        help="Pinned image used only to materialize a local Java 25 runtime",
    )
    parser.add_argument("--duration-seconds", required=True, type=int)
    parser.add_argument("--minimum-samples", default=1000, type=int)
    parser.add_argument("--reconnect-interval-seconds", default=0, type=int)
    raw = parser.parse_args(argv)
    if raw.duration_seconds <= 0:
        parser.error("--duration-seconds must be positive")
    if raw.minimum_samples <= 0:
        parser.error("--minimum-samples must be positive")
    if raw.reconnect_interval_seconds < 0:
        parser.error("--reconnect-interval-seconds cannot be negative")
    return PerformanceArguments(
        plugin=raw.plugin.resolve(),
        report=raw.report.resolve(),
        manifest=raw.manifest.resolve(),
        paper_cache=raw.paper_cache.resolve(),
        java=str(raw.java),
        java_container_image=str(raw.java_container_image),
        duration_seconds=int(raw.duration_seconds),
        minimum_samples=int(raw.minimum_samples),
        reconnect_interval_seconds=int(raw.reconnect_interval_seconds),
    )


def _e2e_arguments(arguments: PerformanceArguments) -> E2EArguments:
    return E2EArguments(
        plugin=arguments.plugin,
        report=arguments.report,
        manifest=arguments.manifest,
        paper_cache=arguments.paper_cache,
        probe=Path("scripts/release/probe/target/mcav-integration-probe-1.0.jar").resolve(),
        java=arguments.java,
        java_container_image=arguments.java_container_image,
    )


def _bytes_from_jcmd(value: int, unit: str) -> int:
    multipliers = {"K": 1024, "M": 1024**2, "G": 1024**3}
    return value * multipliers[unit.upper()]


def _parse_heap_info(output: str) -> tuple[int, int]:
    match = re.search(
        r"heap\s+total\s+reserved\s+\d+[KMG],\s+committed\s+"
        r"(\d+)([KMG]),\s+used\s+(\d+)([KMG])",
        output,
        re.IGNORECASE,
    )
    if match is None:
        match = re.search(
            r"heap\s+total\s+(\d+)([KMG]),\s+used\s+(\d+)([KMG])",
            output,
            re.IGNORECASE,
        )
    if match is None:
        raise ScenarioAssertion("jcmd heap output did not contain total and used values")
    total = _bytes_from_jcmd(int(match.group(1)), match.group(2))
    used = _bytes_from_jcmd(int(match.group(3)), match.group(4))
    return total, used


def _parse_thread_print(output: str) -> tuple[int, int, int]:
    headers = re.findall(r'^"([^"]+)"([^\r\n]*)', output, re.MULTILINE)
    mcav_headers = [
        (name, details)
        for name, details in headers
        if "audioviz" in name.lower() or "mcav" in name.lower()
    ]
    mcav_non_daemon = sum(
        1 for _name, details in mcav_headers if " daemon " not in f" {details.lower()} "
    )
    return len(headers), len(mcav_headers), mcav_non_daemon


def _run_jcmd(java_executable: str, pid: int, command: str) -> str:
    jcmd_name = "jcmd.exe" if Path(java_executable).suffix.lower() == ".exe" else "jcmd"
    jcmd = Path(java_executable).resolve().parent / jcmd_name
    if not jcmd.is_file():
        raise ScenarioAssertion("Java 25 runtime does not include jcmd")
    completed = subprocess.run(  # nosec B603
        [str(jcmd), str(pid), command],
        capture_output=True,
        check=False,
        text=True,
        timeout=30,
    )
    if completed.returncode != 0:
        raise ScenarioAssertion(f"jcmd {command} failed with code {completed.returncode}")
    return completed.stdout


def _resource_snapshot(server: PaperServer, label: str, elapsed_seconds: float) -> dict[str, Any]:
    pid = server.pid
    if pid is None:
        raise ScenarioAssertion("Paper process is not running for resource sampling")
    heap_total, heap_used = _parse_heap_info(_run_jcmd(server.java_executable, pid, "GC.heap_info"))
    thread_count, mcav_thread_count, mcav_non_daemon = _parse_thread_print(
        _run_jcmd(server.java_executable, pid, "Thread.print")
    )
    task_directory = Path(f"/proc/{pid}/task")
    process_task_count = (
        sum(1 for _entry in task_directory.iterdir()) if task_directory.is_dir() else 0
    )
    return {
        "label": label,
        "elapsed_seconds": round(elapsed_seconds, 3),
        "heap_total_bytes": heap_total,
        "heap_used_bytes": heap_used,
        "jvm_thread_count": thread_count,
        "mcav_thread_count": mcav_thread_count,
        "mcav_non_daemon_thread_count": mcav_non_daemon,
        "process_task_count": process_task_count,
    }


async def _get_metrics(client: VizClient) -> dict[str, Any]:
    response = await client.send({"type": "get_ws_metrics"})
    if response is None or response.get("type") != "ws_metrics":
        raise ScenarioAssertion("Renderer did not return WebSocket metrics")
    assert_queue_caps(response)
    return response


async def _wait_for_entity_count(
    client: VizClient, expected_count: int, timeout: float = 15.0
) -> bool:
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        status = await client.query_zone_status()
        zones = status.get("zones", {}) if status else {}
        main = zones.get("main", {}) if isinstance(zones, dict) else {}
        if main.get("entity_count") == expected_count:
            return True
        await asyncio.sleep(0.05)
    return False


def _sample_tps(server: PaperServer) -> tuple[float, float, float]:
    line = server.command(
        "tps",
        re.compile(r"TPS from last 1m, 5m, 15m:"),
        timeout=15.0,
    )
    return parse_tps_line(line)


async def _reconnect(client: VizClient) -> None:
    await client.disconnect()
    await asyncio.sleep(0.1)
    if not await client.connect() or not await client.ping():
        raise ScenarioAssertion("Authenticated reconnect failed during soak")


async def _run_load(
    arguments: PerformanceArguments,
    server: PaperServer,
    secret: str,
    state: dict[str, Any],
) -> None:
    client = VizClient(
        host="127.0.0.1",
        port=8765,
        connect_timeout=10.0,
        auth_token=secret,
        enable_heartbeat=True,
    )
    if not await client.connect() or not await client.ping():
        raise ScenarioAssertion("Performance client authentication failed")

    try:
        baseline_metrics = await _get_metrics(client)
        state["baseline_queue_dropped"] = int(baseline_metrics["queueDropped"])
        state["max_parsed_queue_depth"] = int(baseline_metrics["parsedQueueDepth"])
        state["max_raw_queue_depth"] = int(baseline_metrics["rawQueueDepth"])
        state["max_main_thread_p95_ms"] = float(baseline_metrics["mainThreadUpdateP95Ms"])

        if not await client.init_pool(
            "main", count=REQUIRED_ENTITY_COUNT, material="GLOWSTONE"
        ) or not await _wait_for_entity_count(client, REQUIRED_ENTITY_COUNT):
            raise ScenarioAssertion("Performance entity pool did not reach 256")
        state["entity_count_stable"] = True

        entities = _entity_updates(REQUIRED_ENTITY_COUNT)
        start = time.monotonic()
        state["load_start_monotonic"] = start
        next_frame = start
        next_tps = start + TPS_INTERVAL_SECONDS
        next_resource = start + RESOURCE_INTERVAL_SECONDS
        reconnect_interval = arguments.reconnect_interval_seconds
        next_reconnect = start + reconnect_interval if reconnect_interval else math.inf
        frame_index = 0
        latest_metrics = baseline_metrics

        while time.monotonic() - start < arguments.duration_seconds:
            now = time.monotonic()
            if now < next_frame:
                await asyncio.sleep(next_frame - now)

            before_batches = int(latest_metrics["queueBatches"])
            phase = frame_index % 20
            await client.batch_update_fast(
                "main",
                entities,
                audio={
                    "bands": [((phase + offset * 3) % 20) / 19 for offset in range(5)],
                    "amplitude": phase / 19,
                    "is_beat": phase == 0,
                    "beat_intensity": 1.0 if phase == 0 else 0.0,
                },
            )
            sent_at = time.monotonic()
            applied_deadline = sent_at + APPLIED_FRAME_TIMEOUT_SECONDS
            while True:
                latest_metrics = await _get_metrics(client)
                state["max_parsed_queue_depth"] = max(
                    state["max_parsed_queue_depth"],
                    int(latest_metrics["parsedQueueDepth"]),
                )
                state["max_raw_queue_depth"] = max(
                    state["max_raw_queue_depth"],
                    int(latest_metrics["rawQueueDepth"]),
                )
                state["max_main_thread_p95_ms"] = max(
                    state["max_main_thread_p95_ms"],
                    float(latest_metrics["mainThreadUpdateP95Ms"]),
                )
                if int(latest_metrics["queueBatches"]) > before_batches:
                    state["applied_latency_ms"].append((time.monotonic() - sent_at) * 1000)
                    break
                if time.monotonic() >= applied_deadline:
                    raise ScenarioAssertion(
                        "Applied-frame counter did not advance within "
                        f"{APPLIED_FRAME_TIMEOUT_SECONDS:g}s"
                    )
                await asyncio.sleep(0.001)

            frame_index += 1
            next_frame += FRAME_INTERVAL_SECONDS
            if next_frame < time.monotonic():
                next_frame = time.monotonic()

            now = time.monotonic()
            if now >= next_tps:
                state["tps_samples"].append(_sample_tps(server))
                next_tps += TPS_INTERVAL_SECONDS
                if next_tps < now:
                    next_tps = now + TPS_INTERVAL_SECONDS

            if now >= next_resource:
                snapshot = _resource_snapshot(server, "hourly", now - start)
                snapshot["delta_from_baseline"] = resource_delta(
                    state["resource_snapshots"][0], snapshot
                )
                state["resource_snapshots"].append(snapshot)
                next_resource += RESOURCE_INTERVAL_SECONDS

            if now >= next_reconnect:
                await _reconnect(client)
                state["reconnect_count"] += 1
                if not await _wait_for_entity_count(client, REQUIRED_ENTITY_COUNT):
                    state["entity_count_stable"] = False
                    raise ScenarioAssertion("Entity pool changed across reconnect")
                latest_metrics = await _get_metrics(client)
                next_reconnect += reconnect_interval

        state["elapsed_seconds"] = time.monotonic() - start
        state["final_queue_dropped"] = int(latest_metrics["queueDropped"])

        if not await client.cleanup_zone("main"):
            raise ScenarioAssertion("Explicit performance cleanup failed")
        state["cleanup_entity_count_zero"] = await _wait_for_entity_count(client, 0)
        cleanup_metrics = await _get_metrics(client)
        state["cleanup_queues_zero"] = (
            int(cleanup_metrics["parsedQueueDepth"]) == 0
            and int(cleanup_metrics["rawQueueDepth"]) == 0
        )
        cleanup_snapshot = _resource_snapshot(server, "after_cleanup", state["elapsed_seconds"])
        cleanup_snapshot["delta_from_baseline"] = resource_delta(
            state["resource_snapshots"][0], cleanup_snapshot
        )
        state["resource_snapshots"].append(cleanup_snapshot)
    finally:
        await client.disconnect()


def _initial_state() -> dict[str, Any]:
    return {
        "applied_latency_ms": [],
        "tps_samples": [],
        "resource_snapshots": [],
        "elapsed_seconds": 0.0,
        "max_main_thread_p95_ms": 0.0,
        "max_parsed_queue_depth": 0,
        "max_raw_queue_depth": 0,
        "baseline_queue_dropped": 0,
        "final_queue_dropped": 0,
        "entity_count_stable": False,
        "cleanup_entity_count_zero": False,
        "cleanup_queues_zero": False,
        "reconnect_count": 0,
        "process_stopped": False,
        "failure_type": None,
    }


def _acceptance_checks(
    arguments: PerformanceArguments, state: Mapping[str, Any]
) -> dict[str, bool]:
    latencies = state["applied_latency_ms"]
    tps_samples = state["tps_samples"]
    applied_p95 = percentile(latencies, 95) if latencies else math.inf
    minimum_tps = minimum_one_minute_tps(tps_samples) if tps_samples else 0.0
    return {
        "applied_frame_p95": applied_p95 <= MAX_APPLIED_P95_MS,
        "cleanup_entities": state["cleanup_entity_count_zero"] is True,
        "cleanup_queues": state["cleanup_queues_zero"] is True,
        "duration": float(state["elapsed_seconds"]) >= arguments.duration_seconds,
        "entity_count": state["entity_count_stable"] is True,
        "main_thread_p95": float(state["max_main_thread_p95_ms"]) <= MAX_MAIN_THREAD_P95_MS,
        "minimum_samples": len(latencies) >= arguments.minimum_samples,
        "no_queue_drops": int(state["final_queue_dropped"]) == int(state["baseline_queue_dropped"]),
        "process_stopped": state["process_stopped"] is True,
        "queue_caps": int(state["max_parsed_queue_depth"]) <= PARSED_QUEUE_CAP
        and int(state["max_raw_queue_depth"]) <= RAW_QUEUE_CAP,
        "tps": minimum_tps >= MIN_TPS,
    }


def _build_report(
    arguments: PerformanceArguments,
    manifest: PaperManifest,
    state: Mapping[str, Any],
    start_utc: str,
    end_utc: str,
) -> dict[str, Any]:
    checks = _acceptance_checks(arguments, state)
    failed_checks = sorted(check for check, passed in checks.items() if not passed)
    latencies = state["applied_latency_ms"]
    tps_samples = state["tps_samples"]
    return {
        "artifact": {
            "file": arguments.plugin.name,
            "sha256": calculate_sha256(arguments.plugin),
        },
        "checks": checks,
        "elapsed_seconds": round(float(state["elapsed_seconds"]), 3),
        "end_utc": end_utc,
        "failed_checks": failed_checks,
        "failure_type": state["failure_type"],
        "metrics": {
            "applied_latency_ms": {
                "count": len(latencies),
                "p50": round(percentile(latencies, 50), 3) if latencies else None,
                "p95": round(percentile(latencies, 95), 3) if latencies else None,
                "p99": round(percentile(latencies, 99), 3) if latencies else None,
                "max": round(max(latencies), 3) if latencies else None,
            },
            "main_thread_update_p95_ms_max": round(float(state["max_main_thread_p95_ms"]), 3),
            "minimum_tps": (round(minimum_one_minute_tps(tps_samples), 3) if tps_samples else None),
            "queue_dropped_delta": int(state["final_queue_dropped"])
            - int(state["baseline_queue_dropped"]),
            "queue_depth_max": {
                "parsed": int(state["max_parsed_queue_depth"]),
                "raw": int(state["max_raw_queue_depth"]),
            },
            "reconnect_count": int(state["reconnect_count"]),
            "tps_samples": [list(sample) for sample in tps_samples],
        },
        "paper": {
            "build": manifest.build,
            "file": manifest.file,
            "minecraft_version": manifest.minecraft_version,
            "sha256": manifest.sha256,
        },
        "release_soak_eligible": is_exact_release_soak(arguments.duration_seconds)
        and float(state["elapsed_seconds"]) >= REQUIRED_SOAK_SECONDS
        and not failed_checks,
        "requested": {
            "duration_seconds": arguments.duration_seconds,
            "minimum_samples": arguments.minimum_samples,
            "reconnect_interval_seconds": arguments.reconnect_interval_seconds,
        },
        "resource_snapshots": state["resource_snapshots"],
        "start_utc": start_utc,
        "status": "pass" if not failed_checks else "fail",
        "thresholds": {
            "max_applied_p95_ms": MAX_APPLIED_P95_MS,
            "max_main_thread_p95_ms": MAX_MAIN_THREAD_P95_MS,
            "min_tps": MIN_TPS,
            "parsed_queue_cap": PARSED_QUEUE_CAP,
            "raw_queue_cap": RAW_QUEUE_CAP,
            "required_entity_count": REQUIRED_ENTITY_COUNT,
            "required_soak_seconds": REQUIRED_SOAK_SECONDS,
        },
    }


def _write_report(path: Path, payload: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(f"{path.suffix}.part")
    try:
        temporary_path.write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def execute_cli(argv: Sequence[str] | None = None) -> int:
    """Run one sustained measurement and return zero only for a passing report."""

    arguments = _parse_arguments(argv)
    if not arguments.plugin.is_file():
        raise FileNotFoundError(f"Plugin artifact not found: {arguments.plugin}")
    manifest = PaperManifest.from_path(arguments.manifest)
    paper_jar = download_paper(manifest, arguments.paper_cache)
    e2e_arguments = _e2e_arguments(arguments)
    state = _initial_state()
    start_utc = datetime.now(UTC).isoformat()
    server: PaperServer | None = None
    original_pid: int | None = None
    try:
        server = _new_server(e2e_arguments, paper_jar, server_port=25580)
        _write_primary_zone(server)
        server.start()
        server.wait_for_log("WebSocket server started on port 8765", timeout=30.0)
        server.command(
            "forceload add 0 0",
            re.compile(r"(?:force loaded|already marked)", re.IGNORECASE),
            timeout=15.0,
        )
        secret = _read_generated_secret(server)
        _register_secret_after_leak_check(server, secret)
        original_pid = server.pid
        if original_pid is None:
            raise ScenarioAssertion("Paper PID was unavailable")
        state["resource_snapshots"].append(_resource_snapshot(server, "baseline", 0.0))
        asyncio.run(_run_load(arguments, server, secret, state))
    except Exception as error:
        state["failure_type"] = type(error).__name__
        detail = f": {error}" if isinstance(error, ScenarioAssertion) else ""
        print(f"Performance measurement failed safely ({type(error).__name__}){detail}")
    finally:
        if server is not None:
            server.close()
        if original_pid is not None:
            state["process_stopped"] = not Path(f"/proc/{original_pid}").exists()
    end_utc = datetime.now(UTC).isoformat()
    payload = _build_report(arguments, manifest, state, start_utc, end_utc)
    _write_report(arguments.report, payload)
    if payload["status"] == "pass":
        print("PASS Paper 26.2 performance checks")
        return 0
    print(f"FAIL Paper 26.2 performance checks: {', '.join(payload['failed_checks'])}")
    return 1


if __name__ == "__main__":
    raise SystemExit(execute_cli())
