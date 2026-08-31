"""Transport-neutral WebSocket peers used by VJ protocol handlers."""

from __future__ import annotations

from collections.abc import AsyncIterator, Mapping
from typing import Any, Protocol, runtime_checkable

from websockets.exceptions import ConnectionClosed

NORMAL_CLOSE_CODES = frozenset({1000, 1001})


class PeerError(Exception):
    """Base error that deliberately excludes frame and peer-provided content."""


class PeerClosed(PeerError):
    """The peer closed or can no longer accept application messages."""

    def __init__(self, code: int) -> None:
        self.code = code if 1000 <= code <= 4999 else 1006
        super().__init__(f"WebSocket peer closed (code {self.code})")


class PeerProtocolError(PeerError):
    """The transport failed or produced an invalid application message."""


@runtime_checkable
class WebSocketPeer(Protocol):
    @property
    def remote_address(self) -> tuple[str, int] | None: ...

    @property
    def request_headers(self) -> Mapping[str, str]: ...

    @property
    def closed(self) -> bool: ...

    async def recv(self) -> str | bytes: ...

    async def send(self, message: str | bytes) -> None: ...

    async def close(self, code: int = 1000, reason: str = "") -> None: ...

    def __aiter__(self) -> AsyncIterator[str | bytes]: ...


class _PeerIteration:
    def __aiter__(self) -> AsyncIterator[str | bytes]:
        return self._messages()

    async def _messages(self) -> AsyncIterator[str | bytes]:
        while True:
            try:
                yield await self.recv()  # type: ignore[attr-defined]
            except PeerClosed as error:
                if error.code in NORMAL_CLOSE_CODES:
                    return
                raise


class WebsocketsPeer(_PeerIteration):
    """Adapter for websockets legacy and asyncio server connections."""

    def __init__(self, connection: Any, *, max_message_bytes: int) -> None:
        self._connection = connection
        self._max_message_bytes = _positive_limit(max_message_bytes)

    @property
    def remote_address(self) -> tuple[str, int] | None:
        return _remote_tuple(getattr(self._connection, "remote_address", None))

    @property
    def request_headers(self) -> Mapping[str, str]:
        legacy_headers = getattr(self._connection, "request_headers", None)
        if legacy_headers is not None:
            return legacy_headers
        request = getattr(self._connection, "request", None)
        headers = getattr(request, "headers", None)
        return headers if headers is not None else {}

    @property
    def closed(self) -> bool:
        legacy_closed = getattr(self._connection, "closed", None)
        if legacy_closed is not None:
            return bool(legacy_closed)
        state = getattr(self._connection, "state", None)
        return getattr(state, "name", "") == "CLOSED"

    async def recv(self) -> str | bytes:
        try:
            message = await self._connection.recv()
        except ConnectionClosed as error:
            raise _closed_error(error) from None
        except Exception:
            raise PeerProtocolError("WebSocket receive failed") from None
        if not isinstance(message, (str, bytes)):
            raise PeerProtocolError("WebSocket returned a non-application message")
        await self._enforce_size(message)
        return message

    async def send(self, message: str | bytes) -> None:
        _application_message(message)
        if self.closed:
            raise PeerClosed(_connection_close_code(self._connection))
        try:
            await self._connection.send(message)
        except ConnectionClosed as error:
            raise _closed_error(error) from None
        except Exception:
            raise PeerProtocolError("WebSocket send failed") from None

    async def close(self, code: int = 1000, reason: str = "") -> None:
        _close_arguments(code, reason)
        try:
            await self._connection.close(code=code, reason=reason)
        except ConnectionClosed:
            return
        except Exception:
            raise PeerProtocolError("WebSocket close failed") from None

    async def _enforce_size(self, message: str | bytes) -> None:
        if _message_size(message) <= self._max_message_bytes:
            return
        try:
            await self.close(1009, "Message too large")
        finally:
            raise PeerProtocolError("WebSocket message exceeds route limit")


class AiohttpPeer(_PeerIteration):
    """Adapter for an aiohttp WebSocketResponse without importing aiohttp eagerly."""

    def __init__(self, connection: Any, request: Any, *, max_message_bytes: int) -> None:
        self._connection = connection
        self._request = request
        self._max_message_bytes = _positive_limit(max_message_bytes)

    @property
    def remote_address(self) -> tuple[str, int] | None:
        transport = getattr(self._request, "transport", None)
        if transport is not None:
            peer = transport.get_extra_info("peername")
            normalized = _remote_tuple(peer)
            if normalized is not None:
                return normalized
        return None

    @property
    def request_headers(self) -> Mapping[str, str]:
        headers = getattr(self._request, "headers", None)
        return headers if headers is not None else {}

    @property
    def closed(self) -> bool:
        return bool(getattr(self._connection, "closed", False))

    async def recv(self) -> str | bytes:
        while True:
            try:
                message = await self._connection.receive()
            except Exception:
                if self.closed:
                    raise PeerClosed(_connection_close_code(self._connection)) from None
                raise PeerProtocolError("WebSocket receive failed") from None
            message_type = getattr(getattr(message, "type", None), "name", "")
            if message_type in {"PING", "PONG"}:
                continue
            if message_type == "TEXT":
                payload = getattr(message, "data", None)
                if not isinstance(payload, str):
                    raise PeerProtocolError("Invalid WebSocket text message")
                await self._enforce_size(payload)
                return payload
            if message_type == "BINARY":
                payload = getattr(message, "data", None)
                if not isinstance(payload, bytes):
                    raise PeerProtocolError("Invalid WebSocket binary message")
                await self._enforce_size(payload)
                return payload
            if message_type in {"CLOSE", "CLOSED", "CLOSING"}:
                code = getattr(message, "data", None)
                if not isinstance(code, int):
                    code = _connection_close_code(self._connection)
                raise PeerClosed(code)
            if message_type == "ERROR":
                raise PeerProtocolError("WebSocket transport error")
            raise PeerProtocolError("WebSocket returned a non-application message")

    async def send(self, message: str | bytes) -> None:
        _application_message(message)
        if self.closed:
            raise PeerClosed(_connection_close_code(self._connection))
        try:
            if isinstance(message, str):
                await self._connection.send_str(message)
            else:
                await self._connection.send_bytes(message)
        except Exception:
            if self.closed:
                raise PeerClosed(_connection_close_code(self._connection)) from None
            raise PeerProtocolError("WebSocket send failed") from None

    async def close(self, code: int = 1000, reason: str = "") -> None:
        _close_arguments(code, reason)
        if self.closed:
            return
        try:
            await self._connection.close(code=code, message=reason.encode("utf-8"))
        except Exception:
            raise PeerProtocolError("WebSocket close failed") from None

    async def _enforce_size(self, message: str | bytes) -> None:
        if _message_size(message) <= self._max_message_bytes:
            return
        try:
            await self.close(1009, "Message too large")
        finally:
            raise PeerProtocolError("WebSocket message exceeds route limit")


def _closed_error(error: ConnectionClosed) -> PeerClosed:
    received = getattr(error, "rcvd", None)
    code = getattr(received, "code", None)
    if not isinstance(code, int):
        code = getattr(error, "code", 1006)
    return PeerClosed(code if isinstance(code, int) else 1006)


def _connection_close_code(connection: Any) -> int:
    code = getattr(connection, "close_code", None)
    return code if isinstance(code, int) else 1006


def _remote_tuple(value: Any) -> tuple[str, int] | None:
    if (
        isinstance(value, tuple)
        and len(value) >= 2
        and isinstance(value[0], str)
        and isinstance(value[1], int)
    ):
        return value[0], value[1]
    return None


def _message_size(message: str | bytes) -> int:
    return len(message.encode("utf-8")) if isinstance(message, str) else len(message)


def _application_message(message: str | bytes) -> None:
    if not isinstance(message, (str, bytes)):
        raise TypeError("WebSocket application messages must be text or bytes")


def _positive_limit(value: int) -> int:
    if value <= 0:
        raise ValueError("max_message_bytes must be positive")
    return value


def _close_arguments(code: int, reason: str) -> None:
    if code < 1000 or code > 4999:
        raise ValueError("invalid WebSocket close code")
    if not isinstance(reason, str) or len(reason.encode("utf-8")) > 123:
        raise ValueError("invalid WebSocket close reason")
