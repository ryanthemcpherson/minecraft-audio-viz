"""Disposable Paper 26.2 process harness used by release verification."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess  # nosec B404
import tempfile
import threading
import time
import urllib.request
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Pattern, TextIO
from urllib.parse import urlparse

PAPER_DOWNLOAD_HOST = "fill-data.papermc.io"
DEFAULT_STARTUP_PATTERN = re.compile(r'Done \([^)]*\)! For help, type "help"')
JSON_TOKEN_PATTERN = re.compile(r'("token"\s*:\s*")[^"]*(")', re.IGNORECASE)
DEFAULT_SERVER_PROPERTIES: dict[str, str] = {
    "online-mode": "false",
    "server-ip": "127.0.0.1",
    "server-port": "25575",
    "spawn-protection": "0",
    "view-distance": "4",
    "simulation-distance": "4",
    "enable-rcon": "false",
    "motd": "MCAV Paper 26.2 integration",
}


@dataclass(frozen=True)
class PaperManifest:
    """Immutable Paper artifact identity loaded from the release manifest."""

    project: str
    minecraft_version: str
    build: int
    channel: str
    file: str
    sha256: str
    url: str

    @classmethod
    def from_path(cls, path: Path) -> PaperManifest:
        raw = json.loads(path.read_text(encoding="utf-8"))
        manifest = cls(
            project=str(raw["project"]),
            minecraft_version=str(raw["minecraftVersion"]),
            build=int(raw["build"]),
            channel=str(raw["channel"]),
            file=str(raw["file"]),
            sha256=str(raw["sha256"]).lower(),
            url=str(raw["url"]),
        )
        manifest.validate()
        return manifest

    def validate(self) -> None:
        if self.project != "paper":
            raise ValueError(f"Unsupported server project: {self.project}")
        if Path(self.file).name != self.file:
            raise ValueError("Paper manifest file must be a basename")
        if re.fullmatch(r"[0-9a-f]{64}", self.sha256) is None:
            raise ValueError("Paper manifest SHA-256 must be 64 lowercase hex characters")
        parsed_url = urlparse(self.url)
        if parsed_url.scheme != "https" or parsed_url.hostname != PAPER_DOWNLOAD_HOST:
            raise ValueError(f"Paper manifest URL must use https://{PAPER_DOWNLOAD_HOST}/")


def calculate_sha256(path: Path) -> str:
    """Return the lowercase SHA-256 digest for a file."""

    digest = hashlib.sha256()
    with path.open("rb") as input_file:
        for chunk in iter(lambda: input_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_sha256(path: Path, expected_sha256: str) -> None:
    """Raise when a Paper artifact does not match its immutable digest."""

    actual_sha256 = calculate_sha256(path)
    normalized_expected = expected_sha256.lower()
    if actual_sha256 != normalized_expected:
        raise ValueError(
            f"Paper SHA-256 mismatch: expected {normalized_expected}, got {actual_sha256}"
        )


def download_paper(manifest: PaperManifest, cache_dir: Path) -> Path:
    """Download a pinned Paper JAR safely and atomically into a local cache."""

    manifest.validate()
    cache_dir.mkdir(parents=True, exist_ok=True)
    destination = cache_dir / manifest.file

    if destination.is_file():
        try:
            verify_sha256(destination, manifest.sha256)
        except ValueError:
            pass
        else:
            return destination

    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{manifest.file}.",
        suffix=".part",
        dir=cache_dir,
    )
    os.close(file_descriptor)
    temporary_path = Path(temporary_name)
    request = urllib.request.Request(
        manifest.url,
        headers={"User-Agent": "MCAV-Release-Verification/1.0"},
        method="GET",
    )
    try:
        with (
            urllib.request.urlopen(request, timeout=60) as response,  # nosec B310
            temporary_path.open("wb") as output_file,
        ):
            while chunk := response.read(1024 * 1024):
                output_file.write(chunk)
        verify_sha256(temporary_path, manifest.sha256)
        os.replace(temporary_path, destination)
    finally:
        temporary_path.unlink(missing_ok=True)

    return destination


class PaperServer:
    """Own one disposable Paper process and its isolated server directory."""

    def __init__(
        self,
        *,
        java_executable: str | Path,
        paper_jar: Path,
        plugin_jar: Path,
        additional_plugins: Sequence[Path] = (),
        work_dir: Path | None = None,
        startup_timeout: float = 180.0,
        stop_timeout: float = 30.0,
        startup_pattern: str | Pattern[str] = DEFAULT_STARTUP_PATTERN,
        process_factory: Callable[..., Any] = subprocess.Popen,
        server_properties: Mapping[str, str | int | bool] | None = None,
        redactions: Iterable[str] = (),
    ) -> None:
        self.java_executable = str(java_executable)
        self.paper_jar = paper_jar.resolve(strict=True)
        self.plugin_jar = plugin_jar.resolve(strict=True)
        self.additional_plugins = tuple(
            plugin.resolve(strict=True) for plugin in additional_plugins
        )
        self.startup_timeout = startup_timeout
        self.stop_timeout = stop_timeout
        self.startup_pattern = self._compile_pattern(startup_pattern)
        self._process_factory = process_factory
        self._properties = {
            **DEFAULT_SERVER_PROPERTIES,
            **{key: str(value).lower() for key, value in (server_properties or {}).items()},
        }
        self._redactions = {value for value in redactions if value}
        self._condition = threading.Condition()
        self._logs: list[str] = []
        self._process: Any | None = None
        self._reader_thread: threading.Thread | None = None
        self._prepared = False
        self._closed = False

        if work_dir is None:
            self._temporary_directory: tempfile.TemporaryDirectory[str] | None = (
                tempfile.TemporaryDirectory(prefix="mcav-paper-26-2-")
            )
            self.work_dir = Path(self._temporary_directory.name)
        else:
            self._temporary_directory = None
            self.work_dir = work_dir.resolve()
            self.work_dir.mkdir(parents=True, exist_ok=True)

    @property
    def pid(self) -> int | None:
        process = self._process
        if process is None or process.poll() is not None:
            return None
        return int(process.pid)

    @property
    def logs(self) -> tuple[str, ...]:
        with self._condition:
            return tuple(self._logs)

    @property
    def plugins_dir(self) -> Path:
        return self.work_dir / "plugins"

    def register_redaction(self, value: str) -> None:
        """Register a secret and retroactively sanitize captured log lines."""

        if not value:
            return
        with self._condition:
            self._redactions.add(value)
            self._logs = [self._sanitize(line) for line in self._logs]
            self._condition.notify_all()

    def start(self) -> PaperServer:
        """Prepare the isolated server, start Paper, and wait for readiness."""

        if self._closed:
            raise RuntimeError("PaperServer is closed")
        if self._process is not None and self._process.poll() is None:
            raise RuntimeError("Paper is already running")
        if not self._prepared:
            self._prepare_server_directory()

        with self._condition:
            start_index = len(self._logs)

        command = [
            self.java_executable,
            "-Xms1G",
            "-Xmx2G",
            "-jar",
            "paper.jar",
            "--nogui",
        ]
        self._process = self._process_factory(
            command,
            cwd=str(self.work_dir),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        if self._process.stdout is None:
            raise RuntimeError("Paper process did not expose stdout")

        self._reader_thread = threading.Thread(
            target=self._read_process_output,
            args=(self._process, self._process.stdout),
            name="mcav-paper-log-reader",
            daemon=True,
        )
        self._reader_thread.start()
        self._wait_for_log(
            self.startup_pattern,
            self.startup_timeout,
            after_index=start_index,
            startup=True,
        )
        return self

    def wait_for_log(
        self,
        pattern: str | Pattern[str],
        timeout: float = 30.0,
    ) -> str:
        """Wait for a sanitized log line matching a regex or literal marker."""

        return self._wait_for_log(
            self._compile_pattern(pattern),
            timeout,
            after_index=0,
            startup=False,
        )

    def command(
        self,
        command: str,
        marker: str | Pattern[str],
        timeout: float = 30.0,
    ) -> str:
        """Send one console command and wait only for a subsequent marker."""

        process = self._require_running_process()
        if process.stdin is None:
            raise RuntimeError("Paper process did not expose stdin")
        with self._condition:
            start_index = len(self._logs)
        process.stdin.write(f"{command}\n")
        process.stdin.flush()
        return self._wait_for_log(
            self._compile_pattern(marker),
            timeout,
            after_index=start_index,
            startup=False,
        )

    def stop(self) -> None:
        """Stop Paper gracefully and use a forced kill only after timeout."""

        process = self._process
        if process is None:
            return

        if process.poll() is None:
            try:
                if process.stdin is not None:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                process.wait(timeout=self.stop_timeout)
            except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=max(self.stop_timeout, 1.0))

        reader_thread = self._reader_thread
        if reader_thread is not None:
            reader_thread.join(timeout=max(self.stop_timeout, 1.0))
        self._process = None
        self._reader_thread = None

    def restart(self) -> PaperServer:
        """Restart the same isolated server directory without reinstalling files."""

        self.stop()
        return self.start()

    def close(self) -> None:
        """Stop Paper and clean an owned temporary directory."""

        if self._closed:
            return
        try:
            self.stop()
        finally:
            if self._temporary_directory is not None:
                self._temporary_directory.cleanup()
            self._closed = True

    def __enter__(self) -> PaperServer:
        try:
            return self.start()
        except Exception:
            self.close()
            raise

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.close()

    def _prepare_server_directory(self) -> None:
        self.work_dir.mkdir(parents=True, exist_ok=True)
        self.plugins_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(self.paper_jar, self.work_dir / "paper.jar")
        shutil.copy2(self.plugin_jar, self.plugins_dir / self.plugin_jar.name)
        for plugin in self.additional_plugins:
            shutil.copy2(plugin, self.plugins_dir / plugin.name)
        (self.work_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        properties = "".join(f"{key}={value}\n" for key, value in self._properties.items())
        (self.work_dir / "server.properties").write_text(
            properties,
            encoding="utf-8",
        )
        self._prepared = True

    def _read_process_output(self, process: Any, stdout: TextIO) -> None:
        try:
            while True:
                raw_line = stdout.readline()
                if raw_line == "":
                    break
                sanitized_line = self._sanitize(raw_line.rstrip("\r\n"))
                with self._condition:
                    self._logs.append(sanitized_line)
                    self._condition.notify_all()
        finally:
            with self._condition:
                self._condition.notify_all()

    def _wait_for_log(
        self,
        pattern: Pattern[str],
        timeout: float,
        *,
        after_index: int,
        startup: bool,
    ) -> str:
        deadline = time.monotonic() + timeout
        with self._condition:
            while True:
                for line in self._logs[after_index:]:
                    if pattern.search(line):
                        return line
                after_index = len(self._logs)

                process = self._process
                if process is None:
                    raise RuntimeError("Paper process is not running")
                return_code = process.poll()
                if return_code is not None:
                    reader_thread = self._reader_thread
                    if reader_thread is not None and reader_thread.is_alive():
                        remaining = deadline - time.monotonic()
                        if remaining > 0:
                            self._condition.wait(timeout=min(0.05, remaining))
                            continue
                    phase = "startup" if startup else "log marker"
                    raise RuntimeError(
                        f"Paper exited before {phase} with code {return_code}."
                        f"\nRecent sanitized logs:\n{self._recent_log_text()}"
                    )

                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError(
                        f"Timed out waiting for Paper log {pattern.pattern!r}."
                        f"\nRecent sanitized logs:\n{self._recent_log_text()}"
                    )
                self._condition.wait(timeout=min(0.05, remaining))

    def _recent_log_text(self, line_count: int = 30) -> str:
        recent_lines = self._logs[-line_count:]
        return "\n".join(recent_lines) if recent_lines else "<no output>"

    def _sanitize(self, line: str) -> str:
        sanitized = JSON_TOKEN_PATTERN.sub(r"\1[REDACTED]\2", line)
        for secret in sorted(self._redactions, key=len, reverse=True):
            sanitized = sanitized.replace(secret, "[REDACTED]")
        return sanitized

    def _require_running_process(self) -> Any:
        process = self._process
        if process is None or process.poll() is not None:
            raise RuntimeError("Paper process is not running")
        return process

    @staticmethod
    def _compile_pattern(pattern: str | Pattern[str]) -> Pattern[str]:
        if isinstance(pattern, str):
            return re.compile(re.escape(pattern))
        return pattern
