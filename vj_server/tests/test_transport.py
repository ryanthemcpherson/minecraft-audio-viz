from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from enum import Enum, auto
from typing import Any

import pytest
from websockets.exceptions import ConnectionClosedError, ConnectionClosedOK
from websockets.frames import Close

from vj_server.transport import (
    AiohttpPeer,
    PeerClosed,
    PeerProtocolError,
    WebSocketPeer,
    WebsocketsPeer,
)


class MessageType(Enum):
    TEXT = auto()
    BINARY = auto()
    CLOSE = auto()
    CLOSED = auto()
    CLOSING = auto()
    ERROR = auto()
    PING = auto()
    PONG = auto()


@dataclass(frozen=True)
class Message:
    type: MessageType
    data: Any = None
    extra: str = ""


class FakeTransport:
    def get_extra_info(self, name: str) -> Any:
        if name == "peername":
            return ("127.0.0.1", 43210)
        return None


class FakeRequest:
    headers = {"Origin": "https://localhost:8080"}
    transport = FakeTransport()
    remote = "127.0.0.1"


class FakeWebsocketsConnection:
    remote_address = ("127.0.0.1", 43210)
    request_headers = {"Origin": "https://localhost:8080"}

    def __init__(self, incoming: list[str | bytes | BaseException]) -> None:
        self.incoming = deque(incoming)
        self.sent: list[str | bytes] = []
        self.closed = False
        self.close_code: int | None = None
        self.close_reason = ""

    async def recv(self) -> str | bytes:
        value = self.incoming.popleft()
        if isinstance(value, BaseException):
            raise value
        return value

    async def send(self, message: str | bytes) -> None:
        if self.closed:
            raise ConnectionClosedOK(
                Close(1000, "done"),
                Close(1000, "done"),
                True,
            )
        self.sent.append(message)

    async def close(self, code: int = 1000, reason: str = "") -> None:
        self.closed = True
        self.close_code = code
        self.close_reason = reason


class FakeAiohttpConnection:
    def __init__(self, incoming: list[Message]) -> None:
        self.incoming = deque(incoming)
        self.sent: list[str | bytes] = []
        self.closed = False
        self.close_code: int | None = None
        self.close_reason = ""
        self.exception_value: BaseException | None = None

    async def receive(self) -> Message:
        return self.incoming.popleft()

    async def send_str(self, message: str) -> None:
        if self.closed:
            raise RuntimeError("closed with hidden frame data")
        self.sent.append(message)

    async def send_bytes(self, message: bytes) -> None:
        if self.closed:
            raise RuntimeError("closed with hidden frame data")
        self.sent.append(message)

    async def close(self, *, code: int = 1000, message: bytes = b"") -> None:
        self.closed = True
        self.close_code = code
        self.close_reason = message.decode("utf-8")

    def exception(self) -> BaseException | None:
        return self.exception_value


async def assert_peer_contract(peer: WebSocketPeer) -> None:
    assert peer.remote_address == ("127.0.0.1", 43210)
    assert peer.request_headers.get("Origin") == "https://localhost:8080"
    await peer.send("hello")
    assert await peer.recv() == "reply"
    await peer.close(4003, "policy")
    assert peer.closed


@pytest.mark.asyncio
async def test_websockets_peer_contract_and_binary_messages() -> None:
    connection = FakeWebsocketsConnection(["reply", b"binary"])
    peer = WebsocketsPeer(connection, max_message_bytes=64)

    assert isinstance(peer, WebSocketPeer)
    await assert_peer_contract(peer)
    assert connection.sent == ["hello"]
    assert connection.close_code == 4003
    assert connection.close_reason == "policy"


@pytest.mark.asyncio
async def test_aiohttp_peer_contract_skips_control_frames() -> None:
    connection = FakeAiohttpConnection(
        [
            Message(MessageType.PING),
            Message(MessageType.PONG),
            Message(MessageType.TEXT, "reply"),
        ]
    )
    peer = AiohttpPeer(connection, FakeRequest(), max_message_bytes=64)

    assert isinstance(peer, WebSocketPeer)
    await assert_peer_contract(peer)
    assert connection.sent == ["hello"]
    assert connection.close_code == 4003
    assert connection.close_reason == "policy"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("factory", "payload"),
    [
        (
            lambda value: WebsocketsPeer(FakeWebsocketsConnection([value]), max_message_bytes=4),
            "five!",
        ),
        (
            lambda value: AiohttpPeer(
                FakeAiohttpConnection([Message(MessageType.BINARY, value)]),
                FakeRequest(),
                max_message_bytes=4,
            ),
            b"five!",
        ),
    ],
)
async def test_oversized_messages_fail_closed_without_payload(
    factory: Any,
    payload: str | bytes,
) -> None:
    peer = factory(payload)

    with pytest.raises(PeerProtocolError) as error:
        await peer.recv()

    assert "five" not in str(error.value)
    assert peer.closed


@pytest.mark.asyncio
async def test_iteration_stops_on_normal_websockets_close() -> None:
    closed = ConnectionClosedOK(
        Close(1000, "done"),
        Close(1000, "done"),
        True,
    )
    peer = WebsocketsPeer(
        FakeWebsocketsConnection(["one", b"two", closed]),
        max_message_bytes=64,
    )

    assert [message async for message in peer] == ["one", b"two"]


@pytest.mark.asyncio
async def test_abnormal_websockets_close_is_normalized() -> None:
    closed = ConnectionClosedError(Close(1011, "private detail"), None)
    peer = WebsocketsPeer(FakeWebsocketsConnection([closed]), max_message_bytes=64)

    with pytest.raises(PeerClosed) as error:
        await peer.recv()

    assert error.value.code == 1011
    assert "private detail" not in str(error.value)


@pytest.mark.asyncio
async def test_aiohttp_error_and_close_are_normalized() -> None:
    failed = FakeAiohttpConnection([Message(MessageType.ERROR)])
    failed.exception_value = RuntimeError("private frame contents")
    peer = AiohttpPeer(failed, FakeRequest(), max_message_bytes=64)

    with pytest.raises(PeerProtocolError) as error:
        await peer.recv()
    assert "private" not in str(error.value)

    closed = AiohttpPeer(
        FakeAiohttpConnection([Message(MessageType.CLOSE, 1001, "leaving")]),
        FakeRequest(),
        max_message_bytes=64,
    )
    with pytest.raises(PeerClosed) as close_error:
        await closed.recv()
    assert close_error.value.code == 1001


@pytest.mark.asyncio
async def test_send_after_close_is_normalized_without_payload() -> None:
    connection = FakeAiohttpConnection([])
    peer = AiohttpPeer(connection, FakeRequest(), max_message_bytes=64)
    await peer.close()

    with pytest.raises(PeerClosed) as error:
        await peer.send("secret frame")

    assert "secret" not in str(error.value)
