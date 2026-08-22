#!/usr/bin/env python3
"""Retained, structured smoke test for the packaged two-port deployment."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import re
import shlex
import signal
import ssl
import struct
import subprocess
import sys
import tempfile
import time
import traceback
import zipfile
from contextlib import suppress
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import aiohttp
import websockets
from websockets.exceptions import ConnectionClosed
from yarl import URL

DEPLOY_ROOT = Path(__file__).resolve().parents[1]
PUBLIC_ORIGIN = "https://127.0.0.1:25927"
PUBLIC_PORTS = {25927, 25808}
INTERNAL_PORTS = {8765, 8766, 9001}
METRICS_PORT = 19001
DJ_TLS_RECORDER_PORT = 25809
PINNED_PLAINTEXT_RECORDER_PORT = 25810
RENDERER_SECRET = "packaged-smoke-renderer-secret"
VJ_USERNAME = "smoke-vj"
VJ_PASSWORD = "packaged-smoke-vj-password"
DJ_ID = "smoke-dj"
DJ_PASSWORD = "packaged-smoke-dj-password"
REDACTED = "<redacted>"
SENSITIVE_COMMAND_OPTIONS = {"--minecraft-ws-secret"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def redact_command(command: list[str]) -> list[str]:
    """Return a retention-safe command while leaving the subprocess argv untouched."""
    redacted: list[str] = []
    redact_next = False
    for argument in command:
        if redact_next:
            redacted.append(REDACTED)
            redact_next = False
            continue
        matching_option = next(
            (option for option in SENSITIVE_COMMAND_OPTIONS if argument.startswith(f"{option}=")),
            None,
        )
        if argument in SENSITIVE_COMMAND_OPTIONS:
            redacted.append(argument)
            redact_next = True
        elif matching_option is not None:
            redacted.append(f"{matching_option}={REDACTED}")
        else:
            redacted.append(argument)
    return redacted


def redact_text(value: str, secrets: tuple[str, ...]) -> str:
    redacted = value
    for secret in secrets:
        if secret:
            redacted = redacted.replace(secret, REDACTED)
    return redacted


def redact_bytes(value: bytes, secrets: tuple[str, ...]) -> bytes:
    redacted = value
    for secret in secrets:
        if secret:
            redacted = redacted.replace(secret.encode(), REDACTED.encode())
    return redacted


def parse_rust_smoke_output(stdout: str, *, expected_mode: str) -> dict[str, Any]:
    lines = [line for line in stdout.splitlines() if line.strip()]
    require(len(lines) == 1, "Rust smoke must emit exactly one JSON line")
    try:
        output = json.loads(lines[0])
    except json.JSONDecodeError as error:
        raise AssertionError("Rust smoke output is not valid JSON") from error
    require(isinstance(output, dict), "Rust smoke output must be a JSON object")
    require(output.get("schema_version") == 1, "Rust smoke schema version mismatch")
    require(output.get("mode") == expected_mode, "Rust smoke mode mismatch")
    require(output.get("status") == "passed", "Rust smoke did not pass")
    require(
        isinstance(output.get("process_id"), int) and output["process_id"] > 0,
        "Rust smoke process identity is missing",
    )
    require(
        isinstance(output.get("executable"), str) and output["executable"],
        "Rust smoke executable identity is missing",
    )
    expected_production_paths = {
        "match": "DjClient::connect + DjClient::try_send",
        "mismatch": "DjClient::connect",
        "plaintext": "connect_verified",
    }
    require(
        output.get("production_path") == expected_production_paths[expected_mode],
        "Rust smoke did not exercise the expected production path",
    )
    for field_name in ("connected", "authenticated", "audio_frame_queued"):
        require(
            isinstance(output.get(field_name), bool),
            f"Rust smoke {field_name} result is missing",
        )
    if expected_mode == "match":
        require(
            output["connected"] and output["authenticated"] and output["audio_frame_queued"],
            "Rust production DJ did not connect, authenticate, and queue audio",
        )
        require(output.get("error_code") is None, "matching Rust DJ returned an error")
    else:
        expected_error = {
            "mismatch": "tls_fingerprint_mismatch",
            "plaintext": "missing_peer_certificate",
        }[expected_mode]
        require(
            output.get("error_code") == expected_error,
            f"Rust {expected_mode} smoke returned the wrong fail-closed error",
        )
        require(
            not output["connected"]
            and not output["authenticated"]
            and not output["audio_frame_queued"],
            f"Rust {expected_mode} smoke crossed the security boundary",
        )
    return output


class WebSocketApplicationRecorder:
    """Counts decrypted DJ client traffic without retaining credentials or payloads."""

    def __init__(self) -> None:
        self._buffer = bytearray()
        self._http_complete = False
        self._application_bytes = 0
        self._upgrade_requests = 0
        self._auth_messages = 0
        self._audio_messages = 0
        self._message_types: list[str] = []
        self._parse_errors = 0

    def feed(self, chunk: bytes) -> None:
        self._application_bytes += len(chunk)
        self._buffer.extend(chunk)
        if not self._http_complete:
            marker = self._buffer.find(b"\r\n\r\n")
            if marker < 0:
                return
            header = bytes(self._buffer[: marker + 4])
            del self._buffer[: marker + 4]
            lowered = header.lower()
            if (
                header.startswith(b"GET ")
                and b"\r\nupgrade: websocket\r\n" in lowered
                and b"\r\nconnection: upgrade\r\n" in lowered
            ):
                self._upgrade_requests += 1
            else:
                self._parse_errors += 1
            self._http_complete = True
        self._parse_frames()

    def _parse_frames(self) -> None:
        while len(self._buffer) >= 2:
            first = self._buffer[0]
            second = self._buffer[1]
            opcode = first & 0x0F
            masked = bool(second & 0x80)
            payload_length = second & 0x7F
            header_length = 2
            if payload_length == 126:
                if len(self._buffer) < 4:
                    return
                payload_length = struct.unpack("!H", self._buffer[2:4])[0]
                header_length = 4
            elif payload_length == 127:
                if len(self._buffer) < 10:
                    return
                payload_length = struct.unpack("!Q", self._buffer[2:10])[0]
                header_length = 10
            if not masked or payload_length > 1_048_576:
                self._parse_errors += 1
                self._buffer.clear()
                return
            frame_length = header_length + 4 + payload_length
            if len(self._buffer) < frame_length:
                return
            mask = self._buffer[header_length : header_length + 4]
            masked_payload = self._buffer[header_length + 4 : frame_length]
            payload = bytes(value ^ mask[index % 4] for index, value in enumerate(masked_payload))
            del self._buffer[:frame_length]
            if opcode != 0x1:
                continue
            try:
                message = json.loads(payload)
            except (UnicodeDecodeError, json.JSONDecodeError):
                self._parse_errors += 1
                continue
            message_type = message.get("type") if isinstance(message, dict) else None
            if not isinstance(message_type, str):
                self._parse_errors += 1
                continue
            self._message_types.append(message_type)
            if message_type in {"dj_auth", "code_auth"}:
                self._auth_messages += 1
            elif message_type == "dj_audio_frame":
                self._audio_messages += 1

    def evidence(self) -> dict[str, Any]:
        return {
            "post_tls_application_bytes": self._application_bytes,
            "websocket_upgrade_requests": self._upgrade_requests,
            "auth_messages": self._auth_messages,
            "audio_messages": self._audio_messages,
            "message_types": self._message_types.copy(),
            "parse_errors": self._parse_errors,
        }


def sha256_password(password: str, salt: str) -> str:
    digest = hashlib.sha256(f"{salt}:{password}".encode()).hexdigest()
    return f"sha256:{salt}:{digest}"


def make_auth_config(path: Path) -> None:
    config = {
        "djs": {
            DJ_ID: {
                "name": "Packaged Smoke DJ",
                "key_hash": sha256_password(DJ_PASSWORD, "smoke-dj-salt"),
                "priority": 1,
            }
        },
        "vj_operators": {
            VJ_USERNAME: {
                "name": "Packaged Smoke VJ",
                "key_hash": sha256_password(VJ_PASSWORD, "smoke-vj-salt"),
                "permissions": ["control", "pattern"],
            }
        },
    }
    path.write_text(json.dumps(config, indent=2), encoding="utf-8")


def generate_certificate(root: Path) -> tuple[Path, Path, str]:
    certificate = root / "tls.crt"
    private_key = root / "tls.key"
    subprocess.run(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-sha256",
            "-nodes",
            "-days",
            "1",
            "-subj",
            "/CN=127.0.0.1",
            "-addext",
            "subjectAltName=IP:127.0.0.1,DNS:localhost",
            "-addext",
            "basicConstraints=critical,CA:FALSE",
            "-addext",
            "keyUsage=critical,digitalSignature,keyEncipherment",
            "-addext",
            "extendedKeyUsage=serverAuth",
            "-keyout",
            str(private_key),
            "-out",
            str(certificate),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    der = ssl.PEM_cert_to_DER_cert(certificate.read_text(encoding="ascii"))
    fingerprint = hashlib.sha256(der).hexdigest()
    return certificate, private_key, fingerprint


def verify_and_extract(archive: Path, extraction_root: Path) -> Path:
    subprocess.run(
        [sys.executable, str(DEPLOY_ROOT / "release_archive.py"), "verify", str(archive)],
        check=True,
    )
    with zipfile.ZipFile(archive) as release:
        for entry in release.infolist():
            parts = entry.filename.split("/")
            allowed_payload = (
                entry.filename.startswith("mcav-vj/") or entry.filename == "plugins/AudioViz.jar"
            )
            require(
                allowed_payload
                and "\\" not in entry.filename
                and all(part not in {"", ".", ".."} for part in parts),
                f"unsafe archive entry: {entry.filename}",
            )
            target = extraction_root.joinpath(*parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(release.read(entry))
            mode = (entry.external_attr >> 16) & 0o777
            target.chmod(mode or 0o644)
    return extraction_root / "mcav-vj"


def listener_snapshot() -> list[dict[str, Any]]:
    completed = subprocess.run(
        ["ss", "-H", "-ltnp"],
        check=True,
        capture_output=True,
        text=True,
    )
    listeners: list[dict[str, Any]] = []
    for line in completed.stdout.splitlines():
        columns = line.split()
        if len(columns) < 4:
            continue
        local = columns[3]
        match = re.search(r":(\d+)$", local)
        if match is None:
            continue
        pid_match = re.search(r"pid=(\d+)", line)
        process_match = re.search(r"users:\(\(\"([^\"]+)", line)
        host = local[: match.start()]
        listeners.append(
            {
                "host": host,
                "port": int(match.group(1)),
                "pid": int(pid_match.group(1)) if pid_match else None,
                "process": process_match.group(1) if process_match else None,
                "public_bind": host in {"0.0.0.0", "*", "[::]", "::"},
                "raw": line,
            }
        )
    return listeners


def listeners_on_ports(ports: set[int]) -> list[dict[str, Any]]:
    return [listener for listener in listener_snapshot() if listener["port"] in ports]


async def wait_for_listener_state(
    ports: set[int], *, present: bool, timeout: float = 8.0
) -> list[dict[str, Any]]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        matches = listeners_on_ports(ports)
        if (present and ports <= {item["port"] for item in matches}) or (
            not present and not matches
        ):
            return matches
        await asyncio.sleep(0.1)
    state = listeners_on_ports(ports)
    raise AssertionError(
        f"listener state did not become present={present} for {sorted(ports)}: {state}"
    )


@dataclass
class _RecordedTlsConnection:
    index: int
    peer: str
    traffic: WebSocketApplicationRecorder = field(default_factory=WebSocketApplicationRecorder)
    upstream_connected: bool = False
    failure: str | None = None
    completed: asyncio.Event = field(default_factory=asyncio.Event, repr=False)

    def evidence(self) -> dict[str, Any]:
        return {
            "connection_index": self.index,
            "peer": self.peer,
            "upstream_connected": self.upstream_connected,
            "failure": self.failure,
            "completed": self.completed.is_set(),
            **self.traffic.evidence(),
        }


class RecordingTlsForwarder:
    """Loopback TLS forwarder that records bytes after the production pin check."""

    def __init__(self, certificate: Path, private_key: Path) -> None:
        self._certificate = certificate
        self._private_key = private_key
        self._server: asyncio.Server | None = None
        self._records: list[_RecordedTlsConnection] = []
        self._handlers: set[asyncio.Task[Any]] = set()

    @property
    def record_count(self) -> int:
        return len(self._records)

    async def start(self) -> None:
        server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server_context.load_cert_chain(self._certificate, self._private_key)
        self._server = await asyncio.start_server(
            self._handle,
            "127.0.0.1",
            DJ_TLS_RECORDER_PORT,
            ssl=server_context,
        )

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
        if self._handlers:
            done, pending = await asyncio.wait(self._handlers, timeout=3.0)
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
            for task in done:
                with suppress(asyncio.CancelledError, Exception):
                    task.result()

    async def wait_for_record(self, index: int, timeout: float = 8.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while len(self._records) <= index and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        require(len(self._records) > index, "TLS recorder did not observe a connection")
        remaining = max(0.01, deadline - time.monotonic())
        await asyncio.wait_for(self._records[index].completed.wait(), timeout=remaining)
        return self._records[index].evidence()

    async def _copy_server_responses(
        self,
        upstream_reader: asyncio.StreamReader,
        client_writer: asyncio.StreamWriter,
    ) -> None:
        while response := await upstream_reader.read(65_536):
            client_writer.write(response)
            await client_writer.drain()

    async def _handle(
        self,
        client_reader: asyncio.StreamReader,
        client_writer: asyncio.StreamWriter,
    ) -> None:
        handler = asyncio.current_task()
        if handler is not None:
            self._handlers.add(handler)
        peer = client_writer.get_extra_info("peername")
        record = _RecordedTlsConnection(len(self._records), repr(peer))
        self._records.append(record)
        upstream_writer: asyncio.StreamWriter | None = None
        response_task: asyncio.Task[None] | None = None
        try:
            while client_chunk := await client_reader.read(65_536):
                record.traffic.feed(client_chunk)
                if upstream_writer is None:
                    upstream_context = ssl.create_default_context(cafile=str(self._certificate))
                    upstream_reader, upstream_writer = await asyncio.open_connection(
                        "127.0.0.1",
                        25808,
                        ssl=upstream_context,
                        server_hostname="127.0.0.1",
                    )
                    record.upstream_connected = True
                    response_task = asyncio.create_task(
                        self._copy_server_responses(upstream_reader, client_writer)
                    )
                upstream_writer.write(client_chunk)
                await upstream_writer.drain()
        except (ConnectionError, OSError, ssl.SSLError) as error:
            record.failure = type(error).__name__
        finally:
            if upstream_writer is not None:
                upstream_writer.close()
                with suppress(ConnectionError, OSError, ssl.SSLError):
                    await upstream_writer.wait_closed()
            if response_task is not None:
                with suppress(asyncio.CancelledError, ConnectionError, OSError, ssl.SSLError):
                    await response_task
            client_writer.close()
            with suppress(ConnectionError, OSError, ssl.SSLError):
                await client_writer.wait_closed()
            record.completed.set()
            if handler is not None:
                self._handlers.discard(handler)


class PlaintextConnectionRecorder:
    """Counts connections and bytes at the pinned plaintext fail-closed boundary."""

    def __init__(self, *, listen_port: int = PINNED_PLAINTEXT_RECORDER_PORT) -> None:
        self._listen_port = listen_port
        self._server: asyncio.Server | None = None
        self._connections = 0
        self._traffic = WebSocketApplicationRecorder()

    @property
    def port(self) -> int:
        require(self._server is not None, "plaintext recorder is not running")
        sockets = self._server.sockets or []
        require(len(sockets) == 1, "plaintext recorder listener is unavailable")
        return int(sockets[0].getsockname()[1])

    async def start(self) -> None:
        self._server = await asyncio.start_server(self._handle, "127.0.0.1", self._listen_port)

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self._connections += 1
        try:
            while chunk := await reader.read(65_536):
                self._traffic.feed(chunk)
        finally:
            writer.close()
            with suppress(ConnectionError, OSError):
                await writer.wait_closed()

    def evidence(self) -> dict[str, Any]:
        traffic = self._traffic.evidence()
        return {
            "connections": self._connections,
            "post_policy_application_bytes": traffic.pop("post_tls_application_bytes"),
            **traffic,
        }


class FakeRenderer:
    """Minimal authenticated Paper renderer used by the packaged service."""

    def __init__(self) -> None:
        self.server: Any = None
        self.received: list[dict[str, Any]] = []

    async def start(self) -> None:
        self.server = await websockets.serve(self._handle, "127.0.0.1", 8765)

    async def stop(self) -> None:
        if self.server is not None:
            self.server.close()
            await self.server.wait_closed()

    async def _handle(self, websocket: Any) -> None:
        await websocket.send(
            json.dumps({"type": "connected", "auth_required": True, "server_type": "paper"})
        )
        try:
            async for raw_message in websocket:
                await self._respond(websocket, json.loads(raw_message))
        except ConnectionClosed:
            pass

    async def _respond(self, websocket: Any, message: dict[str, Any]) -> None:
        self.received.append(message)
        message_type = message.get("type")
        sequence = message.get("_seq")
        if message_type == "auth":
            require(message.get("token") == RENDERER_SECRET, "renderer secret mismatch")
            await websocket.send(json.dumps({"type": "auth_ok"}))
            return
        responses: dict[str, dict[str, Any]] = {
            "ping": {"type": "pong"},
            "get_zones": {
                "type": "zones",
                "zones": [{"name": "main", "world": "world"}],
            },
            "get_stages": {"type": "stages", "stages": []},
            "cleanup_zone": {"type": "zone_cleaned", "zone": "main"},
            "init_pool": {"type": "pool_initialized", "zone": "main"},
            "get_bitmap_patterns": {
                "type": "bitmap_patterns",
                "patterns": [
                    {
                        "id": "bmp_plasma",
                        "name": "Plasma",
                        "description": "Smoke fixture bitmap",
                    }
                ],
            },
            "init_bitmap": {
                "type": "bitmap_initialized",
                "zone": message.get("zone", "main"),
                "width": 2,
                "height": 2,
                "pattern": message.get("pattern", "bmp_plasma"),
            },
            "set_bitmap_pattern": {
                "type": "bitmap_pattern_set",
                "zone": message.get("zone", "main"),
                "pattern": message.get("pattern", "bmp_plasma"),
            },
            "teardown_bitmap": {"type": "bitmap_teardown", "zone": "main"},
            "batch_update": {"type": "batch_updated"},
            "bitmap_frame": {"type": "bitmap_frame_accepted"},
        }
        response = responses.get(message_type, {"type": "ack"})
        if sequence is not None:
            response["_seq"] = sequence
        await websocket.send(json.dumps(response))
        if message_type == "init_bitmap":
            await asyncio.sleep(0.05)
            await websocket.send(
                json.dumps(
                    {
                        "type": "bitmap_frame",
                        "zone": message.get("zone", "main"),
                        "width": 2,
                        "height": 2,
                        "pixel_array": [4294901760, 4278255360, 4278190335, 4294967295],
                    }
                )
            )


async def run_rust_smoke_executable(
    executable: Path,
    *,
    mode: str,
    port: int,
    fingerprint: str,
) -> dict[str, Any]:
    executable = executable.resolve()
    require(executable.is_file(), f"Rust smoke executable not found: {executable}")
    command = [
        str(executable),
        "--mode",
        mode,
        "--host",
        "127.0.0.1",
        "--port",
        str(port),
        "--fingerprint",
        fingerprint,
        "--dj-id",
        DJ_ID,
        "--dj-key",
        DJ_PASSWORD,
    ]
    redacted_command = command.copy()
    redacted_command[redacted_command.index("--fingerprint") + 1] = "<redacted>"
    redacted_command[redacted_command.index("--dj-key") + 1] = "<redacted>"
    process = await asyncio.create_subprocess_exec(
        *command,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout_bytes, stderr_bytes = await asyncio.wait_for(process.communicate(), timeout=20.0)
    except asyncio.TimeoutError:
        process.kill()
        await process.wait()
        raise AssertionError(f"Rust {mode} smoke timed out") from None
    stdout = stdout_bytes.decode("utf-8", errors="replace")
    stderr = stderr_bytes.decode("utf-8", errors="replace")
    require(
        DJ_PASSWORD not in stdout and DJ_PASSWORD not in stderr,
        "Rust smoke output exposed the DJ credential",
    )
    require(
        process.returncode == 0,
        f"Rust {mode} smoke exited {process.returncode}: "
        f"stdout={stdout.strip()} stderr={stderr.strip()}",
    )
    output = parse_rust_smoke_output(stdout, expected_mode=mode)
    return {
        "process": {
            "launcher_pid": process.pid,
            "reported_pid": output["process_id"],
            "requested_executable": str(executable),
            "reported_executable": output["executable"],
            "executable_size_bytes": executable.stat().st_size,
            "executable_sha256": hashlib.sha256(executable.read_bytes()).hexdigest(),
            "command": shlex.join(redacted_command),
            "returncode": process.returncode,
        },
        "raw_stdout": stdout,
        "raw_stderr": stderr,
        "result": output,
    }


def validate_recorded_tls_connections(
    mode: str,
    records: list[dict[str, Any]],
) -> dict[str, Any]:
    """Validate the production pin probe and select its application connection."""

    require(mode in {"match", "mismatch"}, "invalid recorded Rust DJ mode")
    expected_count = 2 if mode == "match" else 1
    require(
        len(records) == expected_count,
        f"Rust {mode} smoke must open "
        f"{'two TLS connections' if mode == 'match' else 'one TLS connection'}",
    )
    probe = records[0]
    require(
        not probe["upstream_connected"]
        and probe["post_tls_application_bytes"] == 0
        and probe["websocket_upgrade_requests"] == 0
        and probe["auth_messages"] == 0
        and probe["audio_messages"] == 0,
        "certificate pin probe sent application data",
    )
    if mode == "mismatch":
        return probe

    application = records[1]
    require(application["upstream_connected"], "matching DJ did not reach packaged service")
    require(
        application["websocket_upgrade_requests"] == 1,
        "matching DJ did not send one WebSocket Upgrade",
    )
    require(application["auth_messages"] == 1, "matching DJ auth was not observed")
    require(application["audio_messages"] >= 1, "matching DJ audio was not observed")
    require(application["parse_errors"] == 0, "matching DJ traffic could not be parsed")
    return application


async def run_recorded_rust_dj(
    executable: Path,
    forwarder: RecordingTlsForwarder,
    *,
    mode: str,
    fingerprint: str,
) -> dict[str, Any]:
    require(mode in {"match", "mismatch"}, "invalid recorded Rust DJ mode")
    record_index = forwarder.record_count
    client = await run_rust_smoke_executable(
        executable,
        mode=mode,
        port=DJ_TLS_RECORDER_PORT,
        fingerprint=fingerprint,
    )
    expected_count = 2 if mode == "match" else 1
    records = [
        await forwarder.wait_for_record(record_index + offset) for offset in range(expected_count)
    ]
    require(
        forwarder.record_count == record_index + expected_count,
        f"Rust {mode} smoke opened an unexpected number of TLS connections",
    )
    application = validate_recorded_tls_connections(mode, records)
    return {
        "production_client": client,
        "pin_probe_recorder": records[0],
        "server_side_recorder": application,
    }


async def run_recorded_pinned_plaintext(
    executable: Path,
    recorder: PlaintextConnectionRecorder,
    fingerprint: str,
) -> dict[str, Any]:
    before = recorder.evidence()
    client = await run_rust_smoke_executable(
        executable,
        mode="plaintext",
        port=PINNED_PLAINTEXT_RECORDER_PORT,
        fingerprint=fingerprint,
    )
    await asyncio.sleep(0.2)
    after = recorder.evidence()
    require(after == before, "pinned plaintext production path attempted network traffic")
    require(
        after["connections"] == 0
        and after["post_policy_application_bytes"] == 0
        and after["websocket_upgrade_requests"] == 0
        and after["auth_messages"] == 0,
        "pinned plaintext production path crossed the fail-closed boundary",
    )
    return {"production_client": client, "server_side_recorder": after}


async def receive_browser_json(websocket: aiohttp.ClientWebSocketResponse) -> dict[str, Any]:
    message = await websocket.receive(timeout=8.0)
    if message.type is aiohttp.WSMsgType.TEXT:
        return json.loads(message.data)
    raise AssertionError(
        f"browser WebSocket closed unexpectedly: type={message.type} data={message.data!r}"
    )


async def collect_browser_messages(
    websocket: aiohttp.ClientWebSocketResponse,
    condition: Any,
) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        messages.append(await receive_browser_json(websocket))
        if condition(messages):
            return messages
    raise AssertionError(
        f"browser message condition was not met; types={[item.get('type') for item in messages]}"
    )


async def wait_for_https(fingerprint: str, process: asyncio.subprocess.Process) -> None:
    deadline = time.monotonic() + 20.0
    while time.monotonic() < deadline:
        require(process.returncode is None, f"packaged service exited early: {process.returncode}")
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{PUBLIC_ORIGIN}/runtime-config.js",
                    ssl=aiohttp.Fingerprint(bytes.fromhex(fingerprint)),
                ) as response:
                    if response.status == 200:
                        return
        except (aiohttp.ClientError, OSError, asyncio.TimeoutError):
            pass
        await asyncio.sleep(0.1)
    raise AssertionError("packaged HTTPS listener did not become ready")


async def static_transport_checks(fingerprint: str) -> dict[str, Any]:
    pin = aiohttp.Fingerprint(bytes.fromhex(fingerprint))
    responses: list[dict[str, Any]] = []
    async with aiohttp.ClientSession() as session:
        for method in ("GET", "HEAD"):
            for path in ("/", "/preview/", "/runtime-config.js"):
                async with session.request(method, f"{PUBLIC_ORIGIN}{path}", ssl=pin) as response:
                    body = await response.text()
                    require(response.status == 200, f"{method} {path} returned {response.status}")
                    responses.append(
                        {
                            "method": method,
                            "path": path,
                            "status": response.status,
                            "body_bytes": len(body.encode()),
                            "cache_control": response.headers.get("Cache-Control"),
                        }
                    )
                    if path == "/runtime-config.js":
                        require(
                            response.headers.get("Cache-Control") == "no-store",
                            "runtime config must be no-store",
                        )
                        if method == "GET":
                            require("same-origin" in body, "runtime config is not same-origin")

        try:
            await session.ws_connect(
                f"{PUBLIC_ORIGIN}/ws",
                origin="https://wrong-origin.invalid",
                ssl=pin,
            )
        except aiohttp.WSServerHandshakeError as error:
            wrong_origin_status = error.status
        else:
            raise AssertionError("wrong Origin WebSocket upgrade was accepted")
        require(wrong_origin_status == 403, "wrong Origin did not fail with HTTP 403")

        async with session.get(f"{PUBLIC_ORIGIN}/ws/", ssl=pin) as response:
            trailing_ws_status = response.status
        require(trailing_ws_status == 404, "/ws/ did not fail with HTTP 404")

        traversal_url = URL(f"{PUBLIC_ORIGIN}/preview/%2e%2e/%2e%2e/README.md", encoded=True)
        async with session.get(traversal_url, ssl=pin) as response:
            traversal_status = response.status
        require(traversal_status == 404, "encoded traversal did not fail with HTTP 404")

    return {
        "static_responses": responses,
        "wrong_origin_status": wrong_origin_status,
        "ws_trailing_slash_status": trailing_ws_status,
        "encoded_traversal_status": traversal_status,
    }


async def invalid_browser_auth_check(fingerprint: str) -> dict[str, Any]:
    async with aiohttp.ClientSession() as session:
        websocket = await session.ws_connect(
            f"{PUBLIC_ORIGIN}/ws",
            origin=PUBLIC_ORIGIN,
            ssl=aiohttp.Fingerprint(bytes.fromhex(fingerprint)),
        )
        try:
            required = await receive_browser_json(websocket)
            require(required == {"type": "auth_required"}, "auth gate was not required")
            await websocket.send_json(
                {"type": "vj_auth", "username": VJ_USERNAME, "password": "wrong"}
            )
            rejected = await receive_browser_json(websocket)
            require(rejected.get("type") == "auth_error", "invalid VJ auth was accepted")
            await websocket.receive(timeout=3.0)
            return {
                "auth_required": True,
                "auth_error": rejected.get("error"),
                "close_code": websocket.close_code,
                "failed_closed": websocket.closed,
            }
        finally:
            await websocket.close()


async def browser_protocol_check(
    fingerprint: str,
    renderer: FakeRenderer,
    rust_smoke_executable: Path,
    forwarder: RecordingTlsForwarder,
    *,
    no_auth: bool,
) -> tuple[dict[str, Any], dict[str, Any]]:
    renderer_start = len(renderer.received)
    async with aiohttp.ClientSession() as session:
        websocket = await session.ws_connect(
            f"{PUBLIC_ORIGIN}/ws",
            origin=PUBLIC_ORIGIN,
            ssl=aiohttp.Fingerprint(bytes.fromhex(fingerprint)),
        )
        seen: list[dict[str, Any]] = []
        try:
            first = await receive_browser_json(websocket)
            seen.append(first)
            if no_auth:
                require(first == {"type": "auth_success"}, "no-auth did not pass immediately")
            else:
                require(first == {"type": "auth_required"}, "auth challenge missing")
                await websocket.send_json(
                    {
                        "type": "vj_auth",
                        "username": VJ_USERNAME,
                        "password": VJ_PASSWORD,
                    }
                )
                authenticated = await receive_browser_json(websocket)
                seen.append(authenticated)
                require(authenticated == {"type": "auth_success"}, "valid VJ auth failed")

            initial = await collect_browser_messages(
                websocket, lambda messages: any(m.get("type") == "vj_state" for m in messages)
            )
            seen.extend(initial)
            initial_state = next(m for m in initial if m.get("type") == "vj_state")
            require(initial_state.get("minecraft_connected") is True, "renderer is not live")
            await websocket.send_json({"type": "get_state", "request_id": "smoke-state"})
            state_messages = await collect_browser_messages(
                websocket, lambda messages: any(m.get("type") == "vj_state" for m in messages)
            )
            seen.extend(state_messages)

            await websocket.send_json(
                {
                    "type": "set_pattern",
                    "pattern": "bmp_plasma",
                    "zones": ["main"],
                    "request_id": "smoke-pattern",
                }
            )
            dj_task = asyncio.create_task(
                run_recorded_rust_dj(
                    rust_smoke_executable,
                    forwarder,
                    mode="match",
                    fingerprint=fingerprint,
                )
            )

            def live_contract(messages: list[dict[str, Any]]) -> bool:
                message_types = {message.get("type") for message in messages}
                live_audio = any(
                    message.get("type") == "state"
                    and message.get("bands")
                    and float(message["bands"][0]) >= 0.5
                    for message in messages
                )
                return {
                    "bitmap_initialized",
                    "bitmap_frame",
                    "pattern_changed",
                } <= message_types and live_audio

            live_messages = await collect_browser_messages(websocket, live_contract)
            seen.extend(live_messages)
            dj_result = await dj_task
        finally:
            await websocket.close()

    renderer_messages = renderer.received[renderer_start:]
    renderer_types = [message.get("type") for message in renderer_messages]
    message_types = [message.get("type") for message in seen]
    return (
        {
            "mode": "no-auth" if no_auth else "auth",
            "auth_required": not no_auth,
            "auth_success": "auth_success" in message_types,
            "initial_state": "vj_state" in message_types,
            "control_round_trip": "pattern_changed" in message_types
            and "init_bitmap" in renderer_types,
            "bitmap_2x2": any(
                message.get("type") == "bitmap_frame"
                and message.get("width") == 2
                and message.get("height") == 2
                for message in seen
            ),
            "live_audio": any(
                message.get("type") == "state"
                and message.get("bands")
                and float(message["bands"][0]) >= 0.5
                for message in seen
            ),
            "message_types": message_types,
            "renderer_message_types": renderer_types,
            "renderer_batch_update": "batch_update" in renderer_types,
        },
        dj_result,
    )


def service_command(
    bundle: Path,
    certificate: Path,
    private_key: Path,
    auth_file: Path,
    *,
    no_auth: bool,
    renderer_secret: str = RENDERER_SECRET,
) -> list[str]:
    command = [
        str(bundle / "bin/linux-amd64/audioviz-vj"),
        "--port",
        "25808",
        "--minecraft-host",
        "127.0.0.1",
        "--minecraft-port",
        "8765",
        "--minecraft-ws-secret",
        renderer_secret,
        "--broadcast-port",
        "8766",
        "--http-host",
        "0.0.0.0",
        "--http-port",
        "25927",
        "--unified-web",
        "--public-origin",
        PUBLIC_ORIGIN,
        "--project-root",
        str(bundle),
        "--tls-cert",
        str(certificate),
        "--tls-key",
        str(private_key),
        "--auth-file",
        str(auth_file),
        "--metrics-port",
        str(METRICS_PORT),
        "--no-spectrograph",
    ]
    if no_auth:
        command.append("--no-auth")
    return command


async def stop_service(process: asyncio.subprocess.Process) -> dict[str, Any]:
    graceful = True
    if process.returncode is None:
        process.send_signal(signal.SIGTERM)
        try:
            await asyncio.wait_for(process.wait(), timeout=10.0)
        except asyncio.TimeoutError:
            graceful = False
            process.kill()
            await process.wait()
    return {"graceful": graceful, "returncode": process.returncode}


async def run_service_scenario(
    bundle: Path,
    certificate: Path,
    private_key: Path,
    auth_file: Path,
    fingerprint: str,
    renderer: FakeRenderer,
    rust_smoke_executable: Path,
    forwarder: RecordingTlsForwarder,
    plaintext_recorder: PlaintextConnectionRecorder,
    log_handle: Any,
    *,
    no_auth: bool,
) -> dict[str, Any]:
    mode = "no-auth" if no_auth else "auth"
    occupied = listeners_on_ports(PUBLIC_PORTS | {METRICS_PORT, 8766})
    require(not occupied, f"ports required for {mode} service are occupied: {occupied}")
    command = service_command(bundle, certificate, private_key, auth_file, no_auth=no_auth)
    retained_command = redact_command(command)
    log_handle.write(f"\n=== packaged service: {mode} ===\n".encode())
    log_handle.write(f"command: {shlex.join(retained_command)}\n".encode())
    log_handle.flush()
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    environment["PYTHONUNBUFFERED"] = "1"
    environment["MCAV_ASYNC_LUA"] = "0"
    process = await asyncio.create_subprocess_exec(
        *command,
        cwd=bundle,
        env=environment,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    require(process.stdout is not None, "packaged service output pipe is unavailable")
    output_task = asyncio.create_task(process.stdout.read())
    shutdown: dict[str, Any] | None = None
    result: dict[str, Any] = {
        "mode": mode,
        "command": retained_command,
        "pid": process.pid,
    }
    try:
        await wait_for_https(fingerprint, process)
        listener_records = await wait_for_listener_state(
            PUBLIC_PORTS | {METRICS_PORT}, present=True
        )
        process_records = [item for item in listener_records if item["pid"] == process.pid]
        require(
            {item["port"] for item in process_records} == PUBLIC_PORTS | {METRICS_PORT},
            f"unexpected packaged listener set: {process_records}",
        )
        require(
            {item["port"] for item in process_records if item["public_bind"]} == PUBLIC_PORTS,
            f"packaged public bindings are not exactly {sorted(PUBLIC_PORTS)}",
        )
        require(
            not ({item["port"] for item in process_records} & INTERNAL_PORTS),
            "packaged process bound an internal port",
        )
        executable = os.readlink(f"/proc/{process.pid}/exe")
        raw_cmdline = (
            Path(f"/proc/{process.pid}/cmdline").read_bytes().replace(b"\0", b" ").decode()
        )
        cmdline = redact_text(raw_cmdline, (RENDERER_SECRET,))
        expected_executable = str((bundle / "bin/linux-amd64/python/bin/python3.12").resolve())
        require(
            str(Path(executable).resolve()) == expected_executable,
            f"service is not using packaged Python: {executable}",
        )
        result["process_identity"] = {
            "pid": process.pid,
            "executable": executable,
            "expected_executable": expected_executable,
            "cmdline": cmdline,
        }
        result["listeners"] = process_records
        result["internal_listener_audit"] = listeners_on_ports(INTERNAL_PORTS)
        result["transport"] = await static_transport_checks(fingerprint)
        if not no_auth:
            result["invalid_browser_auth"] = await invalid_browser_auth_check(fingerprint)
        browser_result, dj_result = await browser_protocol_check(
            fingerprint,
            renderer,
            rust_smoke_executable,
            forwarder,
            no_auth=no_auth,
        )
        result["browser_protocol"] = browser_result
        result["dj_matching_pin"] = dj_result
        if not no_auth:
            wrong_pin = ("0" if fingerprint[0] != "0" else "1") + fingerprint[1:]
            result["dj_mismatching_pin"] = await run_recorded_rust_dj(
                rust_smoke_executable,
                forwarder,
                mode="mismatch",
                fingerprint=wrong_pin,
            )
            result["pinned_plaintext_dj"] = await run_recorded_pinned_plaintext(
                rust_smoke_executable,
                plaintext_recorder,
                fingerprint,
            )
    finally:
        shutdown = await stop_service(process)
        process_output = await output_task
        log_handle.write(redact_bytes(process_output, (RENDERER_SECRET,)))
        log_handle.flush()
        result["shutdown"] = shutdown
        result["post_shutdown_listeners"] = await wait_for_listener_state(
            PUBLIC_PORTS | {METRICS_PORT, 8766}, present=False
        )
    require(shutdown["graceful"], f"{mode} service required forced termination")
    require(shutdown["returncode"] == 0, f"{mode} service exited {shutdown['returncode']}")
    return result


async def execute_smoke(arguments: argparse.Namespace, evidence: dict[str, Any]) -> None:
    archive = arguments.archive.resolve()
    rust_smoke_executable = arguments.rust_smoke_executable.resolve()
    require(archive.is_file(), f"release archive not found: {archive}")
    require(
        rust_smoke_executable.is_file(),
        f"Rust smoke executable not found: {rust_smoke_executable}",
    )
    evidence["archive"] = {
        "path": str(archive),
        "size_bytes": archive.stat().st_size,
        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
    }
    fixture_ports = {8765, DJ_TLS_RECORDER_PORT, PINNED_PLAINTEXT_RECORDER_PORT}
    preflight_ports = PUBLIC_PORTS | fixture_ports | {8766, METRICS_PORT}
    evidence["preflight_listeners"] = listeners_on_ports(preflight_ports | {9001})
    conflicts = [
        item for item in evidence["preflight_listeners"] if item["port"] in preflight_ports
    ]
    require(not conflicts, f"smoke ports are occupied; no process was stopped: {conflicts}")

    with tempfile.TemporaryDirectory(prefix="mcav-packaged-smoke-") as temporary_text:
        temporary = Path(temporary_text)
        bundle = verify_and_extract(archive, temporary / "extracted")
        certificate, private_key, fingerprint = generate_certificate(temporary)
        auth_file = temporary / "dj_auth.json"
        make_auth_config(auth_file)
        evidence["temporary_identity"] = {
            "certificate_sha256_fingerprint": fingerprint,
            "sans": ["IP:127.0.0.1", "DNS:localhost"],
            "trust_store_mutated": False,
        }

        renderer = FakeRenderer()
        forwarder = RecordingTlsForwarder(certificate, private_key)
        plaintext_recorder = PlaintextConnectionRecorder()
        try:
            await forwarder.start()
            await plaintext_recorder.start()
            await renderer.start()
            fixture_listeners = await wait_for_listener_state(fixture_ports, present=True)
            require(
                all(not item["public_bind"] for item in fixture_listeners),
                "smoke fixture was publicly bound",
            )
            evidence["fixture_listeners"] = fixture_listeners
            with arguments.log.open("wb") as log_handle:
                evidence["scenarios"] = [
                    await run_service_scenario(
                        bundle,
                        certificate,
                        private_key,
                        auth_file,
                        fingerprint,
                        renderer,
                        rust_smoke_executable,
                        forwarder,
                        plaintext_recorder,
                        log_handle,
                        no_auth=False,
                    ),
                    await run_service_scenario(
                        bundle,
                        certificate,
                        private_key,
                        auth_file,
                        fingerprint,
                        renderer,
                        rust_smoke_executable,
                        forwarder,
                        plaintext_recorder,
                        log_handle,
                        no_auth=True,
                    ),
                ]
        finally:
            await renderer.stop()
            await forwarder.stop()
            await plaintext_recorder.stop()

    evidence["post_cleanup_listeners"] = listeners_on_ports(
        PUBLIC_PORTS
        | {
            8765,
            8766,
            METRICS_PORT,
            9001,
            DJ_TLS_RECORDER_PORT,
            PINNED_PLAINTEXT_RECORDER_PORT,
        }
    )
    remaining_owned = [
        item
        for item in evidence["post_cleanup_listeners"]
        if item["port"]
        in PUBLIC_PORTS
        | {
            8765,
            8766,
            METRICS_PORT,
            DJ_TLS_RECORDER_PORT,
            PINNED_PLAINTEXT_RECORDER_PORT,
        }
    ]
    require(not remaining_owned, f"temporary smoke listeners remain: {remaining_owned}")
    evidence["cleanup"] = {
        "temporary_directory_removed": True,
        "temporary_certificates_removed": True,
        "temporary_processes_stopped": True,
        "temporary_ports_released": True,
        "unrelated_port_9001_untouched": [
            item for item in evidence["post_cleanup_listeners"] if item["port"] == 9001
        ],
    }


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--rust-smoke-executable", required=True, type=Path)
    arguments = parser.parse_args()
    arguments.output = arguments.output.resolve()
    arguments.log = arguments.log.resolve()
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.log.parent.mkdir(parents=True, exist_ok=True)
    return arguments


def main() -> int:
    arguments = parse_arguments()
    evidence: dict[str, Any] = {
        "schema_version": 1,
        "started_at": datetime.now(UTC).isoformat(),
        "command": shlex.join([sys.executable, str(Path(__file__).resolve()), *sys.argv[1:]]),
        "host": {"platform": sys.platform, "machine": os.uname().machine},
        "log_path": str(arguments.log),
    }
    status = 0
    try:
        asyncio.run(execute_smoke(arguments, evidence))
        evidence["status"] = "passed"
    except BaseException as error:
        status = 1
        evidence["status"] = "failed"
        evidence["error"] = {
            "type": type(error).__name__,
            "message": str(error),
            "traceback": traceback.format_exc(),
        }
    finally:
        evidence["finished_at"] = datetime.now(UTC).isoformat()
        arguments.output.write_text(
            json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    print(json.dumps({"status": evidence["status"], "evidence": str(arguments.output)}))
    if status:
        print(evidence["error"]["traceback"], file=sys.stderr)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
