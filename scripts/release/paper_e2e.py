"""Run and report the mandatory Paper 26.2 release lifecycle checks."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import secrets
import shutil
import subprocess  # nosec B404
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import websockets
from websockets.exceptions import ConnectionClosed

from scripts.release.paper_harness import (
    PaperManifest,
    PaperServer,
    calculate_sha256,
    download_paper,
)
from vj_server.viz_client import VizClient

REQUIRED_CHECKS = {
    "plugin_loaded",
    "secret_generated",
    "bad_secret_rejected",
    "authenticated",
    "zone_loaded",
    "pool_initialized",
    "display_entities_applied",
    "malformed_frame_rejected",
    "oversize_frame_rejected",
    "reconnected",
    "disconnect_cleanup",
    "world_unload_cleanup",
    "restart_has_no_orphans",
    "port_conflict_safe",
    "clean_machine_install",
    "uninstall_cleanup",
    "optional_integrations_absent_safe",
}

DEFAULT_MANIFEST = Path("scripts/release/paper_26_2_manifest.json")
DEFAULT_PAPER_CACHE = Path("build/cache/paper")
DEFAULT_PROBE = Path("scripts/release/probe/target/mcav-integration-probe-1.0.jar")
DEFAULT_JAVA_CONTAINER_IMAGE = (
    "eclipse-temurin@sha256:c42fecf62f32725c65cfea284c012526d6fb31cc78123c740ebdc1cfd2dced12"
)


@dataclass(frozen=True)
class E2EArguments:
    """Resolved command-line inputs for one disposable verification run."""

    plugin: Path
    report: Path
    manifest: Path
    paper_cache: Path
    probe: Path
    java: str
    java_container_image: str


ScenarioRunner = Callable[[E2EArguments], Mapping[str, bool]]


class ScenarioAssertion(RuntimeError):
    """A release invariant was not demonstrated by the disposable scenario."""


def _parse_arguments(argv: Sequence[str] | None) -> E2EArguments:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--paper-cache", type=Path, default=DEFAULT_PAPER_CACHE)
    parser.add_argument("--probe", type=Path, default=DEFAULT_PROBE)
    parser.add_argument("--java", default="java")
    parser.add_argument(
        "--java-container-image",
        default=DEFAULT_JAVA_CONTAINER_IMAGE,
        help="Pinned Java 25 image used when the requested Java executable is unavailable",
    )
    raw = parser.parse_args(argv)
    return E2EArguments(
        plugin=raw.plugin.resolve(),
        report=raw.report.resolve(),
        manifest=raw.manifest.resolve(),
        paper_cache=raw.paper_cache.resolve(),
        probe=raw.probe.resolve(),
        java=str(raw.java),
        java_container_image=str(raw.java_container_image),
    )


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ScenarioAssertion(message)


def _java_25_is_available(java_executable: str) -> bool:
    resolved_java = shutil.which(java_executable)
    if resolved_java is None:
        return False
    try:
        completed = subprocess.run(  # nosec B603
            [resolved_java, "-version"],
            capture_output=True,
            check=False,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    version_output = f"{completed.stdout}\n{completed.stderr}"
    return (
        completed.returncode == 0
        and re.search(r'(?:openjdk|java) version "25(?:[.\"]|$)', version_output) is not None
    )


def _materialize_java_25(image: str, cache_root: Path) -> Path:
    docker = shutil.which("docker")
    if docker is None:
        raise ScenarioAssertion("Java 25 and Docker are both unavailable")
    if "@sha256:" not in image:
        raise ScenarioAssertion("Java container image must be pinned by digest")

    cache_root.mkdir(parents=True, exist_ok=True)
    target = cache_root / "temurin-25-runtime"
    target_java = target / "bin" / "java"
    completion_marker = target / ".complete"
    if completion_marker.is_file() and _java_25_is_available(str(target_java)):
        return target_java
    if target.exists():
        raise ScenarioAssertion("Cached Java 25 runtime exists but failed validation")

    target.mkdir()
    user_arguments: list[str] = []
    if hasattr(os, "getuid") and hasattr(os, "getgid"):
        user_arguments = ["--user", f"{os.getuid()}:{os.getgid()}"]
    try:
        completed = subprocess.run(  # nosec B603
            [
                docker,
                "run",
                "--rm",
                *user_arguments,
                "-v",
                f"{target}:/output",
                image,
                "cp",
                "-R",
                "/opt/java/openjdk/.",
                "/output/",
            ],
            capture_output=True,
            check=False,
            text=True,
            timeout=180,
        )
        if completed.returncode != 0:
            raise ScenarioAssertion(
                f"Pinned Java 25 extraction failed with code {completed.returncode}"
            )
        if not _java_25_is_available(str(target_java)):
            raise ScenarioAssertion("Extracted Java runtime failed Java 25 validation")
        completion_marker.write_text("pinned-image-java-25\n", encoding="utf-8")
        return target_java
    except Exception:
        shutil.rmtree(target)
        raise


def _resolve_java_25(arguments: E2EArguments) -> str:
    if _java_25_is_available(arguments.java):
        return arguments.java
    materialized_java = _materialize_java_25(
        arguments.java_container_image,
        arguments.paper_cache.parent / "java",
    )
    if not _java_25_is_available(str(materialized_java)):
        raise ScenarioAssertion("No validated Java 25 runtime is available")
    return str(materialized_java)


def _new_server(
    arguments: E2EArguments,
    paper_jar: Path,
    *,
    additional_plugins: Sequence[Path] = (),
    server_port: int = 25575,
) -> PaperServer:
    java_executable = _resolve_java_25(arguments)
    return PaperServer(
        java_executable=java_executable,
        paper_jar=paper_jar,
        plugin_jar=arguments.plugin,
        additional_plugins=additional_plugins,
        startup_timeout=240.0,
        stop_timeout=45.0,
        server_properties={"server-port": server_port},
    )


def _write_primary_zone(server: PaperServer) -> None:
    plugin_data = server.plugins_dir / "AudioViz"
    plugin_data.mkdir(parents=True, exist_ok=True)
    (plugin_data / "zones.yml").write_text(
        """zones:
  main:
    name: main
    id: 4e0303db-30e2-4ce5-bd20-65ccdc2b6842
    world: world
    origin:
      x: 0.0
      y: 80.0
      z: 0.0
    size:
      x: 16.0
      y: 12.0
      z: 8.0
    rotation: 0.0
""",
        encoding="utf-8",
    )


def _write_port_conflict_config(server: PaperServer, secret: str, port: int) -> None:
    plugin_data = server.plugins_dir / "AudioViz"
    plugin_data.mkdir(parents=True, exist_ok=True)
    (plugin_data / "config.yml").write_text(
        f'''websocket:
  address: "127.0.0.1"
  port: {port}
ws-secret: "{secret}"
connection:
  disconnect_grace_ticks: 100
performance:
  max_entities_per_zone: 256
''',
        encoding="utf-8",
    )


def _read_generated_secret(server: PaperServer) -> str:
    config_path = server.plugins_dir / "AudioViz" / "config.yml"
    deadline = time.monotonic() + 15.0
    secret_pattern = re.compile(r'^ws-secret:\s*["\']?([A-Za-z0-9_-]{43})["\']?\s*$', re.MULTILINE)
    while time.monotonic() < deadline:
        if config_path.is_file():
            match = secret_pattern.search(config_path.read_text(encoding="utf-8"))
            if match is not None:
                return match.group(1)
        time.sleep(0.05)
    raise ScenarioAssertion("Generated WebSocket secret was not persisted")


def _register_secret_after_leak_check(server: PaperServer, secret: str) -> None:
    _require(secret not in "\n".join(server.logs), "WebSocket secret appeared in Paper logs")
    server.register_redaction(secret)


def _entity_updates(count: int) -> list[dict[str, Any]]:
    updates: list[dict[str, Any]] = []
    for index in range(count):
        updates.append(
            {
                "id": f"block_{index}",
                "x": (index % 16) / 15,
                "y": ((index // 16) % 16) / 15,
                "z": 0.5,
                "scale": 0.2 + ((index % 5) * 0.1),
                "rotation": float((index * 7) % 360),
                "visible": True,
                "band": index % 5,
            }
        )
    return updates


async def _wait_for_entity_count(
    client: VizClient,
    zone_name: str,
    expected_count: int,
    timeout: float = 15.0,
) -> bool:
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        status = await client.query_zone_status()
        zones = status.get("zones", {}) if status else {}
        zone_status = zones.get(zone_name, {}) if isinstance(zones, dict) else {}
        if zone_status.get("entity_count") == expected_count:
            return True
        await asyncio.sleep(0.1)
    return False


async def _open_authenticated_socket(secret: str):
    websocket = await websockets.connect("ws://127.0.0.1:8765", open_timeout=10.0, max_size=None)
    welcome = json.loads(await asyncio.wait_for(websocket.recv(), timeout=10.0))
    _require(
        welcome.get("type") == "connected" and welcome.get("auth_required") is True,
        "Raw WebSocket welcome did not require authentication",
    )
    await websocket.send(json.dumps({"type": "auth", "token": secret}))
    authenticated = json.loads(await asyncio.wait_for(websocket.recv(), timeout=10.0))
    _require(authenticated.get("type") == "auth_ok", "Raw WebSocket auth failed")
    return websocket


async def _exercise_protocol_rejections(server: PaperServer, secret: str) -> tuple[bool, bool]:
    malformed_rejected = False
    malformed_socket = await _open_authenticated_socket(secret)
    try:
        await malformed_socket.send("{not-json")
        response = json.loads(await asyncio.wait_for(malformed_socket.recv(), timeout=10.0))
        malformed_rejected = (
            response.get("type") == "error" and response.get("code") == "invalid_message"
        )
    finally:
        await malformed_socket.close()

    oversized_rejected = False
    oversized_socket = await _open_authenticated_socket(secret)
    try:
        await oversized_socket.send("x" * 262_145)
        try:
            await asyncio.wait_for(oversized_socket.recv(), timeout=2.0)
        except ConnectionClosed as closed:
            oversized_rejected = closed.code == 1009
        except TimeoutError:
            await oversized_socket.send(json.dumps({"type": "ping"}))
            response = json.loads(await asyncio.wait_for(oversized_socket.recv(), timeout=10.0))
            oversized_rejected = response.get("type") == "pong"
            if oversized_rejected:
                server.wait_for_log("Oversized message rejected", timeout=10.0)
    finally:
        await oversized_socket.close()

    return malformed_rejected, oversized_rejected


async def _exercise_primary_vj(server: PaperServer, secret: str, checks: dict[str, bool]) -> None:
    wrong_secret = secrets.token_urlsafe(32)
    while wrong_secret == secret:
        wrong_secret = secrets.token_urlsafe(32)
    bad_client = VizClient(
        host="127.0.0.1", port=8765, connect_timeout=10.0, auth_token=wrong_secret
    )
    checks["bad_secret_rejected"] = not await bad_client.connect()
    await bad_client.disconnect()
    _require(checks["bad_secret_rejected"], "Incorrect secret was accepted")

    client = VizClient(host="127.0.0.1", port=8765, connect_timeout=10.0, auth_token=secret)
    try:
        valid_client_connected = await client.connect()
        if not valid_client_connected:
            raw_socket = await _open_authenticated_socket(secret)
            await raw_socket.close()
            raise ScenarioAssertion("VizClient valid auth failed while raw valid auth succeeded")
        checks["authenticated"] = await client.ping()
        _require(checks["authenticated"], "VizClient authentication or ping failed")

        zones = await client.get_zones()
        checks["zone_loaded"] = any(zone.get("name") == "main" for zone in zones)
        _require(checks["zone_loaded"], "Seeded main zone was not returned")

        checks["pool_initialized"] = await client.init_pool(
            "main", count=256, material="GLOWSTONE"
        ) and await _wait_for_entity_count(client, "main", 256)
        _require(checks["pool_initialized"], "256-entity pool was not initialized")

        await client.batch_update_fast(
            "main",
            _entity_updates(256),
            audio={
                "bands": [0.9, 0.7, 0.5, 0.3, 0.1],
                "amplitude": 0.8,
                "is_beat": True,
                "beat_intensity": 0.95,
            },
        )
        pool_count_stable = await _wait_for_entity_count(client, "main", 256)
        probe_result = server.command(
            "mcavprobe verify-main-batch",
            re.compile(r"MCAV_PROBE_MAIN_BATCH_(?:APPLIED|FAILED[^\r\n]*)"),
            timeout=15.0,
        )
        checks["display_entities_applied"] = (
            pool_count_stable and "MCAV_PROBE_MAIN_BATCH_APPLIED" in probe_result
        )
        _require(
            checks["display_entities_applied"],
            f"Five-band update was not applied to all display entities ({probe_result})",
        )

        malformed, oversized = await _exercise_protocol_rejections(server, secret)
        checks["malformed_frame_rejected"] = malformed
        checks["oversize_frame_rejected"] = oversized
        _require(malformed, "Malformed frame was not rejected safely")
        _require(oversized, "Oversized frame was not rejected safely")
    finally:
        await client.disconnect()


async def _exercise_reconnect(secret: str, checks: dict[str, bool]) -> None:
    client = VizClient(host="127.0.0.1", port=8765, connect_timeout=10.0, auth_token=secret)
    try:
        checks["reconnected"] = await client.connect() and await client.ping()
        _require(checks["reconnected"], "Authenticated reconnect failed")
        _require(
            await client.init_pool("main", count=256, material="GLOWSTONE"),
            "Pool reinitialization after reconnect failed",
        )
        _require(
            await _wait_for_entity_count(client, "main", 256),
            "Reconnected pool did not reach 256 entities",
        )
    finally:
        await client.disconnect()


def _run_clean_install(arguments: E2EArguments, paper_jar: Path, checks: dict[str, bool]) -> None:
    with _new_server(arguments, paper_jar, server_port=25576) as server:
        server.wait_for_log("AudioViz plugin enabled!", timeout=30.0)
        server.wait_for_log("WebSocket server started on port 8765", timeout=30.0)
        secret = _read_generated_secret(server)
        _register_secret_after_leak_check(server, secret)
        checks["clean_machine_install"] = True

        geyser_absent = any(
            "Geyser not detected - Bedrock support inactive" in line for line in server.logs
        )
        voice_absent = any(
            "Simple Voice Chat not installed - audio streaming disabled" in line
            for line in server.logs
        )
        checks["optional_integrations_absent_safe"] = geyser_absent and voice_absent
        _require(
            checks["optional_integrations_absent_safe"],
            "Optional integration absence was not handled cleanly",
        )


def _run_primary_lifecycle(
    arguments: E2EArguments, paper_jar: Path, checks: dict[str, bool]
) -> None:
    server = _new_server(
        arguments,
        paper_jar,
        additional_plugins=[arguments.probe],
        server_port=25577,
    )
    _write_primary_zone(server)
    with server:
        server.wait_for_log("AudioViz plugin enabled!", timeout=30.0)
        server.wait_for_log("WebSocket server started on port 8765", timeout=30.0)
        checks["plugin_loaded"] = True
        server.command(
            "forceload add 0 0",
            re.compile(r"(?:force loaded|already marked)", re.IGNORECASE),
            timeout=15.0,
        )
        server.wait_for_log("Generated a WebSocket pairing secret", timeout=30.0)
        secret = _read_generated_secret(server)
        _register_secret_after_leak_check(server, secret)
        checks["secret_generated"] = True

        asyncio.run(_exercise_primary_vj(server, secret, checks))
        time.sleep(6.5)
        server.command(
            "audioviz status",
            re.compile(r"Total Entities:.*0"),
            timeout=10.0,
        )
        checks["disconnect_cleanup"] = True

        asyncio.run(_exercise_reconnect(secret, checks))
        server.command("mcavprobe unload-cycle", "MCAV_PROBE_WORLD_UNLOAD_CLEAN", timeout=30.0)
        checks["world_unload_cleanup"] = True

        server.restart()
        server.wait_for_log("WebSocket server started on port 8765", timeout=30.0)
        server.command(
            "execute unless entity @e[type=minecraft:block_display] "
            "run say MCAV_PROBE_ENTITY_ABSENT",
            "MCAV_PROBE_ENTITY_ABSENT",
            timeout=15.0,
        )
        checks["restart_has_no_orphans"] = True

        server.stop()
        installed_plugin = server.plugins_dir / arguments.plugin.name
        installed_probe = server.plugins_dir / arguments.probe.name
        installed_plugin.unlink()
        installed_probe.unlink()
        log_start = len(server.logs)
        server.start()
        uninstall_logs = server.logs[log_start:]
        plugin_absent = not any("AudioViz plugin enabled!" in line for line in uninstall_logs)
        server.command(
            "execute unless entity @e[type=minecraft:block_display] "
            "run say MCAV_PROBE_UNINSTALL_CLEAN",
            "MCAV_PROBE_UNINSTALL_CLEAN",
            timeout=15.0,
        )
        checks["uninstall_cleanup"] = (
            plugin_absent and not installed_plugin.exists() and not installed_probe.exists()
        )
        _require(checks["uninstall_cleanup"], "Disposable uninstall left plugin state active")


def _run_port_conflict(arguments: E2EArguments, paper_jar: Path, checks: dict[str, bool]) -> None:
    conflict_port = 25578
    conflict_secret = secrets.token_urlsafe(32)
    server = _new_server(arguments, paper_jar, server_port=conflict_port)
    _write_port_conflict_config(server, conflict_secret, conflict_port)
    with server:
        server.wait_for_log("AudioViz plugin enabled!", timeout=30.0)
        server.wait_for_log("Failed to start WebSocket server after 5 attempts", timeout=30.0)
        _require(
            conflict_secret not in "\n".join(server.logs),
            "Configured secret appeared in port-conflict logs",
        )
        server.register_redaction(conflict_secret)
        server.command("audioviz status", re.compile(r"WebSocket:.*Not running"), timeout=10.0)
        server.command(
            "execute unless entity @e[type=minecraft:block_display] "
            "run say MCAV_PROBE_PORT_CONFLICT_SAFE",
            "MCAV_PROBE_PORT_CONFLICT_SAFE",
            timeout=15.0,
        )
        checks["port_conflict_safe"] = True


def run_real_scenario(arguments: E2EArguments) -> Mapping[str, bool]:
    """Execute the full disposable-server scenario without exporting secrets."""

    if not arguments.manifest.is_file():
        raise FileNotFoundError(f"Paper manifest not found: {arguments.manifest}")
    if not arguments.probe.is_file():
        raise FileNotFoundError(f"Integration probe not found: {arguments.probe}")

    manifest = PaperManifest.from_path(arguments.manifest)
    paper_jar = download_paper(manifest, arguments.paper_cache)
    checks = {check: False for check in REQUIRED_CHECKS}
    phases = (
        ("clean install", _run_clean_install),
        ("primary lifecycle", _run_primary_lifecycle),
        ("port conflict", _run_port_conflict),
    )
    for phase_name, phase_runner in phases:
        try:
            phase_runner(arguments, paper_jar, checks)
        except Exception as error:
            detail = f": {error}" if isinstance(error, ScenarioAssertion) else ""
            print(f"Scenario phase failed safely: {phase_name} ({type(error).__name__}){detail}")
    return checks


def _write_report(arguments: E2EArguments, checks: Mapping[str, bool]) -> None:
    normalized_checks = {check: checks.get(check) is True for check in sorted(REQUIRED_CHECKS)}
    failed_checks = [check for check, passed in normalized_checks.items() if not passed]
    payload = {
        "artifact": {
            "file": arguments.plugin.name,
            "sha256": calculate_sha256(arguments.plugin),
        },
        "checks": normalized_checks,
        "failed_checks": failed_checks,
        "status": "pass" if not failed_checks else "fail",
    }
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    temporary_report = arguments.report.with_suffix(f"{arguments.report.suffix}.part")
    try:
        temporary_report.write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary_report, arguments.report)
    finally:
        temporary_report.unlink(missing_ok=True)


def execute_cli(
    argv: Sequence[str] | None = None,
    *,
    scenario_runner: ScenarioRunner = run_real_scenario,
) -> int:
    """Execute one scenario and return a shell-safe pass/fail status."""

    arguments = _parse_arguments(argv)
    if not arguments.plugin.is_file():
        raise FileNotFoundError(f"Plugin artifact not found: {arguments.plugin}")
    scenario_checks = scenario_runner(arguments)
    _write_report(arguments, scenario_checks)
    failed_checks = sorted(
        check for check in REQUIRED_CHECKS if scenario_checks.get(check) is not True
    )
    if failed_checks:
        print(f"FAIL Paper 26.2 lifecycle checks: {', '.join(failed_checks)}")
        return 1
    print("PASS Paper 26.2 lifecycle checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(execute_cli())
