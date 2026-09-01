"""Lifecycle contract for a VJ runtime supervised by the Paper plugin."""

from __future__ import annotations

import asyncio
import ctypes
import inspect
import logging
import os
import re
import sys
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from vj_server.config import ManagedPerformanceSettings

logger = logging.getLogger(__name__)

_SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$")
_NONCE = re.compile(r"^[A-Za-z0-9_-]{43}$")
_SHUTDOWN_REASON = re.compile(r"^[A-Z_]{1,64}$")
_MAX_SIGNED_LONG = 2**63 - 1
_MAX_QUEUE_DEPTH = 1_000_000
_MAX_RENDER_AGE_MS = 60_000
_EVENT_LOOP_STALL_MS = 1_000
_RENDER_LOOP_STALL_MS = 5_000


def _required(environment: Mapping[str, str], name: str) -> str:
    value = environment.get(name)
    if value is None or not value:
        raise ValueError(f"{name} is required in Paper-managed mode")
    return value


def _bounded_integer(
    environment: Mapping[str, str],
    name: str,
    *,
    minimum: int,
    maximum: int,
) -> int:
    raw_value = _required(environment, name)
    if not raw_value.isascii() or not raw_value.isdecimal():
        raise ValueError(f"{name} must be a decimal integer")
    value = int(raw_value)
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} is outside its supported range")
    return value


def _optional_bounded_integer(
    environment: Mapping[str, str],
    name: str,
    *,
    default: int,
    minimum: int,
    maximum: int,
) -> int:
    if name not in environment:
        return default
    return _bounded_integer(environment, name, minimum=minimum, maximum=maximum)


@dataclass(frozen=True)
class ManagedEnvironment:
    """Validated process identity supplied through the private launch environment."""

    renderer_token: str = field(repr=False)
    generation: int
    launch_nonce: str = field(repr=False)
    parent_pid: int
    parent_start_identity: str
    state_directory: Path
    release_version: str
    runtime_api: int
    initial_performance: ManagedPerformanceSettings

    @classmethod
    def from_environ(cls, environment: Mapping[str, str] | None = None) -> "ManagedEnvironment":
        values = os.environ if environment is None else environment

        renderer_token = _required(values, "MCAV_RENDERER_TOKEN")
        token_size = len(renderer_token.encode("utf-8"))
        if not 32 <= token_size <= 1_024:
            raise ValueError("MCAV_RENDERER_TOKEN must contain 32 to 1024 UTF-8 bytes")

        generation = _bounded_integer(
            values,
            "MCAV_LAUNCH_GENERATION",
            minimum=1,
            maximum=_MAX_SIGNED_LONG,
        )
        launch_nonce = _required(values, "MCAV_LAUNCH_NONCE")
        if _NONCE.fullmatch(launch_nonce) is None:
            raise ValueError("MCAV_LAUNCH_NONCE is invalid")

        parent_pid = _bounded_integer(
            values,
            "MCAV_PARENT_PID",
            minimum=1,
            maximum=_MAX_SIGNED_LONG,
        )
        parent_start_identity = _required(values, "MCAV_PARENT_START_ID")
        if (
            len(parent_start_identity) > 64
            or not parent_start_identity.isascii()
            or not parent_start_identity.isdecimal()
        ):
            raise ValueError("MCAV_PARENT_START_ID must be a bounded decimal identity")

        raw_state_directory = _required(values, "MCAV_STATE_DIR")
        state_directory = Path(raw_state_directory)
        if not state_directory.is_absolute():
            raise ValueError("managed state directory must be absolute")
        try:
            state_stat = state_directory.stat()
        except OSError as error:
            raise ValueError("managed state directory must exist") from error
        if not state_directory.is_dir() or state_directory.is_symlink():
            raise ValueError("managed state directory must be a non-symlink directory")
        if hasattr(os, "getuid") and state_stat.st_uid != os.getuid():
            raise ValueError("managed state directory must be owned by the runtime user")
        state_directory = state_directory.resolve(strict=True)

        release_version = _required(values, "MCAV_RELEASE_VERSION")
        if len(release_version) > 64 or _SEMVER.fullmatch(release_version) is None:
            raise ValueError("MCAV_RELEASE_VERSION must be a supported semantic version")
        runtime_api = _bounded_integer(
            values,
            "MCAV_RUNTIME_API",
            minimum=1,
            maximum=1_000_000,
        )
        performance_profile = values.get("MCAV_PERFORMANCE_PROFILE", "BALANCED")
        if performance_profile not in {"SAFE", "BALANCED", "PERFORMANCE"}:
            raise ValueError("MCAV_PERFORMANCE_PROFILE is unsupported")
        target_render_fps = _optional_bounded_integer(
            values,
            "MCAV_TARGET_RENDER_FPS",
            default=60,
            minimum=1,
            maximum=240,
        )
        entity_budget = _optional_bounded_integer(
            values,
            "MCAV_ENTITY_BUDGET",
            default=10_000,
            minimum=1,
            maximum=10_000,
        )
        initial_performance = ManagedPerformanceSettings(
            level="SAFE" if performance_profile == "SAFE" else "NORMAL",
            target_fps=target_render_fps,
            entity_budget=entity_budget,
            particles_enabled=performance_profile != "SAFE",
        )

        return cls(
            renderer_token=renderer_token,
            generation=generation,
            launch_nonce=launch_nonce,
            parent_pid=parent_pid,
            parent_start_identity=parent_start_identity,
            state_directory=state_directory,
            release_version=release_version,
            runtime_api=runtime_api,
            initial_performance=initial_performance,
        )


class ParentInspector(Protocol):
    """Read the stable start identity for a process, or ``None`` if absent."""

    def start_identity(self, pid: int) -> str | None: ...


class LinuxParentInspector:
    """Inspect Linux process start ticks from procfs field 22."""

    def __init__(self, proc_root: Path = Path("/proc")) -> None:
        self._proc_root = proc_root

    def start_identity(self, pid: int) -> str | None:
        try:
            stat_line = (self._proc_root / str(pid) / "stat").read_text(
                encoding="utf-8",
                errors="strict",
            )
        except FileNotFoundError:
            return None
        closing_parenthesis = stat_line.rfind(")")
        if closing_parenthesis < 0:
            raise OSError("invalid procfs process stat")
        fields_after_name = stat_line[closing_parenthesis + 1 :].split()
        if len(fields_after_name) <= 19 or not fields_after_name[19].isdecimal():
            raise OSError("invalid procfs process start identity")
        return fields_after_name[19]


class WindowsParentInspector:
    """Inspect the Windows process creation time in Unix epoch milliseconds."""

    def __init__(
        self,
        start_time_reader: Callable[[int], int | None] | None = None,
    ) -> None:
        self._start_time_reader = start_time_reader or self._read_start_time

    def start_identity(self, pid: int) -> str | None:
        value = self._start_time_reader(pid)
        return None if value is None else str(value)

    @staticmethod
    def _read_start_time(pid: int) -> int | None:
        if sys.platform != "win32":
            raise OSError("Windows process inspection is unavailable")

        process_query_limited_information = 0x1000
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
        kernel32.OpenProcess.restype = ctypes.c_void_p
        kernel32.GetProcessTimes.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_ulonglong),
            ctypes.POINTER(ctypes.c_ulonglong),
            ctypes.POINTER(ctypes.c_ulonglong),
            ctypes.POINTER(ctypes.c_ulonglong),
        ]
        kernel32.GetProcessTimes.restype = ctypes.c_int
        kernel32.CloseHandle.argtypes = [ctypes.c_void_p]

        handle = kernel32.OpenProcess(process_query_limited_information, 0, pid)
        if not handle:
            error = ctypes.get_last_error()
            if error in {5, 87}:  # Access denied or no such process/invalid PID.
                return None
            raise OSError(error, "OpenProcess failed")

        creation = ctypes.c_ulonglong()
        exit_time = ctypes.c_ulonglong()
        kernel_time = ctypes.c_ulonglong()
        user_time = ctypes.c_ulonglong()
        try:
            if not kernel32.GetProcessTimes(
                handle,
                ctypes.byref(creation),
                ctypes.byref(exit_time),
                ctypes.byref(kernel_time),
                ctypes.byref(user_time),
            ):
                raise OSError(ctypes.get_last_error(), "GetProcessTimes failed")
        finally:
            kernel32.CloseHandle(handle)

        # Windows FILETIME counts 100ns units since 1601-01-01 UTC.
        return (creation.value - 116_444_736_000_000_000) // 10_000


def default_parent_inspector() -> ParentInspector:
    if sys.platform == "win32":
        return WindowsParentInspector()
    if sys.platform.startswith("linux"):
        return LinuxParentInspector()
    raise RuntimeError("Paper-managed runtime supervision supports Windows and Linux")


@dataclass(frozen=True)
class ManagedHealthSnapshot:
    """Current bounded inputs used to construct a runtime health message."""

    renderer_connected: bool
    ingress_healthy: bool
    event_loop_lag_ms: int
    last_render_age_ms: int
    ingress_queue_depth: int
    render_queue_depth: int


class RuntimeControl(Protocol):
    def on(self, message_type: str, callback: Callable[[dict], object]) -> None: ...

    async def send_runtime_message(self, payload: dict) -> None: ...


class ManagedRuntime:
    """Coordinate readiness, health, control, and parent-death handling."""

    def __init__(
        self,
        environment: ManagedEnvironment,
        *,
        request_stop: Callable[[], object],
        apply_performance: Callable[[ManagedPerformanceSettings], object],
        health_provider: Callable[[], ManagedHealthSnapshot],
        parent_inspector: ParentInspector | None = None,
        parent_poll_interval: float = 1.0,
        health_interval: float = 5.0,
    ) -> None:
        self.environment = environment
        self.pid = os.getpid()
        self._request_stop = request_stop
        self._apply_performance = apply_performance
        self._health_provider = health_provider
        self._parent_inspector = parent_inspector or default_parent_inspector()
        self._parent_poll_interval = parent_poll_interval
        self._health_interval = health_interval
        self._identity_validated = False
        self._ingress_bound = False
        self._metrics_bound = False
        self._control: RuntimeControl | None = None
        self._ready_controls: set[int] = set()
        self._parent_failures = 0
        self._stop_requested = False
        self._sequence = 0
        self._scheduler_lag_ms = 0
        self._tasks: list[asyncio.Task[Any]] = []
        self.closed = False
        self.parent_failure_total = 0
        self.rejected_control_total = 0

    def __repr__(self) -> str:
        return (
            "ManagedRuntime(generation="
            f"{self.environment.generation}, parent_pid={self.environment.parent_pid})"
        )

    @property
    def health_sequence(self) -> int:
        return self._sequence

    async def start(self) -> None:
        if self._tasks:
            return
        await self.observe_parent_once()
        self._tasks = [
            asyncio.create_task(self._parent_loop(), name="mcav-parent-monitor"),
            asyncio.create_task(self._health_loop(), name="mcav-runtime-health"),
        ]

    async def close(self) -> None:
        self.closed = True
        tasks, self._tasks = self._tasks, []
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def mark_ingress_bound(self) -> None:
        self._ingress_bound = True
        await self._maybe_send_ready()

    async def mark_metrics_bound(self) -> None:
        self._metrics_bound = True
        await self._maybe_send_ready()

    async def renderer_authenticated(self, control: RuntimeControl) -> None:
        self._control = control
        control.on("runtime_shutdown", self.handle_shutdown)
        control.on("runtime_performance", self.handle_performance)
        await self._maybe_send_ready()

    async def _maybe_send_ready(self) -> None:
        control = self._control
        if (
            control is None
            or not self._identity_validated
            or not self._ingress_bound
            or not self._metrics_bound
            or id(control) in self._ready_controls
        ):
            return
        await control.send_runtime_message(
            {
                "type": "runtime_ready",
                "v": "1.0.0",
                "release_version": self.environment.release_version,
                "runtime_api": self.environment.runtime_api,
                "generation": self.environment.generation,
                "launch_nonce": self.environment.launch_nonce,
                "pid": self.pid,
                "capabilities": ["health", "performance", "shutdown"],
            }
        )
        self._ready_controls.add(id(control))

    async def observe_parent_once(self) -> bool:
        if self._stop_requested:
            return False
        try:
            observed = self._parent_inspector.start_identity(self.environment.parent_pid)
            matched = observed == self.environment.parent_start_identity
        except Exception:
            matched = False

        if matched:
            self._identity_validated = True
            self._parent_failures = 0
            await self._maybe_send_ready()
            return True

        self.parent_failure_total += 1
        self._identity_validated = False
        self._parent_failures += 1
        if self._parent_failures >= 2:
            self._stop_requested = True
            logger.error("Managed Paper parent identity is no longer valid; stopping")
            await self._invoke(self._request_stop)
        return False

    async def handle_shutdown(self, message: dict) -> bool:
        allowed_keys = {"type", "v", "generation", "reason"}
        if (
            not isinstance(message, dict)
            or set(message) - allowed_keys
            or message.get("type") != "runtime_shutdown"
            or message.get("v", "1.0.0") != "1.0.0"
            or type(message.get("generation")) is not int
            or message.get("generation") != self.environment.generation
            or not isinstance(message.get("reason"), str)
            or _SHUTDOWN_REASON.fullmatch(message["reason"]) is None
        ):
            self.rejected_control_total += 1
            return False
        if not self._stop_requested:
            self._stop_requested = True
            await self._invoke(self._request_stop)
        return True

    async def handle_performance(self, message: dict) -> bool:
        allowed_keys = {
            "type",
            "v",
            "generation",
            "level",
            "target_fps",
            "entity_budget",
            "particles_enabled",
        }
        if (
            not isinstance(message, dict)
            or set(message) - allowed_keys
            or message.get("type") != "runtime_performance"
            or message.get("v", "1.0.0") != "1.0.0"
            or type(message.get("generation")) is not int
            or message.get("generation") != self.environment.generation
        ):
            self.rejected_control_total += 1
            return False
        try:
            settings = ManagedPerformanceSettings(
                level=message.get("level"),
                target_fps=message.get("target_fps"),
                entity_budget=message.get("entity_budget"),
                particles_enabled=message.get("particles_enabled"),
            )
        except (TypeError, ValueError):
            self.rejected_control_total += 1
            return False
        await self._invoke(self._apply_performance, settings)
        return True

    async def send_health_once(self) -> None:
        control = self._control
        if control is None:
            return
        snapshot = self._health_provider()
        last_render_age_ms = self._clamp(snapshot.last_render_age_ms, _MAX_RENDER_AGE_MS)
        ingress_queue_depth = self._clamp(snapshot.ingress_queue_depth, _MAX_QUEUE_DEPTH)
        render_queue_depth = self._clamp(snapshot.render_queue_depth, _MAX_QUEUE_DEPTH)
        await control.send_runtime_message(
            {
                "type": "runtime_health",
                "v": "1.0.0",
                "generation": self.environment.generation,
                "launch_nonce": self.environment.launch_nonce,
                "sequence": self._sequence,
                "process_alive": True,
                "renderer_connected": bool(snapshot.renderer_connected),
                "ingress_healthy": bool(snapshot.ingress_healthy),
                "event_loop_healthy": max(
                    snapshot.event_loop_lag_ms,
                    self._scheduler_lag_ms,
                )
                <= _EVENT_LOOP_STALL_MS,
                "render_loop_healthy": snapshot.last_render_age_ms <= _RENDER_LOOP_STALL_MS,
                "last_render_age_ms": last_render_age_ms,
                "ingress_queue_depth": ingress_queue_depth,
                "render_queue_depth": render_queue_depth,
            }
        )
        self._sequence += 1

    def record_scheduler_wakeup(
        self,
        *,
        expected_deadline: float,
        observed_time: float,
    ) -> None:
        """Record periodic task drift independently of render-loop pacing."""
        drift_seconds = max(0.0, observed_time - expected_deadline)
        self._scheduler_lag_ms = min(_MAX_RENDER_AGE_MS, int(drift_seconds * 1000))

    @staticmethod
    def _clamp(value: int, maximum: int) -> int:
        if type(value) is not int:
            return maximum
        return max(0, min(value, maximum))

    @staticmethod
    async def _invoke(callback: Callable[..., object], *args: object) -> None:
        result = callback(*args)
        if inspect.isawaitable(result):
            await result

    async def _parent_loop(self) -> None:
        try:
            while not self.closed and not self._stop_requested:
                await asyncio.sleep(self._parent_poll_interval)
                await self.observe_parent_once()
        except asyncio.CancelledError:
            pass

    async def _health_loop(self) -> None:
        try:
            event_loop = asyncio.get_running_loop()
            next_deadline = event_loop.time() + self._health_interval
            while not self.closed and not self._stop_requested:
                await asyncio.sleep(max(0.0, next_deadline - event_loop.time()))
                observed_time = event_loop.time()
                self.record_scheduler_wakeup(
                    expected_deadline=next_deadline,
                    observed_time=observed_time,
                )
                next_deadline = observed_time + self._health_interval
                try:
                    await self.send_health_once()
                except Exception:
                    logger.warning("Unable to publish managed runtime health")
        except asyncio.CancelledError:
            pass
