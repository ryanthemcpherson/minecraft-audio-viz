"""Authentication-handshake tests for the Minecraft visualization client."""

from __future__ import annotations

import asyncio
import inspect
import json
import logging
from collections.abc import Callable
from typing import Any

import pytest
from websockets.exceptions import ConnectionClosedError
from websockets.frames import Close, Frame, Opcode

import vj_server.viz_client as viz_client_module
from vj_server.viz_client import VizClient


class FakeWebSocket:
    """Small queue-backed WebSocket used to exercise the real client handshake."""

    def __init__(
        self,
        *responses: dict[str, Any] | str | BaseException,
        on_send: Callable[[dict[str, Any]], Any] | None = None,
    ) -> None:
        self.sent_messages: list[dict[str, Any]] = []
        self.recv_tasks: list[asyncio.Task[Any] | None] = []
        self.closed = False
        self.close_calls = 0
        self._responses: asyncio.Queue[dict[str, Any] | str | BaseException] = asyncio.Queue()
        self._on_send = on_send
        for response in responses:
            self.queue_response(response)

    def queue_response(self, response: dict[str, Any] | str | BaseException) -> None:
        self._responses.put_nowait(response)

    async def send(self, raw_message: str) -> None:
        message = json.loads(raw_message)
        self.sent_messages.append(message)
        if self._on_send is not None:
            result = self._on_send(message)
            if inspect.isawaitable(result):
                await result

    async def recv(self) -> str:
        self.recv_tasks.append(asyncio.current_task())
        response = await self._responses.get()
        if isinstance(response, BaseException):
            raise response
        return response if isinstance(response, str) else json.dumps(response)

    async def close(self) -> None:
        self.close_calls += 1
        self.closed = True


def install_websocket_factory(
    monkeypatch: pytest.MonkeyPatch, *websockets: FakeWebSocket
) -> list[dict[str, Any]]:
    pending = list(websockets)
    connect_calls: list[dict[str, Any]] = []

    async def fake_connect(*_args: Any, **_kwargs: Any) -> FakeWebSocket:
        connect_calls.append({"uri": _args[0], **_kwargs})
        return pending.pop(0)

    monkeypatch.setattr(viz_client_module.websockets, "connect", fake_connect)
    return connect_calls


@pytest.mark.parametrize(
    "host",
    ["0.0.0.0", "192.168.1.25", "mc.local", "8.8.8.8", "::ffff:192.168.1.25"],
)
@pytest.mark.asyncio
async def test_connect_rejects_non_loopback_transport_before_opening_socket(
    monkeypatch: pytest.MonkeyPatch, host: str
) -> None:
    websocket = FakeWebSocket(
        {
            "type": "connected",
            "auth_required": False,
            "server_type": "paper",
        }
    )
    connect_calls = install_websocket_factory(monkeypatch, websocket)
    client = VizClient(host=host, connect_timeout=0.05)

    assert await client.connect() is False
    assert client.connected is False
    assert connect_calls == []
    assert websocket.close_calls == 0


@pytest.mark.asyncio
async def test_connect_derives_actual_destination_from_validated_endpoint(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    websocket = FakeWebSocket(
        {
            "type": "connected",
            "auth_required": False,
            "server_type": "paper",
        }
    )
    connect_calls = install_websocket_factory(monkeypatch, websocket)
    client = VizClient(host="127.0.0.1", port=8765, connect_timeout=0.05)
    client.uri = "ws://8.8.8.8:8765"

    assert await client.connect() is True
    assert connect_calls[0]["uri"] == "ws://127.0.0.1:8765"

    await client.disconnect()


@pytest.mark.parametrize("enable_heartbeat", [False, True])
@pytest.mark.parametrize(
    ("host", "expected_uri"),
    [
        ("localhost", "ws://localhost:8765"),
        ("localhost.", "ws://localhost.:8765"),
        ("127.0.0.42", "ws://127.0.0.42:8765"),
        ("::1", "ws://[::1]:8765"),
        ("[::1]", "ws://[::1]:8765"),
    ],
)
@pytest.mark.asyncio
async def test_loopback_connects_without_token_when_server_disables_auth(
    monkeypatch: pytest.MonkeyPatch,
    enable_heartbeat: bool,
    host: str,
    expected_uri: str,
) -> None:
    websocket = FakeWebSocket(
        {
            "type": "connected",
            "auth_required": False,
            "server_type": "paper",
        }
    )
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(
        host=host,
        connect_timeout=0.05,
        enable_heartbeat=enable_heartbeat,
    )

    assert client.uri == expected_uri
    assert await client.connect() is True
    assert client.connected is True
    assert client.server_type == "paper"
    assert websocket.sent_messages == []

    await client.disconnect()


@pytest.mark.asyncio
async def test_connect_authenticates_before_returning_success(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    websocket = FakeWebSocket(
        {
            "type": "connected",
            "auth_required": True,
            "server_type": "fabric",
        },
        {"type": "auth_ok"},
    )
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(auth_token="  shared-secret  ", connect_timeout=0.05)

    assert await client.connect() is True
    assert client.connected is True
    assert client.server_type == "fabric"
    assert client.auth_token == "shared-secret"
    assert websocket.sent_messages == [{"type": "auth", "token": "shared-secret"}]


@pytest.mark.asyncio
async def test_transport_logger_redacts_encoded_auth_frame_with_escaped_token(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    secret = 'quoted" token with \\backslashes'
    websocket = FakeWebSocket(
        {
            "type": "connected",
            "auth_required": True,
            "server_type": "paper",
        },
        {"type": "auth_ok"},
    )
    connect_calls = install_websocket_factory(monkeypatch, websocket)
    client = VizClient(auth_token=secret, connect_timeout=0.05)

    assert await client.connect() is True

    auth_wire = client._encode({"type": "auth", "token": secret})
    auth_frame = Frame(Opcode.TEXT, auth_wire.encode())
    peer_echo_frame = Frame(Opcode.TEXT, f"peer echoed {secret}".encode())
    embedded_auth_wire = f"peer echoed {auth_wire}"
    embedded_auth_frame = Frame(Opcode.TEXT, embedded_auth_wire.encode())
    transport_logger = connect_calls[0]["logger"]
    caplog.set_level(logging.DEBUG)

    transport_logger.debug("> %s", auth_frame)
    transport_logger.debug("< %s", peer_echo_frame)
    transport_logger.debug("< %s", embedded_auth_wire)
    transport_logger.debug("< %s", embedded_auth_wire.encode())
    transport_logger.debug("< %s", embedded_auth_frame)

    encoded_secret = json.dumps(secret)[1:-1]
    repr_encoded_secret = encoded_secret.replace("\\", "\\\\")
    assert str(auth_frame) not in caplog.text
    assert str(peer_echo_frame) not in caplog.text
    assert str(embedded_auth_frame) not in caplog.text
    assert embedded_auth_wire not in caplog.text
    assert repr(embedded_auth_wire) not in caplog.text
    assert auth_wire not in caplog.text
    assert repr(auth_wire) not in caplog.text
    assert secret not in caplog.text
    assert encoded_secret not in caplog.text
    assert repr_encoded_secret not in caplog.text
    assert "[REDACTED]" in caplog.text

    await client.disconnect()


@pytest.mark.parametrize("enable_heartbeat", [False, True])
@pytest.mark.asyncio
async def test_connect_fails_closed_when_required_token_is_missing(
    monkeypatch: pytest.MonkeyPatch, enable_heartbeat: bool
) -> None:
    websocket = FakeWebSocket({"type": "connected", "auth_required": True, "server_type": "paper"})
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(
        auth_token="   ",
        connect_timeout=0.05,
        enable_heartbeat=enable_heartbeat,
    )

    assert await client.connect() is False
    assert client.connected is False
    assert websocket.sent_messages == []
    assert websocket.closed is True


@pytest.mark.parametrize("enable_heartbeat", [False, True])
@pytest.mark.asyncio
async def test_connect_fails_closed_without_logging_token_when_server_rejects_auth(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
    enable_heartbeat: bool,
) -> None:
    secret = "do-not-log-this-secret"
    websocket = FakeWebSocket(
        {"type": "connected", "auth_required": True, "server_type": "paper"},
        ConnectionClosedError(Close(4001, f"rejecting {secret}"), None, None),
    )
    connect_calls = install_websocket_factory(monkeypatch, websocket)
    caplog.set_level(logging.DEBUG)
    client = VizClient(
        auth_token=secret,
        connect_timeout=0.05,
        enable_heartbeat=enable_heartbeat,
    )

    assert await client.connect() is False
    assert client.connected is False
    assert websocket.closed is True
    assert websocket.sent_messages == [{"type": "auth", "token": secret}]
    assert client._heartbeat_task is None

    transport_logger = connect_calls[0]["logger"]
    transport_logger.debug("sent frame: %r", websocket.sent_messages[0])
    try:
        raise RuntimeError(f"peer echoed {secret}")
    except RuntimeError:
        transport_logger.exception("transport failure")

    assert secret not in caplog.text


@pytest.mark.parametrize("enable_heartbeat", [False, True])
@pytest.mark.asyncio
async def test_connect_fails_closed_when_auth_response_times_out(
    monkeypatch: pytest.MonkeyPatch, enable_heartbeat: bool
) -> None:
    websocket = FakeWebSocket({"type": "connected", "auth_required": True, "server_type": "paper"})
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(
        auth_token="shared-secret",
        connect_timeout=0.01,
        enable_heartbeat=enable_heartbeat,
    )

    result = await asyncio.wait_for(client.connect(), timeout=0.2)

    assert result is False
    assert client.connected is False
    assert websocket.closed is True
    assert websocket.sent_messages == [{"type": "auth", "token": "shared-secret"}]
    assert client._heartbeat_task is None


@pytest.mark.asyncio
async def test_connect_fails_closed_when_auth_requirement_is_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    websocket = FakeWebSocket({"type": "connected", "server_type": "paper"})
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(connect_timeout=0.05)

    assert await client.connect() is False
    assert client.connected is False
    assert websocket.closed is True


@pytest.mark.parametrize(
    ("server_type", "include_server_type"),
    [
        pytest.param(None, False, id="missing"),
        pytest.param("forge", True, id="unknown"),
        pytest.param(7, True, id="wrong-type"),
    ],
)
@pytest.mark.asyncio
async def test_connect_rejects_invalid_renderer_server_type(
    monkeypatch: pytest.MonkeyPatch,
    server_type: Any,
    include_server_type: bool,
) -> None:
    welcome: dict[str, Any] = {"type": "connected", "auth_required": False}
    if include_server_type:
        welcome["server_type"] = server_type
    websocket = FakeWebSocket(welcome)
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(connect_timeout=0.05, enable_heartbeat=True)

    result = await client.connect()
    connected_after_handshake = client.connected
    server_type_after_handshake = client.server_type
    await client.disconnect()

    assert result is False
    assert connected_after_handshake is False
    assert server_type_after_handshake is None
    assert websocket.closed is True
    assert client.ws is None


@pytest.mark.asyncio
async def test_connect_rejects_non_connected_first_message(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    websocket = FakeWebSocket({"type": "auth_ok"})
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(connect_timeout=0.05)

    assert await client.connect() is False
    assert client.connected is False
    assert websocket.closed is True


@pytest.mark.asyncio
async def test_receive_loop_does_not_hide_non_connected_first_message(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    websocket = FakeWebSocket(
        {"type": "ping"},
        {"type": "connected", "auth_required": False, "server_type": "paper"},
    )
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(connect_timeout=0.05, enable_heartbeat=True)

    assert await client.connect() is False
    assert client.connected is False
    assert websocket.closed is True
    assert websocket.sent_messages == []


@pytest.mark.parametrize("enable_heartbeat", [False, True])
@pytest.mark.asyncio
async def test_connect_rejects_malformed_first_message(
    monkeypatch: pytest.MonkeyPatch, enable_heartbeat: bool
) -> None:
    websocket = FakeWebSocket(
        "not-json",
        {"type": "connected", "auth_required": False, "server_type": "paper"},
    )
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(
        connect_timeout=0.05,
        enable_heartbeat=enable_heartbeat,
    )

    assert await client.connect() is False
    assert client.connected is False
    assert websocket.closed is True
    assert websocket.sent_messages == []


@pytest.mark.parametrize("enable_heartbeat", [False, True])
@pytest.mark.asyncio
async def test_cancelled_handshake_closes_transport_and_receive_task(
    monkeypatch: pytest.MonkeyPatch, enable_heartbeat: bool
) -> None:
    auth_sent = asyncio.Event()

    def observe_auth(message: dict[str, Any]) -> None:
        if message.get("type") == "auth":
            auth_sent.set()

    websocket = FakeWebSocket(
        {"type": "connected", "auth_required": True, "server_type": "paper"},
        on_send=observe_auth,
    )
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(
        auth_token="shared-secret",
        connect_timeout=1.0,
        enable_heartbeat=enable_heartbeat,
    )

    connect_task = asyncio.create_task(client.connect())
    await asyncio.wait_for(auth_sent.wait(), timeout=0.2)
    connect_task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await connect_task

    closed_after_cancel = websocket.closed
    receive_task_after_cancel = client._receive_task
    connected_after_cancel = client.connected
    if not closed_after_cancel:
        await client.disconnect()

    assert closed_after_cancel is True
    assert receive_task_after_cancel is None
    assert connected_after_cancel is False


@pytest.mark.asyncio
async def test_heartbeat_receive_loop_is_sole_reader_and_starts_heartbeat_after_auth(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    heartbeat_at_auth_send: list[asyncio.Task[Any] | None] = []
    connected_at_auth_send: list[bool] = []
    client: VizClient
    websocket: FakeWebSocket

    def respond_to_auth(message: dict[str, Any]) -> None:
        if message.get("type") == "auth":
            heartbeat_at_auth_send.append(client._heartbeat_task)
            connected_at_auth_send.append(client.connected)
            websocket.queue_response({"type": "auth_ok"})

    websocket = FakeWebSocket(
        {"type": "connected", "auth_required": True, "server_type": "paper"},
        on_send=respond_to_auth,
    )
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(auth_token="shared-secret", connect_timeout=0.05, enable_heartbeat=True)

    assert await client.connect() is True
    receive_task = client._receive_task
    assert receive_task is not None
    assert heartbeat_at_auth_send == [None]
    assert connected_at_auth_send == [False]
    assert client._heartbeat_task is not None
    assert websocket.recv_tasks
    assert all(task is receive_task for task in websocket.recv_tasks)

    await client.disconnect()


@pytest.mark.asyncio
async def test_heartbeat_receive_loop_routes_correlated_pong_to_ping_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    websocket: FakeWebSocket

    def respond_to_ping(message: dict[str, Any]) -> None:
        if message.get("type") == "ping":
            websocket.queue_response({"type": "pong", "_seq": message["_seq"]})

    websocket = FakeWebSocket(
        {"type": "connected", "auth_required": False, "server_type": "paper"},
        on_send=respond_to_ping,
    )
    install_websocket_factory(monkeypatch, websocket)
    client = VizClient(connect_timeout=0.05, enable_heartbeat=True)

    assert await client.connect() is True
    assert await client.ping() is True

    await client.disconnect()


@pytest.mark.asyncio
async def test_disconnect_closes_disconnected_transport_and_clears_identity() -> None:
    websocket = FakeWebSocket()
    client = VizClient()
    client.ws = websocket
    client.server_type = "paper"

    await client.disconnect()

    assert websocket.close_calls == 1
    assert client.ws is None
    assert client.server_type is None
    assert client.connected is False


@pytest.mark.asyncio
async def test_disconnect_awaits_tasks_cancels_requests_and_resets_receive_state() -> None:
    async def wait_forever() -> None:
        await asyncio.Event().wait()

    websocket = FakeWebSocket()
    client = VizClient()
    client.ws = websocket
    client.server_type = "fabric"
    client._connected = True
    client._use_receive_loop = True
    client._unmatched_responses.put_nowait({"type": "unsolicited"})
    heartbeat_task = asyncio.create_task(wait_forever())
    receive_task = asyncio.create_task(wait_forever())
    client._heartbeat_task = heartbeat_task
    client._receive_task = receive_task
    first_future = asyncio.get_running_loop().create_future()
    second_future = asyncio.get_running_loop().create_future()
    client._pending_futures = {1: first_future, 2: second_future}
    await asyncio.sleep(0)

    await client.disconnect()
    tasks_done_on_return = heartbeat_task.done() and receive_task.done()
    await asyncio.gather(heartbeat_task, receive_task, return_exceptions=True)

    assert tasks_done_on_return is True
    assert heartbeat_task.cancelled() is True
    assert receive_task.cancelled() is True
    assert first_future.cancelled() is True
    assert second_future.cancelled() is True
    assert client._pending_futures == {}
    assert client._heartbeat_task is None
    assert client._receive_task is None
    assert client._use_receive_loop is False
    assert client._unmatched_responses.empty()
    assert client.ws is None
    assert client.server_type is None


@pytest.mark.asyncio
async def test_disconnect_is_idempotent() -> None:
    websocket = FakeWebSocket()
    client = VizClient()
    client.ws = websocket
    client.server_type = "paper"

    await client.disconnect()
    await client.disconnect()

    assert websocket.close_calls == 1
    assert client.ws is None
    assert client.server_type is None


@pytest.mark.asyncio
async def test_disconnect_does_not_cancel_or_await_calling_transport_task() -> None:
    client = VizClient()
    other_task_started = asyncio.Event()

    async def other_transport_task() -> None:
        other_task_started.set()
        await asyncio.Event().wait()

    other_task = asyncio.create_task(other_transport_task())
    await other_task_started.wait()
    client._heartbeat_task = other_task

    async def disconnect_from_receive_task() -> None:
        client._receive_task = asyncio.current_task()
        await client.disconnect()
        await asyncio.sleep(0)

    receive_task = asyncio.create_task(disconnect_from_receive_task())
    await asyncio.wait_for(receive_task, timeout=0.2)

    assert receive_task.cancelled() is False
    assert other_task.done() is True
    assert other_task.cancelled() is True
    assert client._heartbeat_task is None
    assert client._receive_task is None


@pytest.mark.asyncio
async def test_disconnect_cancels_spawned_reconnect_before_transport_can_reopen(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    reconnect_started = asyncio.Event()
    allow_reconnect = asyncio.Event()
    reconnect_tasks: list[asyncio.Task[Any]] = []

    def fail_ping(_message: dict[str, Any]) -> None:
        raise RuntimeError("ping failed")

    websocket = FakeWebSocket(on_send=fail_ping)
    reopened_websocket = FakeWebSocket()
    client = VizClient(auto_reconnect=True)
    client.ws = websocket
    client.server_type = "paper"
    client._connected = True

    async def no_delay(_delay: float) -> None:
        return None

    async def delayed_reconnect() -> bool:
        reconnect_task = asyncio.current_task()
        assert reconnect_task is not None
        reconnect_tasks.append(reconnect_task)
        reconnect_started.set()
        await allow_reconnect.wait()
        client.ws = reopened_websocket
        client.server_type = "paper"
        client._connected = True
        return True

    monkeypatch.setattr(viz_client_module.asyncio, "sleep", no_delay)
    monkeypatch.setattr(client, "reconnect", delayed_reconnect)

    await client._heartbeat_loop()
    await asyncio.wait_for(reconnect_started.wait(), timeout=0.2)
    reconnect_task = reconnect_tasks[0]
    await client.disconnect()
    reconnect_done_on_return = reconnect_task.done()
    if not reconnect_done_on_return:
        allow_reconnect.set()
    await asyncio.gather(reconnect_task, return_exceptions=True)

    assert reconnect_done_on_return is True
    assert reconnect_task.cancelled() is True
    assert client.connected is False
    assert client.ws is None
    assert client.server_type is None


@pytest.mark.parametrize("enable_heartbeat", [False, True])
@pytest.mark.asyncio
async def test_reconnect_reuses_auth_token(
    monkeypatch: pytest.MonkeyPatch, enable_heartbeat: bool
) -> None:
    first_websocket = FakeWebSocket(
        {"type": "connected", "auth_required": True, "server_type": "paper"},
        {"type": "auth_ok"},
    )
    second_websocket = FakeWebSocket(
        {"type": "connected", "auth_required": True, "server_type": "paper"},
        {"type": "auth_ok"},
    )
    install_websocket_factory(monkeypatch, first_websocket, second_websocket)

    async def no_delay(_delay: float) -> None:
        return None

    monkeypatch.setattr(viz_client_module.asyncio, "sleep", no_delay)
    client = VizClient(
        auth_token="stable-secret",
        connect_timeout=0.05,
        enable_heartbeat=enable_heartbeat,
    )

    assert await client.connect() is True
    assert await client.reconnect() is True
    expected_auth = [{"type": "auth", "token": "stable-secret"}]
    assert first_websocket.sent_messages == expected_auth
    assert second_websocket.sent_messages == expected_auth

    await client.disconnect()


@pytest.mark.asyncio
async def test_reconnect_reauthenticates_before_setup_and_render_traffic(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    secret = "stable-reconnect-secret"
    first_websocket = FakeWebSocket(
        {"type": "connected", "auth_required": True, "server_type": "paper"},
        {"type": "auth_ok"},
    )
    second_websocket = FakeWebSocket(
        {"type": "connected", "auth_required": True, "server_type": "paper"},
        {"type": "auth_ok"},
        {"type": "zones", "zones": [{"name": "main"}]},
    )
    install_websocket_factory(monkeypatch, first_websocket, second_websocket)

    async def no_delay(_delay: float) -> None:
        return None

    monkeypatch.setattr(viz_client_module.asyncio, "sleep", no_delay)
    caplog.set_level(logging.DEBUG)
    client = VizClient(
        auth_token=secret,
        connect_timeout=0.05,
        enable_heartbeat=False,
    )

    assert await client.connect() is True
    assert await client.reconnect() is True
    assert await client.get_zones() == [{"name": "main"}]
    await client.batch_update_fast("main", [{"id": "block_0", "visible": True}])

    assert first_websocket.sent_messages == [{"type": "auth", "token": secret}]
    assert second_websocket.sent_messages == [
        {"type": "auth", "token": secret},
        {"type": "get_zones"},
        {
            "type": "batch_update",
            "zone": "main",
            "entities": [{"id": "block_0", "visible": True}],
        },
    ]
    assert secret not in caplog.text

    await client.disconnect()


def peer_close(secret: str) -> ConnectionClosedError:
    return ConnectionClosedError(Close(4001, f"peer echoed {secret}"), None, None)


def fail_send_with(error: Exception) -> Callable[[dict[str, Any]], None]:
    def fail_send(_message: dict[str, Any]) -> None:
        raise error

    return fail_send


@pytest.mark.parametrize("error_kind", ["connection_closed", "generic"])
@pytest.mark.asyncio
async def test_post_auth_send_does_not_log_peer_close_reason(
    caplog: pytest.LogCaptureFixture, error_kind: str
) -> None:
    secret = "post-auth-send-secret"
    error: Exception
    if error_kind == "connection_closed":
        error = peer_close(secret)
    else:
        error = RuntimeError(f"peer echoed {secret}")
    websocket = FakeWebSocket(on_send=fail_send_with(error))
    client = VizClient(auth_token=secret)
    client.ws = websocket
    client._connected = True
    caplog.set_level(logging.DEBUG)

    assert await client.send({"type": "get_zones"}) is None

    assert client.connected is False
    assert type(error).__name__ in caplog.text
    assert secret not in caplog.text


@pytest.mark.asyncio
async def test_heartbeat_send_does_not_log_peer_close_reason(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    secret = "heartbeat-send-secret"
    websocket = FakeWebSocket(on_send=fail_send_with(peer_close(secret)))
    client = VizClient(auth_token=secret)
    client.ws = websocket
    client._connected = True
    caplog.set_level(logging.DEBUG)

    async def no_delay(_delay: float) -> None:
        return None

    monkeypatch.setattr(viz_client_module.asyncio, "sleep", no_delay)

    await client._heartbeat_loop()

    assert client.connected is False
    assert "ConnectionClosedError" in caplog.text
    assert secret not in caplog.text


@pytest.mark.asyncio
async def test_pong_send_does_not_log_peer_close_reason(
    caplog: pytest.LogCaptureFixture,
) -> None:
    secret = "pong-send-secret"
    websocket = FakeWebSocket(
        {"type": "ping"},
        peer_close(secret),
        on_send=fail_send_with(peer_close(secret)),
    )
    client = VizClient(auth_token=secret)
    client.ws = websocket
    client._connected = True
    caplog.set_level(logging.DEBUG)

    await client._receive_loop()

    assert client.connected is False
    assert "ConnectionClosedError" in caplog.text
    assert secret not in caplog.text


@pytest.mark.asyncio
async def test_fast_update_does_not_log_peer_close_reason(
    caplog: pytest.LogCaptureFixture,
) -> None:
    secret = "fast-update-secret"
    websocket = FakeWebSocket(on_send=fail_send_with(RuntimeError(f"peer echoed {secret}")))
    client = VizClient(auth_token=secret)
    client.ws = websocket
    client._connected = True
    caplog.set_level(logging.DEBUG)

    await client.batch_update_fast("main", [])

    assert client.connected is False
    assert "RuntimeError" in caplog.text
    assert secret not in caplog.text


def test_fire_and_forget_diagnostic_does_not_log_peer_close_reason(
    caplog: pytest.LogCaptureFixture,
) -> None:
    secret = "fire-and-forget-secret"
    client = VizClient(auth_token=secret)
    caplog.set_level(logging.DEBUG)

    client._record_fire_and_forget_error("voice_audio", peer_close(secret))

    assert "ConnectionClosedError" in caplog.text
    assert secret not in caplog.text
