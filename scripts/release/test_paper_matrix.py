"""Gate failure paths must not turn successful transport into rendering evidence."""

import asyncio
import json
import sys
from pathlib import Path
from unittest.mock import AsyncMock, Mock

import pytest

from scripts.release import paper_matrix as gate


@pytest.mark.parametrize(
    "url",
    [
        "http://example.org/file",
        "file:///tmp/file",
        "https:///missing-host",
        "https://user:secret@example.org/file",
    ],
)
def test_downloads_and_redirects_reject_non_https_or_credentials(url):
    with pytest.raises(gate.GateError):
        gate.require_https(url)
    handler = gate.HttpsRedirectHandler()
    with pytest.raises(gate.GateError):
        handler.redirect_request(Mock(), None, 302, "Found", {}, url)


@pytest.mark.parametrize("vector", ["[2.0d, 82.0d, 1.5d]", "[0.3f, 0.3f, 0.3f]"])
def test_reads_actual_console_nbt(vector):
    result = gate.parse_vector(
        ["unrelated log", f"Block Display has the following entity data: {vector}"]
    )
    assert len(result) == 3


@pytest.mark.parametrize(
    "lines",
    [
        [],
        ["Updated 8 entities"],
        ["entity data: [NaNd, 0d, 0d]"],
        ["entity data: [1d, 2d]"],
        ["entity data: [bad, 2d, 3d]"],
    ],
)
def test_acknowledgements_or_invalid_data_cannot_pass(lines):
    with pytest.raises(gate.GateError):
        gate.parse_vector(lines)


@pytest.mark.parametrize("actual", [[0, 0, 0], [2, 82], [float("nan"), 82, 1.5]])
def test_requires_expected_world_position(actual):
    with pytest.raises(gate.GateError):
        gate.assert_vector(actual, [2, 82, 1.5])


def test_valid_transform_tolerates_only_float_rounding():
    gate.assert_vector([0.30000001] * 3, [0.3] * 3)


def test_cached_artifact_tampering_fails_before_execution(tmp_path):
    payload = tmp_path / "source"
    payload.write_bytes(b"expected bytes")
    digest = gate.sha256(payload)
    (tmp_path / f"{digest}.jar").write_bytes(b"tampered")
    with pytest.raises(gate.GateError, match="digest mismatch"):
        gate.verified_download(
            {"url": "https://example.invalid/file", "sha256": digest}, tmp_path, ".jar"
        )


def test_fixture_never_overwrites_an_existing_server(tmp_path):
    sentinel = tmp_path / "world.dat"
    sentinel.write_bytes(b"user data")
    with pytest.raises(gate.GateError, match="nonempty"):
        gate.write_fixture(tmp_path, Path("unused"), Path("unused"), [1, 2])
    assert sentinel.read_bytes() == b"user data"


def test_fixture_binds_only_loopback_and_disables_managed_runtime(tmp_path):
    artifact = tmp_path / "fixture.jar"
    artifact.write_bytes(b"fixture")
    server = tmp_path / "server"
    server.mkdir()
    gate.write_fixture(server, artifact, artifact, [25565, 8765])
    properties = (server / "server.properties").read_text()
    config = (server / "plugins/AudioViz/config.yml").read_text()
    assert "server-ip=127.0.0.1" in properties
    assert "enable-rcon=false" in properties
    assert "address: '127.0.0.1'" in config
    assert "runtime:\n  enabled: false" in config
    assert not (server / "plugins/AudioViz/stages.yml").exists()


def test_paper_pin_matches_product_compatibility():
    lock = json.loads(gate.LOCK_PATH.read_text())
    product = json.loads((gate.ROOT / "release/product-version.json").read_text())
    assert lock["minecraft"] == product["compatibility"]["minecraft"]
    assert lock["java"]["major"] == product["compatibility"]["java"]


@pytest.mark.asyncio
async def test_console_query_ignores_old_output_and_uses_unique_markers(monkeypatch, tmp_path):
    server = gate.PaperProcess(Path("java"), tmp_path, "fixture", 1)
    server.send = AsyncMock()
    monkeypatch.setattr(gate.secrets, "token_hex", lambda _: "fixed")
    for line in [
        "stale entity data: [1d, 2d, 3d]",
        "MCAV_fixed_BEGIN",
        "entity data: [2d, 82d, 1.5d]",
        "MCAV_fixed_END",
    ]:
        server.lines.put_nowait(line)
    result = await server.command("data get entity fixture Pos")
    assert gate.parse_vector(result) == [2, 82, 1.5]


@pytest.mark.asyncio
async def test_eof_and_timeout_fail_explicitly(tmp_path):
    server = gate.PaperProcess(Path("java"), tmp_path, "fixture", 1)
    server.lines.put_nowait(None)
    with pytest.raises(gate.GateError, match="exited"):
        await server.line(gate.time.monotonic() + 1)
    with pytest.raises(gate.GateError, match="timeout"):
        await server.line(gate.time.monotonic() + 0.001)


@pytest.mark.asyncio
async def test_plugin_error_is_not_ignored():
    websocket = AsyncMock()
    websocket.recv.return_value = json.dumps({"type": "error", "message": "missing zone"})
    with pytest.raises(gate.GateError, match="Plugin rejected"):
        await gate.request(websocket, {"type": "get_zone"}, "zone")


@pytest.mark.asyncio
async def test_stop_timeout_kills_only_the_owned_process_group(monkeypatch, tmp_path):
    server = gate.PaperProcess(Path("java"), tmp_path, "fixture", 1)
    server.process = Mock(pid=12345, returncode=None)
    server.process.wait = AsyncMock(return_value=0)
    server.send = AsyncMock()
    kill = Mock()
    monkeypatch.setattr(gate.os, "killpg", kill)

    async def timed_out(awaitable, timeout):
        await awaitable
        raise TimeoutError

    monkeypatch.setattr(asyncio, "wait_for", timed_out)
    with pytest.raises(gate.GateError, match="forced shutdown"):
        await server.stop()
    kill.assert_called_once_with(12345, gate.signal.SIGKILL)


@pytest.mark.asyncio
async def test_real_subprocess_console_lifecycle_and_log_retention(monkeypatch, tmp_path):
    spawn = asyncio.create_subprocess_exec
    child = (
        "import sys\n"
        "print('Done (0.1s)!', flush=True)\n"
        "for command in sys.stdin:\n"
        " if command.strip() == 'stop': break\n"
        " print(command.strip(), flush=True)\n"
    )

    async def fixture_process(*args, **kwargs):
        return await spawn(sys.executable, "-u", "-c", child, **kwargs)

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fixture_process)
    server = gate.PaperProcess(Path("java"), tmp_path, "fixture", 5)
    try:
        await server.start()
        assert await server.command("hello") == ["hello"]
    finally:
        await server.stop()
    assert server.process.returncode == 0
    assert "hello" in (tmp_path / "fixture.log").read_text()
