"""Unified HTTPS static and browser WebSocket gateway."""

from __future__ import annotations

import asyncio
import mimetypes
import re
import ssl
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web

from vj_server.models import _resolve_static_path

RUNTIME_CONFIG_BODY = (
    "window.MCAV_RUNTIME_CONFIG = Object.freeze({"
    'browserWebSocketMode: "same-origin",'
    'browserWebSocketPath: "/ws"'
    "});\n"
).encode("utf-8")

_ENCODED_SEPARATOR = re.compile(r"%(?:2f|5c)", re.IGNORECASE)
_NOT_FOUND_BODY = b"Not Found\n"
_FORBIDDEN_BODY = b"Forbidden\n"
_BAD_REQUEST_BODY = b"Bad Request\n"


@dataclass(frozen=True)
class UnifiedWebConfig:
    """Configuration for the unified admin, preview, and browser gateway."""

    project_root: Path
    public_origin: str
    ws_path: str = "/ws"
    runtime_config_path: str = "/runtime-config.js"
    max_message_size: int = 65_536


def _no_store_response(
    *,
    status: int = 200,
    body: bytes = b"",
    content_type: str | None = None,
    headers: dict[str, str] | None = None,
) -> web.Response:
    response_headers = {
        name: value for name, value in (headers or {}).items() if name.lower() != "cache-control"
    }
    response_headers["Cache-Control"] = "no-store"
    return web.Response(
        status=status,
        body=body,
        content_type=content_type,
        headers=response_headers,
    )


def resolve_contained_path(root: Path, raw_relative_path: str) -> Path | None:
    """Resolve a URL path under ``root`` without allowing cross-platform escapes."""
    if "\x00" in raw_relative_path or _ENCODED_SEPARATOR.search(raw_relative_path):
        return None
    return _resolve_static_path(root, raw_relative_path)


def _static_selection(request: web.Request) -> tuple[Path, str] | web.Response:
    config = request.app[UNIFIED_CONFIG_KEY]
    raw_path = request.raw_path.partition("?")[0]
    if raw_path == "/preview":
        return _no_store_response(
            status=301,
            headers={"Location": "/preview/"},
        )
    if raw_path.startswith("/preview/"):
        return config.project_root / "preview_tool" / "frontend", raw_path[len("/preview/") :]
    return config.project_root / "admin_panel", raw_path.removeprefix("/")


def _directory_response(request: web.Request, directory: Path) -> web.Response | Path:
    raw_path = request.raw_path.partition("?")[0]
    if not raw_path.endswith("/"):
        return _no_store_response(status=301, headers={"Location": f"{raw_path}/"})

    for index_name in ("index.html", "index.htm"):
        index_path = resolve_contained_path(directory, index_name)
        if index_path is not None and index_path.is_file():
            return index_path
    return _no_store_response(status=404, body=_NOT_FOUND_BODY, content_type="text/plain")


async def serve_static_request(request: web.Request) -> web.StreamResponse:
    """Serve contained admin/preview assets without cache reuse or directory listings."""
    if request.method not in {"GET", "HEAD"}:
        return _no_store_response(status=404, body=_NOT_FOUND_BODY, content_type="text/plain")

    selection = _static_selection(request)
    if isinstance(selection, web.Response):
        return selection

    root, raw_relative_path = selection
    path = resolve_contained_path(root, raw_relative_path)
    if path is None or not path.exists():
        return _no_store_response(status=404, body=_NOT_FOUND_BODY, content_type="text/plain")
    if path.is_dir():
        directory_result = _directory_response(request, path)
        if isinstance(directory_result, web.Response):
            return directory_result
        path = directory_result
    if not path.is_file():
        return _no_store_response(status=404, body=_NOT_FOUND_BODY, content_type="text/plain")

    try:
        body = await asyncio.to_thread(path.read_bytes)
    except OSError:
        return _no_store_response(status=404, body=_NOT_FOUND_BODY, content_type="text/plain")
    content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    return _no_store_response(body=body, content_type=content_type)


async def serve_runtime_config(request: web.Request) -> web.Response:
    """Return fixed, non-secret browser connection settings."""
    return _no_store_response(body=RUNTIME_CONFIG_BODY, content_type="text/javascript")


class _WebSocketClosed(Exception):
    """Internal normal-closure signal shared by recv and iteration."""


class AiohttpBrowserSocket(AsyncIterator[str | bytes]):
    """Adapt aiohttp WebSockets to the interface used by the browser relay."""

    def __init__(self, response: web.WebSocketResponse) -> None:
        self._response = response

    @property
    def remote_address(self) -> Any:
        return self._response.get_extra_info("peername")

    async def send(self, message: str | bytes) -> None:
        if isinstance(message, str):
            await self._response.send_str(message)
            return
        if isinstance(message, bytes):
            await self._response.send_bytes(message)
            return
        raise TypeError("WebSocket messages must be str or bytes")

    async def _receive(self) -> str | bytes:
        while True:
            message = await self._response.receive()
            if message.type is WSMsgType.TEXT:
                return message.data
            if message.type is WSMsgType.BINARY:
                return message.data
            if message.type in {WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED}:
                raise _WebSocketClosed
            if message.type is WSMsgType.ERROR:
                cause = message.data
                if isinstance(cause, BaseException):
                    raise ConnectionError(str(cause)) from cause
                raise ConnectionError("WebSocket transport error")

    async def recv(self) -> str | bytes:
        try:
            return await self._receive()
        except _WebSocketClosed as error:
            raise ConnectionError("WebSocket closed") from error

    async def close(self, code: int = 1000, reason: str = "") -> None:
        await self._response.close(code=code, message=reason.encode("utf-8"))

    def __aiter__(self) -> AiohttpBrowserSocket:
        return self

    async def __anext__(self) -> str | bytes:
        try:
            return await self._receive()
        except _WebSocketClosed:
            raise StopAsyncIteration from None


BrowserHandler = Callable[[AiohttpBrowserSocket], Awaitable[None]]
UNIFIED_CONFIG_KEY = web.AppKey("unified_config", UnifiedWebConfig)
BROWSER_HANDLER_KEY = web.AppKey("browser_handler", BrowserHandler)


async def serve_browser_websocket(request: web.Request) -> web.WebSocketResponse:
    """Validate a same-origin upgrade and delegate it to the browser relay."""
    config = request.app[UNIFIED_CONFIG_KEY]
    if request.headers.get("Origin") != config.public_origin:
        return _no_store_response(status=403, body=_FORBIDDEN_BODY, content_type="text/plain")

    response = web.WebSocketResponse(max_msg_size=config.max_message_size)
    response.headers["Cache-Control"] = "no-store"
    if not response.can_prepare(request).ok:
        return _no_store_response(status=400, body=_BAD_REQUEST_BODY, content_type="text/plain")

    await response.prepare(request)
    socket = AiohttpBrowserSocket(response)
    try:
        await request.app[BROWSER_HANDLER_KEY](socket)
    except ConnectionError:
        pass
    finally:
        await socket.close()
    return response


def create_unified_web_app(
    browser_handler: Callable[[AiohttpBrowserSocket], Awaitable[None]],
    config: UnifiedWebConfig,
) -> web.Application:
    """Create the unified gateway application without binding a listener."""
    app = web.Application(client_max_size=config.max_message_size)
    app[UNIFIED_CONFIG_KEY] = config
    app[BROWSER_HANDLER_KEY] = browser_handler
    app.router.add_route("GET", config.runtime_config_path, serve_runtime_config)
    app.router.add_route("GET", config.ws_path, serve_browser_websocket)
    app.router.add_route("*", "/{request_path:.*}", serve_static_request)
    return app


async def start_unified_web_gateway(
    browser_handler: Callable[[AiohttpBrowserSocket], Awaitable[None]],
    host: str,
    port: int,
    ssl_context: ssl.SSLContext,
    config: UnifiedWebConfig,
) -> web.AppRunner:
    """Start the unified HTTPS gateway and return its lifecycle owner."""
    runner = web.AppRunner(create_unified_web_app(browser_handler, config))
    await runner.setup()
    await web.TCPSite(runner, host, port, ssl_context=ssl_context).start()
    return runner
