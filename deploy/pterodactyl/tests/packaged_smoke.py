#!/usr/bin/env python3
"""Retained, structured smoke test for the packaged two-port deployment."""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import os
import re
import shlex
import signal
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import time
import traceback
import zipfile
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import aiohttp
import websockets
from websockets.exceptions import ConnectionClosed
from yarl import URL

DEPLOY_ROOT = Path(__file__).resolve().parents[1]
PUBLIC_ORIGIN = "https://127.0.0.1:8080"
PUBLIC_PORTS = {8080, 25808}
INTERNAL_PORTS = {8765, 8766, 9001}
METRICS_PORT = 19001
RENDERER_SECRET = "packaged-smoke-renderer-secret"
VJ_USERNAME = "smoke-vj"
VJ_PASSWORD = "packaged-smoke-vj-password"
DJ_ID = "smoke-dj"
DJ_PASSWORD = "packaged-smoke-dj-password"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


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
            require(
                entry.filename.startswith("mcav-vj/")
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


def _receive_exact(stream: ssl.SSLSocket, length: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < length:
        chunk = stream.recv(length - len(chunks))
        if not chunk:
            raise ConnectionError("WebSocket closed before the frame completed")
        chunks.extend(chunk)
    return bytes(chunks)


def _send_client_frame(stream: socket.socket, opcode: int, payload: bytes) -> int:
    first = 0x80 | opcode
    mask = os.urandom(4)
    length = len(payload)
    if length < 126:
        header = bytes((first, 0x80 | length))
    elif length < 65_536:
        header = bytes((first, 0x80 | 126)) + struct.pack("!H", length)
    else:
        header = bytes((first, 0x80 | 127)) + struct.pack("!Q", length)
    masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    frame = header + mask + masked
    stream.sendall(frame)
    return len(frame)


def _receive_server_frame(stream: ssl.SSLSocket) -> tuple[int, bytes]:
    first, second = _receive_exact(stream, 2)
    opcode = first & 0x0F
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", _receive_exact(stream, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", _receive_exact(stream, 8))[0]
    mask = _receive_exact(stream, 4) if second & 0x80 else None
    payload = _receive_exact(stream, length)
    if mask is not None:
        payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    return opcode, payload


def _receive_json_type(stream: ssl.SSLSocket, expected_type: str) -> tuple[dict[str, Any], int]:
    frames = 0
    while frames < 30:
        opcode, payload = _receive_server_frame(stream)
        frames += 1
        if opcode == 0x9:
            _send_client_frame(stream, 0xA, payload)
            continue
        if opcode == 0x8:
            raise ConnectionError(f"WebSocket closed while waiting for {expected_type}")
        if opcode != 0x1:
            continue
        message = json.loads(payload)
        if message.get("type") == expected_type:
            return message, frames
    raise AssertionError(f"did not receive WebSocket message type {expected_type}")


def run_pinned_dj(fingerprint: str, *, expect_match: bool, no_auth: bool) -> dict[str, Any]:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    application_bytes = 0
    with socket.create_connection(("127.0.0.1", 25808), timeout=5.0) as raw_socket:
        with context.wrap_socket(raw_socket, server_hostname="127.0.0.1") as secure_socket:
            secure_socket.settimeout(5.0)
            actual = hashlib.sha256(secure_socket.getpeercert(binary_form=True)).hexdigest()
            pin_matches = actual == fingerprint
            if not pin_matches:
                require(not expect_match, "certificate pin unexpectedly mismatched")
                return {
                    "tls_established": True,
                    "pin_matches": False,
                    "expected_fingerprint": fingerprint,
                    "actual_fingerprint": actual,
                    "post_tls_application_bytes": 0,
                }
            require(expect_match, "certificate pin unexpectedly matched")

            websocket_key = base64.b64encode(os.urandom(16)).decode()
            request = (
                "GET / HTTP/1.1\r\n"
                "Host: 127.0.0.1:25808\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {websocket_key}\r\n"
                "Sec-WebSocket-Version: 13\r\n\r\n"
            ).encode("ascii")
            secure_socket.sendall(request)
            application_bytes += len(request)
            response = bytearray()
            while b"\r\n\r\n" not in response:
                response.extend(secure_socket.recv(4096))
            require(response.startswith(b"HTTP/1.1 101"), "DJ WebSocket upgrade failed")

            auth = {
                "type": "dj_auth",
                "dj_id": DJ_ID,
                "dj_key": "ignored-in-no-auth" if no_auth else DJ_PASSWORD,
                "dj_name": "Packaged Smoke DJ",
            }
            application_bytes += _send_client_frame(
                secure_socket, 0x1, json.dumps(auth, separators=(",", ":")).encode()
            )
            auth_success, _ = _receive_json_type(secure_socket, "auth_success")
            clock_request, _ = _receive_json_type(secure_socket, "clock_sync_request")
            server_time = clock_request["server_time"]
            clock_response = {
                "type": "clock_sync_response",
                "dj_recv_time": server_time,
                "dj_send_time": server_time,
            }
            application_bytes += _send_client_frame(
                secure_socket,
                0x1,
                json.dumps(clock_response, separators=(",", ":")).encode(),
            )
            stream_route, _ = _receive_json_type(secure_socket, "stream_route")
            audio_frame = {
                "type": "dj_audio_frame",
                "bands": [0.8, 0.6, 0.4, 0.2, 0.1],
                "peak": 0.9,
                "beat": True,
                "bpm": 128.0,
                "beat_i": 0.8,
                "i_bass": 0.7,
                "i_kick": True,
                "seq": 1,
                "ts": time.time(),
                "tempo_conf": 0.9,
                "beat_phase": 0.0,
            }
            application_bytes += _send_client_frame(
                secure_socket,
                0x1,
                json.dumps(audio_frame, separators=(",", ":")).encode(),
            )
            time.sleep(0.5)
            application_bytes += _send_client_frame(secure_socket, 0x8, b"")
            return {
                "tls_established": True,
                "pin_matches": True,
                "expected_fingerprint": fingerprint,
                "actual_fingerprint": actual,
                "post_tls_application_bytes": application_bytes,
                "auth_success": auth_success.get("dj_id") == DJ_ID,
                "clock_sync": clock_request.get("type") == "clock_sync_request",
                "stream_route": stream_route.get("route_mode") == "relay",
                "audio_frame_sent": True,
            }


def run_plaintext_probe() -> dict[str, Any]:
    request = (
        "GET / HTTP/1.1\r\nHost: 127.0.0.1:25808\r\n"
        "Upgrade: websocket\r\nConnection: Upgrade\r\n"
        "Sec-WebSocket-Key: cGxhaW50ZXh0LXByb2JlIQ==\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    ).encode("ascii")
    response = b""
    with socket.create_connection(("127.0.0.1", 25808), timeout=3.0) as stream:
        stream.settimeout(2.0)
        stream.sendall(request)
        try:
            response = stream.recv(4096)
        except (ConnectionResetError, TimeoutError, socket.timeout):
            response = b""
    return {
        "plaintext_bytes_sent": len(request),
        "response_prefix": response[:80].decode("ascii", errors="replace"),
        "websocket_upgrade_refused": b"101 Switching Protocols" not in response,
    }


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
                asyncio.to_thread(
                    run_pinned_dj,
                    fingerprint,
                    expect_match=True,
                    no_auth=no_auth,
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
        RENDERER_SECRET,
        "--broadcast-port",
        "8766",
        "--http-host",
        "0.0.0.0",
        "--http-port",
        "8080",
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
    log_handle: Any,
    *,
    no_auth: bool,
) -> dict[str, Any]:
    mode = "no-auth" if no_auth else "auth"
    occupied = listeners_on_ports(PUBLIC_PORTS | {METRICS_PORT, 8766})
    require(not occupied, f"ports required for {mode} service are occupied: {occupied}")
    command = service_command(bundle, certificate, private_key, auth_file, no_auth=no_auth)
    log_handle.write(f"\n=== packaged service: {mode} ===\n".encode())
    log_handle.write(f"command: {shlex.join(command)}\n".encode())
    log_handle.flush()
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    environment["PYTHONUNBUFFERED"] = "1"
    environment["MCAV_ASYNC_LUA"] = "0"
    process = await asyncio.create_subprocess_exec(
        *command,
        cwd=bundle,
        env=environment,
        stdout=log_handle,
        stderr=asyncio.subprocess.STDOUT,
    )
    shutdown: dict[str, Any] | None = None
    result: dict[str, Any] = {"mode": mode, "command": command, "pid": process.pid}
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
        cmdline = Path(f"/proc/{process.pid}/cmdline").read_bytes().replace(b"\0", b" ").decode()
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
            fingerprint, renderer, no_auth=no_auth
        )
        result["browser_protocol"] = browser_result
        result["dj_matching_pin"] = dj_result
        if not no_auth:
            wrong_pin = ("0" if fingerprint[0] != "0" else "1") + fingerprint[1:]
            result["dj_mismatching_pin"] = await asyncio.to_thread(
                run_pinned_dj, wrong_pin, expect_match=False, no_auth=False
            )
            result["plaintext_dj"] = await asyncio.to_thread(run_plaintext_probe)
            require(
                result["dj_mismatching_pin"]["post_tls_application_bytes"] == 0,
                "mismatching DJ pin sent application bytes",
            )
            require(
                result["plaintext_dj"]["websocket_upgrade_refused"],
                "plaintext DJ WebSocket was accepted",
            )
    finally:
        shutdown = await stop_service(process)
        result["shutdown"] = shutdown
        result["post_shutdown_listeners"] = await wait_for_listener_state(
            PUBLIC_PORTS | {METRICS_PORT, 8766}, present=False
        )
    require(shutdown["graceful"], f"{mode} service required forced termination")
    require(shutdown["returncode"] == 0, f"{mode} service exited {shutdown['returncode']}")
    return result


async def execute_smoke(arguments: argparse.Namespace, evidence: dict[str, Any]) -> None:
    archive = arguments.archive.resolve()
    require(archive.is_file(), f"release archive not found: {archive}")
    evidence["archive"] = {
        "path": str(archive),
        "size_bytes": archive.stat().st_size,
        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
    }
    preflight_ports = PUBLIC_PORTS | {8765, 8766, METRICS_PORT}
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
        await renderer.start()
        try:
            renderer_listener = await wait_for_listener_state({8765}, present=True)
            require(
                all(not item["public_bind"] for item in renderer_listener),
                "renderer fixture was publicly bound",
            )
            evidence["renderer_listener"] = renderer_listener
            with arguments.log.open("wb") as log_handle:
                evidence["scenarios"] = [
                    await run_service_scenario(
                        bundle,
                        certificate,
                        private_key,
                        auth_file,
                        fingerprint,
                        renderer,
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
                        log_handle,
                        no_auth=True,
                    ),
                ]
        finally:
            await renderer.stop()

    evidence["post_cleanup_listeners"] = listeners_on_ports(
        PUBLIC_PORTS | {8765, 8766, METRICS_PORT, 9001}
    )
    remaining_owned = [
        item
        for item in evidence["post_cleanup_listeners"]
        if item["port"] in PUBLIC_PORTS | {8765, 8766, METRICS_PORT}
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
