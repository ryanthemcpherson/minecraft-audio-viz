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
from websockets.frames import Close

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
        self.closed = True


def install_websocket_factory(
    monkeypatch: pytest.MonkeyPatch, *websockets: FakeWebSocket
) -> list[dict[str, Any]]:
    pending = list(websockets)
    connect_calls: list[dict[str, Any]] = []

    async def fake_connect(*_args: Any, **_kwargs: Any) -> FakeWebSocket:
        connect_calls.append(_kwargs)
        return pending.pop(0)

    monkeypatch.setattr(viz_client_module.websockets, "connect", fake_connect)
    return connect_calls


@pytest.mark.parametrize("enable_heartbeat", [False, True])
@pytest.mark.asyncio
async def test_loopback_connects_without_token_when_server_disables_auth(
    monkeypatch: pytest.MonkeyPatch, enable_heartbeat: bool
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
        host="127.0.0.1",
        connect_timeout=0.05,
        enable_heartbeat=enable_heartbeat,
    )

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
