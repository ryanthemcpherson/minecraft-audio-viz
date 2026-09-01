"""End-to-end smoke test for the real Paper-managed runtime payload."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import secrets
import shutil
import socket
import subprocess  # nosec B404 -- the release smoke invokes fixed local verification tools.
import tempfile
import time
from pathlib import Path
from typing import Any, Callable

import aiohttp
import pytest
from aiohttp import WSMsgType
from websockets.asyncio.server import Server, ServerConnection, serve

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIRECTORY.parent.parent
PLUGIN_ROOT = REPOSITORY_ROOT / "minecraft_plugin"
DEFAULT_ARTIFACT_DIRECTORY = REPOSITORY_ROOT / "dist/runtime-dev"
PLATFORM = "linux-x86_64"
ADMIN_USERNAME = "release_admin"
ADMIN_PASSWORD = "correct horse battery staple"  # nosec B105 -- non-production fixture.
GENERATION = 42


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def wsl_to_windows(path: Path) -> str:
    return subprocess.run(  # nosec B603 -- fixed WSL path translator.
        ["wslpath", "-w", str(path.resolve())],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()


def java_toolchain() -> tuple[Path, bool]:
    native_java = shutil.which("java")
    if native_java:
        version = subprocess.run(  # nosec B603 -- discovered local Java executable.
            [native_java, "-version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=True,
        ).stdout
        if any(f'version "{major}' in version for major in range(21, 30)):
            return Path(native_java), False

    configured = os.environ.get("MCAV_JAVA_HOME")
    candidates = []
    if configured:
        candidates.append(Path(configured) / "bin/java.exe")
    candidates.extend(
        sorted(
            Path("/mnt/c/Program Files/Eclipse Adoptium").glob("jdk-21*/bin/java.exe"),
            reverse=True,
        )
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate, True
    raise RuntimeError("Java 21 is required for the managed-runtime verifier")


def compile_verifier(java: Path, windows: bool) -> str:
    if windows:
        java_home = wsl_to_windows(java.parent.parent)
        environment = dict(os.environ)
        environment["JAVA_HOME"] = java_home
        environment["WSLENV"] = ":".join(
            value for value in (environment.get("WSLENV"), "JAVA_HOME") if value
        )
        subprocess.run(  # nosec B603 -- fixed Maven wrapper command.
            [
                "cmd.exe",
                "/d",
                "/c",
                "call mvnw.cmd -q test-compile dependency:build-classpath "
                '"-Dmdep.outputFile=target/test-classpath.txt"',
            ],
            check=True,
            cwd=PLUGIN_ROOT,
            env=environment,
        )
        dependencies = (
            (PLUGIN_ROOT / "target/test-classpath.txt").read_text(encoding="utf-8").strip()
        )
        return ";".join(
            (
                wsl_to_windows(PLUGIN_ROOT / "target/test-classes"),
                wsl_to_windows(PLUGIN_ROOT / "target/classes"),
                dependencies,
            )
        )

    subprocess.run(  # nosec B603 -- fixed Maven wrapper command.
        [
            str(PLUGIN_ROOT / "mvnw"),
            "-q",
            "test-compile",
            "dependency:build-classpath",
            "-Dmdep.outputFile=target/test-classpath.txt",
        ],
        check=True,
        cwd=PLUGIN_ROOT,
    )
    dependencies = (PLUGIN_ROOT / "target/test-classpath.txt").read_text(encoding="utf-8").strip()
    return ":".join(
        (
            str(PLUGIN_ROOT / "target/test-classes"),
            str(PLUGIN_ROOT / "target/classes"),
            dependencies,
        )
    )


def extract_with_java(
    java: Path,
    windows: bool,
    classpath: str,
    archive: Path,
    metadata: dict[str, Any],
    verifier_root: Path,
) -> tuple[Path, Path]:
    def tool_path(path: Path) -> str:
        return wsl_to_windows(path) if windows else str(path)

    result = subprocess.run(  # nosec B603 -- fixed Java verifier class and validated paths.
        [
            str(java),
            "-cp",
            classpath,
            "com.audioviz.runtime.store.RuntimeArchiveVerifierCli",
            tool_path(archive),
            tool_path(verifier_root),
            metadata["platform"],
            str(metadata["archive_size"]),
            str(metadata["uncompressed_size"]),
            metadata["sha256"],
            metadata["files_manifest_sha256"],
            metadata["entrypoint"],
        ],
        check=True,
        cwd=PLUGIN_ROOT,
        stdout=subprocess.PIPE,
        text=True,
    )
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if len(lines) != 2:
        raise RuntimeError(f"unexpected Java verifier output: {result.stdout!r}")
    if windows:
        converted = [
            subprocess.run(  # nosec B603 -- fixed WSL path translator.
                ["wslpath", "-u", line],
                check=True,
                stdout=subprocess.PIPE,
                text=True,
            ).stdout.strip()
            for line in lines
        ]
        return Path(converted[0]), Path(converted[1])
    return Path(lines[0]), Path(lines[1])


def linux_process_start_identity(pid: int) -> str:
    fields = Path(f"/proc/{pid}/stat").read_text(encoding="ascii").split()
    return fields[21]


def reserve_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def assert_default_metrics_port_available() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 9001))


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
        assert self.server is not None and self.server.sockets
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
        timeout: float = 60.0,
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
            await websocket.send(
                json.dumps(
                    {"type": "connected", "auth_required": True, "server_type": "paper"},
                    separators=(",", ":"),
                )
            )
            auth = json.loads(await websocket.recv())
            if auth != {"type": "auth", "token": self.token}:
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
        renderer.wait_for(lambda message: message.get("type") == "runtime_ready", timeout=90.0)
    )
    exit_task = asyncio.create_task(process.wait())
    done, _pending = await asyncio.wait(
        (ready_task, exit_task),
        return_when=asyncio.FIRST_COMPLETED,
    )
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


def verify_extracted_inventory(runtime_root: Path) -> None:
    manifest_path = runtime_root / "files.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    declared = {item["path"]: item for item in manifest["files"]}
    observed = {
        path.relative_to(runtime_root).as_posix()
        for path in runtime_root.rglob("*")
        if path.is_file()
    }
    assert observed == set(declared) | {"files.json"}
    assert not any(path.is_symlink() for path in runtime_root.rglob("*"))
    for relative, item in declared.items():
        path = runtime_root / relative
        assert path.stat().st_size == item["size"]
        assert sha256_file(path) == item["sha256"]


@pytest.mark.asyncio
async def test_real_managed_runtime_end_to_end() -> None:
    artifact_directory = Path(
        os.environ.get("MCAV_RUNTIME_ARTIFACT_DIR", DEFAULT_ARTIFACT_DIRECTORY)
    ).resolve()
    archive = artifact_directory / f"mcav-runtime-{PLATFORM}.zip"
    metadata_path = artifact_directory / f"mcav-runtime-{PLATFORM}.artifact.json"
    assert archive.is_file(), f"build the managed runtime first: {archive}"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    assert metadata["platform"] == PLATFORM
    assert archive.stat().st_size == metadata["archive_size"]
    assert sha256_file(archive) == metadata["sha256"]

    java, windows_java = java_toolchain()
    classpath = compile_verifier(java, windows_java)
    assert_default_metrics_port_available()
    renderer_token = secrets.token_urlsafe(32)
    setup_token = secrets.token_urlsafe(32)
    launch_nonce = secrets.token_urlsafe(32)
    renderer = FakePaperRenderer(renderer_token)
    await renderer.start()
    public_port = reserve_port()
    process: asyncio.subprocess.Process | None = None

    with tempfile.TemporaryDirectory(prefix="managed-runtime-") as root:
        temporary_root = Path(root)
        verifier_root = temporary_root / "verifier"
        verifier_root.mkdir()
        state_directory = temporary_root / "state"
        state_directory.mkdir()
        runtime_root, entrypoint = extract_with_java(
            java,
            windows_java,
            classpath,
            archive,
            metadata,
            verifier_root,
        )
        assert entrypoint == runtime_root / metadata["entrypoint"]
        if windows_java:
            # Windows cannot apply POSIX modes through the WSL UNC bridge;
            # Linux RuntimeArchiveVerifier extraction applies this exact mode.
            entrypoint.chmod(0o700)

        environment = {
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "MCAV_RENDERER_URL": f"ws://127.0.0.1:{renderer.port}",
            "MCAV_RENDERER_TOKEN": renderer_token,
            "MCAV_SETUP_TOKEN": setup_token,
            "MCAV_LAUNCH_NONCE": launch_nonce,
            "MCAV_LAUNCH_GENERATION": str(GENERATION),
            "MCAV_RUNTIME_API": "1",
            "MCAV_RELEASE_VERSION": "1.2.0",
            "MCAV_STATE_DIR": str(state_directory),
            "MCAV_PARENT_PID": str(os.getpid()),
            "MCAV_PARENT_START_ID": linux_process_start_identity(os.getpid()),
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
        started_at = time.monotonic()
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
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            ready = await wait_for_ready_or_exit(
                renderer,
                process,
                (renderer_token, setup_token, launch_nonce, ADMIN_PASSWORD),
            )
            assert ready["generation"] == GENERATION
            assert ready["launch_nonce"] == launch_nonce
            assert ready["release_version"] == "1.2.0"
            assert set(ready["capabilities"]) == {"health", "performance", "shutdown"}

            connector = aiohttp.TCPConnector(ssl=False)
            base_url = f"https://127.0.0.1:{public_port}"
            async with aiohttp.ClientSession(connector=connector) as session:
                async with session.get(f"{base_url}/healthz") as response:
                    assert response.status == 200
                    assert await response.json() == {"status": "ok"}
                async with session.get(f"{base_url}/") as response:
                    assert response.status == 200
                async with session.get(f"{base_url}/preview/") as response:
                    assert response.status == 200
                async with session.get(f"{base_url}/setup/status") as response:
                    assert response.status == 200
                    assert await response.json() == {"status": "available"}
                async with session.post(
                    f"{base_url}/setup/verify", json={"token": setup_token}
                ) as response:
                    assert response.status == 200
                    assert await response.json() == {"valid": True}
                async with session.post(
                    f"{base_url}/setup/admin",
                    json={
                        "token": setup_token,
                        "username": ADMIN_USERNAME,
                        "password": ADMIN_PASSWORD,
                    },
                ) as response:
                    assert response.status == 201
                    assert (await response.json())["status"] == "complete"

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
                    assert (await receive_type(admin, "auth_success"))["type"] == "auth_success"
                    assert (await receive_type(admin, "vj_state"))["minecraft_connected"] is True
                    assert (await receive_type(preview, "auth_success"))["type"] == "auth_success"
                    assert (await receive_type(preview, "vj_state"))["minecraft_connected"] is True

                    await admin.send_json({"type": "generate_connect_code", "ttl_minutes": 5})
                    invite = await receive_type(admin, "connect_code_generated")
                    assert invite["runtime"]["public_url"] == base_url
                    assert len(invite["runtime"]["certificate_sha256"]) == 64

                    dj = await session.ws_connect(f"{base_url}/ws/dj")
                    try:
                        await dj.send_json(
                            {
                                "type": "code_auth",
                                "code": invite["code"],
                                "dj_name": "Release Smoke DJ",
                                "direct_mode": False,
                            }
                        )
                        pending = await receive_type(dj, "auth_pending")
                        await admin.send_json({"type": "approve_dj", "dj_id": pending["dj_id"]})
                        authenticated = await receive_type(dj, "auth_success")
                        assert authenticated["dj_id"] == pending["dj_id"]
                    finally:
                        await dj.close()
                finally:
                    await preview.close()
                    await admin.close()

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
                lambda message: message.get("type") == "runtime_health",
                timeout=20.0,
            )
            assert health["generation"] == GENERATION
            assert health["renderer_connected"] is True
            assert health["ingress_healthy"] is True
            assert health["process_alive"] is True

            await renderer.send(
                {
                    "type": "runtime_shutdown",
                    "generation": GENERATION,
                    "reason": "PLUGIN_DISABLE",
                }
            )
            stdout_bytes, stderr_bytes = await asyncio.wait_for(process.communicate(), timeout=30.0)
            assert process.returncode == 0
            output = (stdout_bytes + stderr_bytes).decode("utf-8", errors="replace")
            for secret in (
                renderer_token,
                setup_token,
                launch_nonce,
                ADMIN_PASSWORD,
                invite["code"],
            ):
                assert secret not in output
            verify_extracted_inventory(runtime_root)
            assert time.monotonic() - started_at < 150.0
        finally:
            if process.returncode is None:
                process.terminate()
                try:
                    await asyncio.wait_for(process.wait(), timeout=5.0)
                except TimeoutError:
                    process.kill()
                    await process.wait()
            await asyncio.wait_for(renderer.close(), timeout=5.0)
