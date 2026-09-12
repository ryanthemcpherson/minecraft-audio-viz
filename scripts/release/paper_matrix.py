"""Real Paper render/restart gate. Run with WSL/Linux Python 3.12+.

This is a renderer lifecycle gate, not managed-runtime installation or visual QA.
It never reuses/deletes a server directory and only binds loopback listeners.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import math
import os
import platform
import re
import shutil
import signal
import socket
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

import websockets

ROOT = Path(__file__).resolve().parents[2]
LOCK_PATH = ROOT / "deploy/paper/paper-lock.json"
USER_AGENT = "mcav-paper-acceptance/1.0 (https://github.com/ryanthemcpherson/minecraft-audio-viz)"
ENTITY_COUNT = 8
STAGE = "acceptance"
ZONE = "acceptance_main_stage"


class GateError(RuntimeError):
    """An unmet acceptance criterion; never converted into a successful result."""


def require_https(url: str) -> None:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise GateError("Artifact downloads require HTTPS without embedded credentials")


class HttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(
        self,
        request: urllib.request.Request,
        stream: Any,
        code: int,
        message: str,
        headers: Any,
        new_url: str,
    ) -> urllib.request.Request | None:
        require_https(new_url)
        return super().redirect_request(request, stream, code, message, headers, new_url)


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verified_download(spec: dict[str, Any], cache: Path, suffix: str) -> Path:
    digest = spec["sha256"]
    if not re.fullmatch(r"[a-f0-9]{64}", digest):
        raise GateError("Lock requires lowercase SHA-256")
    require_https(spec["url"])
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / f"{digest}{suffix}"
    if not destination.exists():
        request = urllib.request.Request(spec["url"], headers={"User-Agent": USER_AGENT})
        # Partial downloads are retained separately and never executed.
        with tempfile.NamedTemporaryFile(dir=cache, suffix=".partial", delete=False) as output:
            partial = Path(output.name)
            opener = urllib.request.build_opener(HttpsRedirectHandler())
            with opener.open(request, timeout=60) as response:
                shutil.copyfileobj(response, output)
        if sha256(partial) != digest:
            raise GateError(f"Download digest mismatch: {partial}")
        partial.replace(destination)
    if sha256(destination) != digest:
        raise GateError(f"Cached artifact digest mismatch: {destination}")
    return destination


def prepare(cache: Path, lock: dict[str, Any]) -> tuple[Path, Path]:
    if platform.system() != "Linux" or platform.machine() not in {"x86_64", "AMD64"}:
        raise GateError("This pinned harness requires Linux x86_64 (use WSL on Windows)")
    paper = verified_download(lock["paper"], cache, ".jar")
    java_archive = verified_download(lock["java"], cache, ".tar.gz")
    # Extract verified bytes anew: do not trust a previously modified JDK tree.
    java_dir = Path(tempfile.mkdtemp(prefix="jdk-", dir=cache))
    with tarfile.open(java_archive) as archive:
        archive.extractall(java_dir, filter="data")
    candidates = list(java_dir.glob("*/bin/java"))
    if len(candidates) != 1:
        raise GateError("Pinned JDK does not contain exactly one bin/java")
    return paper, candidates[0]


def free_ports(count: int) -> list[int]:
    sockets = [socket.socket() for _ in range(count)]
    try:
        for listener in sockets:
            listener.bind(("127.0.0.1", 0))
        return [listener.getsockname()[1] for listener in sockets]
    finally:
        for listener in sockets:
            listener.close()


def write_fixture(directory: Path, plugin: Path, paper: Path, ports: list[int]) -> None:
    """Called only for the harness-created empty directory."""
    if any(directory.iterdir()):
        raise GateError("Refusing to populate a nonempty server directory")
    data = directory / "plugins/AudioViz"
    data.mkdir(parents=True)
    shutil.copyfile(plugin, directory / "plugins/AudioViz.jar")
    shutil.copyfile(paper, directory / "paper.jar")
    (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (directory / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={ports[0]}\n"
        "online-mode=true\nenable-rcon=false\nenable-query=false\n"
        "level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
        'generator-settings={"biome":"minecraft:plains","layers":['
        '{"block":"minecraft:bedrock","height":1},'
        '{"block":"minecraft:dirt","height":2},'
        '{"block":"minecraft:grass_block","height":1}]}\n'
        "spawn-protection=0\nview-distance=2\nsimulation-distance=2\n"
        "max-players=1\nwhite-list=true\nallow-nether=false\n"
        "pause-when-empty-seconds=-1\n",
        encoding="utf-8",
    )
    (data / "config.yml").write_text(
        "websocket:\n  address: '127.0.0.1'\n"
        f"  port: {ports[1]}\nws-secret: ''\n"
        "runtime:\n  enabled: false\n"
        f"defaults:\n  entity_count: {ENTITY_COUNT}\n"
        f"performance:\n  max_entities_per_zone: {ENTITY_COUNT}\n",
        encoding="utf-8",
    )


class PaperProcess:
    def __init__(self, java: Path, directory: Path, label: str, timeout: float) -> None:
        self.java, self.directory, self.label, self.timeout = java, directory, label, timeout
        self.process: asyncio.subprocess.Process | None = None
        self.reader: asyncio.Task | None = None
        self.lines: asyncio.Queue[str | None] = asyncio.Queue(maxsize=2048)

    async def _read(self) -> None:
        assert self.process is not None and self.process.stdout is not None
        with (self.directory / f"{self.label}.log").open("w", encoding="utf-8") as log:
            while line := await self.process.stdout.readline():
                text = line.decode("utf-8", errors="replace").rstrip()
                log.write(text + "\n")
                log.flush()
                if self.lines.full():
                    self.lines.get_nowait()
                self.lines.put_nowait(text)
        if self.lines.full():
            self.lines.get_nowait()
        self.lines.put_nowait(None)

    async def line(self, deadline: float) -> str:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise GateError(f"{self.label}: console timeout; inspect retained log")
        try:
            line = await asyncio.wait_for(self.lines.get(), remaining)
        except TimeoutError as error:
            raise GateError(f"{self.label}: console timeout; inspect retained log") from error
        if line is None:
            raise GateError(f"{self.label}: server exited; inspect retained log")
        return line

    async def start(self) -> None:
        self.process = await asyncio.create_subprocess_exec(
            str(self.java),
            "-Xms512M",
            "-Xmx1536M",
            "-XX:ActiveProcessorCount=2",
            "-Dterminal.jline=false",
            "-Dterminal.ansi=false",
            "-jar",
            "paper.jar",
            "nogui",
            cwd=self.directory,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
            start_new_session=True,
        )
        self.reader = asyncio.create_task(self._read())
        deadline = time.monotonic() + self.timeout
        while "Done (" not in await self.line(deadline):
            pass

    async def send(self, command: str) -> None:
        assert self.process is not None and self.process.stdin is not None
        self.process.stdin.write((command + "\n").encode())
        await self.process.stdin.drain()

    async def command(self, command: str, response_pattern: str) -> list[str]:
        # Paper's asynchronous chat can reorder `say` markers around console data.
        # Keep exactly one query outstanding and await its actual response instead.
        while not self.lines.empty():
            if self.lines.get_nowait() is None:
                raise GateError(f"{self.label}: server exited before console query")
        await self.send(command)
        deadline = time.monotonic() + 15
        while True:
            line = await self.line(deadline)
            if re.search(response_pattern, line):
                return [line]

    async def stop(self) -> None:
        if self.process is None:
            return
        try:
            if self.process.returncode is None:
                await self.send("stop")
                try:
                    await asyncio.wait_for(self.process.wait(), 45)
                except TimeoutError as error:
                    os.killpg(self.process.pid, signal.SIGKILL)
                    await self.process.wait()
                    raise GateError(f"{self.label}: forced shutdown after timeout") from error
            if self.process.returncode != 0:
                raise GateError(f"{self.label}: server exit code {self.process.returncode}")
        finally:
            if self.reader is not None:
                await self.reader


def parse_vector(lines: list[str]) -> list[float]:
    for line in lines:
        match = re.search(r"entity data: \[([^\]]+)\]", line)
        if match:
            try:
                values = [float(part.strip().rstrip("dDfF")) for part in match[1].split(",")]
            except ValueError as error:
                raise GateError("Invalid entity vector in console response") from error
            if len(values) == 3 and all(math.isfinite(value) for value in values):
                return values
    raise GateError(f"No three-dimensional entity vector: {lines}")


def assert_vector(actual: list[float], expected: list[float]) -> None:
    if (
        len(actual) != 3
        or len(expected) != 3
        or any(
            not math.isfinite(a) or not math.isfinite(b) or abs(a - b) > 0.001
            for a, b in zip(actual, expected, strict=True)
        )
    ):
        raise GateError(f"Entity transform mismatch: expected {expected}, observed {actual}")


async def request(ws: Any, payload: dict[str, Any], response_type: str) -> dict[str, Any]:
    await ws.send(json.dumps(payload))
    async with asyncio.timeout(15):
        while True:
            response = json.loads(await ws.recv())
            if response.get("type") == "error":
                raise GateError(f"Plugin rejected {payload['type']}: {response}")
            if response.get("type") == response_type:
                return response


async def wait_pool(ws: Any) -> int:
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        response = await request(ws, {"type": "get_zone", "zone": ZONE}, "zone")
        if response.get("entity_count") == ENTITY_COUNT:
            return ENTITY_COUNT
        await asyncio.sleep(0.1)
    raise GateError("Stage pool did not restore the expected entity count")


async def check_movement(server: PaperProcess, ws: Any) -> list[dict[str, Any]]:
    # Tag one real entity at the main-stage origin; all pool members get each frame.
    await server.command(
        "tag @e[type=minecraft:block_display,x=0,y=80,z=0,distance=..0.1,limit=1] add mcav_acceptance",
        r"Added tag 'mcav_acceptance' to",
    )
    observations = []
    for coordinate, scale in [(0.25, 0.3), (0.75, 0.8)]:
        await ws.send(
            json.dumps(
                {
                    "type": "batch_update",
                    "zone": ZONE,
                    "entities": [
                        {
                            "id": f"block_{index}",
                            "x": coordinate,
                            "y": coordinate,
                            "z": coordinate,
                            "scale": scale,
                            "visible": True,
                        }
                        for index in range(ENTITY_COUNT)
                    ],
                }
            )
        )
        # The pinned built-in club template has an 8 x 8 x 6 main zone.
        expected = [coordinate * 8, 80 + coordinate * 8, coordinate * 6]
        deadline = time.monotonic() + 10
        while True:
            position = parse_vector(
                await server.command(
                    "data get entity @e[tag=mcav_acceptance,limit=1] Pos",
                    r"has the following entity data: \[",
                )
            )
            transform = parse_vector(
                await server.command(
                    "data get entity @e[tag=mcav_acceptance,limit=1] transformation.scale",
                    r"has the following entity data: \[",
                )
            )
            try:
                assert_vector(position, expected)
                assert_vector(transform, [scale] * 3)
                break
            except GateError:
                if time.monotonic() >= deadline:
                    raise
                await asyncio.sleep(0.1)
        observations.append({"position": position, "scale": transform})
    return observations


async def run_gate(
    java: Path, directory: Path, ws_port: int, timeout: float, evidence: dict
) -> None:
    for label in ("clean-start", "cold-restart"):
        server = PaperProcess(java, directory, label, timeout)
        try:
            await server.start()
            await server.command(
                "forceload add -32 -32 32 32",
                r"Marked .*force loaded|No chunks were marked for force loading",
            )
            async with websockets.connect(f"ws://127.0.0.1:{ws_port}", open_timeout=15) as ws:
                stages = await request(ws, {"type": "get_stages"}, "stages")
                if label == "clean-start":
                    if stages["count"] != 0:
                        raise GateError("Fresh server unexpectedly contains stages")
                    await request(
                        ws,
                        {
                            "type": "create_stage",
                            "name": STAGE,
                            "template": "club",
                            "anchor": {"world": "world", "x": 0, "y": 80, "z": 0},
                        },
                        "stage_created",
                    )
                    await request(ws, {"type": "activate_stage", "name": STAGE}, "stage_activated")
                else:
                    stage = await request(ws, {"type": "get_stage", "name": STAGE}, "stage")
                    if not stage["stage"]["active"]:
                        raise GateError("Stage lost its persisted active state")
                    # Deliberately no activate_stage/init_pool on restart.
                count = await wait_pool(ws)
                movement = await check_movement(server, ws)
                evidence[label] = {"entity_count": count, "movement": movement}
        finally:
            await server.stop()
        evidence[label]["clean_shutdown"] = True
        evidence[label]["stages_sha256"] = sha256(directory / "plugins/AudioViz/stages.yml")
    if evidence["clean-start"]["stages_sha256"] != evidence["cold-restart"]["stages_sha256"]:
        raise GateError("Restart rewrote persisted stage configuration/history")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", type=Path)
    parser.add_argument("--plugin-sha256")
    parser.add_argument("--cache", type=Path, default=ROOT / "minecraft_plugin/target/paper-cache")
    parser.add_argument(
        "--output", type=Path, default=ROOT / "minecraft_plugin/target/paper-acceptance"
    )
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument(
        "--accept-eula",
        action="store_true",
        help="Confirm acceptance of https://www.minecraft.net/eula",
    )
    parser.add_argument("--startup-timeout", type=float, default=240)
    args = parser.parse_args()
    if not args.prepare_only and (
        not args.accept_eula or not args.plugin or not args.plugin_sha256
    ):
        parser.error("Running requires --accept-eula, --plugin, and --plugin-sha256")
    if args.startup_timeout <= 0 or not math.isfinite(args.startup_timeout):
        parser.error("--startup-timeout must be positive and finite")
    lock = json.loads(LOCK_PATH.read_text())
    paper, java = prepare(args.cache.resolve(), lock)
    if args.prepare_only:
        print(json.dumps({"paper": str(paper), "java": str(java)}))
        return 0
    plugin = args.plugin.resolve()
    if sha256(plugin) != args.plugin_sha256:
        raise GateError("Plugin digest does not match --plugin-sha256")
    args.output.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="run-", dir=args.output.resolve()))
    ports = free_ports(2)
    evidence: dict[str, Any] = {
        "passed": False,
        "scope": "Paper renderer lifecycle; managed runtime disabled",
        "lock": lock,
        "plugin_sha256": args.plugin_sha256,
        "directory": str(directory),
        "ports": ports,
    }
    try:
        write_fixture(directory, plugin, paper, ports)
        if sha256(directory / "plugins/AudioViz.jar") != args.plugin_sha256:
            raise GateError("Copied plugin digest changed")
        asyncio.run(run_gate(java, directory, ports[1], args.startup_timeout, evidence))
        evidence["passed"] = True
    except Exception as error:
        evidence["error"] = f"{type(error).__name__}: {error}"
    finally:
        (directory / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
        print(
            json.dumps({"passed": evidence["passed"], "evidence": str(directory / "evidence.json")})
        )
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
