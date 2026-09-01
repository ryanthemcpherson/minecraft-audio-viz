#!/usr/bin/env python3
"""Run the native, single-port MCAV managed-runtime release smoke."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import secrets
import socket
import sys
import time
from pathlib import Path
from typing import Any, Callable

import aiohttp
from aiohttp import WSMsgType
from websockets.asyncio.server import Server, ServerConnection, serve

ADMIN_USERNAME = "release_admin"
ADMIN_PASSWORD = "correct horse battery staple"  # nosec B105 -- release fixture only.
GENERATION = 42


def reserve_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


class FakePaperRenderer:
    def __init__(self, token: str) -> None:
        self.token = token
        self.server: Server | None = None
        self.connection: ServerConnection | None = None
        self.messages: list[dict[str, Any]] = []
        self.errors: list[BaseException] = []
        self._condition = asyncio.Condition()

    async def start(self) -> None:
        self.server = await serve(self._handle, "127.0.0.1", 0, max_size=10 * 1024 * 1024)

    @property
    def port(self) -> int:
        if self.server is None or not self.server.sockets:
            raise RuntimeError("fake Paper renderer is not bound")
        return int(self.server.sockets[0].getsockname()[1])

    async def close(self) -> None:
        if self.connection is not None:
            await self.connection.close()
        if self.server is not None:
            self.server.close()
            await self.server.wait_closed()

    async def send(self, payload: dict[str, Any]) -> None:
        if self.connection is None:
            raise RuntimeError("managed renderer is not authenticated")
        await self.connection.send(json.dumps(payload, separators=(",", ":")))

    async def wait_for(
        self,
        predicate: Callable[[dict[str, Any]], bool],
        *,
        timeout: float = 90.0,
    ) -> dict[str, Any]:
        async def observe() -> dict[str, Any]:
            async with self._condition:
                while True:
                    if self.errors:
                        raise self.errors[0]
                    for message in self.messages:
                        if predicate(message):
                            return message
                    await self._condition.wait()

        return await asyncio.wait_for(observe(), timeout=timeout)

    async def _record(self, message: dict[str, Any]) -> None:
        if message.get("type") != "batch_update":
            async with self._condition:
                self.messages.append(message)
                self._condition.notify_all()

    async def _handle(self, websocket: ServerConnection) -> None:
        try:
            await websocket.send('{"type":"connected","auth_required":true,"server_type":"paper"}')
            authentication = json.loads(await websocket.recv())
            if authentication != {"type": "auth", "token": self.token}:
                raise AssertionError("managed renderer received invalid authentication")
            self.connection = websocket
            await websocket.send('{"type":"auth_ok"}')
            async for raw in websocket:
                message = json.loads(raw)
                await self._record(message)
                response = self._response(message)
                if response is not None:
                    await websocket.send(json.dumps(response, separators=(",", ":")))
        except BaseException as error:
            if not isinstance(error, asyncio.CancelledError):
                async with self._condition:
                    self.errors.append(error)
                    self._condition.notify_all()

    @staticmethod
    def _response(message: dict[str, Any]) -> dict[str, Any] | None:
        message_type = message.get("type")
        sequence = message.get("_seq")
        if message_type == "ping":
            return {"type": "pong"}
        if sequence is None:
            return None
        if message_type == "get_zones":
            return {"type": "zones", "zones": [{"name": "main"}], "_seq": sequence}
        if message_type == "get_stages":
            return {"type": "stages", "stages": [], "_seq": sequence}
        if message_type == "init_pool":
            return {"type": "pool_initialized", "_seq": sequence}
        if message_type == "get_bitmap_patterns":
            return {"type": "bitmap_patterns", "patterns": [], "_seq": sequence}
        return {"type": "ok", "_seq": sequence}


async def receive_type(websocket: aiohttp.ClientWebSocketResponse, expected: str) -> dict[str, Any]:
    deadline = asyncio.get_running_loop().time() + 20.0
    while True:
        remaining = deadline - asyncio.get_running_loop().time()
        if remaining <= 0:
            raise TimeoutError(f"timed out waiting for WebSocket message {expected}")
        message = await asyncio.wait_for(websocket.receive(), timeout=remaining)
        if message.type != WSMsgType.TEXT:
            raise AssertionError(f"WebSocket closed before {expected}: {message.type}")
        payload = json.loads(message.data)
        if payload.get("type") == expected:
            return payload


async def wait_for_ready_or_exit(
    renderer: FakePaperRenderer,
    process: asyncio.subprocess.Process,
    secrets_to_redact: tuple[str, ...],
) -> dict[str, Any]:
    ready_task = asyncio.create_task(
        renderer.wait_for(lambda message: message.get("type") == "runtime_ready")
    )
    exit_task = asyncio.create_task(process.wait())
    done, _ = await asyncio.wait((ready_task, exit_task), return_when=asyncio.FIRST_COMPLETED)
    if exit_task in done:
        ready_task.cancel()
        await asyncio.gather(ready_task, return_exceptions=True)
        stdout_bytes, stderr_bytes = await process.communicate()
        output = (stdout_bytes + stderr_bytes).decode("utf-8", errors="replace")
        for secret in secrets_to_redact:
            output = output.replace(secret, "[REDACTED]")
        raise AssertionError(f"managed runtime exited before readiness:\n{output[-8000:]}")
    exit_task.cancel()
    await asyncio.gather(exit_task, return_exceptions=True)
    return ready_task.result()


async def exercise_one_port(
    base_url: str,
    setup_token: str,
) -> str:
    connector = aiohttp.TCPConnector(ssl=False)
    async with aiohttp.ClientSession(connector=connector) as session:
        async with session.get(f"{base_url}/healthz") as response:
            if response.status != 200 or await response.json() != {"status": "ok"}:
                raise AssertionError("managed TLS health endpoint is not ready")
        for path in ("/", "/preview/"):
            async with session.get(base_url + path) as response:
                if response.status != 200:
                    raise AssertionError(f"managed static route failed: {path}")
        async with session.get(f"{base_url}/setup/status") as response:
            if response.status != 200 or await response.json() != {"status": "available"}:
                raise AssertionError("managed empty-state setup is unavailable")
        async with session.post(
            f"{base_url}/setup/admin",
            json={
                "token": setup_token,
                "username": ADMIN_USERNAME,
                "password": ADMIN_PASSWORD,
            },
        ) as response:
            if response.status != 201 or (await response.json()).get("status") != "complete":
                raise AssertionError("managed initial admin setup failed")

        admin = await session.ws_connect(f"{base_url}/ws/admin", origin=base_url)
        preview = await session.ws_connect(f"{base_url}/ws/preview", origin=base_url)
        try:
            credentials = {
                "type": "vj_auth",
                "username": ADMIN_USERNAME,
                "password": ADMIN_PASSWORD,
            }
            await admin.send_json(credentials)
            await preview.send_json(credentials)
            await receive_type(admin, "auth_success")
            admin_state = await receive_type(admin, "vj_state")
            await receive_type(preview, "auth_success")
            preview_state = await receive_type(preview, "vj_state")
            if not admin_state.get("minecraft_connected") or not preview_state.get(
                "minecraft_connected"
            ):
                raise AssertionError("managed renderer state did not reach browser clients")

            await admin.send_json({"type": "generate_connect_code", "ttl_minutes": 5})
            invitation = await receive_type(admin, "connect_code_generated")
            if invitation.get("runtime", {}).get("public_url") != base_url:
                raise AssertionError("managed DJ invitation has the wrong public URL")
            if len(invitation.get("runtime", {}).get("certificate_sha256", "")) != 64:
                raise AssertionError("managed DJ invitation is missing its TLS fingerprint")

            dj = await session.ws_connect(f"{base_url}/ws/dj")
            try:
                await dj.send_json(
                    {
                        "type": "code_auth",
                        "code": invitation["code"],
                        "dj_name": "Release Smoke DJ",
                        "direct_mode": False,
                    }
                )
                pending = await receive_type(dj, "auth_pending")
                await admin.send_json({"type": "approve_dj", "dj_id": pending["dj_id"]})
                authenticated = await receive_type(dj, "auth_success")
                if authenticated.get("dj_id") != pending["dj_id"]:
                    raise AssertionError("managed DJ authentication did not complete")
            finally:
                await dj.close()
            return invitation["code"]
        finally:
            await preview.close()
            await admin.close()


async def run_smoke(runtime_root: Path, entrypoint: Path, release_version: str) -> None:
    runtime_root = runtime_root.resolve()
    entrypoint = entrypoint.resolve()
    if not runtime_root.is_dir() or not entrypoint.is_file():
        raise ValueError("runtime root or entrypoint is missing")
    if not entrypoint.is_relative_to(runtime_root):
        raise ValueError("runtime entrypoint is outside the runtime root")
    sys.path.insert(0, str(runtime_root))
    from vj_server.managed import default_parent_inspector

    parent_start_identity = default_parent_inspector().start_identity(os.getpid())
    if parent_start_identity is None:
        raise RuntimeError("unable to read native smoke parent identity")

    renderer_token = secrets.token_urlsafe(32)
    setup_token = secrets.token_urlsafe(32)
    launch_nonce = secrets.token_urlsafe(32)
    renderer = FakePaperRenderer(renderer_token)
    await renderer.start()
    public_port = reserve_port()
    state_directory = runtime_root / ".release-smoke-state"
    state_directory.mkdir()
    process: asyncio.subprocess.Process | None = None
    started_at = time.monotonic()
    environment = dict(os.environ)
    environment.update(
        {
            "MCAV_RENDERER_URL": f"ws://127.0.0.1:{renderer.port}",
            "MCAV_RENDERER_TOKEN": renderer_token,
            "MCAV_SETUP_TOKEN": setup_token,
            "MCAV_LAUNCH_NONCE": launch_nonce,
            "MCAV_LAUNCH_GENERATION": str(GENERATION),
            "MCAV_RUNTIME_API": "1",
            "MCAV_RELEASE_VERSION": release_version,
            "MCAV_STATE_DIR": str(state_directory),
            "MCAV_PARENT_PID": str(os.getpid()),
            "MCAV_PARENT_START_ID": parent_start_identity,
            "MCAV_TLS_MODE": "GENERATED",
            "MCAV_PUBLIC_URL": f"https://127.0.0.1:{public_port}/",
            "MCAV_RESOURCE_PROFILE": "BALANCED",
            "MCAV_PERFORMANCE_PROFILE": "BALANCED",
            "MCAV_ENTITY_BUDGET": "256",
            "MCAV_TARGET_RENDER_FPS": "20",
            "PYTHONDONTWRITEBYTECODE": "1",
            "OPENBLAS_NUM_THREADS": "1",
            "OMP_NUM_THREADS": "1",
            "MKL_NUM_THREADS": "1",
            "NUMEXPR_NUM_THREADS": "1",
            "VECLIB_MAXIMUM_THREADS": "1",
        }
    )
    process = await asyncio.create_subprocess_exec(
        str(entrypoint),
        "-m",
        "vj_server.cli",
        "--project-root",
        str(runtime_root),
        "--managed-by-paper",
        "--public-host",
        "127.0.0.1",
        "--public-port",
        str(public_port),
        cwd=runtime_root,
        env=environment,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        ready = await wait_for_ready_or_exit(
            renderer,
            process,
            (renderer_token, setup_token, launch_nonce, ADMIN_PASSWORD),
        )
        if (
            ready.get("generation") != GENERATION
            or ready.get("launch_nonce") != launch_nonce
            or ready.get("release_version") != release_version
            or set(ready.get("capabilities", [])) != {"health", "performance", "shutdown"}
        ):
            raise AssertionError("managed readiness payload is invalid")
        base_url = f"https://127.0.0.1:{public_port}"
        invitation_code = await exercise_one_port(base_url, setup_token)
        await renderer.send(
            {
                "type": "runtime_performance",
                "generation": GENERATION,
                "level": "FPS_REDUCED",
                "target_fps": 15,
                "entity_budget": 32,
                "particles_enabled": False,
            }
        )
        await renderer.wait_for(
            lambda message: message.get("type") == "init_pool" and message.get("count") == 32,
            timeout=20.0,
        )
        health = await renderer.wait_for(
            lambda message: message.get("type") == "runtime_health", timeout=20.0
        )
        if not all(
            (
                health.get("renderer_connected"),
                health.get("ingress_healthy"),
                health.get("process_alive"),
            )
        ):
            raise AssertionError("managed runtime health payload is unhealthy")
        await renderer.send(
            {
                "type": "runtime_shutdown",
                "generation": GENERATION,
                "reason": "PLUGIN_DISABLE",
            }
        )
        stdout_bytes, stderr_bytes = await asyncio.wait_for(process.communicate(), timeout=30.0)
        if process.returncode != 0:
            raise AssertionError(f"managed shutdown exited with {process.returncode}")
        output = (stdout_bytes + stderr_bytes).decode("utf-8", errors="replace")
        for secret in (
            renderer_token,
            setup_token,
            launch_nonce,
            ADMIN_PASSWORD,
            invitation_code,
        ):
            if secret in output:
                raise AssertionError("managed runtime output leaked a release smoke secret")
        if time.monotonic() - started_at >= 150.0:
            raise AssertionError("managed release smoke exceeded the startup/shutdown budget")
    finally:
        if process is not None and process.returncode is None:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=5.0)
            except TimeoutError:
                process.kill()
                await process.wait()
        await asyncio.wait_for(renderer.close(), timeout=5.0)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--entrypoint", type=Path, required=True)
    parser.add_argument("--release-version", required=True)
    arguments = parser.parse_args()
    try:
        asyncio.run(
            run_smoke(arguments.runtime_root, arguments.entrypoint, arguments.release_version)
        )
    except (AssertionError, OSError, RuntimeError, TimeoutError, ValueError) as error:
        parser.exit(1, f"native managed-runtime smoke failed: {error}\n")
    print('{"status":"verified"}')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
