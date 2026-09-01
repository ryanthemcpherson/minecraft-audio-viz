from __future__ import annotations

import asyncio
import json
import socket
import time
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
from aiohttp import ClientSession, WSMsgType, WSServerHandshakeError

import vj_server.vj_server as vj_server_module
from vj_server import metrics as metrics_module
from vj_server.ingress import static as static_module
from vj_server.ingress.app import IngressServer, WebSocketHandlers
from vj_server.ingress.limits import IngressLimits, RouteLimits
from vj_server.ingress.security import browser_origin_allowed
from vj_server.transport import WebSocketPeer
from vj_server.vj_server import VJServer


def create_project(root: Path) -> None:
    admin = root / "admin_panel"
    preview = root / "preview_tool" / "frontend"
    assets = root / "assets"
    admin.mkdir(parents=True)
    preview.mkdir(parents=True)
    assets.mkdir()
    (admin / "index.html").write_text("admin", encoding="utf-8")
    (admin / "app.js").write_text("app", encoding="utf-8")
    (preview / "index.html").write_text("preview", encoding="utf-8")


PeerHandler = Callable[[WebSocketPeer], Awaitable[None]]


@pytest.mark.parametrize(
    ("origin", "allowed_origins", "expected"),
    [
        ("https://panel.example", (), True),
        ("https://panel.example/", (), True),
        ("https://operator.example", ("https://operator.example",), True),
        ("https://operator.example", ("https://operator.example/path",), False),
        ("file:///", (), False),
        ("null", (), False),
        ("https://user@panel.example", (), False),
    ],
)
def test_browser_origin_policy_accepts_only_exact_http_origins(
    origin: str,
    allowed_origins: tuple[str, ...],
    expected: bool,
) -> None:
    assert (
        browser_origin_allowed(
            origin,
            "panel.example",
            secure_transport=True,
            allowed_origins=allowed_origins,
        )
        is expected
    )


@asynccontextmanager
async def running_ingress(
    root: Path,
    handlers: WebSocketHandlers,
    *,
    limits: IngressLimits | None = None,
) -> AsyncIterator[tuple[ClientSession, str]]:
    create_project(root)
    server = IngressServer(
        root,
        host="127.0.0.1",
        port=0,
        ssl_context=None,
        allow_insecure_loopback=True,
        limits=limits,
        websocket_handlers=handlers,
    )
    await server.start()
    host, port = server.bound_address
    session = ClientSession()
    try:
        yield session, f"http://{host}:{port}"
    finally:
        await session.close()
        await server.stop()


def route_echo(name: str, reached: list[str]) -> PeerHandler:
    async def handler(peer: WebSocketPeer) -> None:
        reached.append(name)
        message = await peer.recv()
        await peer.send(json.dumps({"route": name, "message": message}))

    return handler


@pytest.mark.asyncio
async def test_managed_ingress_serves_a_noop_legacy_runtime_config(tmp_path: Path) -> None:
    async def no_op(_peer: WebSocketPeer) -> None:
        return None

    handlers = WebSocketHandlers(dj=no_op, admin=no_op, preview=no_op)
    async with running_ingress(tmp_path, handlers) as (session, base_url):
        response = await session.get(f"{base_url}/mcav-runtime-config.js")

        assert response.status == 200
        assert await response.text() == "window.__MCAV_LEGACY_WS_PORT__ = null;\n"
        assert response.headers["Cache-Control"] == "no-store"


@pytest.mark.asyncio
async def test_each_websocket_route_reaches_only_its_handler(tmp_path: Path) -> None:
    reached: list[str] = []
    handlers = WebSocketHandlers(
        dj=route_echo("dj", reached),
        admin=route_echo("admin", reached),
        preview=route_echo("preview", reached),
    )
    async with running_ingress(tmp_path, handlers) as (session, base_url):
        origin = base_url
        for route in ("dj", "admin", "preview"):
            kwargs = {} if route == "dj" else {"origin": origin}
            async with session.ws_connect(f"{base_url}/ws/{route}", **kwargs) as websocket:
                await websocket.send_str(route)
                response = await websocket.receive_json()
                assert response == {"route": route, "message": route}

    assert reached == ["dj", "admin", "preview"]


@pytest.mark.asyncio
async def test_browser_origin_is_required_but_dj_origin_is_not_restricted(tmp_path: Path) -> None:
    reached: list[str] = []
    handlers = WebSocketHandlers(
        dj=route_echo("dj", reached),
        admin=route_echo("admin", reached),
        preview=route_echo("preview", reached),
    )
    async with running_ingress(tmp_path, handlers) as (session, base_url):
        for origin in (
            None,
            "null",
            "file:///",
            "https://attacker.example",
            f"{base_url}/unexpected-path",
        ):
            with pytest.raises(WSServerHandshakeError) as error:
                await session.ws_connect(f"{base_url}/ws/admin", origin=origin)
            assert error.value.status == 403

        async with session.ws_connect(
            f"{base_url}/ws/dj",
            origin="https://dj-client.example",
        ) as websocket:
            await websocket.send_str("frame")
            assert (await websocket.receive_json())["route"] == "dj"

    assert reached == ["dj"]


@pytest.mark.asyncio
async def test_route_connection_cap_rejects_before_upgrade(tmp_path: Path) -> None:
    entered = asyncio.Event()
    release = asyncio.Event()

    async def held(_peer: WebSocketPeer) -> None:
        entered.set()
        await release.wait()

    route_limit = RouteLimits(1, 65_536, 60, 5, 10.0, 60.0)
    limits = IngressLimits(admin=route_limit)
    handlers = WebSocketHandlers(dj=held, admin=held, preview=held)
    async with running_ingress(tmp_path, handlers, limits=limits) as (session, base_url):
        first = await session.ws_connect(f"{base_url}/ws/admin", origin=base_url)
        await entered.wait()
        try:
            with pytest.raises(WSServerHandshakeError) as error:
                await session.ws_connect(f"{base_url}/ws/admin", origin=base_url)
            assert error.value.status == 503
        finally:
            release.set()
            await first.close()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("send", "expected_code"),
    [
        (lambda websocket: websocket.send_str("x" * 65), 1009),
        (lambda websocket: websocket.send_bytes(b"binary"), 1003),
    ],
)
async def test_browser_routes_reject_oversized_and_binary_frames(
    tmp_path: Path,
    send: Callable,
    expected_code: int,
) -> None:
    async def receive_once(peer: WebSocketPeer) -> None:
        await peer.recv()

    route_limit = RouteLimits(4, 64, 60, 5, 10.0, 60.0)
    limits = IngressLimits(admin=route_limit)
    handlers = WebSocketHandlers(
        dj=receive_once,
        admin=receive_once,
        preview=receive_once,
    )
    async with running_ingress(tmp_path, handlers, limits=limits) as (session, base_url):
        websocket = await session.ws_connect(f"{base_url}/ws/admin", origin=base_url)
        await send(websocket)
        message = await websocket.receive()
        assert message.type in {WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.ERROR}
        assert websocket.close_code == expected_code


@pytest.mark.asyncio
async def test_idle_route_closes_and_handler_exception_is_contained(tmp_path: Path) -> None:
    async def idle(peer: WebSocketPeer) -> None:
        await peer.recv()

    async def fail(_peer: WebSocketPeer) -> None:
        raise RuntimeError("secret handler detail")

    route_limit = RouteLimits(4, 64, 60, 5, 0.05, 1.0)
    limits = IngressLimits(admin=route_limit, preview=route_limit)
    handlers = WebSocketHandlers(dj=idle, admin=idle, preview=fail)
    async with running_ingress(tmp_path, handlers, limits=limits) as (session, base_url):
        idle_socket = await session.ws_connect(f"{base_url}/ws/admin", origin=base_url)
        await idle_socket.receive()
        assert idle_socket.close_code == 1008

        failed_socket = await session.ws_connect(f"{base_url}/ws/preview", origin=base_url)
        await failed_socket.receive()
        assert failed_socket.close_code == 1011


@pytest.mark.asyncio
async def test_slow_static_read_does_not_block_dj_frame(tmp_path: Path, monkeypatch) -> None:
    original_read = static_module._read_asset_body

    def slow_read(descriptor: int, max_bytes: int) -> bytes:
        time.sleep(0.2)
        return original_read(descriptor, max_bytes)

    monkeypatch.setattr(static_module, "_read_asset_body", slow_read)
    reached: list[str] = []
    handlers = WebSocketHandlers(
        dj=route_echo("dj", reached),
        admin=route_echo("admin", reached),
        preview=route_echo("preview", reached),
    )
    async with running_ingress(tmp_path, handlers) as (session, base_url):
        static_task = asyncio.create_task(session.get(f"{base_url}/app.js"))
        await asyncio.sleep(0.02)
        async with session.ws_connect(f"{base_url}/ws/dj") as websocket:
            await websocket.send_str("frame")
            response = await asyncio.wait_for(websocket.receive_json(), timeout=0.1)
            assert response["route"] == "dj"
        static_response = await static_task
        assert static_response.status == 200


@pytest.mark.asyncio
async def test_real_browser_handler_rejects_commands_before_authentication(tmp_path: Path) -> None:
    server = VJServer(
        project_root=tmp_path,
        metrics_port=None,
        show_spectrograph=False,
        require_auth=True,
    )

    async def no_op(_peer: WebSocketPeer) -> None:
        return None

    handlers = WebSocketHandlers(
        dj=no_op,
        admin=server._handle_browser_client,
        preview=server._handle_browser_client,
    )
    async with running_ingress(tmp_path, handlers) as (session, base_url):
        websocket = await session.ws_connect(f"{base_url}/ws/admin", origin=base_url)
        await websocket.send_json({"type": "get_zones"})
        assert await websocket.receive_json() == {
            "type": "auth_error",
            "error": "authentication required",
        }
        await websocket.receive()
        assert websocket.close_code == 4003

    assert server._broadcast_clients == set()


@pytest.mark.asyncio
async def test_route_auth_attempt_limit_is_enforced_by_real_browser_handler(tmp_path: Path) -> None:
    server = VJServer(
        project_root=tmp_path,
        metrics_port=None,
        show_spectrograph=False,
        require_auth=True,
    )

    async def no_op(_peer: WebSocketPeer) -> None:
        return None

    route_limit = RouteLimits(4, 65_536, 60, 1, 10.0, 60.0)
    limits = IngressLimits(admin=route_limit)
    handlers = WebSocketHandlers(
        dj=no_op,
        admin=server._handle_browser_client,
        preview=no_op,
    )
    async with running_ingress(tmp_path, handlers, limits=limits) as (session, base_url):
        first = await session.ws_connect(f"{base_url}/ws/admin", origin=base_url)
        await first.send_json({"type": "vj_auth", "username": "operator", "password": "wrong"})
        assert (await first.receive_json())["type"] == "auth_error"
        await first.receive()
        assert first.close_code == 4004

        second = await session.ws_connect(f"{base_url}/ws/admin", origin=base_url)
        await second.send_json({"type": "vj_auth", "username": "operator", "password": "wrong"})
        assert (await second.receive_json())["type"] == "auth_error"
        await second.receive()
        assert second.close_code == 4008


@pytest.mark.asyncio
async def test_route_auth_attempt_limit_is_enforced_by_real_dj_handler(tmp_path: Path) -> None:
    server = VJServer(
        project_root=tmp_path,
        metrics_port=None,
        show_spectrograph=False,
        require_auth=True,
    )

    async def no_op(_peer: WebSocketPeer) -> None:
        return None

    route_limit = RouteLimits(4, 65_536, 60, 1, 10.0, 60.0)
    limits = IngressLimits(dj=route_limit)
    handlers = WebSocketHandlers(
        dj=server._handle_dj_connection,
        admin=no_op,
        preview=no_op,
    )
    auth_message = {
        "type": "dj_auth",
        "dj_id": "missing",
        "dj_key": "wrong",
        "dj_name": "Test DJ",
    }
    async with running_ingress(tmp_path, handlers, limits=limits) as (session, base_url):
        first = await session.ws_connect(f"{base_url}/ws/dj")
        await first.send_json(auth_message)
        await first.receive()
        assert first.close_code == 4004

        second = await session.ws_connect(f"{base_url}/ws/dj")
        await second.send_json(auth_message)
        await second.receive()
        assert second.close_code == 4029


@pytest.mark.asyncio
async def test_managed_vj_run_uses_one_ingress_and_no_legacy_listener(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    create_project(tmp_path)
    captured: dict = {}

    class FakeIngress:
        def __init__(self, *args, **kwargs) -> None:
            captured["init"] = (args, kwargs)
            captured["started"] = 0
            captured["accepting_stopped"] = 0
            captured["stopped"] = 0
            self.bound_address = ("127.0.0.1", 8443)

        async def start(self) -> None:
            captured["started"] += 1

        async def stop_accepting(self) -> None:
            captured["accepting_stopped"] += 1

        async def stop(self) -> None:
            captured["stopped"] += 1

    async def fail_legacy_listener(*_args, **_kwargs):
        pytest.fail("managed mode must not start a legacy WebSocket listener")

    async def no_op() -> None:
        return None

    server = VJServer(
        project_root=tmp_path,
        public_host="127.0.0.1",
        public_port=8443,
        metrics_port=None,
        show_spectrograph=False,
    )
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = no_op
    monkeypatch.setattr(vj_server_module, "IngressServer", FakeIngress)
    monkeypatch.setattr(vj_server_module, "ws_serve", fail_legacy_listener)

    await server.run()

    assert captured["started"] == 1
    assert captured["accepting_stopped"] == 1
    assert captured["stopped"] == 1
    handlers = captured["init"][1]["websocket_handlers"]
    assert handlers.dj == server._handle_dj_connection
    assert handlers.admin == server._handle_admin_client
    assert handlers.preview == server._handle_preview_client


@pytest.mark.asyncio
async def test_managed_vj_run_cleans_up_when_ingress_start_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    create_project(tmp_path)
    captured = {"stopped": 0}

    class FailingIngress:
        def __init__(self, *_args, **_kwargs) -> None:
            pass

        async def start(self) -> None:
            raise RuntimeError("bind failed")

        async def stop(self) -> None:
            captured["stopped"] += 1

    server = VJServer(
        project_root=tmp_path,
        public_host="127.0.0.1",
        public_port=8443,
        metrics_port=None,
        show_spectrograph=False,
    )
    monkeypatch.setattr(vj_server_module, "IngressServer", FailingIngress)

    with pytest.raises(RuntimeError, match="bind failed"):
        await server.run()

    assert server._running is False
    assert captured["stopped"] == 1


@pytest.mark.asyncio
async def test_managed_vj_run_cleans_up_when_dependent_service_start_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    create_project(tmp_path)
    captured = {"stopped": 0}

    class FakeIngress:
        def __init__(self, *_args, **_kwargs) -> None:
            self.bound_address = ("127.0.0.1", 8443)

        async def start(self) -> None:
            return None

        async def stop(self) -> None:
            captured["stopped"] += 1

    async def fail_metrics(*_args, **_kwargs):
        raise RuntimeError("metrics bind failed")

    server = VJServer(
        project_root=tmp_path,
        public_host="127.0.0.1",
        public_port=8443,
        metrics_port=9001,
        show_spectrograph=False,
    )
    monkeypatch.setattr(vj_server_module, "IngressServer", FakeIngress)
    monkeypatch.setattr(metrics_module, "start_metrics_server", fail_metrics)

    with pytest.raises(RuntimeError, match="metrics bind failed"):
        await server.run()

    assert server._running is False
    assert captured["stopped"] == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("signal_error", [RuntimeError, ValueError])
async def test_managed_vj_run_tolerates_unavailable_signal_handlers_and_cleans_up(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    signal_error: type[Exception],
) -> None:
    create_project(tmp_path)
    captured = {"stop_accepting": 0, "stopped": 0}

    class FakeIngress:
        def __init__(self, *_args, **_kwargs) -> None:
            self.bound_address = ("127.0.0.1", 8443)

        async def start(self) -> None:
            return None

        async def stop_accepting(self) -> None:
            captured["stop_accepting"] += 1

        async def stop(self) -> None:
            captured["stopped"] += 1

    async def no_op() -> None:
        return None

    def reject_signal_handler(*_args, **_kwargs) -> None:
        raise signal_error("signal handlers unavailable")

    server = VJServer(
        project_root=tmp_path,
        public_host="127.0.0.1",
        public_port=8443,
        metrics_port=None,
        show_spectrograph=False,
    )
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = no_op
    monkeypatch.setattr(vj_server_module, "IngressServer", FakeIngress)
    monkeypatch.setattr(
        asyncio.get_running_loop(),
        "add_signal_handler",
        reject_signal_handler,
    )

    await server.run()

    assert server._running is False
    assert captured == {"stop_accepting": 1, "stopped": 1}


@pytest.mark.asyncio
async def test_legacy_partial_startup_releases_http_port_for_restart(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    create_project(tmp_path)
    with socket.socket() as port_reservation:
        port_reservation.bind(("127.0.0.1", 0))
        http_port = int(port_reservation.getsockname()[1])

    class FakeWebSocketServer:
        def close(self) -> None:
            return None

        async def wait_closed(self) -> None:
            return None

    listener_calls = 0

    async def fail_second_listener(*_args, **_kwargs):
        nonlocal listener_calls
        listener_calls += 1
        if listener_calls == 2:
            raise RuntimeError("browser bind failed")
        return FakeWebSocketServer()

    first = VJServer(
        project_root=tmp_path,
        http_host="127.0.0.1",
        http_port=http_port,
        metrics_port=None,
        show_spectrograph=False,
        legacy_separate_listeners=True,
    )
    monkeypatch.setattr(vj_server_module, "ws_serve", fail_second_listener)

    with pytest.raises(RuntimeError, match="browser bind failed"):
        await first.run()

    assert first._running is False

    async def successful_listener(*_args, **_kwargs):
        return FakeWebSocketServer()

    async def no_op() -> None:
        return None

    second = VJServer(
        project_root=tmp_path,
        http_host="127.0.0.1",
        http_port=http_port,
        metrics_port=None,
        show_spectrograph=False,
        legacy_separate_listeners=True,
    )
    second._skip_minecraft = True
    second._pattern_hot_reload_enabled = False
    second._init_coordinator = no_op
    second._browser_heartbeat_loop = no_op
    second._main_loop = no_op
    monkeypatch.setattr(vj_server_module, "ws_serve", successful_listener)

    await second.run()

    assert second._running is False
