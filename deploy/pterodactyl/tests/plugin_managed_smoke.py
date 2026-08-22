#!/usr/bin/env python3
"""Rehearse a plugin-managed Pterodactyl release on a disposable Paper server."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import stat
import subprocess  # nosec B404
import sys
import tempfile
import threading
import time
import zipfile
from collections.abc import Callable, Sequence
from pathlib import Path, PurePosixPath
from typing import Any, NamedTuple

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from scripts.release.paper_harness import (  # noqa: E402
    PaperManifest,
    calculate_sha256,
    download_paper,
)

DEFAULT_MANIFEST = REPOSITORY_ROOT / "scripts" / "release" / "paper_26_1_2_manifest.json"
DEFAULT_PAPER_CACHE = REPOSITORY_ROOT / "build" / "cache" / "paper"
DEFAULT_JAVA_CACHE = Path(tempfile.gettempdir()) / "mcav-java-cache"
DEFAULT_HTTP_PORT = 25927
DEFAULT_DJ_PORT = 25808
DEFAULT_MINECRAFT_PORT = 25586
DEFAULT_JAVA_CONTAINER_IMAGE = (
    "eclipse-temurin@sha256:c42fecf62f32725c65cfea284c012526d6fb31cc78123c740ebdc1cfd2dced12"
)
ALLOWED_ARCHIVE_ROOTS = {"mcav-vj", "plugins"}

ListenerProbe = Callable[[str, int], bool]
ProcessProbe = Callable[[int], bool]


class RehearsalError(RuntimeError):
    """A required release behavior was not demonstrated."""


class ReleaseEnvironment(NamedTuple):
    """Required public endpoint values loaded from mcav.env."""

    public_host: str
    http_port: int
    dj_port: int


def paper_command(java_executable: str) -> list[str]:
    """Return the normal Paper command; the release must not add a wrapper."""

    return [
        java_executable,
        "-Xms1G",
        "-Xmx2G",
        "-jar",
        "paper.jar",
        "--nogui",
    ]


def _safe_archive_path(name: str) -> PurePosixPath:
    normalized = PurePosixPath(name.replace("\\", "/"))
    if normalized.is_absolute() or not normalized.parts:
        raise RehearsalError(f"Unsafe archive path: {name!r}")
    if any(part in {"", ".", ".."} for part in normalized.parts):
        raise RehearsalError(f"Unsafe archive path: {name!r}")
    if normalized.parts[0] not in ALLOWED_ARCHIVE_ROOTS:
        raise RehearsalError(f"Unexpected archive root: {name!r}")
    if normalized.parts[0] == "plugins" and normalized.parts != (
        "plugins",
        "AudioViz.jar",
    ):
        raise RehearsalError(f"Unexpected plugins payload: {name!r}")
    return normalized


def extract_release(archive: Path, destination: Path) -> None:
    """Extract the verified two-root release while preserving executable modes."""

    destination.mkdir(parents=True, exist_ok=True)
    destination_root = destination.resolve()
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            relative = _safe_archive_path(member.filename)
            unix_mode = member.external_attr >> 16
            if stat.S_ISLNK(unix_mode):
                raise RehearsalError(f"Archive links are not allowed: {member.filename}")
            target = destination.joinpath(*relative.parts)
            resolved_target = target.resolve()
            if (
                destination_root not in resolved_target.parents
                and resolved_target != destination_root
            ):
                raise RehearsalError(f"Archive path escaped destination: {member.filename}")
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
            permissions = unix_mode & 0o777
            if permissions:
                target.chmod(permissions)


def load_required_environment(env_path: Path) -> ReleaseEnvironment:
    """Load and validate the three deployment values the plugin sidecar requires."""

    values: dict[str, str] = {}
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        values[name.strip()] = value.strip().strip('"').strip("'")

    public_host = values.get("MCAV_PUBLIC_HOST", "")
    if not public_host or public_host == "YOUR_PUBLIC_IP":
        raise RehearsalError("MCAV_PUBLIC_HOST must be set in mcav-vj/mcav.env")

    def required_port(name: str) -> int:
        try:
            port = int(values[name])
        except (KeyError, ValueError) as error:
            raise RehearsalError(f"{name} must be a valid port") from error
        if not 1 <= port <= 65535:
            raise RehearsalError(f"{name} must be between 1 and 65535")
        return port

    return ReleaseEnvironment(
        public_host=public_host,
        http_port=required_port("HTTP_PORT"),
        dj_port=required_port("VJ_SERVER_PORT"),
    )


def listener_open(host: str, port: int) -> bool:
    """Return whether a TCP listener accepts a loopback connection."""

    try:
        with socket.create_connection((host, port), timeout=0.25):
            return True
    except OSError:
        return False


def wait_for_listeners(
    host: str,
    ports: Sequence[int],
    *,
    timeout: float,
    interval: float = 0.1,
    probe: ListenerProbe = listener_open,
) -> bool:
    """Wait until every required TCP listener is ready."""

    deadline = time.monotonic() + timeout
    while True:
        states = [probe(host, port) for port in ports]
        if all(states):
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(interval)


def stop_process(process: Any, *, timeout: float) -> None:
    """Stop Paper with its console command and force it only after the bound."""

    if process.poll() is not None:
        return
    try:
        if process.stdin is not None:
            process.stdin.write("stop\n")
            process.stdin.flush()
        process.wait(timeout=timeout)
    except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
        process.kill()
        process.wait(timeout=max(timeout, 1.0))


def _process_exists(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def wait_for_cleanup(
    *,
    sidecar_pid: int,
    host: str,
    ports: Sequence[int],
    timeout: float,
    interval: float = 0.1,
    process_exists: ProcessProbe = _process_exists,
    listener_open: ListenerProbe = listener_open,
) -> bool:
    """Prove both the owned sidecar process and its listeners have stopped."""

    deadline = time.monotonic() + timeout
    while True:
        process_running = process_exists(sidecar_pid)
        listeners_running = [listener_open(host, port) for port in ports]
        if not process_running and not any(listeners_running):
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(interval)


def _write_mcav_environment(
    server_root: Path,
    *,
    public_host: str,
    http_port: int,
    dj_port: int,
) -> ReleaseEnvironment:
    env_path = server_root / "mcav-vj" / "mcav.env"
    env_path.write_text(
        "\n".join(
            [
                f"MCAV_PUBLIC_HOST={public_host}",
                f"HTTP_PORT={http_port}",
                f"VJ_SERVER_PORT={dj_port}",
                "UNIFIED_WEB=true",
                "METRICS_PORT=19001",
                "ENTITY_COUNT=160",
                "",
            ]
        ),
        encoding="utf-8",
    )
    return load_required_environment(env_path)


def _prepare_paper(server_root: Path, paper_jar: Path, minecraft_port: int) -> None:
    shutil.copy2(paper_jar, server_root / "paper.jar")
    (server_root / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_root / "server.properties").write_text(
        "\n".join(
            [
                "online-mode=false",
                "spawn-protection=0",
                "view-distance=2",
                "simulation-distance=2",
                f"server-port={minecraft_port}",
                "",
            ]
        ),
        encoding="utf-8",
    )


def _wait_for_log(
    logs: list[str],
    lock: threading.Condition,
    process: subprocess.Popen[str],
    pattern: re.Pattern[str],
    timeout: float,
) -> bool:
    deadline = time.monotonic() + timeout
    with lock:
        while True:
            if any(pattern.search(line) for line in logs):
                return True
            if process.poll() is not None:
                return False
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            lock.wait(timeout=min(remaining, 0.25))


def _read_output(
    process: subprocess.Popen[str],
    logs: list[str],
    lock: threading.Condition,
) -> None:
    if process.stdout is None:
        return
    for raw_line in process.stdout:
        with lock:
            logs.append(raw_line.rstrip("\r\n"))
            lock.notify_all()


def _find_sidecar_pid(server_root: Path, paper_pid: int) -> int | None:
    expected_root = str((server_root / "mcav-vj").resolve())
    proc_root = Path("/proc")
    if not proc_root.is_dir():
        return None
    for entry in proc_root.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            command = (entry / "cmdline").read_bytes().split(b"\0")
            status = (entry / "status").read_text(encoding="utf-8")
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
        parent_match = re.search(r"^PPid:\s+(\d+)$", status, re.MULTILINE)
        command_arguments = [os.fsdecode(argument) for argument in command if argument]
        if (
            parent_match is not None
            and int(parent_match.group(1)) == paper_pid
            and any(expected_root in argument for argument in command_arguments)
        ):
            return int(entry.name)
    return None


def _wait_for_sidecar_pid(
    server_root: Path,
    paper_pid: int,
    timeout: float,
) -> int | None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pid = _find_sidecar_pid(server_root, paper_pid)
        if pid is not None:
            return pid
        time.sleep(0.1)
    return None


def _resolve_java(java: str, image: str, cache: Path) -> str:
    if _java_25_is_available(java):
        return java
    materialized = _materialize_java_25(image, cache)
    if not _java_25_is_available(str(materialized)):
        raise RehearsalError("No validated Java 25 runtime is available")
    return str(materialized)


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
        raise RehearsalError("Java 25 and Docker are both unavailable")
    if "@sha256:" not in image:
        raise RehearsalError("Java container image must be pinned by digest")

    cache_root.mkdir(parents=True, exist_ok=True)
    target = cache_root / "temurin-25-runtime"
    target_java = target / "bin" / "java"
    completion_marker = target / ".complete"
    if completion_marker.is_file() and _java_25_is_available(str(target_java)):
        return target_java
    if target.exists():
        raise RehearsalError("Cached Java 25 runtime exists but failed validation")

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
            raise RehearsalError(
                f"Pinned Java 25 extraction failed with code {completed.returncode}"
            )
        if not _java_25_is_available(str(target_java)):
            raise RehearsalError("Extracted Java runtime failed Java 25 validation")
        completion_marker.write_text("pinned-image-java-25\n", encoding="utf-8")
        return target_java
    except Exception:
        shutil.rmtree(target)
        raise


def _write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run_rehearsal(arguments: argparse.Namespace) -> dict[str, Any]:
    """Execute the real server rehearsal and return its machine-readable report."""

    checks = {
        "archive_extracted": False,
        "normal_paper_command": False,
        "paper_ready": False,
        "plugin_enabled": False,
        "vj_listeners_ready": False,
        "sidecar_process_owned": False,
        "secret_absent_from_logs": False,
        "paper_stopped_bounded": False,
        "sidecar_and_listeners_cleaned": False,
    }
    logs: list[str] = []
    lock = threading.Condition()
    process: subprocess.Popen[str] | None = None
    reader: threading.Thread | None = None
    sidecar_pid: int | None = None
    error_message: str | None = None
    command: list[str] = []

    with tempfile.TemporaryDirectory(prefix="mcav-plugin-managed-") as temporary_directory:
        server_root = Path(temporary_directory)
        try:
            extract_release(arguments.archive, server_root)
            checks["archive_extracted"] = True
            environment = _write_mcav_environment(
                server_root,
                public_host=arguments.public_host,
                http_port=arguments.http_port,
                dj_port=arguments.dj_port,
            )
            ports = (environment.http_port, environment.dj_port)
            if any(listener_open("127.0.0.1", port) for port in ports):
                raise RehearsalError("A required rehearsal port is already in use")

            manifest = PaperManifest.from_path(arguments.paper_manifest)
            paper_jar = download_paper(manifest, arguments.paper_cache)
            java = _resolve_java(
                arguments.java,
                arguments.java_container_image,
                arguments.java_cache,
            )
            _prepare_paper(server_root, paper_jar, arguments.minecraft_port)
            command = paper_command(java)
            checks["normal_paper_command"] = command[-3:] == [
                "-jar",
                "paper.jar",
                "--nogui",
            ] and not any("start-mcav" in item for item in command)

            process = subprocess.Popen(  # nosec B603
                command,
                cwd=server_root,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
            reader = threading.Thread(
                target=_read_output,
                args=(process, logs, lock),
                name="mcav-plugin-managed-smoke-log",
                daemon=True,
            )
            reader.start()

            checks["paper_ready"] = _wait_for_log(
                logs,
                lock,
                process,
                re.compile(r'Done \([^)]+\)! For help, type "help"'),
                arguments.startup_timeout,
            )
            if not checks["paper_ready"]:
                raise RehearsalError("Paper did not reach ready state")
            checks["plugin_enabled"] = any("AudioViz plugin enabled!" in line for line in logs)
            if not checks["plugin_enabled"]:
                raise RehearsalError("AudioViz did not enable")

            checks["vj_listeners_ready"] = wait_for_listeners(
                "127.0.0.1",
                ports,
                timeout=arguments.startup_timeout,
            )
            if not checks["vj_listeners_ready"]:
                raise RehearsalError("Plugin-managed VJ listeners did not become ready")

            sidecar_pid = _wait_for_sidecar_pid(server_root, process.pid, 10.0)
            checks["sidecar_process_owned"] = sidecar_pid is not None
            if sidecar_pid is None:
                raise RehearsalError("Could not identify the plugin-owned VJ process")

            config_path = server_root / "plugins" / "AudioViz" / "config.yml"
            config_text = config_path.read_text(encoding="utf-8")
            secret_match = re.search(
                r'^ws-secret:\s*["\']?([A-Za-z0-9_-]{43})["\']?\s*$',
                config_text,
                re.MULTILINE,
            )
            if secret_match is None:
                raise RehearsalError("Plugin WebSocket secret was not persisted")
            secret = secret_match.group(1)
            checks["secret_absent_from_logs"] = secret not in "\n".join(logs)
            if not checks["secret_absent_from_logs"]:
                raise RehearsalError("Plugin WebSocket secret appeared in logs")
        except Exception as error:  # noqa: BLE001
            error_message = f"{type(error).__name__}: {error}"
        finally:
            if process is not None:
                stop_started = time.monotonic()
                stop_process(process, timeout=arguments.stop_timeout)
                checks["paper_stopped_bounded"] = (
                    time.monotonic() - stop_started <= arguments.stop_timeout + 2.0
                )
            if reader is not None:
                reader.join(timeout=2.0)
            if sidecar_pid is not None:
                checks["sidecar_and_listeners_cleaned"] = wait_for_cleanup(
                    sidecar_pid=sidecar_pid,
                    host="127.0.0.1",
                    ports=(arguments.http_port, arguments.dj_port),
                    timeout=arguments.stop_timeout,
                )

    if not all(checks.values()) and error_message is None:
        error_message = "One or more required checks did not pass"
    report = {
        "archive": {
            "file": arguments.archive.name,
            "sha256": calculate_sha256(arguments.archive),
        },
        "checks": checks,
        "command": command,
        "error": error_message,
        "sidecar_pid": sidecar_pid,
        "status": "pass" if all(checks.values()) and error_message is None else "fail",
    }
    arguments.log.parent.mkdir(parents=True, exist_ok=True)
    arguments.log.write_text("\n".join(logs) + "\n", encoding="utf-8")
    _write_report(arguments.report, report)
    return report


def _parse_arguments(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--paper-manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--paper-cache", type=Path, default=DEFAULT_PAPER_CACHE)
    parser.add_argument("--java-cache", type=Path, default=DEFAULT_JAVA_CACHE)
    parser.add_argument("--java", default="java")
    parser.add_argument("--java-container-image", default=DEFAULT_JAVA_CONTAINER_IMAGE)
    parser.add_argument("--public-host", default="8.8.8.8")
    parser.add_argument("--http-port", type=int, default=DEFAULT_HTTP_PORT)
    parser.add_argument("--dj-port", type=int, default=DEFAULT_DJ_PORT)
    parser.add_argument("--minecraft-port", type=int, default=DEFAULT_MINECRAFT_PORT)
    parser.add_argument("--startup-timeout", type=float, default=240.0)
    parser.add_argument("--stop-timeout", type=float, default=30.0)
    arguments = parser.parse_args(argv)
    for name in (
        "archive",
        "report",
        "log",
        "paper_manifest",
        "paper_cache",
        "java_cache",
    ):
        setattr(arguments, name, getattr(arguments, name).resolve())
    return arguments


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parse_arguments(argv)
    report = run_rehearsal(arguments)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
