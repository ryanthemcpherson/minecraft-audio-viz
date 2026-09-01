"""Contract tests for the Paper-managed VJ runtime lifecycle."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from pathlib import Path

import pytest

from vj_server.config import ManagedPerformanceSettings
from vj_server.managed import (
    LinuxParentInspector,
    ManagedEnvironment,
    ManagedHealthSnapshot,
    ManagedRuntime,
    WindowsParentInspector,
)
from vj_server.viz_client import VizClient
from vj_server.vj_server import VJServer

NONCE = "n" * 43
RENDERER_TOKEN = "renderer-token-that-is-at-least-32-bytes"


def managed_environ(state_dir: Path, **overrides: str) -> dict[str, str]:
    values = {
        "MCAV_RENDERER_TOKEN": RENDERER_TOKEN,
        "MCAV_LAUNCH_GENERATION": "7",
        "MCAV_LAUNCH_NONCE": NONCE,
        "MCAV_PARENT_PID": "321",
        "MCAV_PARENT_START_ID": "987654",
        "MCAV_STATE_DIR": str(state_dir.resolve()),
        "MCAV_RELEASE_VERSION": "1.2.0",
        "MCAV_RUNTIME_API": "1",
    }
    values.update(overrides)
    return values


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("MCAV_RENDERER_TOKEN", "too-short"),
        ("MCAV_LAUNCH_GENERATION", "0"),
        ("MCAV_LAUNCH_GENERATION", str(2**63)),
        ("MCAV_LAUNCH_NONCE", "bad"),
        ("MCAV_PARENT_PID", "0"),
        ("MCAV_PARENT_START_ID", "not-an-integer"),
        ("MCAV_RELEASE_VERSION", "latest"),
        ("MCAV_RUNTIME_API", "0"),
    ],
)
def test_managed_environment_rejects_invalid_values(
    tmp_path: Path,
    key: str,
    value: str,
) -> None:
    with pytest.raises(ValueError):
        ManagedEnvironment.from_environ(managed_environ(tmp_path, **{key: value}))


def test_managed_environment_requires_absolute_owned_directory(tmp_path: Path) -> None:
    missing = tmp_path / "missing"
    with pytest.raises(ValueError, match="state directory"):
        ManagedEnvironment.from_environ(managed_environ(tmp_path, MCAV_STATE_DIR=str(missing)))

    with pytest.raises(ValueError, match="absolute"):
        ManagedEnvironment.from_environ(managed_environ(tmp_path, MCAV_STATE_DIR="relative/state"))


def test_managed_environment_repr_redacts_credentials(tmp_path: Path) -> None:
    environment = ManagedEnvironment.from_environ(managed_environ(tmp_path))

    rendered = repr(environment)

    assert RENDERER_TOKEN not in rendered
    assert NONCE not in rendered


def test_managed_environment_validates_initial_performance_limits(tmp_path: Path) -> None:
    environment = ManagedEnvironment.from_environ(
        managed_environ(
            tmp_path,
            MCAV_PERFORMANCE_PROFILE="SAFE",
            MCAV_TARGET_RENDER_FPS="20",
            MCAV_ENTITY_BUDGET="160",
        )
    )

    assert environment.initial_performance == ManagedPerformanceSettings(
        level="SAFE",
        target_fps=20,
        entity_budget=160,
        particles_enabled=False,
    )

    with pytest.raises(ValueError):
        ManagedEnvironment.from_environ(managed_environ(tmp_path, MCAV_ENTITY_BUDGET="10001"))


class FakeParentInspector:
    def __init__(self, observations: list[str | None | Exception]) -> None:
        self._observations = iter(observations)

    def start_identity(self, _pid: int) -> str | None:
        observation = next(self._observations)
        if isinstance(observation, Exception):
            raise observation
        return observation


class FakeControl:
    def __init__(self) -> None:
        self.handlers: dict[str, Callable[[dict], object]] = {}
        self.messages: list[dict] = []

    def on(self, message_type: str, callback: Callable[[dict], object]) -> None:
        self.handlers[message_type] = callback

    async def send_runtime_message(self, payload: dict) -> None:
        self.messages.append(payload)


def healthy_snapshot(**overrides: object) -> ManagedHealthSnapshot:
    values: dict[str, object] = {
        "renderer_connected": True,
        "ingress_healthy": True,
        "event_loop_lag_ms": 2,
        "last_render_age_ms": 5,
        "ingress_queue_depth": 0,
        "render_queue_depth": 0,
    }
    values.update(overrides)
    return ManagedHealthSnapshot(**values)


def build_runtime(
    tmp_path: Path,
    inspector: FakeParentInspector,
    *,
    stop: Callable[[], object] | None = None,
    apply_performance: Callable[[ManagedPerformanceSettings], object] | None = None,
    snapshot: ManagedHealthSnapshot | None = None,
) -> ManagedRuntime:
    return ManagedRuntime(
        ManagedEnvironment.from_environ(managed_environ(tmp_path)),
        parent_inspector=inspector,
        request_stop=stop or (lambda: None),
        apply_performance=apply_performance or (lambda _settings: None),
        health_provider=lambda: snapshot or healthy_snapshot(),
        parent_poll_interval=3600,
        health_interval=3600,
    )


@pytest.mark.asyncio
async def test_ready_waits_for_identity_ingress_metrics_and_renderer(tmp_path: Path) -> None:
    runtime = build_runtime(tmp_path, FakeParentInspector(["987654"]))
    control = FakeControl()
    await runtime.start()

    await runtime.mark_ingress_bound()
    await runtime.renderer_authenticated(control)
    assert control.messages == []

    await runtime.mark_metrics_bound()

    assert control.messages == [
        {
            "type": "runtime_ready",
            "v": "1.0.0",
            "release_version": "1.2.0",
            "runtime_api": 1,
            "generation": 7,
            "launch_nonce": NONCE,
            "pid": runtime.pid,
            "capabilities": ["health", "performance", "shutdown"],
        }
    ]
    assert set(control.handlers) == {"runtime_shutdown", "runtime_performance"}
    await runtime.close()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "observations",
    [
        [None, None],
        ["different", "different"],
        [OSError("inspection failed"), OSError("inspection failed")],
    ],
)
async def test_two_parent_identity_failures_request_graceful_stop(
    tmp_path: Path,
    observations: list[str | None | Exception],
) -> None:
    stopped = 0

    def stop() -> None:
        nonlocal stopped
        stopped += 1

    runtime = build_runtime(tmp_path, FakeParentInspector(observations), stop=stop)

    assert await runtime.observe_parent_once() is False
    assert stopped == 0
    assert await runtime.observe_parent_once() is False
    assert stopped == 1

    await runtime.observe_parent_once()
    assert stopped == 1


@pytest.mark.asyncio
async def test_matching_parent_resets_failure_count(tmp_path: Path) -> None:
    stopped = 0

    def stop() -> None:
        nonlocal stopped
        stopped += 1

    runtime = build_runtime(
        tmp_path,
        FakeParentInspector([None, "987654", None, None]),
        stop=stop,
    )

    assert await runtime.observe_parent_once() is False
    assert await runtime.observe_parent_once() is True
    assert await runtime.observe_parent_once() is False
    assert await runtime.observe_parent_once() is False
    assert stopped == 1


@pytest.mark.asyncio
async def test_shutdown_requires_matching_generation(tmp_path: Path) -> None:
    stopped = 0

    def stop() -> None:
        nonlocal stopped
        stopped += 1

    runtime = build_runtime(tmp_path, FakeParentInspector(["987654"]), stop=stop)

    assert (
        await runtime.handle_shutdown(
            {"type": "runtime_shutdown", "generation": 6, "reason": "PLUGIN_DISABLE"}
        )
        is False
    )
    assert stopped == 0
    assert (
        await runtime.handle_shutdown(
            {"type": "runtime_shutdown", "generation": 7, "reason": "PLUGIN_DISABLE"}
        )
        is True
    )
    assert stopped == 1


@pytest.mark.asyncio
async def test_performance_updates_are_validated_and_bounded(tmp_path: Path) -> None:
    applied: list[ManagedPerformanceSettings] = []
    runtime = build_runtime(
        tmp_path,
        FakeParentInspector(["987654"]),
        apply_performance=applied.append,
    )

    assert (
        await runtime.handle_performance(
            {
                "type": "runtime_performance",
                "generation": 7,
                "level": "SAFE",
                "target_fps": 20,
                "entity_budget": 256,
                "particles_enabled": False,
            }
        )
        is True
    )
    assert applied == [
        ManagedPerformanceSettings(
            level="SAFE",
            target_fps=20,
            entity_budget=256,
            particles_enabled=False,
        )
    ]

    for invalid in (
        {
            "generation": 6,
            "level": "SAFE",
            "target_fps": 20,
            "entity_budget": 256,
            "particles_enabled": False,
        },
        {
            "generation": 7,
            "level": "UNBOUNDED",
            "target_fps": 20,
            "entity_budget": 256,
            "particles_enabled": False,
        },
        {
            "generation": 7,
            "level": "SAFE",
            "target_fps": 241,
            "entity_budget": 256,
            "particles_enabled": False,
        },
        {
            "generation": 7,
            "level": "SAFE",
            "target_fps": 20,
            "entity_budget": 10_001,
            "particles_enabled": False,
        },
    ):
        assert await runtime.handle_performance({"type": "runtime_performance", **invalid}) is False
    assert len(applied) == 1


@pytest.mark.asyncio
async def test_health_sequences_clamps_values_and_reports_stalls(tmp_path: Path) -> None:
    runtime = build_runtime(
        tmp_path,
        FakeParentInspector(["987654"]),
        snapshot=healthy_snapshot(
            event_loop_lag_ms=2_500,
            last_render_age_ms=90_000,
            ingress_queue_depth=-5,
            render_queue_depth=2_000_000,
        ),
    )
    control = FakeControl()
    runtime._control = control

    await runtime.send_health_once()
    await runtime.send_health_once()

    first, second = control.messages
    assert first["sequence"] == 0
    assert second["sequence"] == 1
    assert first["event_loop_healthy"] is False
    assert first["render_loop_healthy"] is False
    assert first["last_render_age_ms"] == 60_000
    assert first["ingress_queue_depth"] == 0
    assert first["render_queue_depth"] == 1_000_000


@pytest.mark.asyncio
async def test_health_uses_periodic_scheduler_drift_not_render_pacing(tmp_path: Path) -> None:
    runtime = build_runtime(tmp_path, FakeParentInspector(["987654"]))
    control = FakeControl()
    runtime._control = control

    runtime.record_scheduler_wakeup(expected_deadline=10.0, observed_time=12.5)
    await runtime.send_health_once()
    runtime.record_scheduler_wakeup(expected_deadline=20.0, observed_time=20.01)
    await runtime.send_health_once()

    assert control.messages[0]["event_loop_healthy"] is False
    assert control.messages[1]["event_loop_healthy"] is True


def test_linux_parent_inspector_parses_start_ticks_with_complex_comm(tmp_path: Path) -> None:
    proc_dir = tmp_path / "321"
    proc_dir.mkdir()
    fields = ["S"] + ["0"] * 18 + ["987654"] + ["0"] * 4
    (proc_dir / "stat").write_text(
        f"321 (paper server) worker) {' '.join(fields)}\n",
        encoding="utf-8",
    )

    assert LinuxParentInspector(tmp_path).start_identity(321) == "987654"
    assert LinuxParentInspector(tmp_path).start_identity(999) is None


def test_windows_parent_inspector_uses_exact_start_identity() -> None:
    inspected: list[int] = []

    def reader(pid: int) -> int | None:
        inspected.append(pid)
        return 1_725_000_123_456

    inspector = WindowsParentInspector(start_time_reader=reader)

    assert inspector.start_identity(321) == "1725000123456"
    assert inspected == [321]


@pytest.mark.asyncio
async def test_close_cancels_background_tasks(tmp_path: Path) -> None:
    runtime = ManagedRuntime(
        ManagedEnvironment.from_environ(managed_environ(tmp_path)),
        parent_inspector=FakeParentInspector(["987654"]),
        request_stop=lambda: None,
        apply_performance=lambda _settings: None,
        health_provider=healthy_snapshot,
        parent_poll_interval=0.01,
        health_interval=0.01,
    )
    await runtime.start()
    await asyncio.sleep(0)

    await runtime.close()

    assert runtime.closed is True


def test_server_applies_ephemeral_performance_and_total_entity_budget() -> None:
    server = VJServer(
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )

    server._apply_managed_performance(
        ManagedPerformanceSettings(
            level="PREVIEW_REDUCED",
            target_fps=60,
            entity_budget=3,
            particles_enabled=False,
        )
    )
    limited = server._apply_managed_entity_budget(
        {
            "main": [{"id": "a"}, {"id": "b"}],
            "side": [{"id": "c"}, {"id": "d"}],
        }
    )

    assert server._target_render_fps == 60
    assert server._preview_frame_divisor == 6
    assert server._managed_particles_enabled is False
    assert limited == {
        "main": [{"id": "a"}, {"id": "b"}],
        "side": [{"id": "c"}],
    }


@pytest.mark.asyncio
async def test_budget_limits_lua_work_and_pool_allocation_across_zones() -> None:
    server = VJServer(
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )
    main_state = server._get_zone_state("main")
    side_state = server._get_zone_state("side")
    main_state.entity_count = 100
    main_state.config.entity_count = 100
    side_state.entity_count = 100
    side_state.config.entity_count = 100
    server._managed_entity_budget = 120

    observed_counts: list[int] = []

    class RecordingPattern:
        def __init__(self) -> None:
            self.config = side_state.config

        def calculate_entities(self, _audio: object) -> list[dict]:
            observed_counts.append(self.config.entity_count)
            return [{"id": f"block_{index}"} for index in range(self.config.entity_count)]

    side_state.pattern = RecordingPattern()

    class PoolClient:
        def __init__(self) -> None:
            self.calls: list[tuple[str, int, str]] = []

        async def init_pool(self, zone: str, count: int, material: str) -> bool:
            self.calls.append((zone, count, material))
            return True

    client = PoolClient()
    audio = type("Audio", (), {})()

    entities = await server._calculate_entities_for_zone(
        side_state,
        audio,
        "side",
        entity_limit=server._managed_zone_entity_budgets()["side"],
    )
    await server._init_managed_pool(client, "side", 100, "GLOWSTONE")

    assert server._managed_zone_entity_budgets() == {"main": 100, "side": 20}
    assert observed_counts == [20]
    assert len(entities) == 20
    assert side_state.config.entity_count == 100
    assert side_state.pattern.config.entity_count == 100
    assert client.calls == [("side", 20, "GLOWSTONE")]


@pytest.mark.asyncio
async def test_budget_caps_existing_pool_high_water_without_hidden_updates() -> None:
    server = VJServer(
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )
    main_state = server._get_zone_state("main")
    main_state.entity_count = 100
    main_state.minecraft_pool_size = 100
    server._managed_entity_budget = 20

    class UpdateClient:
        connected = True

        def __init__(self) -> None:
            self.updates: list[list[dict]] = []

        async def batch_update_fast(
            self,
            _zone: str,
            entities: list[dict],
            _particles: list[dict],
            _audio: dict,
        ) -> None:
            self.updates.append(entities)

    client = UpdateClient()
    server.viz_client = client  # type: ignore[assignment]
    entities = [{"id": f"block_{index}"} for index in range(100)]

    await server._update_minecraft_zone(
        "main",
        main_state,
        entities,
        [0.0] * 5,
        0.0,
        False,
        0.0,
    )

    assert main_state.minecraft_pool_size == 20
    assert len(client.updates) == 1
    assert len(client.updates[0]) == 20
    assert {entity["id"] for entity in client.updates[0]} == {
        f"block_{index}" for index in range(20)
    }


@pytest.mark.asyncio
async def test_overlapping_budget_updates_reconcile_pools_to_latest_version() -> None:
    server = VJServer(
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )
    main_state = server._get_zone_state("main")
    side_state = server._get_zone_state("side")
    main_state.entity_count = 100
    side_state.entity_count = 100
    first_resize_started = asyncio.Event()
    allow_first_resize = asyncio.Event()

    class LatchedPoolClient:
        connected = True

        def __init__(self) -> None:
            self.calls: list[tuple[str, int]] = []

        async def init_pool(self, zone: str, count: int, _material: str) -> bool:
            self.calls.append((zone, count))
            if len(self.calls) == 1:
                first_resize_started.set()
                await allow_first_resize.wait()
            return True

    client = LatchedPoolClient()
    server.viz_client = client  # type: ignore[assignment]

    server._apply_managed_performance(
        ManagedPerformanceSettings(
            level="PREVIEW_REDUCED",
            target_fps=60,
            entity_budget=120,
            particles_enabled=True,
        )
    )
    reconcile_task = server._managed_pool_reconcile_task
    assert reconcile_task is not None
    await first_resize_started.wait()

    server._apply_managed_performance(
        ManagedPerformanceSettings(
            level="FPS_REDUCED",
            target_fps=30,
            entity_budget=50,
            particles_enabled=False,
        )
    )
    allow_first_resize.set()
    await reconcile_task

    final_allocations = {zone: count for zone, count in client.calls[-2:]}
    assert final_allocations == {"main": 50, "side": 0}
    assert main_state.minecraft_pool_size == 50
    assert side_state.minecraft_pool_size == 0


@pytest.mark.asyncio
async def test_reconcile_skips_zone_that_switches_to_bitmap_during_resize() -> None:
    server = VJServer(
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )
    main_state = server._get_zone_state("main")
    side_state = server._get_zone_state("side")
    main_state.entity_count = 100
    side_state.entity_count = 100
    first_resize_started = asyncio.Event()
    allow_first_resize = asyncio.Event()

    class LatchedPoolClient:
        connected = True

        def __init__(self) -> None:
            self.calls: list[tuple[str, int]] = []

        async def init_pool(self, zone: str, count: int, _material: str) -> bool:
            self.calls.append((zone, count))
            if len(self.calls) == 1:
                first_resize_started.set()
                await allow_first_resize.wait()
            return True

    client = LatchedPoolClient()
    server.viz_client = client  # type: ignore[assignment]
    server._apply_managed_performance(
        ManagedPerformanceSettings(
            level="PREVIEW_REDUCED",
            target_fps=60,
            entity_budget=120,
            particles_enabled=True,
        )
    )
    reconcile_task = server._managed_pool_reconcile_task
    assert reconcile_task is not None
    await first_resize_started.wait()

    side_state.render_mode = "bitmap"
    allow_first_resize.set()
    await reconcile_task

    assert all(zone != "side" for zone, _count in client.calls)


@pytest.mark.asyncio
async def test_viz_client_sends_runtime_control_without_response() -> None:
    sent: list[str] = []

    class FakeWebSocket:
        async def send(self, payload: str) -> None:
            sent.append(payload)

    client = VizClient(auth_token=RENDERER_TOKEN)
    client.ws = FakeWebSocket()  # type: ignore[assignment]
    client._connected = True

    await client.send_runtime_message({"type": "runtime_health", "sequence": 4})

    assert sent == ['{"type":"runtime_health","sequence":4}']
