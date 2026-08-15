from __future__ import annotations

import hashlib
import json
import queue
import subprocess
import urllib.request
from collections.abc import Callable
from pathlib import Path

import pytest

from scripts.release.paper_harness import (
    PaperManifest,
    PaperServer,
    download_paper,
    verify_sha256,
)

READY_LINE = '[Server thread/INFO]: Done (1.234s)! For help, type "help"'


class FakeStdout:
    def __init__(self, lines: list[str] | None = None) -> None:
        self._lines: queue.Queue[str | None] = queue.Queue()
        for line in lines or []:
            self.push(line)

    def push(self, line: str) -> None:
        self._lines.put(f"{line}\n")

    def close(self) -> None:
        self._lines.put(None)

    def readline(self) -> str:
        line = self._lines.get(timeout=2)
        return "" if line is None else line


class FakeStdin:
    def __init__(self, on_write: Callable[[str], None] | None = None) -> None:
        self.writes: list[str] = []
        self.flush_count = 0
        self._on_write = on_write

    def write(self, value: str) -> int:
        self.writes.append(value)
        if self._on_write is not None:
            self._on_write(value)
        return len(value)

    def flush(self) -> None:
        self.flush_count += 1


class FakeProcess:
    def __init__(
        self,
        lines: list[str] | None = None,
        *,
        returncode: int | None = None,
        stop_on_command: bool = True,
        wait_times_out: bool = False,
    ) -> None:
        self.stdout = FakeStdout(lines)
        self.returncode = returncode
        self.pid = 4242
        self.killed = False
        self.wait_times_out = wait_times_out
        self.stdin = FakeStdin(self._on_write)
        self._stop_on_command = stop_on_command
        if returncode is not None:
            self.stdout.close()

    def _on_write(self, value: str) -> None:
        if value == "stop\n" and self._stop_on_command and not self.wait_times_out:
            self.returncode = 0
            self.stdout.close()

    def poll(self) -> int | None:
        return self.returncode

    def wait(self, timeout: float | None = None) -> int:
        if self.wait_times_out and not self.killed:
            raise subprocess.TimeoutExpired("fake-paper", timeout)
        if self.returncode is None:
            raise subprocess.TimeoutExpired("fake-paper", timeout)
        return self.returncode

    def kill(self) -> None:
        self.killed = True
        self.returncode = -9
        self.stdout.close()


class FakeProcessFactory:
    def __init__(self, process: FakeProcess) -> None:
        self.process = process
        self.calls: list[tuple[list[str], dict[str, object]]] = []

    def __call__(self, args: list[str], **kwargs: object) -> FakeProcess:
        self.calls.append((args, kwargs))
        return self.process


@pytest.fixture
def artifacts(tmp_path: Path) -> tuple[Path, Path]:
    paper_jar = tmp_path / "paper-source.jar"
    plugin_jar = tmp_path / "mcav-paper-1.1.0.jar"
    paper_jar.write_bytes(b"paper")
    plugin_jar.write_bytes(b"plugin")
    return paper_jar, plugin_jar


def test_manifest_matches_immutable_paper_pin() -> None:
    manifest_path = Path("scripts/release/paper_26_2_manifest.json")
    raw = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest = PaperManifest.from_path(manifest_path)

    assert raw["channel"] == "STABLE"
    assert manifest.file == "paper-26.2-112.jar"
    assert manifest.sha256 == ("bd3a58cf96874e5ea6643f5f6fe9b4f5bf9e34b795fa078c2f0ee8b98b2f907e")


def test_verify_download_rejects_wrong_hash(tmp_path: Path) -> None:
    server_jar = tmp_path / "paper.jar"
    server_jar.write_bytes(b"not paper")

    with pytest.raises(ValueError, match="Paper SHA-256 mismatch"):
        verify_sha256(server_jar, "0" * 64)


def test_download_uses_identified_https_request(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    content = b"pinned-paper"
    manifest = PaperManifest(
        project="paper",
        minecraft_version="26.2",
        build=112,
        channel="STABLE",
        file="paper.jar",
        sha256=hashlib.sha256(content).hexdigest(),
        url="https://fill-data.papermc.io/v1/objects/hash/paper.jar",
    )

    class FakeResponse:
        def __init__(self) -> None:
            self._remaining = content

        def __enter__(self):
            return self

        def __exit__(self, *_arguments: object) -> None:
            return None

        def read(self, _size: int) -> bytes:
            chunk, self._remaining = self._remaining, b""
            return chunk

    def fake_urlopen(request: object, timeout: int):
        assert isinstance(request, urllib.request.Request)
        assert request.get_header("User-agent") == "MCAV-Release-Verification/1.0"
        assert timeout == 60
        return FakeResponse()

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)

    downloaded = download_paper(manifest, tmp_path / "cache")

    assert downloaded.read_bytes() == content


def test_startup_timeout_reports_sanitized_tail(
    artifacts: tuple[Path, Path],
) -> None:
    paper_jar, plugin_jar = artifacts
    secret = "never-print-this-secret"
    process = FakeProcess([f"Waiting with {secret}"])
    server = PaperServer(
        java_executable="java",
        paper_jar=paper_jar,
        plugin_jar=plugin_jar,
        startup_timeout=0.05,
        process_factory=FakeProcessFactory(process),
        redactions=[secret],
    )

    with pytest.raises(TimeoutError, match="Timed out waiting for Paper log") as error:
        server.start()

    assert secret not in str(error.value)
    assert "[REDACTED]" in str(error.value)
    server.close()


def test_early_process_exit_includes_diagnostics(
    artifacts: tuple[Path, Path],
) -> None:
    paper_jar, plugin_jar = artifacts
    process = FakeProcess(["Fatal startup failure"], returncode=17)
    server = PaperServer(
        java_executable="java",
        paper_jar=paper_jar,
        plugin_jar=plugin_jar,
        startup_timeout=0.1,
        process_factory=FakeProcessFactory(process),
    )

    with pytest.raises(RuntimeError, match="exited before startup.*17") as error:
        server.start()

    assert "Fatal startup failure" in str(error.value)
    server.close()


def test_command_writes_stdin_and_waits_for_marker(
    artifacts: tuple[Path, Path],
) -> None:
    paper_jar, plugin_jar = artifacts
    process = FakeProcess([READY_LINE])

    def emit_marker(value: str) -> None:
        if value.startswith("say "):
            process.stdout.push("[Server thread/INFO]: MCAV_MARKER_42")

    process.stdin._on_write = emit_marker
    server = PaperServer(
        java_executable="java",
        paper_jar=paper_jar,
        plugin_jar=plugin_jar,
        process_factory=FakeProcessFactory(process),
    )
    server.start()

    matched = server.command("say MCAV_MARKER_42", "MCAV_MARKER_42", timeout=0.2)

    assert matched.endswith("MCAV_MARKER_42")
    assert process.stdin.writes == ["say MCAV_MARKER_42\n"]
    assert process.stdin.flush_count == 1
    process.returncode = 0
    process.stdout.close()
    server.close()


def test_stop_uses_kill_fallback_after_graceful_timeout(
    artifacts: tuple[Path, Path],
) -> None:
    paper_jar, plugin_jar = artifacts
    process = FakeProcess(
        [READY_LINE],
        stop_on_command=False,
        wait_times_out=True,
    )
    server = PaperServer(
        java_executable="java",
        paper_jar=paper_jar,
        plugin_jar=plugin_jar,
        process_factory=FakeProcessFactory(process),
        stop_timeout=0.01,
    )
    server.start()

    server.stop()

    assert process.stdin.writes[-1] == "stop\n"
    assert process.killed is True
    server.close()


def test_logs_redact_registered_secrets_and_json_tokens(
    artifacts: tuple[Path, Path],
) -> None:
    paper_jar, plugin_jar = artifacts
    secret = "generated-secret-value"
    process = FakeProcess(
        [
            READY_LINE,
            f'auth secret={secret} payload={{"token":"json-token-value"}}',
        ]
    )
    server = PaperServer(
        java_executable="java",
        paper_jar=paper_jar,
        plugin_jar=plugin_jar,
        process_factory=FakeProcessFactory(process),
    )
    server.start()
    server.register_redaction(secret)
    server.wait_for_log("payload=", timeout=0.2)

    captured = "\n".join(server.logs)
    assert secret not in captured
    assert "json-token-value" not in captured
    assert captured.count("[REDACTED]") >= 2
    process.returncode = 0
    process.stdout.close()
    server.close()


def test_context_manager_cleans_owned_temporary_directory(
    artifacts: tuple[Path, Path],
) -> None:
    paper_jar, plugin_jar = artifacts
    process = FakeProcess([READY_LINE])
    factory = FakeProcessFactory(process)

    with PaperServer(
        java_executable="java",
        paper_jar=paper_jar,
        plugin_jar=plugin_jar,
        process_factory=factory,
    ) as server:
        work_dir = server.work_dir
        assert work_dir.exists()
        assert (work_dir / "eula.txt").read_text(encoding="utf-8") == "eula=true\n"
        properties = (work_dir / "server.properties").read_text(encoding="utf-8")
        assert "server-ip=127.0.0.1" in properties
        assert "server-port=25575" in properties
        assert (work_dir / "plugins" / plugin_jar.name).is_file()

    assert not work_dir.exists()
    assert factory.calls[0][0] == [
        "java",
        "-Xms1G",
        "-Xmx2G",
        "-jar",
        "paper.jar",
        "--nogui",
    ]
